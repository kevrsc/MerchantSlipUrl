package com.shortyurl.web;

import com.jayway.jsonpath.JsonPath;
import com.shortyurl.persistence.Binding;
import com.shortyurl.persistence.BindingRepository;
import com.shortyurl.service.UrlSanitizer;
import com.shortyurl.support.MutableClock;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ResolveUrlIT.MutableClockConfiguration.class)
class ResolveUrlIT {

	static final Duration TEST_PROFILE_INACTIVITY = Duration.ofSeconds(5);

	static final Duration AGE_INSIDE_DEFAULT_WINDOW = Duration.ofSeconds(3);

	private static final String LONG_URL = "https://orders.example.com/checkout?orderId=42&token=abc";
	private static final String SHORT_CODE = "abc123XY";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BindingRepository bindingRepository;

	@Autowired
	private MutableClock clock;

	@Autowired
	private UrlSanitizer urlSanitizer;

	@Value("${shortyurl.inactivity}")
	private Duration inactivity;

	@BeforeEach
	void cleanStore() {
		assertThat(inactivity).isEqualTo(TEST_PROFILE_INACTIVITY);
		bindingRepository.deleteAll();
		clock.setInstant(Instant.now().truncatedTo(ChronoUnit.MILLIS));
	}

	@Test
	void happyResolve_redirectsToOriginalUrlAndSetsLastAccessedAt() throws Exception {
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL, clock.instant(), null);

		mockMvc.perform(get("/" + SHORT_CODE))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", LONG_URL));

