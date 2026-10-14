package dev.sonar.pipewire;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.sonar.domain.EqBand;
import dev.sonar.pipewire.PipeWireModels.AppStream;
import dev.sonar.pipewire.PipeWireModels.Module;
import dev.sonar.pipewire.PipeWireModels.PhysicalSink;

/**
 * The one place that talks to PipeWire. Every method shells out to a native
 * CLI tool (pactl / pw-*); there is no maintained Java binding for libpipewire.
 */
@Service
public class PipeWireService {

	private record Result(int exit, String out) {
	}

	private static final Logger log = LoggerFactory.getLogger(PipeWireService.class);

	/** Problems already logged: these commands run every second, so each distinct one is logged once. */
	private final Set<String> warned = ConcurrentHashMap.newKeySet();

	private Result run(String... cmd) {
		try {
			ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
			pb.environment().put("LC_ALL", "C.UTF-8"); // we parse the output: pactl is translated on e.g. Fedora/Bazzite
			Process p = pb.start();
			String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			int exit = p.waitFor();
			if (exit != 0) {
				warnOnce(cmd[0] + " " + cmd[1] + " failed (exit " + exit + "): " + out.strip().lines().findFirst().orElse(""));
			}
			return new Result(exit, out);
		} catch (Exception e) {
			// typically the tool isn't installed: everything then looks empty (no devices, channels offline)
			warnOnce("Can't run " + cmd[0] + " (" + e.getMessage() + "); is it installed?"
					+ " pactl comes with pulseaudio-utils on Fedora/Bazzite, libpulse on Arch");
			return new Result(-1, "ERROR: " + e.getMessage());
		}
	}

	private void warnOnce(String message) {
		if (warned.add(message)) {
			log.warn(message);
		}
	}

