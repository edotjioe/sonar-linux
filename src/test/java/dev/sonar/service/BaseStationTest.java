package dev.sonar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class BaseStationTest {

	@Test
	void mixFromDial() {
		assertEquals(0, BaseStation.mixFromDial(100, 100));
		assertEquals(-100, BaseStation.mixFromDial(100, 0));
		assertEquals(100, BaseStation.mixFromDial(0, 100));
		assertEquals(-40, BaseStation.mixFromDial(100, 60));
		assertEquals(70, BaseStation.mixFromDial(30, 100));
		assertEquals(-100, BaseStation.mixFromDial(255, 0));
		assertEquals(100, BaseStation.mixFromDial(0, 255));
	}

	private static byte[] status(int... bytes) {
		byte[] r = new byte[64];
		for (int i = 0; i < bytes.length; i++) {
			r[i] = (byte) bytes[i];
		}
		return r;
	}

	private static byte[] headsetOn() {
		return status(0x06, 0xb0, 0x00, 0x00, 0x01, 0x00, 0x04, 0x08, 0x08, 0x00, 0x02, 0x0a, 0x05, 0x00, 0x08, 0x08);
	}

	@Test
	void parseStatusHeadsetOnUnmuted() {
		assertEquals(new BaseStation.Headset(true, false, 50, 100, false), BaseStation.parseStatus(headsetOn()));
	}

	@Test
	void parseStatusMicMuted() {
		byte[] r = headsetOn();
		r[9] = 0x01;
		assertEquals(new BaseStation.Headset(true, false, 50, 100, true), BaseStation.parseStatus(r));
	}

	@Test
	void parseStatusHeadsetOff() {
		byte[] r = status(0x06, 0xb0, 0x00, 0x00, 0x01, 0x00, 0x00, 0x08, 0x08, 0x00, 0x02, 0x0a, 0x05, 0x00, 0x04, 0x01);
		BaseStation.Headset h = BaseStation.parseStatus(r);
		assertEquals(new BaseStation.Headset(false, false, null, 100, false), h);
		assertNull(h.battery());
	}

	@Test
	void parseStatusCableCharging() {
		byte[] r = headsetOn();
		r[15] = 0x02;
		r[6] = 0x02;
		assertEquals(new BaseStation.Headset(true, true, 25, 100, false), BaseStation.parseStatus(r));
	}

	@Test
	void parseStatusClampsOutOfRangeBattery() {
		byte[] r = headsetOn();
		r[6] = 0x0a;
		assertEquals(new BaseStation.Headset(true, false, 100, 100, false), BaseStation.parseStatus(r));
	}
}
