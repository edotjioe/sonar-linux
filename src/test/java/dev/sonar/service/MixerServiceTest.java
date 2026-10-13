package dev.sonar.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import dev.sonar.domain.Channel;
import dev.sonar.domain.EqBand;
import dev.sonar.domain.EqProfile;
import dev.sonar.domain.Preferences;
import dev.sonar.pipewire.PipeWireModels;
import dev.sonar.pipewire.PipeWireService;
import dev.sonar.repo.AppAssignmentRepository;
import dev.sonar.repo.ChannelRepository;
import dev.sonar.repo.EqProfileRepository;
import dev.sonar.repo.PreferencesRepository;

@ExtendWith(MockitoExtension.class)
class MixerServiceTest {

	@Mock
	private PipeWireService pw;
	@Mock
	private ChannelRepository channels;
	@Mock
	private AppAssignmentRepository assignments;
	@Mock
	private LevelMeter meters;
	@Mock
	private EqProfileRepository profiles;
	@Mock
	private PreferencesRepository preferences;

	private Preferences prefs;

	private MixerService service;

	@BeforeEach
	void setUp() {
		prefs = new Preferences();
		lenient().when(preferences.get()).thenReturn(prefs);
		service = new MixerService(pw, channels, assignments, meters, profiles, preferences);
		lenient().when(channels.save(any(Channel.class))).thenAnswer(i -> i.getArgument(0));
	}

	private static Channel channel(long id) {
		Channel c = new Channel();
		ReflectionTestUtils.setField(c, "id", id);
		return c;
	}

	@Test
	void sinkNameForNormalizes() {
		assertEquals("sonar_voice_chat_", MixerService.sinkNameFor("Voice Chat!"));
		assertEquals("sonar_game", MixerService.sinkNameFor(" Game "));
	}

	@Test
	void createChannelRejectsBlankNames() {
		assertThrows(IllegalArgumentException.class, () -> service.createChannel(null, false));
		assertThrows(IllegalArgumentException.class, () -> service.createChannel("", false));
		assertThrows(IllegalArgumentException.class, () -> service.createChannel("  ", false));
		assertThrows(IllegalArgumentException.class, () -> service.createChannel("!!!", false));
	}

	@Test
	void createChannelRejectsDuplicateSinkName() {
		when(channels.findBySinkName("sonar_game")).thenReturn(Optional.of(new Channel()));
		assertThrows(IllegalArgumentException.class, () -> service.createChannel("Game", false));
	}

	@Test
	void createChannelRejectsDuplicateName() {
		when(channels.findBySinkName("sonar_game")).thenReturn(Optional.empty());
		when(channels.findByName("Game")).thenReturn(Optional.of(new Channel()));
		assertThrows(IllegalArgumentException.class, () -> service.createChannel("Game", false));
	}

	@Test
	void createChannelAssignsNextPosition() {
		Channel a = new Channel();
		a.setPosition(0);
		Channel b = new Channel();
		b.setPosition(5);
		when(channels.findAllByOrderByPositionAsc()).thenReturn(List.of(a, b));
		when(pw.defaultSink()).thenReturn("out");
		when(pw.loadNullSink(anyString(), anyString())).thenReturn(10L);

		Channel c = service.createChannel("Extra", false);

		assertEquals("sonar_extra", c.getSinkName());
		assertEquals(6, c.getPosition());
		assertEquals(10L, c.getNullSinkModule());
		verify(pw).startEq(eq("sonar_extra"), eq("Extra"), eq("out"), any(), eq(false), eq(false));
	}

	@Test
	void renameTrimsAndSaves() {
		Channel c = channel(1L);
		when(channels.findById(1L)).thenReturn(Optional.of(c));
		when(channels.findByName("New")).thenReturn(Optional.empty());

		Channel renamed = service.rename(1L, "  New ");

		assertEquals("New", renamed.getName());
		verify(channels).save(c);
	}

	@Test
	void renameToOtherChannelNameThrows() {
		Channel mine = channel(1L);
		when(channels.findById(1L)).thenReturn(Optional.of(mine));
		when(channels.findByName("New")).thenReturn(Optional.of(channel(2L)));

		assertThrows(IllegalArgumentException.class, () -> service.rename(1L, "New"));
	}

