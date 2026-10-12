package dev.edo.sonar.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import dev.edo.sonar.domain.EqProfile;
import dev.edo.sonar.obs.ObsClient;
import dev.edo.sonar.pipewire.PipeWireService;
import dev.edo.sonar.repo.ChannelRepository;
import dev.edo.sonar.repo.EqProfileRepository;

/** On startup: clear leftover modules/EQ processes, seed EQ presets, ensure default channels exist, restore their state. */
@Component
public class Bootstrapper implements ApplicationRunner {

	private final PipeWireService pw;
	private final ChannelRepository channels;
	private final MixerService mixer;
	private final EqProfileRepository profiles;
	private final ObsClient obs;

	@Value("${sonar.default-channels:Game,Chat,Media,Aux}")
	private String defaultChannels;

	public Bootstrapper(PipeWireService pw, ChannelRepository channels, MixerService mixer,
			EqProfileRepository profiles, ObsClient obs) {
		this.pw = pw;
		this.channels = channels;
		this.mixer = mixer;
		this.profiles = profiles;
		this.obs = obs;
	}

	@Override
	public void run(ApplicationArguments args) {
		pw.stopAllEq();
		pw.unloadSonarModules();
		EqProfile.PRESETS.forEach((name, gains) -> {
			if (profiles.findByName(name).isEmpty()) {
				profiles.save(new EqProfile(name, gains, true));
			}
		});
		// restore existing channels first; createChannel loads its own modules, so new ones must come after
		for (var channel : channels.findAll()) {
			mixer.applyChannel(channel);
		}
		for (String name : defaultChannels.split(",")) {
			name = name.trim();
			// by sink name, so a renamed default channel isn't recreated
			if (!name.isBlank() && channels.findBySinkName(MixerService.sinkNameFor(name)).isEmpty()) {
				mixer.createChannel(name, false);
			}
		}
		if (channels.findBySinkName("sonar_mic").isEmpty()) {
			mixer.createChannel("Mic", true);
		}
		// our sinks are brand new: an already-running OBS lost its capture of the old ones
		obs.reattachSonarInputs();
	}
}
