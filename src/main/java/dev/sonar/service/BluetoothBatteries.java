package dev.sonar.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Battery levels of Bluetooth headsets/headphones, from UPower: BlueZ hands it whatever the device
 * reports (LE battery service, or HFP battery reports via PipeWire). Devices that don't report
 * aren't listed. Kept to audio devices: UPower also knows mice, keyboards, the laptop battery.
 */
@Component
public class BluetoothBatteries {

	public record Battery(String name, int percent) {
	}

	private static final Pattern MODEL = Pattern.compile("(?m)^\\s*model:\\s*(.+)$");
	private static final Pattern PERCENT = Pattern.compile("(?m)^\\s*percentage:\\s*(\\d+)");

	public List<Battery> list() {
		List<Battery> out = new ArrayList<>();
		for (String path : run("upower", "-e").lines().toList()) {
			// UPower names devices by kind: .../headset_dev_XX, .../headphones_dev_XX
			if (path.contains("/headset_") || path.contains("/headphones_")) {
				Battery b = parse(run("upower", "-i", path));
				if (b != null) {
					out.add(b);
				}
			}
		}
		return out;
	}

	/** `upower -i` output -> name and percentage; null when it reports no percentage. */
	static Battery parse(String info) {
		Matcher model = MODEL.matcher(info), percent = PERCENT.matcher(info);
		if (!percent.find()) {
			return null;
		}
		return new Battery(model.find() ? model.group(1).trim() : "Headphones", Integer.parseInt(percent.group(1)));
	}

	/** Output of a command, or "" when it fails (e.g. no UPower installed). */
	private static String run(String... cmd) {
		try {
			ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
			pb.environment().put("LC_ALL", "C.UTF-8");
			Process p = pb.start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			return p.waitFor() == 0 ? out : "";
		} catch (Exception e) {
			return "";
		}
	}
}
