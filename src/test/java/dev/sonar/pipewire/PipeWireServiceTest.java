package dev.sonar.pipewire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.sonar.domain.EqBand;

class PipeWireServiceTest {

	@Test
	void quoteEscapesQuotesAndBackslashes() {
		assertEquals("\"a\\\"b\\\\c\"", PipeWireService.quote("a\"b\\c"));
	}

	@Test
	void descriptionArgQuotesAndReplacesQuotes() {
		assertEquals("device.description=\"Sonar Stream\"", PipeWireService.descriptionArg("Sonar Stream"));
		assertEquals("device.description=\"My 'Mix'\"", PipeWireService.descriptionArg("My \"Mix\""));
	}

	@Test
	void eqConfOutputChannel() {
		String conf = PipeWireService.eqConf("sonar_game", "Game", "alsa_out", EqBand.graphic(3.5), false, null, null, null, null);

		assertTrue(conf.contains("target.object = \"sonar_game\" stream.capture.sink = true"));
		assertTrue(conf.contains("node.name = \"sonar_game_eq_out\" target.object = \"alsa_out\""));
		assertTrue(conf.contains("audio.channels = 2"));
		assertTrue(conf.contains("name = b9 label = bq_peaking"));
		assertTrue(conf.contains("\"Freq\" = 31.0 \"Q\" = 1.41 \"Gain\" = 3.5"));
		assertTrue(conf.contains("{ output = \"b8:Out\" input = \"b9:In\" }"));
		assertFalse(conf.contains("\"b9:Out\""));

		List<EqBand> bands = new ArrayList<>(EqBand.graphic(3.5));
		bands.set(0, new EqBand("lowshelf", 31, 3.5, 1.41));
		String lowshelfConf = PipeWireService.eqConf("sonar_game", "Game", "alsa_out", bands, false, null, null, null, null);
		assertTrue(lowshelfConf.contains("label = bq_lowshelf"));
	}

	@Test
	void eqConfNotchAndBandpassLabels() {
		List<EqBand> bands = new ArrayList<>(EqBand.graphic(3.5));
		bands.set(0, new EqBand("notch", 1000, 0, 5));
		bands.set(1, new EqBand("bandpass", 2000, 0, 2));
		String conf = PipeWireService.eqConf("sonar_game", "Game", "alsa_out", bands, false, null, null, null, null);

		assertTrue(conf.contains("label = bq_notch"));
		assertTrue(conf.contains("label = bq_bandpass"));
	}

	@Test
	void eqConfMic() {
		String conf = PipeWireService.eqConf("sonar_mic", "Mic", "alsa_in", EqBand.graphic(), true, null, null, null, null);

		assertTrue(conf.contains("node.name = \"sonar_mic_eq_in\" target.object = \"alsa_in\" node.passive = true"));
		// linked into the virtual mic by startEq: WirePlumber would send it to the default output instead
		assertTrue(conf.contains("node.name = \"sonar_mic_eq_out\" node.autoconnect = false"));
		assertTrue(conf.contains("audio.channels = 1"));
		assertTrue(conf.contains("MONO"));
		assertFalse(conf.contains("stream.capture.sink"));
		assertFalse(conf.contains("media.class = Audio/Source"));
	}

	@Test
	void eqConfEmptyBandsAddsNeutralPeakingNode() {
		String conf = PipeWireService.eqConf("sonar_game", "Game", "alsa_out", List.of(), false, null, null, null, null);
		assertTrue(conf.contains("name = b0 label = bq_peaking"));
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
		String conf = PipeWireService.eqConf("sonar_game", "x\" } evil = { ", "alsa_out", EqBand.graphic(), false, null, null, null, null);

		assertTrue(conf.contains("x\\\" } evil"));
		assertFalse(conf.contains("x\" } evil"));
	}

	@Test
	void eqConfMicWithRnnoiseAddsNoiseSuppressor() {
		String conf = PipeWireService.eqConf("sonar_mic", "Mic", "alsa_in", EqBand.graphic(), true,
				Path.of("/usr/lib/ladspa/librnnoise_ladspa.so"), null, null, null);

		assertTrue(conf.contains("ladspa"));
		assertTrue(conf.contains("ns:Output"));
		assertTrue(conf.contains("noise_suppressor_mono"));
		assertTrue(conf.contains("plugin = \"/usr/lib/ladspa/librnnoise_ladspa.so\""));
		assertTrue(conf.contains("{ output = \"ns:Output\" input = \"b0:In\" }"));
	}

