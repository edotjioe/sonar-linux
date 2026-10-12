package dev.sonar.obs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal obs-websocket v5 client (built into OBS 28+): connect, authenticate, send requests.
 * Port and password come from OBS's own config, so there's nothing to set up in Sonar;
 * the user only has to enable the server in OBS (Tools → WebSocket Server Settings).
 * Connects lazily on the first request and again after OBS restarts.
 */
@Component
public class ObsClient {

	private static final Logger log = LoggerFactory.getLogger(ObsClient.class);

	private static final Path CONFIG = Path.of(System.getProperty("user.home"),
			".config/obs-studio/plugin_config/obs-websocket/config.json");
	private static final long RETRY_MS = 3000; // don't knock on a closed port every poll

	private final JsonMapper json = JsonMapper.builder().build();
	private final Map<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
	private volatile WebSocket ws;
	private volatile String status = "Not connected";
	private long lastAttempt;

	/** Why OBS isn't usable right now ("OBS isn't running", ...), or null when connected. */
	public String unavailable() {
		return connect() ? null : status;
	}

	/** Send a request; returns its responseData (possibly empty). OBS-side failures throw IllegalArgumentException. */
	public JsonNode request(String type, Map<String, ?> data) {
		if (!connect()) {
			throw new IllegalStateException(status);
		}
		String id = UUID.randomUUID().toString();
		CompletableFuture<JsonNode> reply = new CompletableFuture<>();
		pending.put(id, reply);
		try {
			send(Map.of("op", 6, "d", Map.of("requestType", type, "requestId", id, "requestData", data)));
			JsonNode d = reply.get(3, TimeUnit.SECONDS);
			JsonNode result = d.path("requestStatus");
			if (!result.path("result").asBoolean()) {
				throw new IllegalArgumentException("OBS: " + result.path("comment").asString(type + " failed"));
			}
			return d.path("responseData");
		} catch (IllegalArgumentException e) {
			throw e;
		} catch (Exception e) {
			throw new IllegalStateException("OBS didn't answer " + type, e);
		} finally {
			pending.remove(id);
		}
	}

	public JsonNode request(String type) {
		return request(type, Map.of());
	}

	/**
	 * Re-point OBS audio inputs that capture a Sonar channel (device "sonar_…") at that device again.
	 * Sonar recreates its sinks on every start; OBS's capture of the old one dies with it and OBS only
	 * reconnects when the device *changes*, so hop via "default" and back. No-op when OBS is unavailable.
	 *
	 * ponytail: a fixed 1 s pause between the hops. Back-to-back changes race OBS's PulseAudio
	 * teardown and leak a capture of the default output (recording everything); seen on OBS 32.2.
	 */
	public void reattachSonarInputs() {
		if (unavailable() != null) {
			return;
		}
		try {
			for (JsonNode input : request("GetInputList").path("inputs")) {
				String name = input.path("inputName").asString();
				String device = request("GetInputSettings", Map.of("inputName", name))
						.path("inputSettings").path("device_id").asString("");
				if (device.startsWith("sonar_")) {
					request("SetInputSettings", Map.of("inputName", name,
							"inputSettings", Map.of("device_id", "default"), "overlay", true));
					Thread.sleep(1000);
					request("SetInputSettings", Map.of("inputName", name,
							"inputSettings", Map.of("device_id", device), "overlay", true));
					log.info("OBS: reattached '{}' to {}", name, device);
				}
			}
		} catch (RuntimeException e) {
			log.warn("OBS: couldn't reattach Sonar inputs: {}", e.getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private synchronized boolean connect() {
		if (ws != null) {
			return true;
		}
		if (System.currentTimeMillis() - lastAttempt < RETRY_MS) {
			return false;
		}
		lastAttempt = System.currentTimeMillis();
		JsonNode config;
		try {
			config = json.readTree(Files.readString(CONFIG));
		} catch (Exception e) {
			status = "OBS isn't installed (no obs-websocket config)";
			return false;
		}
		if (!config.path("server_enabled").asBoolean()) {
			status = "Enable the WebSocket server in OBS: Tools → WebSocket Server Settings";
			return false;
		}
		String password = config.path("auth_required").asBoolean() ? config.path("server_password").asString() : null;
		CompletableFuture<Void> identified = new CompletableFuture<>();
		try {
			WebSocket socket = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build()
					.newWebSocketBuilder()
					.buildAsync(URI.create("ws://localhost:" + config.path("server_port").asInt(4455)),
							new Listener(password, identified))
					.get(2, TimeUnit.SECONDS);
			ws = socket;
			identified.get(2, TimeUnit.SECONDS);
			status = null;
			return true;
		} catch (Exception e) {
			closed();
			status = identified.isCompletedExceptionally() ? "OBS rejected the WebSocket password" : "OBS isn't running";
			return false;
		}
	}

	/** Send one JSON message; the JDK WebSocket allows only one send in flight. */
	private synchronized void send(Object message) {
		ws.sendText(json.writeValueAsString(message), true).join();
	}

	private void closed() {
		WebSocket old = ws;
		ws = null;
		if (old != null) {
			old.abort();
		}
		pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException("OBS disconnected")));
	}

	/** obs-websocket auth: base64(sha256(base64(sha256(password + salt)) + challenge)). */
	static String auth(String password, String salt, String challenge) {
		return sha256b64(sha256b64(password + salt) + challenge);
	}

	private static String sha256b64(String s) {
		try {
			return Base64.getEncoder().encodeToString(
					MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	@PreDestroy
	void shutdown() {
		closed();
	}

	/** Handles Hello → Identify, Identified, and request responses. Events are off (eventSubscriptions 0). */
	private final class Listener implements WebSocket.Listener {

		private final String password;
		private final CompletableFuture<Void> identified;
		private final StringBuilder buffer = new StringBuilder();

		Listener(String password, CompletableFuture<Void> identified) {
			this.password = password;
			this.identified = identified;
		}

		@Override
		public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
			buffer.append(data);
			if (last) {
				JsonNode msg = json.readTree(buffer.toString());
				buffer.setLength(0);
				JsonNode d = msg.path("d");
				switch (msg.path("op").asInt()) {
					case 0 -> { // Hello
						JsonNode challenge = d.path("authentication");
						Map<String, Object> identify = challenge.isMissingNode() || password == null
								? Map.of("rpcVersion", 1, "eventSubscriptions", 0)
								: Map.of("rpcVersion", 1, "eventSubscriptions", 0, "authentication",
										auth(password, challenge.path("salt").asString(), challenge.path("challenge").asString()));
						socket.sendText(json.writeValueAsString(Map.of("op", 1, "d", identify)), true);
					}
					case 2 -> identified.complete(null); // Identified
					case 7 -> { // RequestResponse
						CompletableFuture<JsonNode> reply = pending.get(d.path("requestId").asString());
						if (reply != null) {
							reply.complete(d);
						}
					}
					default -> {
					}
				}
			}
			socket.request(1);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
			identified.completeExceptionally(new IllegalStateException(reason)); // e.g. 4009 auth failed
			if (ws == socket) {
				closed();
			}
			return null;
		}

		@Override
		public void onError(WebSocket socket, Throwable error) {
			identified.completeExceptionally(error);
			if (ws == socket) {
				closed();
			}
		}
	}
}
