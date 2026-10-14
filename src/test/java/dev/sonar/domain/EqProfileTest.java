package dev.sonar.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class EqProfileTest {

	@Test
	void getKindDefaultsToOutput() {
		EqProfile p = new EqProfile("Mine", EqBand.graphic(), false);

		assertEquals(EqProfile.OUTPUT, p.getKind());
	}

	@Test
	void fitsByKind() {
		EqProfile both = new EqProfile("Both", EqBand.graphic(), false);
		both.setKind(EqProfile.BOTH);
		EqProfile mic = new EqProfile("Mic", EqBand.graphic(), false);
		mic.setKind(EqProfile.MIC);
		EqProfile output = new EqProfile("Output", EqBand.graphic(), false);
		output.setKind(EqProfile.OUTPUT);

		assertTrue(both.fits(true));
		assertTrue(both.fits(false));
		assertTrue(mic.fits(true));
		assertFalse(mic.fits(false));
		assertTrue(output.fits(false));
		assertFalse(output.fits(true));
	}

	@Test
	void presetKindByPresetName() {
		assertEquals(EqProfile.BOTH, EqProfile.presetKind("Flat"));
		assertEquals(EqProfile.MIC, EqProfile.presetKind("Broadcast"));
		assertEquals(EqProfile.OUTPUT, EqProfile.presetKind("Bass Boost"));
	}
}
