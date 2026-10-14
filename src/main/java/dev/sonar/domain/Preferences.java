package dev.sonar.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** App-wide preferences: a single row with id 1. */
@Entity
public class Preferences {

	@Id
	private Long id = 1L;

	/** What the global hotkey opens: "overlay" or "window". */
	private String hotkeyOpens = "overlay";

	/** Route unassigned apps to the channel AppClassifier guesses. */
	private boolean autoRoute = true;

	/** ChatMix dial, -100 (all Game) .. 0 (both full) .. 100 (all Chat). Set via /api/chatmix only. */
	@Column(columnDefinition = "integer default 0")
	private int chatMix = 0;

	/** Show level meters (they record every channel, mic included, while the UI is open). */
	private boolean meters = true;

	/** A second mix (Sonar Stream) for OBS, with its own volume per channel. */
	@Column(columnDefinition = "boolean default false")
	private boolean streamerMode = false;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getHotkeyOpens() {
		return hotkeyOpens;
	}

	public void setHotkeyOpens(String hotkeyOpens) {
		this.hotkeyOpens = "window".equals(hotkeyOpens) ? "window" : "overlay";
	}

	public boolean isAutoRoute() {
		return autoRoute;
	}

	public void setAutoRoute(boolean autoRoute) {
		this.autoRoute = autoRoute;
	}

	public int getChatMix() {
		return chatMix;
	}

	public void setChatMix(int chatMix) {
		this.chatMix = chatMix;
	}

	public boolean isMeters() {
		return meters;
	}

	public void setMeters(boolean meters) {
		this.meters = meters;
	}

	public boolean isStreamerMode() {
		return streamerMode;
	}

	public void setStreamerMode(boolean streamerMode) {
		this.streamerMode = streamerMode;
	}
}
