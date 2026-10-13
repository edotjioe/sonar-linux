package dev.sonar.web;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.sonar.domain.AppAssignment;
import dev.sonar.domain.EqProfile;
import dev.sonar.domain.Preferences;
import dev.sonar.pipewire.PipeWireModels.PhysicalSink;
import dev.sonar.pipewire.PipeWireService;
import dev.sonar.repo.AppAssignmentRepository;
import dev.sonar.repo.EqProfileRepository;
import dev.sonar.repo.PreferencesRepository;
import dev.sonar.service.AppClassifier;
import dev.sonar.service.BaseStation;
import dev.sonar.service.BluetoothBatteries;
import dev.sonar.service.LevelMeter;
import dev.sonar.service.MixerService;

/** Thin HTTP layer over the mixer. */
@RestController
@RequestMapping("/api")
public class MixerController {

	private final MixerService mixer;
	private final PipeWireService pw;
	private final AppAssignmentRepository assignments;
	private final LevelMeter meters;
	private final AppClassifier classifier;
	private final PreferencesRepository preferences;
	private final EqProfileRepository profiles;
	private final ObjectProvider<BuildProperties> build;
	private final BaseStation base;
	private final BluetoothBatteries bluetooth;

	public MixerController(MixerService mixer, PipeWireService pw, AppAssignmentRepository assignments,
			LevelMeter meters, AppClassifier classifier, PreferencesRepository preferences,
			EqProfileRepository profiles, ObjectProvider<BuildProperties> build, BaseStation base,
			BluetoothBatteries bluetooth) {
		this.mixer = mixer;
		this.pw = pw;
		this.assignments = assignments;
		this.meters = meters;
		this.classifier = classifier;
		this.preferences = preferences;
		this.profiles = profiles;
		this.build = build;
		this.base = base;
		this.bluetooth = bluetooth;
	}

	public record ChannelView(long id, String name, String sinkName, int volume, boolean muted,
			String deviceName, String deviceDescription, int position, boolean active, boolean mic, double[] eq,
			boolean noiseSuppression, boolean deletable) {
	}

	public record EqView(int[] bands, double maxGain, List<EqProfile> profiles) {
	}

	public record EqBody(String name, double[] gains) {
	}

	/** autoChannel: the channel AppClassifier guesses, used while the app has no manual assignment. */
	public record AppView(int index, String binary, String appName, String sinkName,
			Long channelId, String channelName, String autoChannel) {
	}

	public record RouteBody(String binary, String displayName, Long channelId) {
	}

	@GetMapping("/channels")
	public List<ChannelView> channels() {
		Set<String> live = new HashSet<>(pw.indexToName("sinks").values());
		live.addAll(pw.indexToName("sources").values());
		Map<String, String> desc = Stream.concat(pw.listPhysicalSinks().stream(), pw.listPhysicalSources().stream())
				.collect(Collectors.toMap(PhysicalSink::name, PhysicalSink::description, (a, b) -> a));
		return mixer.listChannels().stream().map(c -> new ChannelView(
				c.getId(), c.getName(), c.getSinkName(), c.getVolume(), c.isMuted(),
				c.getDeviceName(), desc.getOrDefault(c.getDeviceName(), c.getDeviceName()),
				c.getPosition(), live.contains(c.getSinkName()), c.isMic(), c.getEq(),
				c.isNoiseSuppression(), mixer.isDeletable(c))).toList();
	}

	/** channel id -> peak level 0..100 (-60..0 dB). The UI polls this ~10x/s while open. */
	@GetMapping("/levels")
	public Map<Long, Integer> levels() {
		return meters.levels(mixer.listChannels());
	}

	@PostMapping("/channels")
	public void create(@RequestBody Map<String, String> body) {
		mixer.createChannel(body.get("name"), false);
	}

	@PostMapping("/channels/{id}/name")
	public void rename(@PathVariable long id, @RequestBody Map<String, String> body) {
		mixer.rename(id, body.get("name"));
	}

	/** Body: channel ids in the new display order. */
	@PostMapping("/channels/order")
	public void reorder(@RequestBody List<Long> ids) {
		mixer.reorder(ids);
	}

	@DeleteMapping("/channels/{id}")
	public void delete(@PathVariable long id) {
		mixer.deleteChannel(id);
	}

