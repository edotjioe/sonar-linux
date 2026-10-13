package dev.sonar.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * The global hotkey that toggles the overlay. It belongs to KDE: kglobalaccel binds it to the overlay
 * launcher (install/dev.sonar.overlay.desktop, default Meta+S), so we read and change it there over
 * D-Bus (busctl), as System Settings → Shortcuts does. Elsewhere (e.g. GNOME) it isn't supported.
 *
 * Keys travel as Qt key codes: modifier bits | key, e.g. Meta+S = 0x10000000 | 0x53.
 */
@Component
public class OverlayHotkey {

	private static final String[] ACTION = { "dev.sonar.overlay.desktop", "_launch", "Sonar Overlay", "Sonar Overlay" };

	private static final Map<String, Integer> MODIFIERS = Map.of(
			"Meta", 0x10000000, "Ctrl", 0x04000000, "Alt", 0x08000000, "Shift", 0x02000000);
	private static final String[] ORDER = { "Meta", "Ctrl", "Alt", "Shift" };
	private static final int MODIFIER_MASK = 0x1e000000;
	private static final int F1 = 0x01000030;

	/** The current hotkey, e.g. "Meta+S"; "" when none is set; null when KDE's kglobalaccel isn't there. */
	public String current() {
		String out = busctl("shortcut", "as", "4", ACTION[0], ACTION[1], ACTION[2], ACTION[3]);
		if (out == null) {
			return null;
		}
		List<Integer> keys = ints(out);
		return keys.isEmpty() ? "" : format(keys.get(0));
	}

	/** Bind the overlay to `combo` ("Ctrl+Alt+S"); refuses a key another program already uses. */
	public void set(String combo) {
		int key = parse(combo);
		String owners = busctl("getGlobalShortcutsByKey", "i", Integer.toString(key));
		if (owners == null) {
			throw new IllegalStateException("Changing the hotkey needs KDE Plasma; set it in your desktop's shortcut settings");
		}
		String other = otherOwner(owners);
		if (other != null) {
			throw new IllegalArgumentException(format(key) + " is already used by " + other);
		}
		if (busctl("setForeignShortcut", "asai", "4", ACTION[0], ACTION[1], ACTION[2], ACTION[3], "1",
				Integer.toString(key)) == null) {
			throw new IllegalStateException("KDE refused the hotkey " + format(key));
		}
	}

	/** "Ctrl+Alt+S" -> Qt key code. Letters, digits and F1–F24; a modifier is required except on F-keys. */
	static int parse(String combo) {
		if (combo == null || combo.isBlank()) {
			throw new IllegalArgumentException("No hotkey given");
		}
		int mods = 0;
		Integer key = null;
		for (String part : combo.split("\\+")) {
			Integer mod = MODIFIERS.get(part.trim());
			if (mod != null) {
				mods |= mod;
			} else if (key != null) {
				throw new IllegalArgumentException("Only one key besides modifiers: " + combo);
			} else {
				key = keyCode(part.trim().toUpperCase());
			}
		}
		if (key == null) {
			throw new IllegalArgumentException("A hotkey needs a key besides modifiers: " + combo);
		}
		if (mods == 0 && key < F1) {
			throw new IllegalArgumentException("Use a modifier (Meta, Ctrl, Alt) with " + combo
					+ ", or it would fire while typing");
		}
		return mods | key;
	}

	private static int keyCode(String key) {
		if (key.matches("[A-Z0-9]")) {
			return key.charAt(0); // Qt::Key_A.. and Key_0.. are their ASCII codes
		}
		Matcher f = Pattern.compile("F([1-9]|1[0-9]|2[0-4])").matcher(key);
		if (f.matches()) {
			return F1 + Integer.parseInt(f.group(1)) - 1;
		}
		throw new IllegalArgumentException("Unsupported key: " + key + " (use a letter, digit or F1–F24)");
	}

	/** Qt key code -> "Meta+S". */
	static String format(int code) {
		StringBuilder s = new StringBuilder();
		for (String m : ORDER) {
			if ((code & MODIFIERS.get(m)) != 0) {
				s.append(m).append('+');
			}
		}
		int key = code & ~MODIFIER_MASK;
		return s.append(key >= F1 ? "F" + (key - F1 + 1) : String.valueOf((char) key)).toString();
	}

	/**
	 * From getGlobalShortcutsByKey's busctl output — a(ssssssaiai): action id, action name, component id,
	 * component name, ... per owner — the first owner's friendly name that isn't our overlay, or null.
	 */
	static String otherOwner(String busctlOut) {
		Matcher m = Pattern.compile("\"([^\"]*)\" \"([^\"]*)\" \"([^\"]*)\" \"([^\"]*)\" \"[^\"]*\" \"[^\"]*\"").matcher(busctlOut);
		while (m.find()) {
			if (!m.group(3).equals(ACTION[0])) {
				String component = !m.group(4).isBlank() ? m.group(4) : !m.group(2).isBlank() ? m.group(2) : m.group(3);
				// an app launcher's action is named after the app: "Dolphin", not "Dolphin (Dolphin)"
				return m.group(2).isBlank() || m.group(2).equals(component) ? component : component + " (" + m.group(2) + ")";
			}
		}
		return null;
	}

	/** The ints of busctl's "ai N a b ..." output. */
	private static List<Integer> ints(String out) {
		String[] parts = out.trim().split("\\s+");
		List<Integer> keys = new ArrayList<>();
		for (int i = 2; i < parts.length; i++) {
			keys.add(Integer.parseInt(parts[i]));
		}
		return keys;
	}

	/** A kglobalaccel D-Bus call; its output, or null when it fails (no KDE session bus service). */
	private static String busctl(String method, String signature, String... args) {
		List<String> cmd = new ArrayList<>(List.of("busctl", "--user", "call", "org.kde.kglobalaccel", "/kglobalaccel",
				"org.kde.KGlobalAccel", method, signature));
		cmd.addAll(List.of(args));
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			return p.waitFor() == 0 ? out : null;
		} catch (Exception e) {
			return null;
		}
	}
}
