package dev.sonar.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import dev.sonar.domain.AppAssignment;
import dev.sonar.domain.Channel;
import dev.sonar.domain.EqProfile;
import dev.sonar.domain.Preferences;
import dev.sonar.pipewire.PipeWireModels.AppStream;
import dev.sonar.pipewire.PipeWireService;
import dev.sonar.repo.AppAssignmentRepository;
import dev.sonar.repo.ChannelRepository;
import dev.sonar.repo.EqProfileRepository;
import dev.sonar.repo.PreferencesRepository;
import jakarta.annotation.PreDestroy;

/** Business rules: creates channels, routes apps, applies volume/mute/device. */
@Service
public class MixerService {

	private final PipeWireService pw;
	private final ChannelRepository channels;
	private final AppAssignmentRepository assignments;
	private final LevelMeter meters;
	private final EqProfileRepository profiles;
	private final PreferencesRepository preferences;

	/** ChatMix balances these two channels; by sink name, which survives a rename. */
	static final String GAME_SINK = sinkNameFor("Game");
	static final String CHAT_SINK = sinkNameFor("Chat");

	public MixerService(PipeWireService pw, ChannelRepository channels, AppAssignmentRepository assignments,
			LevelMeter meters, EqProfileRepository profiles, PreferencesRepository preferences) {
		this.pw = pw;
		this.channels = channels;
		this.assignments = assignments;
		this.meters = meters;
		this.profiles = profiles;
		this.preferences = preferences;
	}

	/** PipeWire node name for a channel name: "Voice Chat" -> "sonar_voice_chat". */
	public static String sinkNameFor(String name) {
		return "sonar_" + name.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_");
	}

	public Channel createChannel(String name, boolean mic) {
		name = checkName(name);
		String sinkName = sinkNameFor(name);
		if (channels.findBySinkName(sinkName).isPresent() || channels.findByName(name).isPresent()) {
			throw new IllegalArgumentException("A channel with that name already exists: " + name);
		}
		Channel c = new Channel();
		c.setName(name);
		c.setSinkName(sinkName);
		c.setMic(mic);
		c.setPosition(listChannels().stream().mapToInt(Channel::getPosition).max().orElse(-1) + 1);
		return applyChannel(c);
	}

