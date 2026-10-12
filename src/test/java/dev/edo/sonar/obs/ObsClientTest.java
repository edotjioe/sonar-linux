package dev.edo.sonar.obs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ObsClientTest {

	@Test
	void authDoubleHashesPasswordSaltAndChallenge() {
		assertEquals("1Ct943GAT+6YQUUX47Ia/ncufilbe6+oD6lY+5kaCu4=",
				ObsClient.auth("supersecretpassword",
						"lM1GncleQOaCu9lT1yeUZhFYnqhsLLP1G5lAGo3ixaI=",
						"+IxH4CnCiqpX1rM9scsNynZzbOe4KhDeYcTNS3PDaeY="));
	}

	@Test
	void reattachesOnlySonarInputsByHoppingThroughDefault() throws Exception {
		ObsClient obs = spy(new ObsClient());
		doReturn(null).when(obs).unavailable();

		JsonMapper json = JsonMapper.builder().build();
		doReturn(json.readTree(
				"{\"inputs\":[{\"inputName\":\"Desktop Audio\"},{\"inputName\":\"Mic/Aux\"},{\"inputName\":\"Webcam\"}]}"))
				.when(obs).request("GetInputList");
		doReturn(json.readTree("{\"inputSettings\":{\"device_id\":\"sonar_game.monitor\"}}"))
				.when(obs).request("GetInputSettings", Map.of("inputName", "Desktop Audio"));
		doReturn(json.readTree("{\"inputSettings\":{\"device_id\":\"default\"}}"))
				.when(obs).request("GetInputSettings", Map.of("inputName", "Mic/Aux"));
		doReturn(json.readTree("{\"inputSettings\":{}}"))
				.when(obs).request("GetInputSettings", Map.of("inputName", "Webcam"));
		doReturn(json.readTree("{}")).when(obs).request(eq("SetInputSettings"), any());

		obs.reattachSonarInputs();

		InOrder inOrder = inOrder(obs);
		inOrder.verify(obs).request("SetInputSettings", Map.of("inputName", "Desktop Audio",
				"inputSettings", Map.of("device_id", "default"), "overlay", true));
		inOrder.verify(obs).request("SetInputSettings", Map.of("inputName", "Desktop Audio",
				"inputSettings", Map.of("device_id", "sonar_game.monitor"), "overlay", true));
		verify(obs, times(2)).request(eq("SetInputSettings"), any());
	}

	@Test
	void doesNothingWhenObsUnavailable() {
		ObsClient obs = spy(new ObsClient());
		doReturn("OBS isn't running").when(obs).unavailable();

		assertDoesNotThrow(obs::reattachSonarInputs);

		verify(obs, never()).request(anyString());
		verify(obs, never()).request(anyString(), any());
	}

	@Test
	void logsAndSwallowsObsErrors() {
		ObsClient obs = spy(new ObsClient());
		doReturn(null).when(obs).unavailable();
		doThrow(new IllegalStateException("OBS disconnected")).when(obs).request("GetInputList");

		assertDoesNotThrow(obs::reattachSonarInputs);
	}
}
