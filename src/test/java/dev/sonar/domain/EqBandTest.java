package dev.sonar.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class EqBandTest {

	@Test
	void checkClampsOutOfRangeValues() {
		List<EqBand> bands = new ArrayList<>(EqBand.graphic());
		bands.set(0, new EqBand("peaking", 5, 30, 50));

		List<EqBand> checked = EqBand.check(bands);

		assertEquals(20.0, checked.get(0).freq());
		assertEquals(12.0, checked.get(0).gain());
		assertEquals(10.0, checked.get(0).q());
	}

	@Test
	void checkAcceptsNotchAndBandpassAndClamps() {
		List<EqBand> bands = new ArrayList<>(EqBand.graphic());
		bands.set(0, new EqBand("notch", 30000, 30, 50));
		bands.set(1, new EqBand("bandpass", 30000, 30, 50));

		List<EqBand> checked = EqBand.check(bands);

		assertEquals(20000.0, checked.get(0).freq());
		assertEquals(12.0, checked.get(0).gain());
		assertEquals(10.0, checked.get(0).q());
		assertEquals("notch", checked.get(0).type());
		assertEquals(20000.0, checked.get(1).freq());
		assertEquals(12.0, checked.get(1).gain());
		assertEquals(10.0, checked.get(1).q());
		assertEquals("bandpass", checked.get(1).type());
	}

	@Test
	void checkRejectsUnknownTypeAndWrongSize() {
		List<EqBand> unknown = new ArrayList<>(EqBand.graphic());
		unknown.set(0, new EqBand("allpass", 31, 0, 1.41));
		assertThrows(IllegalArgumentException.class, () -> EqBand.check(unknown));

		List<EqBand> eleven = new ArrayList<>(EqBand.graphic());
		eleven.add(new EqBand("peaking", 1000, 0, 1.41));
		assertThrows(IllegalArgumentException.class, () -> EqBand.check(eleven));
	}

	@Test
	void checkToneClampsAndValidates() {
		assertArrayEquals(new double[] { 12, -12, 1 }, EqBand.checkTone(new double[] { 20, -20, 1 }));
		assertThrows(IllegalArgumentException.class, () -> EqBand.checkTone(null));
		assertThrows(IllegalArgumentException.class, () -> EqBand.checkTone(new double[2]));
	}

	@Test
	void fromLegacyDropsFlatBandsAndNullIsEmpty() {
		assertEquals(List.of(
				new EqBand("peaking", 31, 3, 1.41),
				new EqBand("peaking", 125, -2, 1.41),
				new EqBand("peaking", 500, 1.5, 1.41),
				new EqBand("peaking", 4000, 4, 1.41)),
				EqBand.fromLegacy(new double[] { 3, 0, -2, 0, 1.5, 0, 0, 4, 0, 0 }));
		assertEquals(List.of(), EqBand.fromLegacy(null));
	}
}