	/**
	 * Display name only: the sink name stays, so routing and module ids are untouched.
	 *
	 * ponytail: PipeWire's device.description keeps the old name until the next restart;
	 * reloading the null-sink live would drop audio for a second.
	 */
	public Channel rename(long id, String name) {
		Channel c = require(id);
		name = checkName(name);
		channels.findByName(name).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
			throw new IllegalArgumentException("A channel with that name already exists: " + other.getName());
		});
		c.setName(name);
		return channels.save(c);
	}

	/** Positions follow the order of ids; channels not listed keep their relative order after them. */
	public void reorder(List<Long> ids) {
		List<Channel> all = new ArrayList<>(listChannels());
		all.sort(Comparator.comparingInt(c -> {
			int i = ids.indexOf(c.getId());
			return i < 0 ? Integer.MAX_VALUE : i;
		}));
		for (int i = 0; i < all.size(); i++) {
			all.get(i).setPosition(i);
		}
		channels.saveAll(all);
	}

	private static String checkName(String name) {
		if (name == null || !name.trim().toLowerCase().matches(".*[a-z0-9].*")) {
			throw new IllegalArgumentException("Channel name needs at least one letter or digit");
		}
		return name.trim();
	}

	public void deleteChannel(long id) {
		Channel c = require(id);
		meters.forget(c);
		pw.stopEq(c.getSinkName());
		if (c.getNullSinkModule() >= 0) {
			pw.unloadModule(c.getNullSinkModule());
		}
		assignments.deleteAll(assignments.findAllByChannel(c));
		channels.delete(c);
	}

	/** Assign an app to a channel (or unassign when channelId is null) and move its live streams. */
	public void route(String binary, String displayName, Long channelId) {
		if (channelId == null) {
			assignments.findByBinaryName(binary).ifPresent(assignments::delete);
			String def = pw.defaultSink();
			for (AppStream s : pw.listStreams()) {
				if (s.binary().equals(binary)) {
					pw.moveInput(s.index(), def);
				}
			}
			return;
		}
		Channel c = require(channelId);
		if (c.isMic()) {
			throw new IllegalArgumentException("Apps can't play into the mic channel: " + c.getName());
		}
		AppAssignment a = assignments.findByBinaryName(binary).orElseGet(AppAssignment::new);
		a.setBinaryName(binary);
		if (displayName != null && !displayName.isBlank()) {
			a.setDisplayName(displayName);
		}
		a.setChannel(c);
		assignments.save(a);
		for (AppStream s : pw.listStreams()) {
			if (s.binary().equals(binary)) {
				pw.moveInput(s.index(), c.getSinkName());
			}
		}
	}

	public Channel setVolume(long id, int volume) {
		Channel c = require(id);
		c.setVolume(Math.max(0, Math.min(100, volume)));
		pw.setVolume(c.getSinkName(), effectiveVolume(c), c.isMic());
		return channels.save(c);
	}

	public Channel setMute(long id, boolean muted) {
		Channel c = require(id);
		c.setMuted(muted);
		pw.setMute(c.getSinkName(), muted, c.isMic());
		return channels.save(c);
	}

	public Channel setDevice(long id, String deviceName) {
		Channel c = require(id);
		c.setDeviceName(deviceName);
		restartEq(c);
		return channels.save(c);
	}

	/** Mic only: switch rnnoise on/off. Restarts the mic's EQ process, so the virtual mic blips for a moment. */
	public Channel setNoiseSuppression(long id, boolean enabled) {
		Channel c = require(id);
		if (!c.isMic()) {
			throw new IllegalArgumentException("Noise suppression is only for the mic channel");
		}
		if (enabled && pw.rnnoisePlugin() == null) {
			throw new IllegalStateException("Noise suppression needs the rnnoise plugin: install noise-suppression-for-voice"
					+ " (or put librnnoise_ladspa.so in ~/.local/lib/ladspa)");
		}
		c.setNoiseSuppression(enabled);
		restartEq(c);
		return channels.save(c);
	}

	/** The headset's own mute button (via the base station): mirror it onto the mic channel. */
	public void setMicMuted(boolean muted) {
		listChannels().stream().filter(c -> c.isMic() && c.isMuted() != muted).forEach(c -> setMute(c.getId(), muted));
	}

	/** Move the ChatMix dial and re-apply the Game/Chat volumes it scales. */
	public void setChatMix(int mix) {
		Preferences p = preferences.get();
		p.setChatMix(Math.max(-100, Math.min(100, mix)));
		preferences.save(p);
		for (String sink : List.of(GAME_SINK, CHAT_SINK)) {
			channels.findBySinkName(sink).ifPresent(c -> pw.setVolume(sink, mixed(c.getVolume(), sink, p.getChatMix()), false));
		}
	}

	/** Volume actually sent to PipeWire: the channel's own volume, scaled down by ChatMix for Game/Chat. */
	private int effectiveVolume(Channel c) {
		return c.isMic() ? c.getVolume() : mixed(c.getVolume(), c.getSinkName(), preferences.get().getChatMix());
	}

	/** Towards Chat (mix > 0) fades Game; towards Game (mix < 0) fades Chat; the centre leaves both alone. */
	static int mixed(int volume, String sink, int mix) {
		if (sink.equals(GAME_SINK) && mix > 0) {
			return Math.round(volume * (100 - mix) / 100f);
		}
		if (sink.equals(CHAT_SINK) && mix < 0) {
			return Math.round(volume * (100 + mix) / 100f);
		}
		return volume;
	}

	/** Set a channel's EQ curve (dB per band, clamped to ±MAX_GAIN); applied live. */
	public Channel setEq(long id, double[] gains) {
		Channel c = require(id);
		c.setEq(checkGains(gains));
		pw.setEqGains(c.getSinkName(), c.getEq());
		return channels.save(c);
	}

	/** Create or overwrite a custom profile by name. Presets are read-only. */
	public EqProfile saveProfile(String name, double[] gains) {
		name = checkName(name);
		double[] checked = checkGains(gains);
		EqProfile p = profiles.findByName(name).orElse(null);
		if (p == null) {
			return profiles.save(new EqProfile(name, checked, false));
		}
		if (p.isBuiltIn()) {
			throw new IllegalArgumentException("Can't overwrite a built-in profile: " + name);
		}
		p.setGains(checked);
		return profiles.save(p);
	}

	public void deleteProfile(long id) {
		EqProfile p = profiles.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown profile: " + id));
		if (p.isBuiltIn()) {
			throw new IllegalArgumentException("Can't delete a built-in profile: " + p.getName());
		}
		profiles.delete(p);
	}

	private static double[] checkGains(double[] gains) {
		if (gains == null || gains.length != EqProfile.BANDS.length) {
			throw new IllegalArgumentException("EQ needs " + EqProfile.BANDS.length + " gains");
		}
		double[] out = new double[gains.length];
		for (int i = 0; i < gains.length; i++) {
			out[i] = Math.max(-EqProfile.MAX_GAIN, Math.min(EqProfile.MAX_GAIN, gains[i]));
		}
		return out;
	}

	/**
	 * (Re)create a channel's modules and re-apply its volume/mute. Called from
	 * Bootstrapper, which unloads all sonar modules first — so we always start
	 * from a clean slate and store fresh module ids (PipeWire reuses ids).
	 */
	public Channel applyChannel(Channel c) {
		String device = c.getDeviceName();
		if (device == null || device.isBlank()) {
			device = c.isMic() ? pw.defaultSource() : pw.defaultSink();
		}
		if (c.isMic()) {
			device = realMic(device);
		}
		c.setDeviceName(device);
		c.setNullSinkModule(c.isMic()
				? pw.loadVirtualSource(c.getSinkName(), c.getName())
				: pw.loadNullSink(c.getSinkName(), c.getName()));
		if (!c.isMic()) {
			startEq(c); // the mic's runs on demand: see syncMic
		}
		pw.setVolume(c.getSinkName(), effectiveVolume(c), c.isMic());
		pw.setMute(c.getSinkName(), c.isMuted(), c.isMic());
		return channels.save(c);
	}

	/**
	 * The mic input to use: `device`, unless that's an output's monitor (the default source can be, e.g.
	 * when no mic is plugged in), then the default mic or the first one. A mic that's merely unplugged
	 * is kept, so the choice survives a reboot without it.
	 */
	private String realMic(String device) {
		List<String> mics = pw.listPhysicalSources().stream().map(m -> m.name()).toList();
		if (mics.isEmpty() || mics.contains(device) || !pw.indexToName("sources").containsValue(device)) {
			return device;
		}
		String def = pw.defaultSource();
		return mics.contains(def) ? def : mics.get(0);
	}

	private void startEq(Channel c) {
		pw.startEq(c.getSinkName(), c.getName(), c.getDeviceName(), c.getEq(), c.isMic(), c.isNoiseSuppression());
	}

	/** After a setting the EQ process is built from changed: restart it, unless it's the mic's and that's off. */
	private void restartEq(Channel c) {
		if (!c.isMic() || pw.isEqRunning(c.getSinkName())) {
			startEq(c);
		}
	}

	/** Mic sink name -> when something last recorded from it. */
	private final Map<String, Long> micLastUsed = new ConcurrentHashMap<>();

	/**
	 * Run the mic chain only while something records from the virtual mic (an app, or the mixer's level
	 * meter), so the real mic, and the desktop's "mic in use" indicator, are off the rest of the time.
	 * It stops 5 s after the last user: apps often close and reopen the mic, e.g. when joining a call.
	 */
	@Scheduled(fixedDelay = 1000)
	public void syncMic() {
		long now = System.currentTimeMillis();
		for (Channel c : listChannels()) {
			if (!c.isMic()) {
				continue;
			}
			String sink = c.getSinkName();
			boolean running = pw.isEqRunning(sink);
			if (pw.sourceConsumers(sink) > 0) {
				micLastUsed.put(sink, now);
				if (!running) {
					startEq(c);
				}
			} else if (running && now - micLastUsed.getOrDefault(sink, 0L) > 5000) {
				pw.stopEq(sink);
			}
		}
	}

	public List<Channel> listChannels() {
		return channels.findAllByOrderByPositionAsc();
	}

	private Channel require(long id) {
		return channels.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown channel: " + id));
	}

	@PreDestroy
	void shutdown() {
		// No JPA here: the EntityManager is already closed during shutdown.
		pw.stopAllEq();
		pw.unloadSonarModules();
	}
}
