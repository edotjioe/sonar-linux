package dev.sonar.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.sonar.domain.Channel;
import dev.sonar.domain.EqProfile;
import dev.sonar.obs.ObsClient;
import dev.sonar.pipewire.PipeWireService;
import dev.sonar.repo.ChannelRepository;
import dev.sonar.repo.EqProfileRepository;

/**
 * On startup: clear leftover modules/EQ processes, seed EQ presets, ensure default channels exist, restore their state.
 * Afterwards: rebuild everything when PipeWire restarts (e.g. after an update), which takes our null-sinks and EQs with it.
 */
@Component
public class Bootstrapper implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(Bootstrapper.class);

	private final PipeWireService pw;
	private final ChannelRepository channels;
	private final MixerService mixer;
	private final EqProfileRepository profiles;
	private final ObsClient obs;
	private final LevelMeter meters;

	private volatile boolean ready;
	private boolean missingBefore;

	@Value("${sonar.default-channels:Game,Chat,Media,Aux}")
	private String defaultChannels;

	public Bootstrapper(PipeWireService pw, ChannelRepository channels, MixerService mixer,
			EqProfileRepository profiles, ObsClient obs, LevelMeter meters) {
		this.pw = pw;
		this.channels = channels;
		this.mixer = mixer;
		this.profiles = profiles;
		this.obs = obs;
		this.meters = meters;
	}

	@Override
	public void run(ApplicationArguments args) {
		pw.stopAllEq();
		pw.unloadSonarModules();
		// rewrite the built-ins every start, so changed preset definitions reach existing databases
		EqProfile.PRESETS.forEach((name, bands) -> profiles.findByName(name).ifPresentOrElse(p -> {
			if (p.isBuiltIn()) {
				p.setBands(bands);
				profiles.save(p);
			}
		}, () -> profiles.save(new EqProfile(name, bands, true))));
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
		ready = true;
	}

	/**
	 * Channels gone from a running PipeWire on two checks in a row: it restarted, so rebuild them all.
	 * Two in a row, because a channel briefly lacks its node while it's being deleted or its mic EQ restarts.
	 *
	 * ponytail: polls `pactl` every 3 s rather than subscribing to PipeWire events.
	 */
	@Scheduled(fixedDelay = 3000)
	void recoverAfterPipeWireRestart() {
		if (!ready || !pw.isUp()) {
			return;
		}
		Set<String> live = new HashSet<>(pw.indexToName("sinks").values());
		live.addAll(pw.indexToName("sources").values());
		List<Channel> all = channels.findAll();
		boolean missing = !all.stream().allMatch(c -> live.contains(c.getSinkName()));
		if (missing && missingBefore) {
			log.warn("Sonar channels are gone from PipeWire (did it restart?); rebuilding them");
			all.forEach(meters::forget); // their parec died with the old nodes
			pw.stopAllEq();
			pw.unloadSonarModules();
			all.forEach(mixer::applyChannel);
			obs.reattachSonarInputs();
			missing = false;
		}
		missingBefore = missing;
	}
}
