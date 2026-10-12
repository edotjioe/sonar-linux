package dev.edo.sonar.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import dev.edo.sonar.pipewire.PipeWireModels.AppStream;

/**
 * Guesses which channel (by name: Game/Chat/Media) an unassigned app belongs in.
 * Signals, most reliable first: Steam's SteamAppId in the process environment,
 * the stream's media.role, then the Categories of the app's .desktop file.
 *
 * ponytail: the .desktop index is built once; apps installed while Sonar runs are
 * only picked up after a restart.
 */
@Component
public class AppClassifier {

	private volatile Map<String, String> categoriesByName;

	/** Channel name for this stream, or null when there's no signal. */
	public String classify(AppStream s) {
		if (isSteamGame(s.pid())) {
			return "Game";
		}
		String byRole = byRole(s.mediaRole());
		if (byRole != null) {
			return byRole;
		}
		return byCategories(desktopCategories().get(s.binary().toLowerCase()));
	}

	static String byRole(String role) {
		if (role == null) {
			return null;
		}
		return switch (role) {
			case "Game" -> "Game";
			case "Communication", "Phone" -> "Chat";
			case "Music", "Movie", "Video" -> "Media";
			default -> null;
		};
	}

	static String byCategories(String categories) {
		if (categories == null) {
			return null;
		}
		List<String> cats = List.of(categories.split(";"));
		if (cats.contains("Game")) {
			return "Game";
		}
		if (cats.contains("InstantMessaging") || cats.contains("Chat") || cats.contains("Telephony")
				|| cats.contains("VideoConference")) {
			return "Chat";
		}
		// browsers can't be split per tab: treat them as media
		if (cats.contains("AudioVideo") || cats.contains("Audio") || cats.contains("Video")
				|| cats.contains("Player") || cats.contains("WebBrowser")) {
			return "Media";
		}
		return null;
	}

	/** Binary name an Exec= line launches: skips `env` and VAR=value, drops the path. */
	static String execBinary(String exec) {
		for (String token : exec.trim().split("\\s+")) {
			if (token.equals("env") || token.contains("=")) {
				continue;
			}
			return Path.of(token.replace("\"", "")).getFileName().toString().toLowerCase();
		}
		return null;
	}

	/** Steam sets SteamAppId (non-zero) for every game it launches, native or Proton. */
	private static boolean isSteamGame(int pid) {
		if (pid <= 0) {
			return false;
		}
		try {
			String env = new String(Files.readAllBytes(Path.of("/proc", Integer.toString(pid), "environ")),
					StandardCharsets.UTF_8);
			for (String var : env.split("\0")) {
				if ((var.startsWith("SteamAppId=") || var.startsWith("SteamGameId=")) && !var.endsWith("=0")) {
					return true;
				}
			}
		} catch (IOException e) {
			// process gone or not ours
		}
		return false;
	}

	/** binary or desktop-id (lowercased) -> Categories, from every .desktop file on XDG_DATA_DIRS. */
	private Map<String, String> desktopCategories() {
		if (categoriesByName == null) {
			Map<String, String> map = new HashMap<>();
			String dirs = System.getenv().getOrDefault("XDG_DATA_DIRS", "/usr/local/share:/usr/share");
			String home = System.getProperty("user.home") + "/.local/share";
			// flatpak dirs added explicitly: a systemd user service may not have them in XDG_DATA_DIRS.
			// user dirs last so they win, matching XDG precedence
			String all = "/var/lib/flatpak/exports/share:" + dirs + ":" + home + "/flatpak/exports/share:" + home;
			for (String dir : all.split(":")) {
				try (Stream<Path> files = Files.list(Path.of(dir, "applications"))) {
					files.filter(f -> f.toString().endsWith(".desktop")).forEach(f -> index(f, map));
				} catch (IOException e) {
					// dir doesn't exist
				}
			}
			categoriesByName = map;
		}
		return categoriesByName;
	}

	private static void index(Path file, Map<String, String> map) {
		String exec = null;
		String categories = null;
		try {
			for (String line : Files.readAllLines(file)) {
				if (line.startsWith("[") && !line.equals("[Desktop Entry]")) {
					break; // only the main section; skip [Desktop Action ...]
				}
				if (exec == null && line.startsWith("Exec=")) {
					exec = line.substring(5);
				} else if (categories == null && line.startsWith("Categories=")) {
					categories = line.substring(11);
				}
			}
		} catch (IOException | RuntimeException e) {
			return; // unreadable or not UTF-8
		}
		if (categories == null) {
			return;
		}
		// desktop id: "zen.desktop" -> "zen", "com.discordapp.Discord.desktop" -> "discord" (flatpak ids)
		String id = file.getFileName().toString().replaceFirst("\\.desktop$", "").toLowerCase();
		map.put(id, categories);
		map.put(id.substring(id.lastIndexOf('.') + 1), categories);
		if (exec != null) {
			String bin = execBinary(exec);
			if (bin != null && !bin.equals("flatpak")) {
				map.put(bin, categories);
			}
		}
	}
}