		Binding updated = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow();
		assertThat(updated.getLastAccessedAt()).isEqualTo(clock.instant());
	}

	@Test
	void bindingInsidePeriod_redirects() throws Exception {
		Instant createdAt = clock.instant().minus(AGE_INSIDE_DEFAULT_WINDOW);
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL, createdAt, null);

		mockMvc.perform(get("/" + SHORT_CODE))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", LONG_URL));

		assertThat(bindingRepository.findByShortCode(SHORT_CODE).orElseThrow().getLastAccessedAt())
				.isEqualTo(clock.instant());
	}

	@Test
	void accessResetsWindow_secondResolveMeasuredFromFirstAccess() throws Exception {
		Instant createdAt = clock.instant().minus(inactivity).plusSeconds(1);
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL, createdAt, null);

		mockMvc.perform(get("/" + SHORT_CODE))
				.andExpect(status().isFound());

		Instant firstAccess = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow().getLastAccessedAt();
		assertThat(firstAccess).isEqualTo(clock.instant());

		clock.advance(inactivity.minusSeconds(1));

		mockMvc.perform(get("/" + SHORT_CODE))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", LONG_URL));

		Binding updated = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow();
		assertThat(updated.getLastAccessedAt()).isEqualTo(clock.instant());
		assertThat(updated.getLastAccessedAt()).isAfter(firstAccess);
		assertThat(createdAt.plus(inactivity)).isBefore(clock.instant());
	}

	@Test
	void expired_returns410AndLeavesLastAccessUnchanged() throws Exception {
		Instant anchor = clock.instant().minus(inactivity);
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL, anchor, anchor);

		MvcResult result = mockMvc.perform(get("/" + SHORT_CODE))
				.andExpect(status().isGone())
				.andExpect(header().doesNotExist("Location"))
				.andExpect(jsonPath("$.status").value(410))
				.andReturn();

		assertThat(result.getResponse().getContentAsString()).doesNotContain(LONG_URL);
		Binding stored = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow();
		assertThat(stored.getLastAccessedAt()).isEqualTo(anchor);
	}

	@Test
	void neverAccessed_createdAtOlderThanPeriod_returns410() throws Exception {
		Instant createdAt = clock.instant().minus(inactivity);
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL, createdAt, null);

		MvcResult result = mockMvc.perform(get("/" + SHORT_CODE))
				.andExpect(status().isGone())
				.andExpect(header().doesNotExist("Location"))
				.andExpect(jsonPath("$.status").value(410))
				.andReturn();

		assertThat(result.getResponse().getContentAsString()).doesNotContain(LONG_URL);
		Binding stored = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow();
		assertThat(stored.getLastAccessedAt()).isNull();
		assertThat(stored.getCreatedAt()).isEqualTo(createdAt);
	}

	@Test
	void resolve_usesOriginalUrlNotSanitizedUrl() throws Exception {
		String originalUrl = "https://orders.example.com/checkout?orderId=42&raw=1";
		String sanitizedUrl = "https://orders.example.com/checkout?orderId=42";
		persistBinding(SHORT_CODE, originalUrl, sanitizedUrl, clock.instant(), null);

		mockMvc.perform(get("/" + SHORT_CODE))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", originalUrl));
	}

	@Test
	void missingCode_returns404WithNoRedirect() throws Exception {
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL, clock.instant(), null);

		mockMvc.perform(get("/unknown99"))
				.andExpect(status().isNotFound())
				.andExpect(header().doesNotExist("Location"));

		assertThat(bindingRepository.count()).isEqualTo(1);
		Binding seeded = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow();
		assertThat(seeded.getLastAccessedAt()).isNull();
	}

	@Test
	void resolveWithoutAuth_doesNotReturn401() throws Exception {
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL, clock.instant(), null);

		MvcResult result = mockMvc.perform(get("/" + SHORT_CODE))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", LONG_URL))
				.andReturn();

		assertThat(result.getResponse().getStatus()).isNotEqualTo(401);
	}

	@Test
	void createThenResolve_landsOnSameOriginalUrl() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
			String liveUrl = server.url("/checkout?orderId=42&token=abc").toString();
			createThenResolve(liveUrl);
			assertThat(server.getRequestCount()).isEqualTo(1);
			assertThat(server.takeRequest().getMethod()).isEqualTo("GET");
		}
	}

	@Test
	void reactivate_expiredBinding_returnsSameTinyUrlAndResolves() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
			String liveUrl = server.url("/checkout?orderId=42&token=abc").toString();
			Instant expiredAt = clock.instant().minus(inactivity);
			Binding seeded = persistBinding(
					SHORT_CODE,
					liveUrl,
					urlSanitizer.sanitize(liveUrl),
					expiredAt,
					expiredAt);

			mockMvc.perform(get("/" + SHORT_CODE))
					.andExpect(status().isGone())
					.andExpect(header().doesNotExist("Location"));

			MvcResult createResult = mockMvc.perform(post("/api/v1/urls")
							.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"url":"%s"}
									""".formatted(liveUrl)))
					.andExpect(status().isOk())
					.andReturn();

			assertThat(readTinyUrl(createResult)).endsWith("/" + SHORT_CODE);
			assertThat(bindingRepository.count()).isEqualTo(1);

			Binding reactivated = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow();
			assertThat(reactivated.getId()).isEqualTo(seeded.getId());
			assertThat(reactivated.getCreatedAt()).isEqualTo(expiredAt);
			assertThat(reactivated.getLastAccessedAt()).isEqualTo(clock.instant());
			assertThat(reactivated.getOriginalUrl()).isEqualTo(liveUrl);

			mockMvc.perform(get("/" + SHORT_CODE))
					.andExpect(status().isFound())
					.andExpect(header().string("Location", liveUrl));
		}
	}

	@Test
	void liveCreate_writesNothing() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
			String liveUrl = server.url("/checkout?orderId=7").toString();
			Instant createdAt = clock.instant();
			persistBinding(SHORT_CODE, liveUrl, urlSanitizer.sanitize(liveUrl), createdAt, null);

			mockMvc.perform(get("/" + SHORT_CODE))
					.andExpect(status().isFound());
			Instant accessedAt = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow().getLastAccessedAt();

			mockMvc.perform(post("/api/v1/urls")
							.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"url":"%s"}
									""".formatted(liveUrl)))
					.andExpect(status().isOk());

			assertThat(bindingRepository.count()).isEqualTo(1);
			Binding stored = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow();
			assertThat(stored.getCreatedAt()).isEqualTo(createdAt);
			assertThat(stored.getLastAccessedAt()).isEqualTo(accessedAt);
			assertThat(stored.getShortCode()).isEqualTo(SHORT_CODE);
		}
	}

	@Test
	void recentAccess_createKeepsSameTinyUrlAndLeavesLastAccessedAt() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
			String liveUrl = server.url("/checkout?orderId=11").toString();
			Instant createdAt = clock.instant().minus(inactivity);
			Instant lastAccessedAt = clock.instant().minus(inactivity).plusSeconds(1);
			persistBinding(SHORT_CODE, liveUrl, urlSanitizer.sanitize(liveUrl), createdAt, lastAccessedAt);

			MvcResult createResult = mockMvc.perform(post("/api/v1/urls")
							.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"url":"%s"}
									""".formatted(liveUrl)))
					.andExpect(status().isOk())
					.andReturn();

			assertThat(readTinyUrl(createResult)).endsWith("/" + SHORT_CODE);
			Binding stored = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow();
			assertThat(stored.getLastAccessedAt()).isEqualTo(lastAccessedAt);
			assertThat(stored.getCreatedAt()).isEqualTo(createdAt);
		}
	}

	@Test
	void neverAccessedExpired_createSetsLastAccessedAtAndResolves() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
			String liveUrl = server.url("/checkout?orderId=12").toString();
			Instant createdAt = clock.instant().minus(inactivity).minusSeconds(1);
			persistBinding(SHORT_CODE, liveUrl, urlSanitizer.sanitize(liveUrl), createdAt, null);

			MvcResult createResult = mockMvc.perform(post("/api/v1/urls")
							.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"url":"%s"}
									""".formatted(liveUrl)))
					.andExpect(status().isOk())
					.andReturn();

			assertThat(readTinyUrl(createResult)).endsWith("/" + SHORT_CODE);
			Binding stored = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow();
			assertThat(stored.getLastAccessedAt()).isEqualTo(clock.instant());
			assertThat(stored.getCreatedAt()).isEqualTo(createdAt);

			mockMvc.perform(get("/" + SHORT_CODE))
					.andExpect(status().isFound())
					.andExpect(header().string("Location", liveUrl));
		}
	}

	private void createThenResolve(String liveUrl) throws Exception {
		MvcResult createResult = mockMvc.perform(post("/api/v1/urls")
						.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"url":"%s"}
								""".formatted(liveUrl)))
				.andExpect(status().isCreated())
				.andReturn();

		String tinyUrl = readTinyUrl(createResult);
		String shortCode = tinyUrl.substring(tinyUrl.lastIndexOf('/') + 1);

		mockMvc.perform(get("/" + shortCode))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", liveUrl));

		Binding resolved = bindingRepository.findByShortCode(shortCode).orElseThrow();
		assertThat(resolved.getLastAccessedAt()).isEqualTo(clock.instant());
	}

	private Binding persistBinding(
			String shortCode,
			String originalUrl,
			String sanitizedUrl,
			Instant createdAt,
			Instant lastAccessedAt) {
		Binding binding = new Binding();
		binding.setShortCode(shortCode);
		binding.setOriginalUrl(originalUrl);
		binding.setSanitizedUrl(sanitizedUrl);
		binding.setCreatedAt(createdAt);
		binding.setLastAccessedAt(lastAccessedAt);
		return bindingRepository.saveAndFlush(binding);
	}

	private static String readTinyUrl(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.tinyUrl");
	}

	@TestConfiguration
	static class MutableClockConfiguration {

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));
		}
	}
}

@SpringBootTest(properties = "shortyurl.inactivity=1s")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(ResolveUrlIT.MutableClockConfiguration.class)
class ResolveUrlShorterPeriodIT {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BindingRepository bindingRepository;

	@Autowired
	private MutableClock clock;

	@Value("${shortyurl.inactivity}")
	private Duration inactivity;

	@BeforeEach
	void cleanStore() {
		assertThat(inactivity).isEqualTo(Duration.ofSeconds(1));
		assertThat(ResolveUrlIT.AGE_INSIDE_DEFAULT_WINDOW).isLessThan(ResolveUrlIT.TEST_PROFILE_INACTIVITY);
		assertThat(ResolveUrlIT.AGE_INSIDE_DEFAULT_WINDOW).isGreaterThanOrEqualTo(inactivity);
		bindingRepository.deleteAll();
		clock.setInstant(Instant.now().truncatedTo(ChronoUnit.MILLIS));
	}

	@Test
	void shorterPeriod_refusesBindingInsideTheDefaultWindow() throws Exception {
		Instant createdAt = clock.instant().minus(ResolveUrlIT.AGE_INSIDE_DEFAULT_WINDOW);
		Binding binding = new Binding();
		binding.setShortCode("shortPer");
		binding.setOriginalUrl("https://orders.example.com/checkout?orderId=42&token=abc");
		binding.setSanitizedUrl("https://orders.example.com/checkout?orderId=42&token=abc");
		binding.setCreatedAt(createdAt);
		bindingRepository.saveAndFlush(binding);

		mockMvc.perform(get("/shortPer"))
				.andExpect(status().isGone())
				.andExpect(header().doesNotExist("Location"))
				.andExpect(jsonPath("$.status").value(410));

		Binding stored = bindingRepository.findByShortCode("shortPer").orElseThrow();
		assertThat(stored.getLastAccessedAt()).isNull();
		assertThat(stored.getCreatedAt()).isEqualTo(createdAt);
	}
}
