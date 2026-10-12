package dev.sonar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LevelMeterTest {

	@Test
	void peakDecodesSigned16BitLittleEndian() {
		// samples: 1000, -32768 (full scale negative), 16384
		byte[] buf = { (byte) 0xe8, 0x03, 0x00, (byte) 0x80, 0x00, 0x40 };
		assertEquals(1.0, LevelMeter.peak(buf, 6));
		assertEquals(1000 / 32768.0, LevelMeter.peak(buf, 2));
	}

	@Test
	void percentIsMinus60To0Db() {
		assertEquals(0, LevelMeter.toPercent(0));
		assertEquals(0, LevelMeter.toPercent(0.0001)); // -80 dB, below the floor
		assertEquals(0, LevelMeter.toPercent(0.001)); // -60 dB: the floor
		assertEquals(50, LevelMeter.toPercent(Math.pow(10, -30 / 20.0))); // -30 dB is halfway
		assertEquals(100, LevelMeter.toPercent(1.0));
	}
}