	@Test
	void renameToOwnNameSucceeds() {
		Channel c = channel(1L);
		when(channels.findById(1L)).thenReturn(Optional.of(c));
		when(channels.findByName("Game")).thenReturn(Optional.of(c));

		assertEquals("Game", service.rename(1L, "Game").getName());
	}

	@Test
	void reorderFollowsIdsAndKeepsUnlistedLast() {
		Channel c1 = channel(1L);
		c1.setPosition(0);
		Channel c2 = channel(2L);
		c2.setPosition(1);
		Channel c3 = channel(3L);
		c3.setPosition(2);
		when(channels.findAllByOrderByPositionAsc()).thenReturn(List.of(c1, c2, c3));

		service.reorder(List.of(3L, 1L));

		assertEquals(1, c1.getPosition());
		assertEquals(2, c2.getPosition());
		assertEquals(0, c3.getPosition());
		verify(channels).saveAll(any());
	}

	@Test
	void setVolumeClampsToRange() {
		Channel c = channel(1L);
		c.setSinkName("sonar_game");
		when(channels.findById(1L)).thenReturn(Optional.of(c));

		service.setVolume(1L, 150);
		assertEquals(100, c.getVolume());
		verify(pw).setVolume("sonar_game", 100, false);

		service.setVolume(1L, -5);
		assertEquals(0, c.getVolume());
		verify(pw).setVolume("sonar_game", 0, false);
	}

	@Test
	void micVolumeClampsToMixerMax() {
		Channel c = channel(1L);
		c.setMic(true);
		c.setSinkName("sonar_mic");
		when(channels.findById(1L)).thenReturn(Optional.of(c));

		service.setVolume(1L, 150);
		assertEquals(150, c.getVolume());
		verify(pw).setVolume("sonar_mic", 150, true);

		service.setVolume(1L, 250);
		assertEquals(200, c.getVolume());
		verify(pw).setVolume("sonar_mic", 200, true);
	}

	@Test
	void mixedScalesGameAndChat() {
		assertEquals(80, MixerService.mixed(80, "sonar_game", 0));
		assertEquals(40, MixerService.mixed(80, "sonar_game", 50));
		assertEquals(80, MixerService.mixed(80, "sonar_game", -50));
		assertEquals(60, MixerService.mixed(80, "sonar_chat", -25));
		assertEquals(80, MixerService.mixed(80, "sonar_chat", 100));
		assertEquals(80, MixerService.mixed(80, "sonar_media", 100));
		assertEquals(0, MixerService.mixed(100, "sonar_game", 100));
	}

	@Test
	void setChatMixClamps() {
		service.setChatMix(150);

		assertEquals(100, prefs.getChatMix());
		verify(preferences).save(prefs);
	}

	@Test
	void setChatMixReappliesGameAndChatVolumes() {
		Channel game = channel(1L);
		game.setSinkName("sonar_game");
		game.setVolume(80);
		Channel chat = channel(2L);
		chat.setSinkName("sonar_chat");
		chat.setVolume(60);
		when(channels.findBySinkName("sonar_game")).thenReturn(Optional.of(game));
		when(channels.findBySinkName("sonar_chat")).thenReturn(Optional.of(chat));

		service.setChatMix(50);

		verify(pw).setVolume("sonar_game", 40, false);
		verify(pw).setVolume("sonar_chat", 60, false);
	}

	@Test
	void setChatMixWithMissingChannelsDoesNothing() {
		service.setChatMix(-20);

		verify(pw, never()).setVolume(anyString(), anyInt(), anyBoolean());
	}

	@Test
	void setVolumeStoresUnmixedButSendsMixed() {
		prefs.setChatMix(50);
		Channel game = channel(1L);
		game.setSinkName("sonar_game");
		when(channels.findById(1L)).thenReturn(Optional.of(game));

		service.setVolume(1L, 80);

		assertEquals(80, game.getVolume());
		verify(pw).setVolume("sonar_game", 40, false);
	}

