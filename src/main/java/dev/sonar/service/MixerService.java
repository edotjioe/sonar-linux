package dev.sonar.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import dev.sonar.domain.AppAssignment;
import dev.sonar.domain.Channel;
import dev.sonar.domain.EqBand;
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

	/** Same property Bootstrapper creates them from; matched by sink name, so a renamed one still counts. */
	@Value("${sonar.default-channels:Game,Chat,Media,Aux}")
	private String defaultChannels = "Game,Chat,Media,Aux";

	/** The mic may be boosted past 100% (software gain), for quiet mics; outputs stop at 100%. */
	static final int MIC_MAX_VOLUME = 200;

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
		if (channels.findBySinkName(sinkName).isPresent() || channels.findByName(name).isPresent()
				|| sinkName.equals(PipeWireService.STREAM_SINK)) {
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

	/** Built-in channels (sonar.default-channels, and the mic) stay; only channels the user added can go. */
	public boolean isDeletable(Channel c) {
		return !c.isMic() && Stream.of(defaultChannels.split(","))
				.noneMatch(name -> !name.isBlank() && sinkNameFor(name).equals(c.getSinkName()));
	}

	public void deleteChannel(long id) {
		Channel c = require(id);
		if (!isDeletable(c)) {
			throw new IllegalArgumentException("Built-in channels can't be deleted: " + c.getName());
		}
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
		c.setVolume(Math.max(0, Math.min(c.isMic() ? MIC_MAX_VOLUME : 100, volume)));
		if (c.isMic()) {
			pw.setVolume(c.getSinkName(), c.getVolume(), true);
		} else {
			pw.setGain(c.getSinkName() + "_eq_in", gain(c));
		}
		return channels.save(c);
	}

	public Channel setMute(long id, boolean muted) {
		Channel c = require(id);
		c.setMuted(muted);
		if (c.isMic()) {
			pw.setMute(c.getSinkName(), muted, true);
		} else {
			pw.setGain(c.getSinkName() + "_eq_in", gain(c));
		}
		return channels.save(c);
	}

	/** Streamer mode: the channel's volume in the stream mix. Applied live, and kept while streamer mode is off. */
	public Channel setStreamVolume(long id, int volume) {
		Channel c = requireOutput(id);
		c.setStreamVolume(Math.max(0, Math.min(100, volume)));
		pw.setGain(c.getSinkName() + "_stream_in", streamGain(c));
		return channels.save(c);
	}

	public Channel setStreamMute(long id, boolean muted) {
		Channel c = requireOutput(id);
		c.setStreamMuted(muted);
		pw.setGain(c.getSinkName() + "_stream_in", streamGain(c));
		return channels.save(c);
	}

	/** In-memory only: PipeWire reuses module ids, and Bootstrapper unloads every sonar module on start anyway. */
	private long streamModule = -1;

	/**
	 * Streamer mode on: the Sonar Stream sink, plus a feed into it from every output channel's EQ process.
	 * Restarts those processes, so every channel blips once.
	 */
	public void setStreamerMode(boolean on) {
		Preferences p = preferences.get();
		p.setStreamerMode(on);
		preferences.save(p);
		if (on && streamModule < 0) {
			loadStreamSink();
		}
		listChannels().stream().filter(c -> !c.isMic()).forEach(this::startEq);
		if (!on && streamModule >= 0) {
			pw.unloadModule(streamModule); // after the restarts: no feed plays into it any more
			streamModule = -1;
		}
	}

	/** Bootstrapper, after unloading every sonar module and before the channels: the stream sink, if streamer mode is on. */
	public void loadStreamSink() {
		streamModule = -1;
		if (preferences.get().isStreamerMode()) {
			streamModule = pw.loadNullSink(PipeWireService.STREAM_SINK, "Sonar Stream", false);
			pw.setVolume(PipeWireService.STREAM_SINK, 100, false); // WirePlumber may restore an old volume
			pw.setMute(PipeWireService.STREAM_SINK, false, false);
		}
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

	/**
	 * Output only: virtual 7.1 surround. Reloads the channel's sink (as 7.1, or back to stereo), so its apps drop to
	 * the default output until StreamWatcher moves them back (~1 s). Games pick their channel count when they start.
	 */
	public Channel setSpatial(long id, boolean enabled) {
		Channel c = requireOutput(id);
		if (enabled && pw.spatialHrtf() == null) {
			throw new IllegalStateException("Spatial audio needs PipeWire's SOFA plugin and an HRTF: install libmysofa"
					+ " (or put a .sofa file at ~/.local/share/sonar/hrtf.sofa)");
		}
		c.setSpatial(enabled);
		meters.forget(c); // its parec dies with the old sink
		pw.stopEq(c.getSinkName());
		if (c.getNullSinkModule() >= 0) {
			pw.unloadModule(c.getNullSinkModule());
		}
		return applyChannel(c);
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
			channels.findBySinkName(sink).ifPresent(c -> pw.setGain(sink + "_eq_in", gain(c)));
		}
	}

	/**
	 * An output channel's gain after its EQ: its volume, scaled down by ChatMix for Game/Chat, 0 when muted. Cubed
	 * like pactl's volume %, so the slider feels the same. The sink itself stays at 100%: its monitor feeds the stream mix.
	 */
	private double gain(Channel c) {
		return c.isMuted() ? 0 : cube(mixed(c.getVolume(), c.getSinkName(), preferences.get().getChatMix()));
	}

	/** The channel's gain into the stream mix (ChatMix only affects what you hear). */
	private static double streamGain(Channel c) {
		return c.isStreamMuted() ? 0 : cube(c.getStreamVolume());
	}

	private static double cube(int volume) {
		return Math.pow(volume / 100.0, 3);
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

	/** Set a channel's EQ. Freq/gain/Q apply live; added/removed bands or a type change restart the EQ process (a short blip). */
	public Channel setEq(long id, List<EqBand> bands) {
		Channel c = require(id);
		List<EqBand> checked = EqBand.check(bands);
		boolean sameTypes = c.getEq().stream().map(EqBand::type).toList()
				.equals(checked.stream().map(EqBand::type).toList());
		c.setEq(checked);
		if (sameTypes) {
			pw.setEqParams(c.getSinkName(), chain(c));
		} else {
			restartEq(c);
		}
		return channels.save(c);
	}

	/** Set a channel's Bass/Voice/Treble (dB, clamped to ±MAX_GAIN); applied live. */
	public Channel setTone(long id, double[] tone) {
		Channel c = require(id);
		c.setTone(EqBand.checkTone(tone));
		pw.setEqParams(c.getSinkName(), chain(c));
		return channels.save(c);
	}

	/** What the EQ process runs: the channel's bands, then the tone filters. */
	private static List<EqBand> chain(Channel c) {
		return Stream.concat(c.getEq().stream(), EqBand.tone(c.getTone()).stream()).toList();
	}

	/** Create or overwrite a custom profile by name. Presets are read-only. */
	public EqProfile saveProfile(String name, List<EqBand> bands, double[] tone) {
		name = checkName(name);
		List<EqBand> checked = EqBand.check(bands);
		double[] checkedTone = EqBand.checkTone(tone);
		EqProfile p = profiles.findByName(name).orElse(null);
		if (p == null) {
			p = new EqProfile(name, checked, false);
		} else if (p.isBuiltIn()) {
			throw new IllegalArgumentException("Can't overwrite a built-in profile: " + name);
		}
		p.setBands(checked);
		p.setTone(checkedTone);
		return profiles.save(p);
	}

	public void deleteProfile(long id) {
		EqProfile p = profiles.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown profile: " + id));
		if (p.isBuiltIn()) {
			throw new IllegalArgumentException("Can't delete a built-in profile: " + p.getName());
		}
		profiles.delete(p);
	}


	/**
	 * (Re)create a channel's modules and re-apply its volume/mute. Called from
	 * Bootstrapper, which unloads all sonar modules first — so we always start
	 * from a clean slate and store fresh module ids (PipeWire reuses ids).
	 */
	public Channel applyChannel(Channel c) {
		String device = c.getDeviceName();
		// node names have no spaces; one that does is an error message an older version saved as the device
		if (device == null || device.isBlank() || device.chars().anyMatch(Character::isWhitespace)) {
			device = c.isMic() ? pw.defaultSource() : pw.defaultSink();
		}
		if (c.isMic()) {
			device = realMic(device);
		}
		c.setDeviceName(device);
		c.setNullSinkModule(c.isMic()
				? pw.loadVirtualSource(c.getSinkName(), c.getName())
				: pw.loadNullSink(c.getSinkName(), c.getName(), c.isSpatial()));
		if (c.isMic()) {
			pw.setVolume(c.getSinkName(), c.getVolume(), true); // the mic's EQ runs on demand: see syncMic
			pw.setMute(c.getSinkName(), c.isMuted(), true);
		} else {
			startEq(c); // volume and mute are its gain
			pw.setVolume(c.getSinkName(), 100, false); // WirePlumber may restore an old volume
			pw.setMute(c.getSinkName(), false, false);
		}
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
		pw.startEq(c.getSinkName(), c.getName(), c.getDeviceName(), chain(c), c.isMic(), c.isNoiseSuppression(),
				c.isMic() ? null : gain(c), !c.isMic() && preferences.get().isStreamerMode() ? streamGain(c) : null,
				c.isSpatial());
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

	private Channel requireOutput(long id) {
		Channel c = require(id);
		if (c.isMic()) {
			throw new IllegalArgumentException("The mic isn't in the stream mix: " + c.getName());
		}
		return c;
	}

	@PreDestroy
	void shutdown() {
		// No JPA here: the EntityManager is already closed during shutdown.
		pw.stopAllEq();
		pw.unloadSonarModules();
	}
}
