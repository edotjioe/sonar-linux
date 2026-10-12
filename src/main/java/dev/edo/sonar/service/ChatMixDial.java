package dev.edo.sonar.service;

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
 * Drives ChatMix from the hardware dial on an Arctis Nova Pro Wireless base station.
 * The base only reports the dial once told to (that's what SteelSeries' Windows Sonar does):
 * we send "enable ChatMix" over its vendor HID interface, then read dial reports from the
 * same /dev/hidraw node. Protocol from github.com/Dymstro/nova-chatmix-linux.
 * Needs udev/70-steelseries-arctis.rules installed; without it (or the headset) this idles.
 */
@Component
public class ChatMixDial {

	private static final Logger log = LoggerFactory.getLogger(ChatMixDial.class);

	/** HID_ID suffixes (vendor 1038) of the Nova Pro Wireless base: PC and Xbox editions. */
	private static final String[] DEVICES = { "00001038:000012E0", "00001038:000012E5" };
	private static final String INTERFACE = ":1.4"; // the vendor interface (bInterfaceNumber 4)
	private static final int REPORT_LEN = 64; // report id + 63 bytes, per the HID descriptor

	private static final byte TX = 0x06, RX = 0x07;
	private static final byte CHATMIX_ENABLE = 0x49, CHATMIX = 0x45;

	private final MixerService mixer;
	private volatile FileOutputStream out;

	public ChatMixDial(MixerService mixer) {
		this.mixer = mixer;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		Thread.ofVirtual().name("chatmix-dial").start(this::run);
	}

	/** Find the base, enable the dial, apply its reports; on unplug or error, look again every 5 s. */
	private void run() {
		boolean warned = false;
		while (true) {
			Path dev = findDevice();
			if (dev != null && !Files.isReadable(dev)) {
				if (!warned) {
					log.warn("ChatMix dial: no access to {}; install udev/70-steelseries-arctis.rules", dev);
					warned = true;
				}
			} else if (dev != null) {
				try (FileInputStream in = new FileInputStream(dev.toFile());
						FileOutputStream o = new FileOutputStream(dev.toFile())) {
					out = o;
					send(CHATMIX_ENABLE, 1);
					log.info("ChatMix dial: listening on {}", dev);
					byte[] buf = new byte[REPORT_LEN];
					int last = Integer.MIN_VALUE;
					while (in.read(buf) > 3) {
						if (buf[0] == RX && buf[1] == CHATMIX) {
							int mix = mixFromDial(buf[2] & 0xff, buf[3] & 0xff);
							if (mix != last) {
								mixer.setChatMix(mix);
								last = mix;
							}
						}
					}
				} catch (IOException | RuntimeException e) {
					log.info("ChatMix dial: {} ({}); retrying", dev, e.getMessage());
				} finally {
					out = null;
				}
			}
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				return;
			}
		}
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
			return nodes.filter(ChatMixDial::isBaseVendorInterface).findFirst()
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
