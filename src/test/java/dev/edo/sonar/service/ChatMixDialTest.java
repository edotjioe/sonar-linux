package dev.edo.sonar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChatMixDialTest {

	@Test
	void mixFromDial() {
		assertEquals(0, ChatMixDial.mixFromDial(100, 100));
		assertEquals(-100, ChatMixDial.mixFromDial(100, 0));
		assertEquals(100, ChatMixDial.mixFromDial(0, 100));
		assertEquals(-40, ChatMixDial.mixFromDial(100, 60));
		assertEquals(70, ChatMixDial.mixFromDial(30, 100));
		assertEquals(-100, ChatMixDial.mixFromDial(255, 0));
		assertEquals(100, ChatMixDial.mixFromDial(0, 255));
	}
}
