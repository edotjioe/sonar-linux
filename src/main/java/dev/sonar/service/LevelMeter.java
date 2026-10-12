package dev.sonar.service;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ProcessBuilder.Redirect;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.sonar.domain.Channel;
import jakarta.annotation.PreDestroy;

/**
 * Per-channel peak meters. Each meter is a `parec` process recording the channel
 * (sink monitor, or the virtual mic itself) at a low rate; a virtual thread turns
 * the raw samples into a decaying peak. Meters only run while someone polls
 * {@link #levels}, so the mic isn't held open when the UI is closed.
 */
@Component
public class LevelMeter {

	private static final int RATE = 8000;
	private static final long IDLE_MS = 5000;

	private static final class Meter {
		final Process proc;
		volatile double peak; // linear 0..1, decays between chunks

		Meter(Process proc) {
			this.proc = proc;
		}
	}

	private final Map<String, Meter> meters = new ConcurrentHashMap<>();
	private volatile long lastPoll;

	/** channel id -> level 0..100 (-60..0 dBFS). Starts meters that aren't running yet. */
	public Map<Long, Integer> levels(List<Channel> channels) {
		lastPoll = System.currentTimeMillis();
		Map<Long, Integer> out = new HashMap<>();
		for (Channel c : channels) {
			// a meter whose parec died (e.g. offline channel) stays in the map at 0: no respawn every poll
			Meter m = meters.computeIfAbsent(source(c), this::start);
			out.put(c.getId(), m == null ? 0 : toPercent(m.peak));
		}
		return out;
	}

	/** Stop a channel's meter. Call before its source goes away, or parec gets moved to the default mic. */
	public void forget(Channel c) {
		stop(source(c));
	}

	private static String source(Channel c) {
		return c.isMic() ? c.getSinkName() : c.getSinkName() + ".monitor";
	}

	private Meter start(String source) {
		try {
			Process p = new ProcessBuilder("parec", "--raw", "--format=s16le", "--channels=1", "--rate=" + RATE,
					"--latency-msec=50", "--client-name=sonar-meter", "-d", source)
					.redirectError(Redirect.DISCARD).start();
			Meter m = new Meter(p);
			Thread.ofVirtual().start(() -> read(m));
			return m;
		} catch (IOException e) {
			return null;
		}
	}

	private static void read(Meter m) {
		byte[] buf = new byte[RATE / 20 * 2]; // 50 ms of s16 mono
		try (InputStream in = m.proc.getInputStream()) {
			int n;
			while ((n = in.readNBytes(buf, 0, buf.length)) > 0) {
				m.peak = Math.max(peak(buf, n), m.peak * 0.6);
			}
		} catch (IOException ignored) {
		}
		m.peak = 0;
	}

	/** Max |sample| of n bytes of s16le, as 0..1. */
	static double peak(byte[] buf, int n) {
		int max = 0;
		for (int i = 0; i + 1 < n; i += 2) {
			max = Math.max(max, Math.abs((short) ((buf[i] & 0xff) | (buf[i + 1] << 8))));
		}
		return max / 32768.0;
	}

	static int toPercent(double peak) {
		if (peak <= 0) {
			return 0;
		}
		double db = 20 * Math.log10(peak);
		return (int) Math.round(Math.max(0, Math.min(100, (db + 60) / 60 * 100)));
	}

	private void stop(String source) {
		Meter m = meters.remove(source);
		if (m != null) {
			m.proc.destroy();
		}
	}

	@Scheduled(fixedDelay = 2000)
	void stopWhenIdle() {
		if (System.currentTimeMillis() - lastPoll > IDLE_MS) {
			meters.keySet().forEach(this::stop);
		}
	}

	@PreDestroy
	void shutdown() {
		meters.keySet().forEach(this::stop);
	}
}
