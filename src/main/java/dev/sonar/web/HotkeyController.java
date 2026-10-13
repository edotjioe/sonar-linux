package dev.sonar.web;

import java.util.HashMap;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.sonar.service.OverlayHotkey;

/** The overlay's global hotkey (KDE only). */
@RestController
@RequestMapping("/api/hotkey")
public class HotkeyController {

	private final OverlayHotkey hotkey;

	public HotkeyController(OverlayHotkey hotkey) {
		this.hotkey = hotkey;
	}

	/** {key: "Meta+S"}; key "" = none set; key null = can't be changed here (not KDE). */
	@GetMapping
	public Map<String, String> get() {
		Map<String, String> out = new HashMap<>(); // Map.of rejects the null
		out.put("key", hotkey.current());
		return out;
	}

	/** Body: {key: "Ctrl+Alt+S"}. 400 when the key is invalid or taken (the message says by what). */
	@PutMapping
	public Map<String, String> set(@RequestBody Map<String, String> body) {
		hotkey.set(body.get("key"));
		return get();
	}
}
