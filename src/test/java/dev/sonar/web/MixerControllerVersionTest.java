package dev.sonar.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;

import dev.sonar.pipewire.PipeWireService;
import dev.sonar.repo.AppAssignmentRepository;
import dev.sonar.repo.EqProfileRepository;
import dev.sonar.repo.PreferencesRepository;
import dev.sonar.service.AppClassifier;
import dev.sonar.service.LevelMeter;
import dev.sonar.service.MixerService;

@ExtendWith(MockitoExtension.class)
class MixerControllerVersionTest {

	@Mock
	private MixerService mixer;
	@Mock
	private PipeWireService pw;
	@Mock
	private AppAssignmentRepository assignments;
	@Mock
	private LevelMeter meters;
	@Mock
	private AppClassifier classifier;
	@Mock
	private PreferencesRepository preferences;
	@Mock
	private EqProfileRepository profiles;
	@Mock
	private ObjectProvider<BuildProperties> build;

	private MixerController controller;

	@BeforeEach
	void setUp() {
		controller = new MixerController(mixer, pw, assignments, meters, classifier, preferences, profiles, build, null);
	}

	@Test
	void versionReturnsBuildInfoWhenAvailable() {
		Properties props = new Properties();
		props.setProperty("version", "1.0.0");
		props.setProperty("time", "2026-10-12T17:00:00Z");
		BuildProperties bp = new BuildProperties(props);
		when(build.getIfAvailable()).thenReturn(bp);

		assertEquals(Map.of("version", "1.0.0", "built", String.valueOf(bp.getTime())), controller.version());
	}

	@Test
	void versionReturnsDevWhenNoBuildInfo() {
		when(build.getIfAvailable()).thenReturn(null);

		assertEquals(Map.of("version", "dev"), controller.version());
	}
}
