package dev.sonar.web;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Auto-update: the sonar-update.timer user unit (install/install.sh) that runs install/update.sh. */
@RestController
@RequestMapping("/api/auto-update")
public class UpdateController {

	private static final String TIMER = "sonar-update.timer";

	/** {enabled}: whether the timer is enabled. */
	@GetMapping
	public Map<String, Boolean> get() {
		return Map.of("enabled", systemctl("is-enabled", TIMER) == 0);
	}

	/** Body: {enabled}. 503 when the timer isn't installed (an install from before it existed). */
	@PutMapping
	public Map<String, Boolean> set(@RequestBody Map<String, Boolean> body) {
		boolean on = Boolean.TRUE.equals(body.get("enabled"));
		// --now: enabling starts the timer, and as its 2 min after login have passed, it runs an update right away
		if (systemctl(on ? "enable" : "disable", "--now", TIMER) != 0) {
			throw new IllegalStateException("Couldn't switch auto-update: re-run install/install.sh to install its timer");
		}
		return get();
	}

	private static int systemctl(String... args) {
		String[] cmd = new String[args.length + 2];
		cmd[0] = "systemctl";
		cmd[1] = "--user";
		System.arraycopy(args, 0, cmd, 2, args.length);
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8); // drain
			return p.waitFor();
		} catch (Exception e) {
			return -1;
		}
	}
}
