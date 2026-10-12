package dev.sonar.service;

import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.sonar.domain.AppAssignment;
import dev.sonar.domain.Channel;
import dev.sonar.pipewire.PipeWireService;
import dev.sonar.repo.AppAssignmentRepository;
import dev.sonar.repo.ChannelRepository;
import dev.sonar.repo.PreferencesRepository;

/**
 * Polls for running streams and moves any whose app is assigned to a channel
 * but is currently playing elsewhere (keeps routing sticky across app restarts).
 * Apps without a manual assignment go to the channel {@link AppClassifier} guesses, if any.
 *
 * ponytail: polling every ~1s instead of subscribing to libpipewire events (C API).
 */
@Component
public class StreamWatcher {

	private final PipeWireService pw;
	private final AppAssignmentRepository assignments;
	private final ChannelRepository channels;
	private final AppClassifier classifier;
	private final PreferencesRepository preferences;

	public StreamWatcher(PipeWireService pw, AppAssignmentRepository assignments, ChannelRepository channels,
			AppClassifier classifier, PreferencesRepository preferences) {
		this.pw = pw;
		this.assignments = assignments;
		this.channels = channels;
		this.classifier = classifier;
		this.preferences = preferences;
	}

	@Scheduled(fixedDelayString = "${sonar.poll-ms:1000}")
	public void enforceRouting() {
		Map<String, AppAssignment> byBinary = assignments.findAll().stream()
				.filter(a -> a.getChannel() != null)
				.collect(Collectors.toMap(AppAssignment::getBinaryName, a -> a));
		boolean autoRoute = preferences.get().isAutoRoute();
		Map<String, String> sinkByChannelName = channels.findAll().stream()
				.filter(c -> !c.isMic())
				.collect(Collectors.toMap(Channel::getName, Channel::getSinkName));
		for (var stream : pw.listStreams()) {
			AppAssignment a = byBinary.get(stream.binary());
			String target = a != null
					? a.getChannel().getSinkName()
					: autoRoute ? sinkByChannelName.get(String.valueOf(classifier.classify(stream))) : null;
			if (target != null && !target.equals(stream.sinkName())) {
				pw.moveInput(stream.index(), target);
			}
		}
	}
}
