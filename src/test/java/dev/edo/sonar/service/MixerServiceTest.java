package dev.edo.sonar.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import dev.edo.sonar.domain.Channel;
import dev.edo.sonar.domain.EqProfile;
import dev.edo.sonar.domain.Preferences;
import dev.edo.sonar.pipewire.PipeWireService;
import dev.edo.sonar.repo.AppAssignmentRepository;
import dev.edo.sonar.repo.ChannelRepository;
import dev.edo.sonar.repo.EqProfileRepository;
import dev.edo.sonar.repo.PreferencesRepository;

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
		verify(pw).startEq(eq("sonar_extra"), eq("Extra"), eq("out"), any(), eq(false));
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
		when(channels.findById(1L)).thenReturn(Optional.of(c));
		double[] gains = new double[EqProfile.BANDS.length];
		gains[0] = 20;
		gains[9] = -20;

		service.setEq(1L, gains);

		assertEquals(12.0, c.getEq()[0]);
		assertEquals(-12.0, c.getEq()[9]);
		verify(pw).setEqGains(eq("sonar_game"), any());
	}

	@Test
	void setEqRejectsWrongGains() {
		Channel c = channel(1L);
		when(channels.findById(1L)).thenReturn(Optional.of(c));

		assertThrows(IllegalArgumentException.class, () -> service.setEq(1L, null));
		assertThrows(IllegalArgumentException.class, () -> service.setEq(1L, new double[3]));
	}

	@Test
	void saveProfileCreatesCustom() {
		when(profiles.findByName("Mine")).thenReturn(Optional.empty());
		when(profiles.save(any(EqProfile.class))).thenAnswer(i -> i.getArgument(0));

		EqProfile p = service.saveProfile("Mine", new double[EqProfile.BANDS.length]);

		assertEquals("Mine", p.getName());
		assertEquals(false, p.isBuiltIn());
	}

	@Test
	void saveProfileRejectsBuiltIn() {
		when(profiles.findByName("Flat"))
				.thenReturn(Optional.of(new EqProfile("Flat", new double[EqProfile.BANDS.length], true)));

		assertThrows(IllegalArgumentException.class, () -> service.saveProfile("Flat", new double[EqProfile.BANDS.length]));
	}

	@Test
	void saveProfileOverwritesCustom() {
		EqProfile existing = new EqProfile("Mine", new double[EqProfile.BANDS.length], false);
		when(profiles.findByName("Mine")).thenReturn(Optional.of(existing));
		when(profiles.save(any(EqProfile.class))).thenAnswer(i -> i.getArgument(0));
		double[] gains = new double[EqProfile.BANDS.length];
		Arrays.fill(gains, 3.0);

		EqProfile p = service.saveProfile("Mine", gains);

		assertEquals(3.0, p.getGains()[0]);
	}

	@Test
	void deleteProfileRejectsBuiltIn() {
		EqProfile builtIn = new EqProfile("Flat", new double[EqProfile.BANDS.length], true);
		when(profiles.findById(1L)).thenReturn(Optional.of(builtIn));

		assertThrows(IllegalArgumentException.class, () -> service.deleteProfile(1L));
	}

	@Test
	void deleteProfileDeletesCustom() {
		EqProfile custom = new EqProfile("Mine", new double[EqProfile.BANDS.length], false);
		when(profiles.findById(1L)).thenReturn(Optional.of(custom));

		service.deleteProfile(1L);

		verify(profiles).delete(custom);
	}

	@Test
	void setDeviceOnOutputChannelStartsEqOnly() {
		Channel c = channel(1L);
		c.setSinkName("sonar_game");
		c.setName("Game");
		when(channels.findById(1L)).thenReturn(Optional.of(c));

		service.setDevice(1L, "hdmi");

		verify(pw).startEq(eq("sonar_game"), eq("Game"), eq("hdmi"), any(), eq(false));
		verify(pw, never()).setVolume(anyString(), anyInt(), anyBoolean());
	}

	@Test
	void setDeviceOnMicChannelForgetsAndSetsVolumeMute() {
		Channel c = channel(1L);
		c.setSinkName("sonar_mic");
		c.setName("Mic");
		c.setMic(true);
		when(channels.findById(1L)).thenReturn(Optional.of(c));

		service.setDevice(1L, "alsa_in");

		verify(meters).forget(c);
		verify(pw).startEq(eq("sonar_mic"), eq("Mic"), eq("alsa_in"), any(), eq(true));
		verify(pw).setVolume("sonar_mic", 100, true);
		verify(pw).setMute("sonar_mic", false, true);
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
}
