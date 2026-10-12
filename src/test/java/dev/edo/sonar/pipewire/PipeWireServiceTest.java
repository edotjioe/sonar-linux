package dev.edo.sonar.pipewire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PipeWireServiceTest {

	@Test
	void quoteEscapesQuotesAndBackslashes() {
		assertEquals("\"a\\\"b\\\\c\"", PipeWireService.quote("a\"b\\c"));
	}

	@Test
	void eqConfOutputChannel() {
		double[] gains = new double[10];
		gains[0] = 3.5;

		String conf = PipeWireService.eqConf("sonar_game", "Game", "alsa_out", gains, false);

		assertTrue(conf.contains("target.object = \"sonar_game\" stream.capture.sink = true"));
		assertTrue(conf.contains("node.name = \"sonar_game_eq_out\" target.object = \"alsa_out\""));
		assertTrue(conf.contains("audio.channels = 2"));
		assertTrue(conf.contains("name = b9 label = bq_peaking"));
		assertTrue(conf.contains("\"Freq\" = 31.0 \"Q\" = 1.41 \"Gain\" = 3.5"));
		assertTrue(conf.contains("{ output = \"b8:Out\" input = \"b9:In\" }"));
		assertFalse(conf.contains("\"b9:Out\""));
	}

	@Test
	void eqConfMic() {
		String conf = PipeWireService.eqConf("sonar_mic", "Mic", "alsa_in", new double[10], true);

		assertTrue(conf.contains("node.name = \"sonar_mic\" media.class = Audio/Source node.description = \"Mic\""));
		assertTrue(conf.contains("target.object = \"alsa_in\" node.passive = true"));
		assertTrue(conf.contains("audio.channels = 1"));
		assertTrue(conf.contains("MONO"));
		assertFalse(conf.contains("stream.capture.sink"));
	}

	@Test
	void appKey() {
		assertEquals("zen-bin", PipeWireService.appKey("zen-bin", "Zen"));
		assertEquals("warhammer 40,000: space marine 2", PipeWireService.appKey("wine64-preloader", "Warhammer 40,000: Space Marine 2"));
		assertEquals("game", PipeWireService.appKey("wine-preloader", "Game"));
		assertEquals("game", PipeWireService.appKey("wine64", "Game"));
		assertEquals("game", PipeWireService.appKey("wine", "Game"));
		assertEquals("winecfg", PipeWireService.appKey("winecfg", "Wine Config"));
		assertEquals("firefox", PipeWireService.appKey(null, "Firefox"));
		assertEquals("firefox", PipeWireService.appKey("", "Firefox"));
	}

	@Test
	void eqConfEscapesDescription() {
		String conf = PipeWireService.eqConf("sonar_game", "x\" } evil = { ", "alsa_out", new double[10], false);

		assertTrue(conf.contains("x\\\" } evil"));
		assertFalse(conf.contains("x\" } evil"));
	}
}
