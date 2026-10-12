package dev.sonar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AppClassifierTest {

	@Test
	void categories() {
		assertEquals("Game", AppClassifier.byCategories("Game;ActionGame;"));
		assertEquals("Chat", AppClassifier.byCategories("Network;InstantMessaging;"));
		assertEquals("Media", AppClassifier.byCategories("AudioVideo;Audio;Player;"));
		assertEquals("Media", AppClassifier.byCategories("Network;WebBrowser;"));
		assertNull(AppClassifier.byCategories("Utility;TextEditor;"));
		assertNull(AppClassifier.byCategories(null));
	}

	@Test
	void roles() {
		assertEquals("Chat", AppClassifier.byRole("Communication"));
		assertEquals("Media", AppClassifier.byRole("Music"));
		assertNull(AppClassifier.byRole("Event"));
		assertNull(AppClassifier.byRole(null));
	}

	@Test
	void execBinary() {
		assertEquals("zen-bin", AppClassifier.execBinary("/opt/zen-browser-bin/zen-bin %u"));
		assertEquals("discord", AppClassifier.execBinary("env GDK_BACKEND=x11 /usr/bin/Discord --flag"));
		assertEquals("flatpak", AppClassifier.execBinary("/usr/bin/flatpak run com.discordapp.Discord"));
	}
}
