package dev.sonar.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import dev.sonar.domain.Channel;
import dev.sonar.obs.ObsClient;
import dev.sonar.pipewire.PipeWireService;
import dev.sonar.repo.ChannelRepository;
import dev.sonar.repo.EqProfileRepository;

@ExtendWith(MockitoExtension.class)
class BootstrapperTest {

	@Mock
	private PipeWireService pw;
	@Mock
	private ChannelRepository channels;
	@Mock
	private MixerService mixer;
	@Mock
	private EqProfileRepository profiles;
	@Mock
	private ObsClient obs;
	@Mock
	private LevelMeter meters;

	private Bootstrapper bootstrapper;

	@BeforeEach
	void setUp() {
		bootstrapper = new Bootstrapper(pw, channels, mixer, profiles, obs, meters);
		ReflectionTestUtils.setField(bootstrapper, "defaultChannels", "");
		bootstrapper.run(null);
		clearInvocations(pw, channels, mixer, obs, meters);
	}

	private static Channel channel(String name, String sinkName) {
		Channel c = new Channel();
		c.setName(name);
		c.setSinkName(sinkName);
		return c;
	}

	@Test
	void recoverDoesNothingWhenPipeWireIsDown() {
		when(pw.isUp()).thenReturn(false);

		bootstrapper.recoverAfterPipeWireRestart();
		bootstrapper.recoverAfterPipeWireRestart();

		verify(pw, never()).stopAllEq();
		verify(pw, never()).unloadSonarModules();
		verify(mixer, never()).applyChannel(any());
		verify(obs, never()).reattachSonarInputs();
	}

	@Test
	void recoverRebuildsOnlyAfterTwoMissingChecks() {
		Channel game = channel("Game", "sonar_game");
		when(pw.isUp()).thenReturn(true);
		when(channels.findAll()).thenReturn(List.of(game));

		bootstrapper.recoverAfterPipeWireRestart();
		verify(pw, never()).stopAllEq();
		verify(pw, never()).unloadSonarModules();
		verify(mixer, never()).applyChannel(any());
		verify(obs, never()).reattachSonarInputs();

		bootstrapper.recoverAfterPipeWireRestart();
		verify(pw).stopAllEq();
		verify(pw).unloadSonarModules();
		verify(mixer).loadStreamSink();
		verify(mixer).applyChannel(game);
		verify(obs).reattachSonarInputs();
	}

	@Test
	void recoverDoesNothingWhenAllChannelSinksArePresent() {
		Channel game = channel("Game", "sonar_game");
		when(pw.isUp()).thenReturn(true);
		when(channels.findAll()).thenReturn(List.of(game));
		when(pw.indexToName("sinks")).thenReturn(Map.of(1, "sonar_game"));
		when(pw.indexToName("sources")).thenReturn(Map.of());

		bootstrapper.recoverAfterPipeWireRestart();
		bootstrapper.recoverAfterPipeWireRestart();

		verify(pw, never()).stopAllEq();
		verify(pw, never()).unloadSonarModules();
		verify(mixer, never()).applyChannel(any());
		verify(obs, never()).reattachSonarInputs();
	}
}
