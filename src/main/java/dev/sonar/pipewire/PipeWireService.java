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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.sonar.domain.EqProfile;
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

	public long loadNullSink(String sinkName, String description) {
		Result r = run("pactl", "load-module", "module-null-sink",
				"sink_name=" + sinkName, "sink_properties=device.description=" + description);
		return parseLong(r.out());
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
	// monitor, plays into the physical device. Mic: reads the physical mic and is itself the
	// virtual mic source. Band gains change live with `pw-cli set-param`, no restart.
	//
	// ponytail: a dead EQ process isn't respawned (it only dies if PipeWire itself restarts,
	// which takes the null-sinks down too); restart Sonar to recover.

	/** Per-user runtime dir (/run/user/<uid>, 0700): /tmp is shared, so another user's sonar-eq would block ours. */
	private static final Path EQ_DIR = Path.of(System.getenv().getOrDefault("XDG_RUNTIME_DIR",
			System.getProperty("java.io.tmpdir")), "sonar-eq");

	private final Map<String, Process> eqProcesses = new ConcurrentHashMap<>();

	/** (Re)start the EQ for channel node `name`, routed to/from `device`. Mic: waits until the source exists. */
	public void startEq(String name, String description, String device, double[] gains, boolean mic) {
		stopEq(name);
		try {
			Files.createDirectories(EQ_DIR);
			Path conf = EQ_DIR.resolve(name + ".conf");
			Files.writeString(conf, eqConf(name, description, device, gains, mic));
			eqProcesses.put(name, new ProcessBuilder("pipewire", "-c", conf.toString())
					.redirectErrorStream(true).redirectOutput(Redirect.DISCARD).start());
		} catch (IOException e) {
			throw new UncheckedIOException("Can't start EQ for " + name, e);
		}
		if (mic) {
			// volume/mute are set on the source right after this; it must exist by then
			for (int i = 0; i < 30 && !indexToName("sources").containsValue(name); i++) {
				sleep(100);
			}
		}
	}

	/** Live gain change on a running EQ; gains[i] is band b{i}. */
	public void setEqGains(String name, double[] gains) {
		StringBuilder params = new StringBuilder();
		for (int i = 0; i < gains.length; i++) {
			params.append(" \"b").append(i).append(":Gain\" ").append(gains[i]);
		}
		run("pw-cli", "set-param", name + "_eq_in", "Props", "{ params = [" + params + " ] }");
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

	/** filter-chain config: a chain of peaking biquads, one per band (SPA-JSON). */
	static String eqConf(String name, String description, String device, double[] gains, boolean mic) {
		StringBuilder nodes = new StringBuilder();
		StringBuilder links = new StringBuilder();
		for (int i = 0; i < gains.length; i++) {
			nodes.append("          { type = builtin name = b").append(i).append(" label = bq_peaking control = { \"Freq\" = ")
					.append(EqProfile.BANDS[i]).append(".0 \"Q\" = 1.41 \"Gain\" = ").append(gains[i]).append(" } }\n");
			if (i > 0) {
				links.append("          { output = \"b").append(i - 1).append(":Out\" input = \"b").append(i).append(":In\" }\n");
			}
		}
		String capture = mic
				? "node.name = " + quote(name + "_eq_in") + " target.object = " + quote(device) + " node.passive = true"
				: "node.name = " + quote(name + "_eq_in") + " target.object = " + quote(name)
						+ " stream.capture.sink = true node.passive = true";
		String playback = mic
				? "node.name = " + quote(name) + " media.class = Audio/Source node.description = " + quote(description)
				: "node.name = " + quote(name + "_eq_out") + " target.object = " + quote(device);
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
				]
				""".formatted(quote(description + " EQ"), nodes, links, mic ? "1" : "2", mic ? "MONO" : "FL FR",
				capture, playback);
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

	private static void sleep(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// --- sinks / routing / volume ---

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
		for (String name : indexToName("sinks").values()) {
			if (name.startsWith("sonar_")) {
				continue;
			}
			out.add(new PhysicalSink(name, prettify(name)));
		}
		return out;
	}

	/** Physical mics: every source except sink monitors and our own virtual mic. */
	public List<PhysicalSink> listPhysicalSources() {
		List<PhysicalSink> out = new ArrayList<>();
		for (String name : indexToName("sources").values()) {
			if (name.startsWith("sonar_") || name.endsWith(".monitor")) {
				continue;
			}
			out.add(new PhysicalSink(name, prettify(name)));
		}
		return out;
	}

	public String defaultSource() {
		return run("pactl", "get-default-source").out().trim();
	}

	public String defaultSink() {
		return run("pactl", "get-default-sink").out().trim();
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
