package dev.sonar.service;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * The Arctis Nova Pro Wireless base station, over its vendor HID interface (/dev/hidraw):
 * - ChatMix: the base only reports its dial once told to (that's what SteelSeries' Windows Sonar
 *   does), so we send "enable ChatMix", then apply its dial reports. Protocol from
 *   github.com/Dymstro/nova-chatmix-linux.
 * - Headset status (battery, spare battery, connection, mic mute): polled every 30 s. Layout as in
 *   github.com/Sapd/HeadsetControl, mute byte found by probing.
 * - Mic mute: the base reports the headset's mute button as it's pressed; we mirror it onto the
 *   Mic channel, so Sonar shows it (the headset mutes the mic itself).
 * Needs udev/70-steelseries-arctis.rules installed; without it (or the headset) this idles.
 */
@Component
public class BaseStation {

	private static final Logger log = LoggerFactory.getLogger(BaseStation.class);

	/** HID_ID suffixes (vendor 1038) of the Nova Pro Wireless base: PC and Xbox editions. */
	private static final String[] DEVICES = { "00001038:000012E0", "00001038:000012E5" };
	private static final String INTERFACE = ":1.4"; // the vendor interface (bInterfaceNumber 4)
	private static final int REPORT_LEN = 64; // report id + 63 bytes, per the HID descriptor

	private static final byte TX = 0x06, RX = 0x07;
	private static final byte CHATMIX_ENABLE = 0x49, CHATMIX = 0x45;
	private static final byte STATUS = (byte) 0xb0, MIC_MUTE = (byte) 0xbb;
	private static final long STATUS_EVERY_MS = 30_000;

	/** battery/spare: percent; null = unknown (headset off or no base). */
	public record Headset(boolean connected, boolean charging, Integer battery, Integer spareBattery, boolean micMuted) {
	}

	private static final Headset NONE = new Headset(false, false, null, null, false);

	private final MixerService mixer;
	private volatile FileOutputStream out;
	private volatile Headset headset = NONE;
	/** Last mute state the headset reported; null after (re)connecting. */
	private Boolean hardwareMute;

	public BaseStation(MixerService mixer) {
		this.mixer = mixer;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		Thread.ofVirtual().name("base-station").start(this::run);
		Thread.ofVirtual().name("base-station-status").start(this::pollStatus);
	}

	/** Headset status as last reported by the base; all unknown without one. */
	public Headset headset() {
		return out == null ? NONE : headset;
	}

	private void pollStatus() {
		while (true) {
			try {
				if (out != null) {
					send(STATUS, 0);
				}
				Thread.sleep(STATUS_EVERY_MS);
			} catch (IOException ignored) {
				// the reader notices the unplug and reconnects
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	/** Find the base, enable the dial, apply its reports; on unplug or error, look again every 5 s. */
	private void run() {
		boolean warned = false;
		while (true) {
			Path dev = findDevice();
			if (dev != null && !Files.isReadable(dev)) {
				if (!warned) {
					log.warn("Base station: no access to {}; install udev/70-steelseries-arctis.rules", dev);
					warned = true;
				}
			} else if (dev != null) {
				try (FileInputStream in = new FileInputStream(dev.toFile());
						FileOutputStream o = new FileOutputStream(dev.toFile())) {
					out = o;
					send(CHATMIX_ENABLE, 1);
					send(STATUS, 0);
					log.info("Base station: listening on {}", dev);
					byte[] buf = new byte[REPORT_LEN];
					int last = Integer.MIN_VALUE;
					while (in.read(buf) > 3) {
						if (buf[0] == RX && buf[1] == CHATMIX) {
							int mix = mixFromDial(buf[2] & 0xff, buf[3] & 0xff);
							if (mix != last) {
								mixer.setChatMix(mix);
								last = mix;
							}
						} else if (buf[0] == RX && buf[1] == MIC_MUTE) {
							micMuted(buf[2] != 0);
						} else if (buf[0] == TX && buf[1] == STATUS) {
							Headset h = parseStatus(buf);
							headset = h;
							if (h.connected()) {
								micMuted(h.micMuted());
							}
						}
					}
				} catch (IOException | RuntimeException e) {
					log.info("Base station: {} ({}); retrying", dev, e.getMessage());
				} finally {
					out = null;
					hardwareMute = null;
				}
			}
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	/** Only on a change: the 30 s status poll mustn't undo a mute set in Sonar's own UI. */
	private void micMuted(boolean muted) {
		headset = new Headset(headset.connected(), headset.charging(), headset.battery(), headset.spareBattery(), muted);
		if (!Boolean.valueOf(muted).equals(hardwareMute)) {
			hardwareMute = muted;
			mixer.setMicMuted(muted);
		}
	}

	/**
	 * Reply to the status request: [6] headset battery 0..8, [7] spare battery 0..8, [9] mic muted,
	 * [15] headset 0x01 offline / 0x02 charging over the cable / 0x08 online.
	 */
	static Headset parseStatus(byte[] r) {
		int state = r[15] & 0xff;
		if (state == 0x01) {
			return new Headset(false, false, null, percent(r[7]), false);
		}
		return new Headset(true, state == 0x02, percent(r[6]), percent(r[7]), r[9] != 0);
	}

	private static int percent(byte level) {
		return Math.min(8, level & 0xff) * 100 / 8;
	}

	/** The base reports each side's level 0..100 in 12 steps; the side the dial points to stays at 100. */
	static int mixFromDial(int game, int chat) {
		return Math.max(-100, Math.min(100, chat - game));
	}

	private void send(byte option, int value) throws IOException {
		byte[] report = new byte[REPORT_LEN];
		report[0] = TX;
		report[1] = option;
		report[2] = (byte) value;
		out.write(report);
	}

	/** /dev/hidrawN of the base's vendor interface, or null when no base is plugged in. */
	static Path findDevice() {
		try (Stream<Path> nodes = Files.list(Path.of("/sys/class/hidraw"))) {
			return nodes.filter(BaseStation::isBaseVendorInterface).findFirst()
					.map(n -> Path.of("/dev", n.getFileName().toString())).orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	private static boolean isBaseVendorInterface(Path node) {
		try {
			String uevent = Files.readString(node.resolve("device/uevent"));
			String usbPath = node.resolve("device").toRealPath().toString();
			return Stream.of(DEVICES).anyMatch(uevent::contains) && usbPath.contains(INTERFACE + "/");
		} catch (IOException e) {
			return false;
		}
	}

	/** Hand the dial back to the base station, so it isn't left waiting for a Sonar that's gone. */
	@PreDestroy
	void shutdown() {
		if (out != null) {
			try {
				send(CHATMIX_ENABLE, 0);
			} catch (IOException ignored) {
			}
		}
	}
}
