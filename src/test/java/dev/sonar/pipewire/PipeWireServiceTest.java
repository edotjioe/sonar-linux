package dev.sonar.pipewire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

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

		String conf = PipeWireService.eqConf("sonar_game", "Game", "alsa_out", gains, false, null);

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
		String conf = PipeWireService.eqConf("sonar_mic", "Mic", "alsa_in", new double[10], true, null);

		assertTrue(conf.contains("node.name = \"sonar_mic_eq_in\" target.object = \"alsa_in\" node.passive = true"));
		// linked into the virtual mic by startEq: WirePlumber would send it to the default output instead
		assertTrue(conf.contains("node.name = \"sonar_mic_eq_out\" node.autoconnect = false"));
		assertTrue(conf.contains("audio.channels = 1"));
		assertTrue(conf.contains("MONO"));
		assertFalse(conf.contains("stream.capture.sink"));
		assertFalse(conf.contains("media.class = Audio/Source"));
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
		String conf = PipeWireService.eqConf("sonar_game", "x\" } evil = { ", "alsa_out", new double[10], false, null);

		assertTrue(conf.contains("x\\\" } evil"));
		assertFalse(conf.contains("x\" } evil"));
	}

	@Test
	void eqConfMicWithRnnoiseAddsNoiseSuppressor() {
		String conf = PipeWireService.eqConf("sonar_mic", "Mic", "alsa_in", new double[10], true,
				Path.of("/usr/lib/ladspa/librnnoise_ladspa.so"));

		assertTrue(conf.contains("ladspa"));
		assertTrue(conf.contains("ns:Output"));
		assertTrue(conf.contains("noise_suppressor_mono"));
		assertTrue(conf.contains("plugin = \"/usr/lib/ladspa/librnnoise_ladspa.so\""));
		assertTrue(conf.contains("{ output = \"ns:Output\" input = \"b0:In\" }"));
	}

	@Test
	void eqConfMicWithoutRnnoiseHasNoSuppressor() {
		String conf = PipeWireService.eqConf("sonar_mic", "Mic", "alsa_in", new double[10], true, null);

		assertFalse(conf.contains("ladspa"));
		assertFalse(conf.contains("ns:Output"));
	}

	@Test
	void isOutputTrueForMonitorSource() {
		String block = """
				75
					Name: alsa_output.pci-0000_03_00.1.hdmi-stereo.monitor
					Monitor of Sink: alsa_output.pci-0000_03_00.1.hdmi-stereo
					Properties:
						device.class = "monitor"
						media.class = "Audio/Sink"
				""";
		assertTrue(PipeWireService.isOutput(block));
	}

	@Test
	void isOutputFalseForRealMic() {
		String block = """
				83
					Name: alsa_input.usb-SteelSeries_Arctis_Nova_Pro_Wireless-00.mono-fallback
					Monitor of Sink: n/a
					Properties:
						device.class = "sound"
						media.class = "Audio/Source"
				""";
		assertFalse(PipeWireService.isOutput(block));
	}

	@Test
	void isOutputTrueWhenMonitorOfSinkNamed() {
		String block = """
				77
					Name: speakers_loopback
					Monitor of Sink: alsa_output.speakers
					Properties:
						media.class = "Audio/Source"
				""";
		assertTrue(PipeWireService.isOutput(block));
	}

	@Test
	void isOutputTrueWhenMediaClassIsSink() {
		String block = """
				88
					Name: alsa_output.usb-Generic_USB_Audio.monitor
					Monitor of Sink: n/a
					Properties:
						media.class = "Audio/Sink"
				""";
		assertTrue(PipeWireService.isOutput(block));
	}

	@Test
	void isOutputFalseForVirtualSource() {
		String block = """
				90
					Name: virtual_mic
					Monitor of Sink: n/a
					Properties:
						media.class = "Audio/Source/Virtual"
				""";
		assertFalse(PipeWireService.isOutput(block));
	}

	@Test
	void describeUsesPipeWireDescription() {
		String block = """
				51
					Name: alsa_input.pci-0000_00_1f.3.analog-stereo
					Description: Built-in Audio Analog Stereo
				""";
		assertEquals("Built-in Audio Analog Stereo",
				PipeWireService.describe(block, "alsa_input.pci-0000_00_1f.3.analog-stereo"));
	}

	@Test
	void describeFallsBackToTidiedName() {
		assertEquals("usb mic mono", PipeWireService.describe("Name: alsa_input.usb_mic.mono", "alsa_input.usb_mic.mono"));
	}
}