	@Test
	void eqConfMicWithoutRnnoiseHasNoSuppressor() {
		String conf = PipeWireService.eqConf("sonar_mic", "Mic", "alsa_in", EqBand.graphic(), true, null, null, null, null);

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

	@Test
	void eqConfVolumeAddsLinearGainNode() {
		String conf = PipeWireService.eqConf("sonar_game", "Game", "alsa_out", EqBand.graphic(), false, null, 0.5, null, null);

		assertTrue(conf.contains("label = linear"));
		assertTrue(conf.contains("\"vol:In\""));
	}

	@Test
	void eqConfStreamAddsStreamFeed() {
		String conf = PipeWireService.eqConf("sonar_game", "Game", "alsa_out", EqBand.graphic(), false, null, null, 0.25, null);

		assertTrue(conf.contains("_stream_in"));
		assertTrue(conf.contains("_stream_out"));
		assertTrue(conf.contains("\"sonar_stream\""));
	}

	@Test
	void eqConfWithoutStreamHasNoStreamFeed() {
		String conf = PipeWireService.eqConf("sonar_game", "Game", "alsa_out", EqBand.graphic(), false, null, null, null, null);

		assertFalse(conf.contains("_stream_in"));
	}

	@Test
	void eqConfSpatialAddsSofaChain() {
		String conf = PipeWireService.eqConf("sonar_game", "Game", "alsa_out", EqBand.graphic(), false, null, null, null,
				Path.of("/x/h.sofa"));

		assertTrue(conf.contains("label = spatializer"));
		assertTrue(conf.contains("\"/x/h.sofa\""));
		assertTrue(conf.contains("sonar_game_spatial_in"));
		assertTrue(conf.contains("sonar_game_spatial_out"));
		assertTrue(conf.contains("audio.channels = 8"));
		assertTrue(conf.contains("target.object = \"sonar_game_spatial_in\""));
	}

	@Test
	void eqConfWithoutHrtfHasNoSpatializer() {
		String conf = PipeWireService.eqConf("sonar_game", "Game", "alsa_out", EqBand.graphic(), false, null, null, null,
				null);

		assertFalse(conf.contains("spatializer"));
	}

	@Test
	void hrtfCandidatesOverrideFirstThenXdgDirsThenFallbacks() {
		List<String> c = PipeWireService.hrtfCandidates(
				Map.of("XDG_DATA_DIRS", "/opt/share/:/usr/share", "HOMEBREW_PREFIX", "/opt/brew"), "/home/u");
		assertEquals("/home/u/.local/share/sonar/hrtf.sofa", c.get(0));
		assertEquals("/home/u/.local/share/libmysofa/MIT_KEMAR_normal_pinna.sofa", c.get(1));
		assertEquals("/home/u/.local/share/libmysofa/default.sofa", c.get(2));
		assertEquals("/opt/share/libmysofa/MIT_KEMAR_normal_pinna.sofa", c.get(3)); // trailing slash trimmed
		assertTrue(c.contains("/home/linuxbrew/.linuxbrew/share/libmysofa/default.sofa"));
		assertTrue(c.contains("/home/u/.nix-profile/share/libmysofa/default.sofa"));
		assertTrue(c.contains("/opt/brew/share/libmysofa/default.sofa"));
		assertEquals(c.size(), c.stream().distinct().count()); // /usr/share from XDG and the fallbacks: once
	}

	@Test
	void hrtfCandidatesUseXdgDefaultsWhenUnset() {
		List<String> c = PipeWireService.hrtfCandidates(Map.of("XDG_DATA_HOME", "/data"), "/home/u");
		assertEquals("/data/sonar/hrtf.sofa", c.get(0));
		assertEquals("/data/libmysofa/MIT_KEMAR_normal_pinna.sofa", c.get(1));
		assertEquals("/usr/local/share/libmysofa/MIT_KEMAR_normal_pinna.sofa", c.get(3));
		assertEquals("/usr/share/libmysofa/MIT_KEMAR_normal_pinna.sofa", c.get(5));
	}
}