	@Test
	void setVolumeOnMicSkipsChatMix() {
		prefs.setChatMix(100);
		Channel mic = channel(1L);
		mic.setSinkName("sonar_mic");
		mic.setMic(true);
		when(channels.findById(1L)).thenReturn(Optional.of(mic));

		service.setVolume(1L, 70);

		verify(pw).setVolume("sonar_mic", 70, true);
	}

	@Test
	void setEqClampsGains() {
		Channel c = channel(1L);
		c.setSinkName("sonar_game");
		c.setEq(EqBand.graphic());
		when(channels.findById(1L)).thenReturn(Optional.of(c));
		List<EqBand> bands = new ArrayList<>(EqBand.graphic());
		bands.set(0, new EqBand("peaking", 31, 20, 1.41));
		bands.set(9, new EqBand("peaking", 16000, -20, 1.41));

		service.setEq(1L, bands);

		assertEquals(12.0, c.getEq().get(0).gain());
		assertEquals(-12.0, c.getEq().get(9).gain());
		verify(pw).setEqParams(eq("sonar_game"), any());
	}

	@Test
	void setEqRejectsWrongGains() {
		Channel c = channel(1L);
		when(channels.findById(1L)).thenReturn(Optional.of(c));

		List<EqBand> eleven = new ArrayList<>(EqBand.graphic());
		eleven.add(new EqBand("peaking", 1000, 0, 1.41));

		assertThrows(IllegalArgumentException.class, () -> service.setEq(1L, null));
		assertThrows(IllegalArgumentException.class, () -> service.setEq(1L, eleven));
	}

	@Test
	void setToneClampsAndAppendsToneFilters() {
		Channel c = channel(1L);
		c.setSinkName("sonar_game");
		when(channels.findById(1L)).thenReturn(Optional.of(c));

		service.setTone(1L, new double[]{20, -3, 0});

		assertArrayEquals(new double[]{12, -3, 0}, c.getTone());
		verify(pw).setEqParams(eq("sonar_game"), eq(EqBand.tone(new double[]{12, -3, 0})));
		assertThrows(IllegalArgumentException.class, () -> service.setTone(1L, new double[]{1, 2}));
	}

	@Test
	void setEqAppliesParamsForSameTypesButRestartsForChangedType() {
		Channel c = channel(1L);
		c.setSinkName("sonar_game");
		c.setName("Game");
		c.setEq(EqBand.graphic());
		when(channels.findById(1L)).thenReturn(Optional.of(c));

		service.setEq(1L, EqBand.graphic());

		verify(pw).setEqParams(eq("sonar_game"), any());
		verify(pw, never()).startEq(any(), any(), any(), any(), anyBoolean(), anyBoolean());

		reset(pw);

		List<EqBand> changed = new ArrayList<>(EqBand.graphic());
		changed.set(0, new EqBand("lowshelf", 31, 0, 1.41));
		service.setEq(1L, changed);

		verify(pw).startEq(eq("sonar_game"), eq("Game"), any(), any(), eq(false), eq(false));
		verify(pw, never()).setEqParams(anyString(), any());
	}

	@Test
	void saveProfileCreatesCustom() {
		when(profiles.findByName("Mine")).thenReturn(Optional.empty());
		when(profiles.save(any(EqProfile.class))).thenAnswer(i -> i.getArgument(0));

		EqProfile p = service.saveProfile("Mine", EqBand.graphic());

		assertEquals("Mine", p.getName());
		assertEquals(false, p.isBuiltIn());
	}

	@Test
	void saveProfileRejectsBuiltIn() {
		when(profiles.findByName("Flat"))
				.thenReturn(Optional.of(new EqProfile("Flat", EqBand.graphic(), true)));

		assertThrows(IllegalArgumentException.class, () -> service.saveProfile("Flat", EqBand.graphic()));
	}

	@Test
	void saveProfileOverwritesCustom() {
		EqProfile existing = new EqProfile("Mine", EqBand.graphic(), false);
		when(profiles.findByName("Mine")).thenReturn(Optional.of(existing));
		when(profiles.save(any(EqProfile.class))).thenAnswer(i -> i.getArgument(0));

		EqProfile p = service.saveProfile("Mine", EqBand.graphic(3.0));

		assertEquals(3.0, p.getBands().get(0).gain());
	}

