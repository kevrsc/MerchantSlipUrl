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

	@Value("${shortyurl.public-base-url}")
	private String publicBaseUrl;

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
	void unusableDestination_returns500AndLeavesLastAccessUnchanged() throws Exception {
		String badUrl = "http://orders.example.com/has space";
		persistBinding(SHORT_CODE, badUrl, "http://orders.example.com/has%20space", clock.instant(), null);

		mockMvc.perform(get("/" + SHORT_CODE))
				.andExpect(status().isInternalServerError())
				.andExpect(header().doesNotExist("Location"))
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.title").value("Internal Server Error"))
				.andExpect(jsonPath("$.detail").value("Stored destination is not a valid redirect target"));

		Binding stored = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow();
		assertThat(stored.getLastAccessedAt()).isNull();
		assertThat(stored.getOriginalUrl()).isEqualTo(badUrl);
		assertThat(stored.getCreatedAt()).isEqualTo(clock.instant());
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
	void expiredCreate_whenProbeFails_leavesLastAccessedAt() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(404).setBody("missing"));
			String liveUrl = server.url("/checkout?orderId=42&token=abc").toString();
			Instant expiredAt = clock.instant().minus(inactivity);
			persistBinding(
					SHORT_CODE,
					liveUrl,
					urlSanitizer.sanitize(liveUrl),
					expiredAt,
					expiredAt);

			mockMvc.perform(post("/api/v1/urls")
							.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"url":"%s"}
									""".formatted(liveUrl)))
					.andExpect(status().isBadGateway())
					.andExpect(jsonPath("$.status").value(502));

			assertThat(server.getRequestCount()).isEqualTo(1);
			assertThat(bindingRepository.count()).isEqualTo(1);
			Binding stored = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow();
			assertThat(stored.getLastAccessedAt()).isEqualTo(expiredAt);
			assertThat(stored.getCreatedAt()).isEqualTo(expiredAt);
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

	@Test
	void assignLiveBinding_replacesShortCodeAndOldCodeIs404() throws Exception {
		Instant createdAt = clock.instant().minusSeconds(2);
		Instant lastAccessedAt = clock.instant().minusSeconds(1);
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL, createdAt, lastAccessedAt);

		mockMvc.perform(assignJson(LONG_URL, "summer_sale"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tinyUrl").value(tinyUrl("summer_sale")))
				.andExpect(jsonPath("$.originalUrl").value(LONG_URL));

		assertThat(bindingRepository.findByShortCode(SHORT_CODE)).isEmpty();
		assertThat(bindingRepository.count()).isEqualTo(1);
		Binding updated = bindingRepository.findByShortCode("summer_sale").orElseThrow();
		assertThat(updated.getOriginalUrl()).isEqualTo(LONG_URL);
		assertThat(updated.getSanitizedUrl()).isEqualTo(LONG_URL);
		assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
		assertThat(updated.getLastAccessedAt()).isEqualTo(lastAccessedAt);

		mockMvc.perform(get("/" + SHORT_CODE))
				.andExpect(status().isNotFound())
				.andExpect(header().doesNotExist("Location"));

		mockMvc.perform(get("/summer_sale"))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", LONG_URL));
	}

	@Test
	void assignByTinyUrl_replacesShortCode() throws Exception {
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL, clock.instant(), clock.instant());

		mockMvc.perform(assignJson(tinyUrl(SHORT_CODE), "from-tiny"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tinyUrl").value(tinyUrl("from-tiny")))
				.andExpect(jsonPath("$.originalUrl").value(LONG_URL));

		assertThat(bindingRepository.findByShortCode(SHORT_CODE)).isEmpty();
		assertThat(bindingRepository.findByShortCode("from-tiny").orElseThrow().getOriginalUrl()).isEqualTo(LONG_URL);

		mockMvc.perform(get("/" + SHORT_CODE))
				.andExpect(status().isNotFound());
	}

	@Test
	void assignByEquivalentLongUrl_replacesShortCode() throws Exception {
		String originalUrl = "https://orders.example.com/path";
		persistBinding(SHORT_CODE, originalUrl, originalUrl, clock.instant(), null);

		mockMvc.perform(assignJson("  HTTPS://ORDERS.example.com/path#frag  ", "messy-vanity"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tinyUrl").value(tinyUrl("messy-vanity")))
				.andExpect(jsonPath("$.originalUrl").value(originalUrl));

		Binding updated = bindingRepository.findByShortCode("messy-vanity").orElseThrow();
		assertThat(updated.getSanitizedUrl()).isEqualTo(originalUrl);
		assertThat(updated.getCreatedAt()).isEqualTo(clock.instant());
	}

	@Test
	void assignExpired_resetsLastAccessAndResolves() throws Exception {
		Instant createdAt = clock.instant().minus(inactivity).minusSeconds(1);
		Instant lastAccessedAt = clock.instant().minus(inactivity);
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL, createdAt, lastAccessedAt);

		mockMvc.perform(assignJson(LONG_URL, "fresh-vanity"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tinyUrl").value(tinyUrl("fresh-vanity")))
				.andExpect(jsonPath("$.originalUrl").value(LONG_URL));

		Binding updated = bindingRepository.findByShortCode("fresh-vanity").orElseThrow();
		assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
		assertThat(updated.getOriginalUrl()).isEqualTo(LONG_URL);
		assertThat(updated.getSanitizedUrl()).isEqualTo(LONG_URL);
		assertThat(updated.getLastAccessedAt()).isEqualTo(clock.instant());
		assertThat(bindingRepository.findByShortCode(SHORT_CODE)).isEmpty();

		mockMvc.perform(get("/" + SHORT_CODE))
				.andExpect(status().isNotFound())
				.andExpect(header().doesNotExist("Location"));

		mockMvc.perform(get("/fresh-vanity"))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", LONG_URL));
	}

	@Test
	void assignRecentAccess_leavesLastAccessedAtWhenCreatedAtIsOutsidePeriod() throws Exception {
		Instant createdAt = clock.instant().minus(inactivity);
		Instant lastAccessedAt = clock.instant().minus(inactivity).plusSeconds(1);
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL, createdAt, lastAccessedAt);

		mockMvc.perform(assignJson(LONG_URL, "still-live"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tinyUrl").value(tinyUrl("still-live")))
				.andExpect(jsonPath("$.originalUrl").value(LONG_URL));

		assertThat(bindingRepository.findByShortCode(SHORT_CODE)).isEmpty();
		Binding updated = bindingRepository.findByShortCode("still-live").orElseThrow();
		assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
		assertThat(updated.getLastAccessedAt()).isEqualTo(lastAccessedAt);

		mockMvc.perform(get("/still-live"))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", LONG_URL));
	}

	@Test
	void assignNeverAccessedExpired_setsLastAccessedAtAndResolves() throws Exception {
		Instant createdAt = clock.instant().minus(inactivity).minusSeconds(1);
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL, createdAt, null);

		mockMvc.perform(assignJson(LONG_URL, "revived"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tinyUrl").value(tinyUrl("revived")))
				.andExpect(jsonPath("$.originalUrl").value(LONG_URL));

		Binding updated = bindingRepository.findByShortCode("revived").orElseThrow();
		assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
		assertThat(updated.getLastAccessedAt()).isEqualTo(clock.instant());
		assertThat(updated.getOriginalUrl()).isEqualTo(LONG_URL);
		assertThat(bindingRepository.findByShortCode(SHORT_CODE)).isEmpty();

		mockMvc.perform(get("/revived"))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", LONG_URL));
	}

	@Test
	void assignTakenVanity_returns409AndLeavesShortCode() throws Exception {
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL, clock.instant(), null);
		persistBinding("taken", "https://orders.example.com/other", "https://orders.example.com/other", clock.instant(), null);

		mockMvc.perform(assignJson(LONG_URL, "taken"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409));

		assertThat(bindingRepository.count()).isEqualTo(2);
		assertThat(bindingRepository.findByShortCode(SHORT_CODE).orElseThrow().getOriginalUrl()).isEqualTo(LONG_URL);
		assertThat(bindingRepository.findByShortCode("taken").orElseThrow().getOriginalUrl())
				.isEqualTo("https://orders.example.com/other");
	}

	@Test
	void assignUnknownUrl_returns404AndLeavesStore() throws Exception {
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL, clock.instant(), null);

		mockMvc.perform(assignJson("https://orders.example.com/missing", "free-code"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404));

		mockMvc.perform(assignJson(tinyUrl("missing-code"), "free-code"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404));

		assertThat(bindingRepository.count()).isEqualTo(1);
		assertThat(bindingRepository.findByShortCode(SHORT_CODE)).isPresent();
		assertThat(bindingRepository.findByShortCode("free-code")).isEmpty();
	}

	@Test
	void assignSameCode_changesNoFields() throws Exception {
		Instant anchor = clock.instant().minus(inactivity);
		Binding seeded = persistBinding(SHORT_CODE, LONG_URL, LONG_URL, anchor, anchor);

		mockMvc.perform(assignJson(LONG_URL, SHORT_CODE))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tinyUrl").value(tinyUrl(SHORT_CODE)))
				.andExpect(jsonPath("$.originalUrl").value(LONG_URL));

		assertThat(bindingRepository.count()).isEqualTo(1);
		Binding stored = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow();
		assertThat(stored.getId()).isEqualTo(seeded.getId());
		assertThat(stored.getOriginalUrl()).isEqualTo(LONG_URL);
		assertThat(stored.getSanitizedUrl()).isEqualTo(LONG_URL);
		assertThat(stored.getCreatedAt()).isEqualTo(anchor);
		assertThat(stored.getLastAccessedAt()).isEqualTo(anchor);

		mockMvc.perform(get("/" + SHORT_CODE))
				.andExpect(status().isGone());
	}

	@Test
	void assignInvalidVanity_returns400AndLeavesStore() throws Exception {
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL, clock.instant(), null);

		mockMvc.perform(assignJson(LONG_URL, "api"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400));

		assertThat(bindingRepository.count()).isEqualTo(1);
		assertThat(bindingRepository.findByShortCode(SHORT_CODE)).isPresent();
	}

	@Test
	void assignWithoutAuth_returns401AndLeavesStore() throws Exception {
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL, clock.instant(), null);

		mockMvc.perform(post("/api/v1/urls/vanity")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"url":"%s","vanity":"free-code"}
								""".formatted(LONG_URL)))
				.andExpect(status().isUnauthorized());

		assertThat(bindingRepository.count()).isEqualTo(1);
		assertThat(bindingRepository.findByShortCode(SHORT_CODE)).isPresent();
		assertThat(bindingRepository.findByShortCode("free-code")).isEmpty();
	}

	@Test
	void assignLongUrlUnderPublicBase_withSlash_replacesShortCode() throws Exception {
		String originalUrl = tinyUrl("docs/guide");
		persistBinding(SHORT_CODE, originalUrl, urlSanitizer.sanitize(originalUrl), clock.instant(), null);

		mockMvc.perform(assignJson(originalUrl, "guide-vanity"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tinyUrl").value(tinyUrl("guide-vanity")))
				.andExpect(jsonPath("$.originalUrl").value(originalUrl));

		assertThat(bindingRepository.findByShortCode(SHORT_CODE)).isEmpty();
		assertThat(bindingRepository.findByShortCode("guide-vanity")).isPresent();
	}

	@Test
	void expiredCreate_withOwnVanity_returnsSameTinyUrlAndSetsLastAccessedAt() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
			String liveUrl = server.url("/checkout?orderId=8&token=own").toString();
			Instant expiredAt = clock.instant().minus(inactivity);
			persistBinding(SHORT_CODE, liveUrl, urlSanitizer.sanitize(liveUrl), expiredAt, expiredAt);

			MvcResult createResult = mockMvc.perform(post("/api/v1/urls")
							.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"url":"%s","vanity":"%s"}
									""".formatted(liveUrl, SHORT_CODE)))
					.andExpect(status().isOk())
					.andReturn();

			assertThat(readTinyUrl(createResult)).endsWith("/" + SHORT_CODE);
			assertThat(bindingRepository.count()).isEqualTo(1);
			Binding reactivated = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow();
			assertThat(reactivated.getLastAccessedAt()).isEqualTo(clock.instant());
			assertThat(reactivated.getCreatedAt()).isEqualTo(expiredAt);
		}
	}

	@Test
	void expiredCreate_withDifferentVanity_keepsShortCodeAndReactivates() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
			String liveUrl = server.url("/checkout?orderId=42&token=abc").toString();
			Instant expiredAt = clock.instant().minus(inactivity);
			persistBinding(SHORT_CODE, liveUrl, urlSanitizer.sanitize(liveUrl), expiredAt, expiredAt);

			MvcResult createResult = mockMvc.perform(post("/api/v1/urls")
							.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"url":"%s","vanity":"new-vanity"}
									""".formatted(liveUrl)))
					.andExpect(status().isOk())
					.andReturn();

			assertThat(readTinyUrl(createResult)).endsWith("/" + SHORT_CODE);
			assertThat(bindingRepository.count()).isEqualTo(1);
			assertThat(bindingRepository.findByShortCode("new-vanity")).isEmpty();

			Binding reactivated = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow();
			assertThat(reactivated.getCreatedAt()).isEqualTo(expiredAt);
			assertThat(reactivated.getLastAccessedAt()).isEqualTo(clock.instant());
			assertThat(reactivated.getOriginalUrl()).isEqualTo(liveUrl);

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

	private org.springframework.test.web.servlet.RequestBuilder assignJson(String url, String vanity) {
		return post("/api/v1/urls/vanity")
				.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"url":"%s","vanity":"%s"}
						""".formatted(url, vanity));
	}

	private String tinyUrl(String shortCode) {
		String base = publicBaseUrl.endsWith("/") ? publicBaseUrl : publicBaseUrl + "/";
		return base + shortCode;
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
