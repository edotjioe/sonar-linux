package dev.sonar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OverlayHotkeyTest {

	@Test
	void parseMetaLetter() {
		assertEquals(0x10000000 | 0x53, OverlayHotkey.parse("Meta+S"));
	}

	@Test
	void parseCtrlAltLetter() {
		assertEquals(0x04000000 | 0x08000000 | 0x53, OverlayHotkey.parse("Ctrl+Alt+S"));
	}

	@Test
	void parseMetaDigit() {
		assertEquals(0x10000000 | 0x31, OverlayHotkey.parse("Meta+1"));
	}

	@Test
	void parseFKeyAlone() {
		assertEquals(0x01000038, OverlayHotkey.parse("F9"));
	}

	@Test
	void parseRejectsLowercaseModifier() {
		assertThrows(IllegalArgumentException.class, () -> OverlayHotkey.parse("ctrl+alt+s"));
	}

	@Test
	void parseRejectsPlainLetter() {
		assertThrows(IllegalArgumentException.class, () -> OverlayHotkey.parse("S"));
	}

	@Test
	void parseRejectsNoKey() {
		assertThrows(IllegalArgumentException.class, () -> OverlayHotkey.parse("Meta"));
	}

	@Test
	void parseRejectsTwoKeys() {
		assertThrows(IllegalArgumentException.class, () -> OverlayHotkey.parse("Meta+S+D"));
	}

	@Test
	void parseRejectsUnsupportedKey() {
		assertThrows(IllegalArgumentException.class, () -> OverlayHotkey.parse("Meta+Space"));
	}

	@Test
	void parseRejectsBlankAndNull() {
		assertThrows(IllegalArgumentException.class, () -> OverlayHotkey.parse(""));
		assertThrows(IllegalArgumentException.class, () -> OverlayHotkey.parse(null));
	}

	@Test
	void formatMetaLetter() {
		assertEquals("Meta+S", OverlayHotkey.format(268435539));
	}

	@Test
	void formatCtrlAltShiftLetter() {
		assertEquals("Ctrl+Alt+Shift+A", OverlayHotkey.format(0x04000000 | 0x08000000 | 0x02000000 | 0x41));
	}

	@Test
	void formatMetaFKey() {
		assertEquals("Meta+F12", OverlayHotkey.format(0x10000000 | 0x0100003B));
	}

	@Test
	void formatRoundTrip() {
		assertEquals("Ctrl+Alt+S", OverlayHotkey.format(OverlayHotkey.parse("Ctrl+Alt+S")));
		assertEquals("Meta+F5", OverlayHotkey.format(OverlayHotkey.parse("Meta+F5")));
		assertEquals("Alt+7", OverlayHotkey.format(OverlayHotkey.parse("Alt+7")));
	}

	@Test
	void otherOwnerFriendly() {
		assertEquals("Dolphin", OverlayHotkey.otherOwner(
				"a(ssssssaiai) 1 \"_launch\" \"Dolphin\" \"org.kde.dolphin.desktop\" \"Dolphin\" \"default\" \"Default Context\" 1 268435525 1 268435525"));
	}

	@Test
	void otherOwnerOwnOverlay() {
		assertNull(OverlayHotkey.otherOwner(
				"a(ssssssaiai) 1 \"_launch\" \"Sonar Overlay\" \"dev.sonar.overlay.desktop\" \"Sonar Overlay\" \"default\" \"Default Context\" 1 268435539 1 268435539"));
	}

	@Test
	void otherOwnerKwin() {
		assertEquals("KWin (Toggle Present Windows (Current desktop))", OverlayHotkey.otherOwner(
				"a(ssssssaiai) 1 \"Expose\" \"Toggle Present Windows (Current desktop)\" \"kwin\" \"KWin\" \"default\" \"Default Context\" 2 285212728 83886136 2 285212728 83886136"));
	}

	@Test
	void otherOwnerEmpty() {
		assertNull(OverlayHotkey.otherOwner("a(ssssssaiai) 0"));
	}

	@Test
	void otherOwnerBlankName() {
		assertEquals("Foo", OverlayHotkey.otherOwner(
				"a(ssssssaiai) 1 \"_launch\" \"Foo\" \"foo.desktop\" \"\" \"default\" \"Default Context\" 1 268435525 1 268435525"));
	}

	@Test
	void otherOwnerSkipsOwn() {
		assertEquals("Dolphin", OverlayHotkey.otherOwner(
				"a(ssssssaiai) 2 \"_launch\" \"Sonar Overlay\" \"dev.sonar.overlay.desktop\" \"Sonar Overlay\" \"default\" \"Default Context\" 1 268435539 1 268435539 \"_launch\" \"Dolphin\" \"org.kde.dolphin.desktop\" \"Dolphin\" \"default\" \"Default Context\" 1 268435525 1 268435525"));
	}
}