	@Test
	void deleteProfileRejectsBuiltIn() {
		EqProfile builtIn = new EqProfile("Flat", EqBand.graphic(), true);
		when(profiles.findById(1L)).thenReturn(Optional.of(builtIn));

		assertThrows(IllegalArgumentException.class, () -> service.deleteProfile(1L));
	}

	@Test
	void deleteProfileDeletesCustom() {
		EqProfile custom = new EqProfile("Mine", EqBand.graphic(), false);
		when(profiles.findById(1L)).thenReturn(Optional.of(custom));

		service.deleteProfile(1L);

		verify(profiles).delete(custom);
	}

	@Test
	void isDeletableFalseForRenamedGameChannel() {
		Channel c = channel(1L);
		c.setName("Games");
		c.setSinkName("sonar_game");

		assertFalse(service.isDeletable(c));
	}

	@Test
	void isDeletableFalseForMicChannel() {
		Channel c = channel(1L);
		c.setMic(true);
		c.setSinkName("sonar_mic");

		assertFalse(service.isDeletable(c));
	}

	@Test
	void isDeletableTrueForUserChannel() {
		Channel c = channel(1L);
		c.setSinkName("sonar_extra");

		assertTrue(service.isDeletable(c));
	}

	@Test
	void deleteChannelOnGameChannelThrows() {
		Channel game = channel(1L);
		game.setName("Game");
		game.setSinkName("sonar_game");
		when(channels.findById(1L)).thenReturn(Optional.of(game));

		assertThrows(IllegalArgumentException.class, () -> service.deleteChannel(1L));

		verify(pw, never()).unloadModule(anyLong());
		verify(channels, never()).delete(any());
	}

	@Test
	void deleteChannelOnUserChannelSucceeds() {
		Channel extra = channel(2L);
		extra.setName("Extra");
		extra.setSinkName("sonar_extra");
		extra.setNullSinkModule(7L);
		when(channels.findById(2L)).thenReturn(Optional.of(extra));

		service.deleteChannel(2L);

		verify(meters).forget(extra);
		verify(pw).stopEq("sonar_extra");
		verify(pw).unloadModule(7L);
		verify(channels).delete(extra);
	}

	@Test
	void setDeviceOnOutputChannelStartsEqOnly() {
		Channel c = channel(1L);
		c.setSinkName("sonar_game");
		c.setName("Game");
		when(channels.findById(1L)).thenReturn(Optional.of(c));

		service.setDevice(1L, "hdmi");

		verify(pw).startEq(eq("sonar_game"), eq("Game"), eq("hdmi"), any(), eq(false), eq(false));
		verify(pw, never()).setVolume(anyString(), anyInt(), anyBoolean());
	}

	@Test
	void setDeviceOnMicChannelSkipsStartEqWhenNotRunning() {
		Channel c = channel(1L);
		c.setSinkName("sonar_mic");
		c.setName("Mic");
		c.setMic(true);
		when(channels.findById(1L)).thenReturn(Optional.of(c));
		when(pw.isEqRunning("sonar_mic")).thenReturn(false);

		service.setDevice(1L, "alsa_in");

		verify(pw, never()).startEq(any(), any(), any(), any(), anyBoolean(), anyBoolean());
	}

	@Test
	void setDeviceOnMicChannelStartsEqWhenRunning() {
		Channel c = channel(1L);
		c.setSinkName("sonar_mic");
		c.setName("Mic");
		c.setMic(true);
		when(channels.findById(1L)).thenReturn(Optional.of(c));
		when(pw.isEqRunning("sonar_mic")).thenReturn(true);

		service.setDevice(1L, "alsa_in");

		verify(pw).startEq(eq("sonar_mic"), eq("Mic"), eq("alsa_in"), any(), eq(true), eq(false));
	}