	@PostMapping("/channels/{id}/volume")
	public void volume(@PathVariable long id, @RequestBody Map<String, Integer> body) {
		mixer.setVolume(id, required(body, "volume"));
	}

	@PostMapping("/channels/{id}/mute")
	public void mute(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
		mixer.setMute(id, required(body, "muted"));
	}

	@PostMapping("/channels/{id}/device")
	public void device(@PathVariable long id, @RequestBody Map<String, String> body) {
		mixer.setDevice(id, required(body, "deviceName"));
	}

	@PostMapping("/channels/{id}/noise")
	public void noise(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
		mixer.setNoiseSuppression(id, required(body, "enabled"));
	}

	@PostMapping("/channels/{id}/eq")
	public void eq(@PathVariable long id, @RequestBody EqBody body) {
		mixer.setEq(id, body.gains());
	}

	@GetMapping("/eq")
	public EqView eqProfiles() {
		return new EqView(EqProfile.BANDS, EqProfile.MAX_GAIN, profiles.findAll());
	}

	/** Create a custom profile, or overwrite the custom one with that name. */
	@PostMapping("/eq/profiles")
	public EqProfile saveProfile(@RequestBody EqBody body) {
		return mixer.saveProfile(body.name(), body.gains());
	}

	@DeleteMapping("/eq/profiles/{id}")
	public void deleteProfile(@PathVariable long id) {
		mixer.deleteProfile(id);
	}

	@PostMapping("/route")
	public void route(@RequestBody RouteBody body) {
		mixer.route(body.binary(), body.displayName(), body.channelId());
	}

	@GetMapping("/preferences")
	public Preferences getPreferences() {
		return preferences.get();
	}

	@PutMapping("/preferences")
	public Preferences savePreferences(@RequestBody Preferences body) {
		body.setId(1L);
		body.setChatMix(preferences.get().getChatMix()); // owned by /chatmix, which also applies it
		return preferences.save(body);
	}

	/** Body: {mix: -100 (all Game) .. 100 (all Chat)}. */
	@PostMapping("/chatmix")
	public void chatMix(@RequestBody Map<String, Integer> body) {
		mixer.setChatMix(required(body, "mix"));
	}

	@GetMapping("/apps")
	public List<AppView> apps() {
		boolean autoRoute = preferences.get().isAutoRoute();
		Map<String, AppAssignment> byBinary = assignments.findAll().stream()
				.collect(Collectors.toMap(AppAssignment::getBinaryName, a -> a));
		// one row per app: games often open several streams, and routing moves them all together anyway
		Set<String> seen = new HashSet<>();
		return pw.listStreams().stream().filter(s -> seen.add(s.binary() + '\0' + s.appName())).map(s -> {
			AppAssignment a = byBinary.get(s.binary());
			Long channelId = a != null && a.getChannel() != null ? a.getChannel().getId() : null;
			String channelName = a != null && a.getChannel() != null ? a.getChannel().getName() : null;
			return new AppView(s.index(), s.binary(), s.appName(), s.sinkName(), channelId, channelName,
					autoRoute ? classifier.classify(s) : null);
		}).toList();
	}

	/** The running build: version from the pom and when it was built (absent when run from an IDE). */
	@GetMapping("/version")
	public Map<String, String> version() {
		BuildProperties build = this.build.getIfAvailable();
		return build == null ? Map.of("version", "dev")
				: Map.of("version", build.getVersion(), "built", String.valueOf(build.getTime()));
	}

	/** Arctis Nova Pro Wireless headset status from its base station (all unknown without one). */
	@GetMapping("/headset")
	public BaseStation.Headset headset() {
		return base.refreshHeadset();
	}

	/** Bluetooth headsets/headphones that report a battery level (via UPower). */
	@GetMapping("/batteries")
	public List<BluetoothBatteries.Battery> batteries() {
		return bluetooth.list();
	}

	@GetMapping("/devices")
	public List<PhysicalSink> devices() {
		return pw.listPhysicalSinks();
	}

	@GetMapping("/sources")
	public List<PhysicalSink> sources() {
		return pw.listPhysicalSources();
	}

	private static <T> T required(Map<String, T> body, String key) {
		T value = body.get(key);
		if (value == null || value instanceof String str && str.isBlank()) {
			throw new IllegalArgumentException("Missing field: " + key);
		}
		return value;
	}
}