	private static long parseLong(String s) {
		if (s == null) {
			return -1;
		}
		try {
			return Long.parseLong(s.trim());
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static String grab(String text, Pattern p) {
		Matcher m = p.matcher(text);
		return m.find() ? m.group(1) : null;
	}

	// --- modules (output channels are null-sinks) ---

	/** surround: 7.1, so games output surround for the spatial audio chain (see {@link #spatialHrtf}). */
	public long loadNullSink(String sinkName, String description, boolean surround) {
		Result r = surround
				? run("pactl", "load-module", "module-null-sink", "sink_name=" + sinkName, descriptionArg(description),
						"channels=8", "channel_map=front-left,front-right,front-center,lfe,rear-left,rear-right,side-left,side-right")
				: run("pactl", "load-module", "module-null-sink", "sink_name=" + sinkName, descriptionArg(description));
		return parseLong(r.out());
	}

	/**
	 * The virtual mic: a source apps can record from, fed by the mic EQ process while it runs.
	 * Separate from that process so it stays selectable while the real mic is off.
	 */
	public long loadVirtualSource(String name, String description) {
		Result r = run("pactl", "load-module", "module-null-sink", "sink_name=" + name,
				"media.class=Audio/Source/Virtual", "channel_map=mono", descriptionArg(description));
		return parseLong(r.out());
	}

	/** Quoted: pactl splits module args at spaces, so "Sonar Stream" became "Sonar". It has no escape for '"'. */
	static String descriptionArg(String description) {
		return "device.description=\"" + description.replace('"', '\'') + "\"";
	}

	/** Recording streams on source `name` (apps using the virtual mic, and our own level meter). */
	public long sourceConsumers(String name) {
		String index = indexToName("sources").entrySet().stream().filter(e -> e.getValue().equals(name))
				.map(e -> e.getKey().toString()).findFirst().orElse(null);
		return run("pactl", "list", "short", "source-outputs").out().lines()
				.filter(l -> l.split("\t").length > 1 && l.split("\t")[1].equals(index)).count();
	}

	public void unloadModule(long id) {
		run("pactl", "unload-module", Long.toString(id));
	}

	public List<Module> listModules() {
		List<Module> mods = new ArrayList<>();
		for (String line : run("pactl", "list", "short", "modules").out().split("\n")) {
			String[] parts = line.split("\t", 3);
			if (parts.length >= 3) {
				long id = parseLong(parts[0]);
				if (id >= 0) {
					mods.add(new Module(id, parts[1], parts[2]));
				}
			}
		}
		return mods;
	}

	/** Unload any null-sink modules we created, e.g. leftovers from a crash. */
	public void unloadSonarModules() {
		for (Module m : listModules()) {
			if (m.args().contains("sonar_")) {
				unloadModule(m.id());
			}
		}
	}

	// --- EQ: one `pipewire -c <conf>` child per channel running a filter-chain ---
	//
	// pactl can't load module-filter-chain, and `pw-cli load-module` unloads it when pw-cli
	// exits, so each EQ is its own long-lived process. Output channel: reads the null-sink's
	// monitor, plays into the physical device. Mic: reads the physical mic, plays into the virtual
	// mic source; it only runs while something records from that. Band gains change live with
	// `pw-cli set-param`, no restart.
	//
	// An EQ process only dies when PipeWire itself restarts, which takes the null-sinks down too:
	// Bootstrapper notices the missing sinks and rebuilds everything.

	/** Per-user runtime dir (/run/user/<uid>, 0700): /tmp is shared, so another user's sonar-eq would block ours. */
	private static final Path EQ_DIR = Path.of(System.getenv().getOrDefault("XDG_RUNTIME_DIR",
			System.getProperty("java.io.tmpdir")), "sonar-eq");

	private final Map<String, Process> eqProcesses = new ConcurrentHashMap<>();

	/** Streamer mode's mix: a null-sink every output channel's stream feed plays into; OBS captures its monitor. */
	public static final String STREAM_SINK = "sonar_stream";

	/**
	 * (Re)start the EQ for channel node `name`, routed to/from `device`.
	 * denoise: put rnnoise in front of the EQ (mic only); skipped when the plugin isn't installed.
	 * volume: linear gain after the EQ (null: none, the mic's volume is its source's).
	 * stream: also feed the channel, without EQ or volume, into {@link #STREAM_SINK} at this linear gain (null: don't).
	 * spatial: virtual 7.1 surround after the EQ (the channel's sink must be 7.1); skipped when unavailable.
	 */
	public void startEq(String name, String description, String device, List<EqBand> bands, boolean mic, boolean denoise,
			Double volume, Double stream, boolean spatial) {
		stopEq(name);
		Path plugin = mic && denoise ? rnnoisePlugin() : null;
		Path hrtf = spatial && !mic ? spatialHrtf() : null;
		try {
			Files.createDirectories(EQ_DIR);
			Path conf = EQ_DIR.resolve(name + ".conf");
			Files.writeString(conf, eqConf(name, description, device, bands, mic, plugin, volume, stream, hrtf));
			eqProcesses.put(name, new ProcessBuilder("pipewire", "-c", conf.toString())
					.redirectErrorStream(true).redirectOutput(Redirect.DISCARD).start());
		} catch (IOException e) {
			throw new UncheckedIOException("Can't start EQ for " + name, e);
		}
		if (mic) {
			// WirePlumber won't link a stream into a virtual source (it falls back to the default
			// *output*, i.e. your own headphones), so the chain doesn't autoconnect and we link it here
			String out = name + "_eq_out";
			for (int i = 0; i < 30 && !run("pw-link", "-o").out().contains(out + ":"); i++) {
				sleep(100);
			}
			run("pw-link", out, name);
		}
	}

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public boolean isEqRunning(String name) {
		Process p = eqProcesses.get(name);
		return p != null && p.isAlive();
	}

	/** Live freq/gain/Q change on a running EQ; bands[i] is node b{i}. Types can't change live (restart instead). */
	public void setEqParams(String name, List<EqBand> bands) {
		StringBuilder params = new StringBuilder();
		for (int i = 0; i < bands.size(); i++) {
			EqBand b = bands.get(i);
			params.append(" \"b").append(i).append(":Freq\" ").append(b.freq())
					.append(" \"b").append(i).append(":Q\" ").append(b.q())
					.append(" \"b").append(i).append(":Gain\" ").append(b.gain());
		}
		run("pw-cli", "set-param", name + "_eq_in", "Props", "{ params = [" + params + " ] }");
	}

	/** Live gain change of a chain's "vol" node; node = the chain's capture node (`<sink>_eq_in`, `<sink>_stream_in`). */
	public void setGain(String node, double gain) {
		run("pw-cli", "set-param", node, "Props", "{ params = [ \"vol:Mult\" " + gain + " ] }");
	}

	public void stopEq(String name) {
		Process p = eqProcesses.remove(name);
		if (p != null) {
			awaitExit(p.toHandle());
		}
	}

	/** Stop every EQ process, including orphans from a crashed run (found by their conf path). */
	public void stopAllEq() {
		eqProcesses.keySet().forEach(this::stopEq);
		ProcessHandle.allProcesses()
				.filter(p -> p.info().command().map(cmd -> cmd.endsWith("/pipewire")).orElse(false)
						&& p.info().commandLine().map(cl -> cl.contains(EQ_DIR.toString())).orElse(false))
				.forEach(PipeWireService::awaitExit);
	}

	/**
	 * The HRTF for spatial audio (a SOFA file, from libmysofa), or null when it or PipeWire's SOFA plugin is missing.
	 * ~/.local/share/sonar/hrtf.sofa overrides it, e.g. with a measured or better-matching head; see hrtfCandidates.
	 */
	public Path spatialHrtf() {
		boolean plugin = Stream.of("/usr/lib/spa-0.2", "/usr/lib64/spa-0.2", "/usr/lib/x86_64-linux-gnu/spa-0.2")
				.anyMatch(dir -> Files.isReadable(Path.of(dir, "filter-graph", "libspa-filter-graph-plugin-sofa.so")));
		if (!plugin) {
			return null;
		}
		return hrtfCandidates(System.getenv(), System.getProperty("user.home")).stream()
				.map(Path::of).filter(Files::isReadable).findFirst().orElse(null);
	}

	/**
	 * Where a SOFA file can be, in order: the user's override, then libmysofa's own HRTFs in each XDG data
	 * dir, then in the usual package managers' data dirs. Those are listed too because a systemd user
	 * service often lacks the XDG_DATA_DIRS a login shell sets up (Homebrew only does it in the shell).
	 */
	static List<String> hrtfCandidates(Map<String, String> env, String home) {
		String dataHome = env.getOrDefault("XDG_DATA_HOME", "");
		if (dataHome.isBlank()) {
			dataHome = home + "/.local/share";
		}
		String dataDirs = env.getOrDefault("XDG_DATA_DIRS", "");
		if (dataDirs.isBlank()) {
			dataDirs = "/usr/local/share:/usr/share";
		}
		List<String> dirs = new ArrayList<>();
		dirs.add(dataHome);
		dirs.addAll(List.of(dataDirs.split(":")));
		dirs.addAll(List.of("/usr/share", "/usr/local/share",
				"/home/linuxbrew/.linuxbrew/share", home + "/.linuxbrew/share", // Homebrew
				home + "/.nix-profile/share", "/nix/var/nix/profiles/default/share", "/run/current-system/sw/share", // Nix
				home + "/.guix-profile/share", "/run/current-system/profile/share")); // Guix
		Stream.of("HOMEBREW_PREFIX", "CONDA_PREFIX").map(env::get).filter(v -> v != null && !v.isBlank())
				.forEach(prefix -> dirs.add(prefix + "/share"));
		Stream<String> bundled = dirs.stream().filter(d -> !d.isBlank()).map(d -> d.replaceAll("/+$", "")).distinct()
				.flatMap(d -> Stream.of(d + "/libmysofa/MIT_KEMAR_normal_pinna.sofa", d + "/libmysofa/default.sofa"));
		return Stream.concat(Stream.of(dataHome + "/sonar/hrtf.sofa"), bundled).toList();
	}

	/** rnnoise's LADSPA plugin (package noise-suppression-for-voice), or null when it isn't installed. */
	public Path rnnoisePlugin() {
		return Stream.of("/usr/lib/ladspa", "/usr/lib64/ladspa", "/usr/local/lib/ladspa",
				System.getProperty("user.home") + "/.local/lib/ladspa")
				.map(dir -> Path.of(dir, "librnnoise_ladspa.so")).filter(Files::isReadable).findFirst().orElse(null);
	}

	/**
	 * filter-chain config: a chain of biquads, one per band (SPA-JSON).
	 * rnnoise: the noise-suppression plugin to put in front of the biquads, or null for none.
	 * volume, stream: see {@link #startEq}. hrtf: SOFA file for spatial audio, or null for none.
	 */
	static String eqConf(String name, String description, String device, List<EqBand> bands, boolean mic, Path rnnoise,
			Double volume, Double stream, Path hrtf) {
		StringBuilder nodes = new StringBuilder();
		StringBuilder links = new StringBuilder();
		if (rnnoise != null) {
			// rnnoise wants 48 kHz mono: the mic chain is mono, and PipeWire's default graph rate is 48 kHz
			nodes.append("          { type = ladspa name = ns plugin = ").append(quote(rnnoise.toString()))
					.append(" label = noise_suppressor_mono control = { \"VAD Threshold (%)\" = 50.0 } }\n");
			links.append("          { output = \"ns:Output\" input = \"b0:In\" }\n");
		}
		if (bands.isEmpty()) {
			bands = List.of(new EqBand("peaking", 1000, 0, 1)); // a filter graph needs a node: 0 dB passes audio through
		}
		for (int i = 0; i < bands.size(); i++) {
			EqBand b = bands.get(i);
			nodes.append("          { type = builtin name = b").append(i).append(" label = bq_").append(b.type())
					.append(" control = { \"Freq\" = ").append(b.freq()).append(" \"Q\" = ").append(b.q())
					.append(" \"Gain\" = ").append(b.gain()).append(" } }\n");
			if (i > 0) {
				links.append("          { output = \"b").append(i - 1).append(":Out\" input = \"b").append(i).append(":In\" }\n");
			}
		}
		if (volume != null) {
			nodes.append(gainNode(volume));
			links.append("          { output = \"b").append(bands.size() - 1).append(":Out\" input = \"vol:In\" }\n");
		}
		String capture = mic
				? "node.name = " + quote(name + "_eq_in") + " target.object = " + quote(device) + " node.passive = true"
				: "node.name = " + quote(name + "_eq_in") + " target.object = " + quote(name)
						+ " stream.capture.sink = true node.passive = true";
		String playback = "node.name = " + quote(name + "_eq_out")
				+ (mic ? " node.autoconnect = false" // mic: linked by startEq
						: " target.object = " + quote(hrtf == null ? device : name + "_spatial_in"));
		return """
				context.spa-libs = { audio.convert.* = audioconvert/libspa-audioconvert support.* = support/libspa-support }
				context.modules = [
				  { name = libpipewire-module-rt flags = [ ifexists nofail ] }
				  { name = libpipewire-module-protocol-native }
				  { name = libpipewire-module-client-node }
				  { name = libpipewire-module-adapter }
				  { name = libpipewire-module-filter-chain
				    args = {
				      node.description = %s
				      filter.graph = {
				        nodes = [
				%s        ]
				        links = [
				%s        ]
				      }
				      audio.channels = %s
				      audio.position = [ %s ]
				      capture.props = { %s }
				      playback.props = { %s }
				    }
				  }
				%s]
				""".formatted(quote(description + " EQ"), nodes, links, mic ? "1" : hrtf != null ? "8" : "2",
				mic ? "MONO" : hrtf != null ? SURROUND : "FL FR",
				capture, playback, (hrtf == null ? "" : spatial(name, description, device, hrtf))
						+ (stream == null ? "" : streamFeed(name, description, stream)));
	}

	private static final String SURROUND = "FL FR FC LFE RL RR SL SR";

	/** Where each 7.1 speaker sits, degrees counter-clockwise from the front (the SOFA convention); LFE as centre. */
	private static final int[] SURROUND_AZIMUTH = { 30, 330, 0, 0, 150, 210, 90, 270 };

	/**
	 * Spatial audio, a second filter-chain after the EQ: each 7.1 speaker through the HRTF from its direction, mixed
	 * down to stereo for headphones. The EQ chain plays into it (its capture is a sink) instead of into the device.
	 * The mix is halved: eight HRTF'd speakers clip loud stereo otherwise.
	 */
	private static String spatial(String name, String description, String device, Path hrtf) {
		String[] speakers = SURROUND.split(" ");
		StringBuilder nodes = new StringBuilder();
		StringBuilder links = new StringBuilder();
		StringBuilder gains = new StringBuilder();
		for (int i = 0; i < speakers.length; i++) {
			nodes.append("          { type = sofa label = spatializer name = sp").append(speakers[i])
					.append(" config = { filename = ").append(quote(hrtf.toString())).append(" } control = { \"Azimuth\" = ")
					.append(SURROUND_AZIMUTH[i]).append(".0 \"Elevation\" = 0.0 \"Radius\" = 1.0 } }\n");
			links.append("          { output = \"sp").append(speakers[i]).append(":Out L\" input = \"mixL:In ").append(i + 1)
					.append("\" }\n          { output = \"sp").append(speakers[i]).append(":Out R\" input = \"mixR:In ")
					.append(i + 1).append("\" }\n");
			gains.append(" \"Gain ").append(i + 1).append("\" = 0.5");
		}
		for (String side : List.of("L", "R")) {
			nodes.append("          { type = builtin label = mixer name = mix").append(side).append(" control = {")
					.append(gains).append(" } }\n");
		}
		return """
				  { name = libpipewire-module-filter-chain
				    args = {
				      node.description = %s
				      filter.graph = {
				        nodes = [
				%s        ]
				        links = [
				%s        ]
				        inputs = [ %s ]
				        outputs = [ "mixL:Out" "mixR:Out" ]
				      }
				      capture.props = { node.name = %s media.class = Audio/Sink audio.channels = 8 audio.position = [ %s ] }
				      playback.props = { node.name = %s target.object = %s audio.channels = 2 audio.position = [ FL FR ] }
				    }
				  }
				""".formatted(quote(description + " Spatial"), nodes, links,
				Stream.of(speakers).map(sp -> quote("sp" + sp + ":In")).collect(Collectors.joining(" ")),
				quote(name + "_spatial_in"), SURROUND, quote(name + "_spatial_out"), quote(device));
	}

	/** The `vol` node: a linear gain, changed live by {@link #setGain}. */
	private static String gainNode(double gain) {
		return "          { type = builtin name = vol label = linear control = { \"Mult\" = " + gain + " } }\n";
	}

	/** Second filter-chain in a channel's EQ process: its sink's monitor (before EQ and volume) into the stream mix. */
	private static String streamFeed(String name, String description, double gain) {
		return """
				  { name = libpipewire-module-filter-chain
				    args = {
				      node.description = %s
				      filter.graph = { nodes = [
				%s      ] }
				      audio.channels = 2
				      audio.position = [ FL FR ]
				      capture.props = { node.name = %s target.object = %s stream.capture.sink = true node.passive = true }
				      playback.props = { node.name = %s target.object = %s }
				    }
				  }
				""".formatted(quote(description + " Stream"), gainNode(gain), quote(name + "_stream_in"), quote(name),
				quote(name + "_stream_out"), quote(STREAM_SINK));
	}

	/** SPA-JSON string literal: names come from users and devices, so escape them. */
	static String quote(String s) {
		return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	/** Terminate and wait, so the process's nodes are gone before a replacement registers the same names. */
	private static void awaitExit(ProcessHandle p) {
		p.destroy();
		try {
			p.onExit().get(2, TimeUnit.SECONDS);
		} catch (Exception e) {
			p.destroyForcibly();
		}
	}

	// --- sinks / routing / volume ---

	/** False while PipeWire (pipewire-pulse) is down or restarting. */
	public boolean isUp() {
		return run("pactl", "info").exit() == 0;
	}

	/** index -> name from `pactl list short <kind>`, kind = "sinks" or "sources". */
	public Map<Integer, String> indexToName(String kind) {
		Map<Integer, String> map = new HashMap<>();
		for (String line : run("pactl", "list", "short", kind).out().split("\n")) {
			String[] parts = line.split("\t");
			if (parts.length >= 2) {
				try {
					map.put(Integer.parseInt(parts[0].trim()), parts[1].trim());
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return map;
	}

	public List<PhysicalSink> listPhysicalSinks() {
		List<PhysicalSink> out = new ArrayList<>();
		for (String block : run("pactl", "list", "sinks").out().split("(?m)^Sink #")) {
			String name = grab(block, NAME);
			if (name == null || name.startsWith("sonar_")) {
				continue;
			}
			out.add(new PhysicalSink(name, describe(block, name)));
		}
		return out;
	}

	/**
	 * Physical mics: every source except our own virtual mic and outputs' monitors. A monitor is told
	 * apart by what PipeWire says it is, not by its name: not every system names them "*.monitor".
	 */
	public List<PhysicalSink> listPhysicalSources() {
		List<PhysicalSink> out = new ArrayList<>();
		for (String block : run("pactl", "list", "sources").out().split("(?m)^Source #")) {
			String name = grab(block, NAME);
			if (name == null || name.startsWith("sonar_") || isOutput(block)) {
				continue;
			}
			out.add(new PhysicalSink(name, describe(block, name)));
		}
		return out;
	}

	private static final Pattern NAME = Pattern.compile("(?m)^\\s*Name: (.+)$");

	/** What the desktop calls a device (e.g. "Built-in Audio Analog Stereo"), else its node name tidied up. */
	static String describe(String block, String name) {
		String description = grab(block, Pattern.compile("(?m)^\\s*Description: (.+)$"));
		return description != null && !description.isBlank() ? description.trim() : prettify(name);
	}

	/** A `pactl list sources` block that's an output's monitor rather than a mic. */
	static boolean isOutput(String block) {
		String monitorOf = grab(block, Pattern.compile("(?m)^\\s*Monitor of Sink: (.+)$"));
		String mediaClass = grab(block, Pattern.compile("media\\.class = \"([^\"]*)\""));
		return (monitorOf != null && !monitorOf.trim().equals("n/a"))
				|| (mediaClass != null && mediaClass.contains("Sink"))
				|| block.contains("device.class = \"monitor\"");
	}

	/** The default mic's node name, or "" when PipeWire can't be asked (not its error message). */
	public String defaultSource() {
		Result r = run("pactl", "get-default-source");
		return r.exit() == 0 ? r.out().trim() : "";
	}

	/** The default output's node name, or "" when PipeWire can't be asked. */
	public String defaultSink() {
		Result r = run("pactl", "get-default-sink");
		return r.exit() == 0 ? r.out().trim() : "";
	}

	public void moveInput(int inputIndex, String sinkName) {
		run("pactl", "move-sink-input", Integer.toString(inputIndex), sinkName);
	}

	/** source = true targets a source (the virtual mic) instead of a sink. */
	public void setVolume(String name, int volume, boolean source) {
		run("pactl", source ? "set-source-volume" : "set-sink-volume", name, volume + "%");
	}

	public void setMute(String name, boolean muted, boolean source) {
		run("pactl", source ? "set-source-mute" : "set-sink-mute", name, muted ? "1" : "0");
	}

	public List<AppStream> listStreams() {
		Map<Integer, String> sinks = indexToName("sinks");
		String text = run("pactl", "list", "sink-inputs").out();
		List<AppStream> out = new ArrayList<>();
		if (text.isBlank()) {
			return out;
		}
		for (String block : text.split("(?m)^Sink Input #")) {
			if (block.isBlank()) {
				continue;
			}
			int index = (int) parseLong(grab(block, Pattern.compile("^(\\d+)")));
			if (index < 0) {
				continue; // not a sink-input block, e.g. a warning printed before the first one
			}
			String appName = grab(block, Pattern.compile("application\\.name = \"([^\"]*)\""));
			String binary = grab(block, Pattern.compile("application\\.process\\.binary = \"([^\"]*)\""));
			if (appName == null && binary == null) {
				continue; // not an application stream (our loopbacks, system streams, etc.)
			}
			if (appName == null) {
				appName = binary;
			}
			binary = appKey(binary, appName);
			int sinkIndex = (int) parseLong(grab(block, Pattern.compile("Sink:\\s*(\\d+)")));
			String pid = grab(block, Pattern.compile("application\\.process\\.id = \"(\\d+)\""));
			String role = grab(block, Pattern.compile("media\\.role = \"([^\"]*)\""));
			out.add(new AppStream(index, binary, appName, sinks.getOrDefault(sinkIndex, "?"),
					pid == null ? -1 : (int) parseLong(pid), role));
		}
		return out;
	}

	/**
	 * The name an app's routing is remembered by: its process binary, except under Wine/Proton,
	 * where every game is `wine64-preloader` — there the game's own application.name tells them apart.
	 */
	static String appKey(String binary, String appName) {
		if (binary == null || binary.isBlank() || binary.matches("wine(64)?(-preloader)?")) {
			return appName.toLowerCase();
		}
		return binary;
	}

	private static String prettify(String name) {
		return name.replaceFirst("^alsa_(output|input)\\.", "")
				.replace('.', ' ')
				.replace('_', ' ')
				.replace('-', ' ')
				.trim();
	}
}