	@Test
	void routeToMicChannelThrows() {
		Channel mic = channel(1L);
		mic.setMic(true);
		when(channels.findById(1L)).thenReturn(Optional.of(mic));

		assertThrows(IllegalArgumentException.class, () -> service.route("bin", "name", 1L));
	}

	@Test
	void setMuteUnknownChannelThrows() {
		when(channels.findById(99L)).thenReturn(Optional.empty());
		assertThrows(IllegalArgumentException.class, () -> service.setMute(99L, true));
	}

	@Test
	void setNoiseSuppressionOnNonMicChannelThrows() {
		Channel c = channel(1L);
		c.setSinkName("sonar_game");
		when(channels.findById(1L)).thenReturn(Optional.of(c));

		assertThrows(IllegalArgumentException.class, () -> service.setNoiseSuppression(1L, true));
		verify(pw, never()).startEq(any(), any(), any(), any(), anyBoolean(), anyBoolean());
	}

	@Test
	void enableNoiseSuppressionWithoutPluginThrows() {
		Channel c = channel(1L);
		c.setSinkName("sonar_mic");
		c.setMic(true);
		when(channels.findById(1L)).thenReturn(Optional.of(c));
		when(pw.rnnoisePlugin()).thenReturn(null);

		assertThrows(IllegalStateException.class, () -> service.setNoiseSuppression(1L, true));
		verify(pw, never()).startEq(any(), any(), any(), any(), anyBoolean(), anyBoolean());
	}

	@Test
	void enableNoiseSuppressionStartsDenoisedEqAndSaves() {
		Channel c = channel(1L);
		c.setSinkName("sonar_mic");
		c.setMic(true);
		when(channels.findById(1L)).thenReturn(Optional.of(c));
		when(pw.rnnoisePlugin()).thenReturn(Path.of("/usr/lib/ladspa/librnnoise_ladspa.so"));
		when(pw.isEqRunning("sonar_mic")).thenReturn(true);

		service.setNoiseSuppression(1L, true);

		assertTrue(c.isNoiseSuppression());
		verify(pw).startEq(eq("sonar_mic"), any(), any(), any(), eq(true), eq(true));
		verify(channels).save(c);
	}

	@Test
	void disableNoiseSuppressionWorksWithoutPlugin() {
		Channel c = channel(1L);
		c.setSinkName("sonar_mic");
		c.setMic(true);
		c.setNoiseSuppression(true);
		when(channels.findById(1L)).thenReturn(Optional.of(c));
		when(pw.isEqRunning("sonar_mic")).thenReturn(true);

		service.setNoiseSuppression(1L, false);

		assertFalse(c.isNoiseSuppression());
		verify(pw).startEq(eq("sonar_mic"), any(), any(), any(), eq(true), eq(false));
		verify(channels).save(c);
	}

	@Test
	void applyChannelOnMicLoadsVirtualSourceAndSkipsStartEq() {
		Channel c = channel(1L);
		c.setMic(true);
		c.setSinkName("sonar_mic");
		c.setName("Mic");
		c.setDeviceName("alsa_in");
		c.setVolume(70);
		c.setMuted(true);
		when(pw.loadVirtualSource("sonar_mic", "Mic")).thenReturn(42L);

		service.applyChannel(c);

		assertEquals(42L, c.getNullSinkModule());
		verify(pw).loadVirtualSource("sonar_mic", "Mic");
		verify(pw, never()).startEq(any(), any(), any(), any(), anyBoolean(), anyBoolean());
		verify(pw).setVolume("sonar_mic", 70, true);
		verify(pw).setMute("sonar_mic", true, true);
	}

	@Test
	void syncMicStartsEqWhenConsumersAndNotRunning() {
		Channel mic = channel(1L);
		mic.setMic(true);
		mic.setSinkName("sonar_mic");
		mic.setName("Mic");
		mic.setDeviceName("alsa_in");
		when(channels.findAllByOrderByPositionAsc()).thenReturn(List.of(mic));
		when(pw.sourceConsumers("sonar_mic")).thenReturn(1L);
		when(pw.isEqRunning("sonar_mic")).thenReturn(false);

		service.syncMic();

		verify(pw).startEq(eq("sonar_mic"), eq("Mic"), eq("alsa_in"), any(), eq(true), eq(false));
		verify(pw, never()).stopEq("sonar_mic");
	}

