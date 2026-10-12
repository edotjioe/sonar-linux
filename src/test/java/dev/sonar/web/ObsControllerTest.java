package dev.sonar.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import dev.sonar.obs.ObsClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ObsControllerTest {

	@Mock
	private ObsClient obs;

	private ObsController controller;

	@BeforeEach
	void setUp() {
		controller = new ObsController(obs);
	}

	private static JsonNode json(String text) {
		return JsonMapper.builder().build().readTree(text);
	}

	@Test
	void statusWhenUnavailableReportsReason() {
		when(obs.unavailable()).thenReturn("OBS isn't running");

		ObsController.ObsView view = controller.status();

		assertEquals("OBS isn't running", view.reason());
		assertNull(view.recording());
		assertEquals(0, view.inputs().size());
		verify(obs, never()).request(anyString());
		verify(obs, never()).request(anyString(), any());
	}

	@Test
	void statusConnectedListsMutedInputsWithDevices() {
		when(obs.unavailable()).thenReturn(null);
		when(obs.request("GetRecordStatus"))
				.thenReturn(json("{\"outputActive\":true,\"outputPaused\":false,\"outputTimecode\":\"00:01:02.345\"}"));
		when(obs.request("GetInputList"))
				.thenReturn(json("{\"inputs\":[{\"inputName\":\"Mic/Aux\"},{\"inputName\":\"Webcam\"}]}"));
		when(obs.request(eq("GetInputMute"), eq(Map.of("inputName", "Mic/Aux"))))
				.thenReturn(json("{\"inputMuted\":true}"));
		when(obs.request(eq("GetInputMute"), eq(Map.of("inputName", "Webcam"))))
				.thenThrow(new IllegalArgumentException("OBS: no audio"));
		when(obs.request(eq("GetInputSettings"), eq(Map.of("inputName", "Mic/Aux"))))
				.thenReturn(json("{\"inputSettings\":{\"device_id\":\"sonar_mic\"}}"));
		when(obs.request(eq("GetInputPropertiesListPropertyItems"),
				eq(Map.of("inputName", "Mic/Aux", "propertyName", "device_id"))))
				.thenReturn(json(
						"{\"propertyItems\":[{\"itemName\":\"Default\",\"itemValue\":\"default\",\"itemEnabled\":true},"
								+ "{\"itemName\":\"Mic\",\"itemValue\":\"sonar_mic\",\"itemEnabled\":true},"
								+ "{\"itemName\":\"Off\",\"itemValue\":\"x\",\"itemEnabled\":false}]}"));

		ObsController.ObsView view = controller.status();

		assertNull(view.reason());
		assertEquals(true, view.recording().active());
		assertEquals(false, view.recording().paused());
		assertEquals("00:01:02.345", view.recording().timecode());
		assertEquals(1, view.inputs().size());
		ObsController.AudioInput mic = view.inputs().get(0);
		assertEquals("Mic/Aux", mic.name());
		assertEquals(true, mic.muted());
		assertEquals("sonar_mic", mic.deviceId());
		assertEquals(2, mic.devices().size());
		assertEquals("default", mic.devices().get(0).value());
		assertEquals("sonar_mic", mic.devices().get(1).value());
	}

	@Test
	void recordMapsActionsToRequestTypes() {
		controller.record(Map.of("action", "pause"));
		controller.record(Map.of("action", "start"));
		controller.record(Map.of("action", "stop"));
		controller.record(Map.of("action", "resume"));

		verify(obs).request("PauseRecord");
		verify(obs).request("StartRecord");
		verify(obs).request("StopRecord");
		verify(obs).request("ResumeRecord");
	}

	@Test
	void recordRejectsUnknownOrMissingAction() {
		assertThrows(IllegalArgumentException.class, () -> controller.record(Map.of("action", "explode")));
		assertThrows(IllegalArgumentException.class, () -> controller.record(new HashMap<>()));
	}

	@Test
	void inputMuteSetsMuteOnly() {
		controller.input(new ObsController.InputBody("Mic/Aux", true, null));

		verify(obs).request(eq("SetInputMute"), eq(Map.of("inputName", "Mic/Aux", "inputMuted", true)));
		verify(obs, never()).request(eq("SetInputSettings"), any());
	}

	@Test
	void inputDeviceSetsSettings() {
		controller.input(new ObsController.InputBody("Desktop Audio", null, "sonar_game"));

		verify(obs).request(eq("SetInputSettings"), eq(Map.of("inputName", "Desktop Audio",
				"inputSettings", Map.of("device_id", "sonar_game"), "overlay", true)));
	}

	@Test
	void previewReturnsDecodedJpeg() {
		when(obs.request("GetCurrentProgramScene"))
				.thenReturn(json("{\"currentProgramSceneName\":\"Gaming\"}"));
		when(obs.request(eq("GetSourceScreenshot"), eq(Map.of("sourceName", "Gaming", "imageFormat", "jpg",
				"imageWidth", 480, "imageCompressionQuality", 70))))
				.thenReturn(json("{\"imageData\":\"data:image/jpg;base64,AQID\"}"));

		byte[] bytes = controller.preview();

		assertArrayEquals(new byte[] {1, 2, 3}, bytes);
	}

	@Test
	void previewStopsWhenObsNotRunning() {
		when(obs.request("GetCurrentProgramScene")).thenThrow(new IllegalStateException("OBS isn't running"));

		assertThrows(IllegalStateException.class, () -> controller.preview());

		verify(obs, never()).request(eq("GetSourceScreenshot"), any());
	}

	@Test
	void inputRejectsBlankName() {
		assertThrows(IllegalArgumentException.class,
				() -> controller.input(new ObsController.InputBody(null, true, null)));
		assertThrows(IllegalArgumentException.class,
				() -> controller.input(new ObsController.InputBody("  ", true, null)));
	}
}
