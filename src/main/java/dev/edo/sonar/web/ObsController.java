package dev.edo.sonar.web;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.edo.sonar.obs.ObsClient;
import tools.jackson.databind.JsonNode;

/** OBS remote: recording controls plus each audio input's device and mute, over obs-websocket. */
@RestController
@RequestMapping("/api/obs")
public class ObsController {

	private final ObsClient obs;

	public ObsController(ObsClient obs) {
		this.obs = obs;
	}

	public record Device(String name, String value) {
	}

	/** devices empty when the input has no device to pick (e.g. an app-audio capture). */
	public record AudioInput(String name, boolean muted, String deviceId, List<Device> devices) {
	}

	public record Recording(boolean active, boolean paused, String timecode) {
	}

	/** reason: why OBS can't be used right now, null when connected (then recording/inputs are set). */
	public record ObsView(String reason, Recording recording, List<AudioInput> inputs) {
	}

	public record InputBody(String name, Boolean muted, String deviceId) {
	}

	@GetMapping
	public ObsView status() {
		String reason = obs.unavailable();
		if (reason != null) {
			return new ObsView(reason, null, List.of());
		}
		JsonNode rec = obs.request("GetRecordStatus");
		List<AudioInput> inputs = new ArrayList<>();
		for (JsonNode input : obs.request("GetInputList").path("inputs")) {
			String name = input.path("inputName").asString();
			Map<String, String> byName = Map.of("inputName", name);
			boolean muted;
			try {
				muted = obs.request("GetInputMute", byName).path("inputMuted").asBoolean();
			} catch (IllegalArgumentException e) {
				continue; // no audio (a scene's video source)
			}
			inputs.add(new AudioInput(name, muted,
					obs.request("GetInputSettings", byName).path("inputSettings").path("device_id").asString("default"),
					devices(name)));
		}
		return new ObsView(null, new Recording(rec.path("outputActive").asBoolean(),
				rec.path("outputPaused").asBoolean(), rec.path("outputTimecode").asString()), inputs);
	}

	/** OBS's own list of devices this input can capture (Sonar channels included), or empty. */
	private List<Device> devices(String input) {
		try {
			List<Device> out = new ArrayList<>();
			for (JsonNode item : obs.request("GetInputPropertiesListPropertyItems",
					Map.of("inputName", input, "propertyName", "device_id")).path("propertyItems")) {
				if (item.path("itemEnabled").asBoolean(true)) {
					out.add(new Device(item.path("itemName").asString(), item.path("itemValue").asString()));
				}
			}
			return out;
		} catch (IllegalArgumentException e) {
			return List.of();
		}
	}

	/**
	 * What OBS is outputting right now (the program scene) as a small JPEG; the OBS page reloads it
	 * every ~0.5 s for a live-ish preview. obs-websocket has no video stream, only screenshots.
	 *
	 * ponytail: each frame is an OBS screenshot (GPU readback + JPEG); fine at ~2 fps and 480 px.
	 */
	@GetMapping(value = "/preview", produces = MediaType.IMAGE_JPEG_VALUE)
	public byte[] preview() {
		String scene = obs.request("GetCurrentProgramScene").path("currentProgramSceneName").asString();
		String image = obs.request("GetSourceScreenshot", Map.of("sourceName", scene, "imageFormat", "jpg",
				"imageWidth", 480, "imageCompressionQuality", 70)).path("imageData").asString();
		// a data URI: "data:image/jpg;base64,...."
		return Base64.getDecoder().decode(image.substring(image.indexOf(',') + 1));
	}

	/** Body: {action: start | stop | pause | resume}. */
	@PostMapping("/record")
	public void record(@RequestBody Map<String, String> body) {
		String request = switch (String.valueOf(body.get("action"))) {
			case "start" -> "StartRecord";
			case "stop" -> "StopRecord";
			case "pause" -> "PauseRecord";
			case "resume" -> "ResumeRecord";
			default -> throw new IllegalArgumentException("action must be start, stop, pause or resume");
		};
		obs.request(request);
	}

	/** Body: {name, muted} and/or {name, deviceId}. */
	@PostMapping("/input")
	public void input(@RequestBody InputBody body) {
		if (body.name() == null || body.name().isBlank()) {
			throw new IllegalArgumentException("Missing field: name");
		}
		if (body.muted() != null) {
			obs.request("SetInputMute", Map.of("inputName", body.name(), "inputMuted", body.muted()));
		}
		if (body.deviceId() != null) {
			obs.request("SetInputSettings", Map.of("inputName", body.name(),
					"inputSettings", Map.of("device_id", body.deviceId()), "overlay", true));
		}
	}
}