	@Test
	void syncMicDoesNothingWhenConsumersAndRunning() {
		Channel mic = channel(1L);
		mic.setMic(true);
		mic.setSinkName("sonar_mic");
		when(channels.findAllByOrderByPositionAsc()).thenReturn(List.of(mic));
		when(pw.sourceConsumers("sonar_mic")).thenReturn(1L);
		when(pw.isEqRunning("sonar_mic")).thenReturn(true);

		service.syncMic();

		verify(pw, never()).startEq(any(), any(), any(), any(), anyBoolean(), anyBoolean());
		verify(pw, never()).stopEq("sonar_mic");
	}

	@Test
	void syncMicStopsEqWhenNoConsumersAndNeverUsed() {
		Channel mic = channel(1L);
		mic.setMic(true);
		mic.setSinkName("sonar_mic");
		when(channels.findAllByOrderByPositionAsc()).thenReturn(List.of(mic));
		when(pw.sourceConsumers("sonar_mic")).thenReturn(0L);
		when(pw.isEqRunning("sonar_mic")).thenReturn(true);

		service.syncMic();

		verify(pw).stopEq("sonar_mic");
		verify(pw, never()).startEq(any(), any(), any(), any(), anyBoolean(), anyBoolean());
	}

	@Test
	void syncMicDoesNotStopRecentlyUsedMic() {
		Channel mic = channel(1L);
		mic.setMic(true);
		mic.setSinkName("sonar_mic");
		when(channels.findAllByOrderByPositionAsc()).thenReturn(List.of(mic));
		when(pw.sourceConsumers("sonar_mic")).thenReturn(1L, 0L);
		when(pw.isEqRunning("sonar_mic")).thenReturn(true);

		service.syncMic();
		service.syncMic();

		verify(pw, never()).stopEq("sonar_mic");
		verify(pw, never()).startEq(any(), any(), any(), any(), anyBoolean(), anyBoolean());
	}

	@Test
	void syncMicSkipsOutputChannels() {
		Channel out = channel(1L);
		out.setSinkName("sonar_game");
		when(channels.findAllByOrderByPositionAsc()).thenReturn(List.of(out));

		service.syncMic();

		verify(pw, never()).sourceConsumers(anyString());
		verify(pw, never()).startEq(any(), any(), any(), any(), anyBoolean(), anyBoolean());
		verify(pw, never()).stopEq(anyString());
	}

	@Test
	void setMicMutedMutesOnlyUnmutedMic() {
		Channel mic = channel(1L);
		mic.setMic(true);
		mic.setSinkName("sonar_mic");
		Channel out = channel(2L);
		out.setSinkName("sonar_game");
		when(channels.findAllByOrderByPositionAsc()).thenReturn(List.of(mic, out));
		when(channels.findById(1L)).thenReturn(Optional.of(mic));

		service.setMicMuted(true);

		verify(pw).setMute("sonar_mic", true, true);
		verify(pw, never()).setMute(eq("sonar_game"), anyBoolean(), anyBoolean());
	}

	@Test
	void setMicMutedSkipsAlreadyMutedMic() {
		Channel mic = channel(1L);
		mic.setMic(true);
		mic.setSinkName("sonar_mic");
		mic.setMuted(true);
		when(channels.findAllByOrderByPositionAsc()).thenReturn(List.of(mic));

		service.setMicMuted(true);

		verify(pw, never()).setMute(anyString(), anyBoolean(), anyBoolean());
	}

	@Test
	void setMicMutedUnmutesMutedMic() {
		Channel mic = channel(1L);
		mic.setMic(true);
		mic.setSinkName("sonar_mic");
		mic.setMuted(true);
		when(channels.findAllByOrderByPositionAsc()).thenReturn(List.of(mic));
		when(channels.findById(1L)).thenReturn(Optional.of(mic));

		service.setMicMuted(false);

		verify(pw).setMute("sonar_mic", false, true);
	}

