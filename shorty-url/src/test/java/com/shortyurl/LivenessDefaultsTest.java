package com.shortyurl;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class LivenessDefaultsTest {

	@Autowired
	private Environment environment;

	@Test
	void defaultProfile_usesSpecLivenessSettings() {
		assertThat(environment.getActiveProfiles()).doesNotContain("test");
		assertThat(environment.getProperty("shortyurl.liveness.max-attempts")).isEqualTo("3");
		assertThat(environment.getProperty("shortyurl.liveness.timeout")).isEqualTo("30s");
		assertThat(environment.getProperty("shortyurl.liveness.max-body-bytes")).isEqualTo("65536");
		assertThat(environment.getProperty("shortyurl.liveness.timeout", Duration.class)).isEqualTo(Duration.ofSeconds(30));
	}
}