	@Test
	void setMicMutedIgnoresOutputChannels() {
		Channel out = channel(1L);
		out.setSinkName("sonar_game");
		out.setMuted(true);
		when(channels.findAllByOrderByPositionAsc()).thenReturn(List.of(out));

		service.setMicMuted(false);

		verify(pw, never()).setMute(anyString(), anyBoolean(), anyBoolean());
	}

	@Test
	void applyChannelMicReplacesMonitorDeviceWithDefaultMic() {
		Channel c = channel(1L);
		c.setMic(true);
		c.setSinkName("sonar_mic");
		c.setName("Mic");
		c.setDeviceName("alsa_output.x.monitor");
		when(pw.indexToName("sources"))
				.thenReturn(Map.of(0, "alsa_output.x.monitor", 1, "alsa_input.a", 2, "alsa_input.b"));
		when(pw.listPhysicalSources())
				.thenReturn(List.of(new PipeWireModels.PhysicalSink("alsa_input.a", "a"),
						new PipeWireModels.PhysicalSink("alsa_input.b", "b")));
		when(pw.defaultSource()).thenReturn("alsa_input.b");

		service.applyChannel(c);

		assertEquals("alsa_input.b", c.getDeviceName());
	}

	@Test
	void applyChannelMicFallsBackToFirstPhysicalWhenDefaultIsMonitor() {
		Channel c = channel(1L);
		c.setMic(true);
		c.setSinkName("sonar_mic");
		c.setName("Mic");
		c.setDeviceName("alsa_output.x.monitor");
		when(pw.indexToName("sources"))
				.thenReturn(Map.of(0, "alsa_output.x.monitor", 1, "alsa_input.a", 2, "alsa_input.b"));
		when(pw.listPhysicalSources())
				.thenReturn(List.of(new PipeWireModels.PhysicalSink("alsa_input.a", "a"),
						new PipeWireModels.PhysicalSink("alsa_input.b", "b")));
		when(pw.defaultSource()).thenReturn("alsa_output.x.monitor");

		service.applyChannel(c);

		assertEquals("alsa_input.a", c.getDeviceName());
	}

	@Test
	void applyChannelMicKeepsUnpluggedDevice() {
		Channel c = channel(1L);
		c.setMic(true);
		c.setSinkName("sonar_mic");
		c.setName("Mic");
		c.setDeviceName("alsa_input.usb-gone");
		when(pw.indexToName("sources")).thenReturn(Map.of(0, "alsa_input.a", 1, "alsa_input.b"));
		when(pw.listPhysicalSources())
				.thenReturn(List.of(new PipeWireModels.PhysicalSink("alsa_input.a", "a"),
						new PipeWireModels.PhysicalSink("alsa_input.b", "b")));
		lenient().when(pw.defaultSource()).thenReturn("alsa_input.a");

		service.applyChannel(c);

		assertEquals("alsa_input.usb-gone", c.getDeviceName());
	}

	@Test
	void applyChannelMicKeepsPhysicalDevice() {
		Channel c = channel(1L);
		c.setMic(true);
		c.setSinkName("sonar_mic");
		c.setName("Mic");
		c.setDeviceName("alsa_input.a");
		lenient().when(pw.indexToName("sources")).thenReturn(Map.of(0, "alsa_input.a", 1, "alsa_input.b"));
		when(pw.listPhysicalSources())
				.thenReturn(List.of(new PipeWireModels.PhysicalSink("alsa_input.a", "a"),
						new PipeWireModels.PhysicalSink("alsa_input.b", "b")));
		lenient().when(pw.defaultSource()).thenReturn("alsa_input.a");

		service.applyChannel(c);

		assertEquals("alsa_input.a", c.getDeviceName());
	}

	@Test
	void applyChannelReplacesSavedErrorMessageWithDefaultOutput() {
		Channel c = channel(1L);
		c.setSinkName("sonar_game");
		c.setName("Game");
		c.setDeviceName("Connection failure: Connection refused\npa_context_connect() failed: Connection refused");
		when(pw.defaultSink()).thenReturn("alsa_output.speakers");

		service.applyChannel(c);

		assertEquals("alsa_output.speakers", c.getDeviceName());
	}
}
