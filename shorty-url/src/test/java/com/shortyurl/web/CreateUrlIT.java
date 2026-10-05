package com.shortyurl.web;

import com.shortyurl.persistence.Binding;
import com.shortyurl.persistence.BindingRepository;
import com.shortyurl.support.StallingBodyServer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CreateUrlIT {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BindingRepository bindingRepository;

	private MockWebServer server;

	private String longUrl;

	private String otherUrl;

	@BeforeEach
	void setUp() throws Exception {
		bindingRepository.deleteAll();
		server = new MockWebServer();
		server.start();
		longUrl = server.url("/checkout?orderId=42&token=abc").toString();
		otherUrl = server.url("/checkout?orderId=99&token=xyz").toString();
	}

	@AfterEach
	void tearDown() throws Exception {
		server.shutdown();
	}

	@Test
	void happyCreate_returnsTinyAndOriginalAndPersistsBinding() throws Exception {
		enqueue(200);
		mockMvc.perform(postJson(longUrl))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.tinyUrl").value(containsString("http://localhost:8080/")))
				.andExpect(jsonPath("$.originalUrl").value(longUrl));

		assertThat(bindingRepository.count()).isEqualTo(1);
		assertThat(bindingRepository.findAll().getFirst().getOriginalUrl()).isEqualTo(longUrl);
		assertThat(server.getRequestCount()).isEqualTo(1);
		assertThat(server.takeRequest().getMethod()).isEqualTo("GET");
	}

	@Test
	void redirect_succeedsOnceAndStoresPaste() throws Exception {
		String pasted = server.url("/pasted").toString();
		server.enqueue(new MockResponse()
				.setResponseCode(302)
				.addHeader("Location", server.url("/elsewhere").toString())
				.setBody("go"));

		mockMvc.perform(postJson(pasted))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.originalUrl").value(pasted));

		assertThat(bindingRepository.count()).isEqualTo(1);
		assertThat(bindingRepository.findAll().getFirst().getOriginalUrl()).isEqualTo(pasted);
		assertThat(server.getRequestCount()).isEqualTo(1);
		assertThat(server.takeRequest().getPath()).isEqualTo("/pasted");
	}

	@Test
	@Timeout(value = 3, unit = TimeUnit.SECONDS)
	void largeBody_stopsAtCapAndCreateSucceeds() throws Exception {
		try (StallingBodyServer stalling = new StallingBodyServer()) {
			stalling.enqueue(200, body(128), true);

			mockMvc.perform(postJson(stalling.url("/big")))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.originalUrl").value(stalling.url("/big")));

			assertThat(stalling.requestCount()).isEqualTo(1);
			assertThat(bindingRepository.count()).isEqualTo(1);
		}
	}

	@Test
	@Timeout(value = 3, unit = TimeUnit.SECONDS)
	void redirectBodyOverCap_createSucceedsWithoutFollowing() throws Exception {
		try (StallingBodyServer stalling = new StallingBodyServer()) {
			String pasted = stalling.url("/pasted");
			stalling.enqueue(302, body(128), true, stalling.url("/secret"));

			mockMvc.perform(postJson(pasted))
					.andExpect(status().isCreated())
					.andExpect(jsonPath("$.originalUrl").value(pasted));

			assertThat(stalling.requestCount()).isEqualTo(1);
			assertThat(stalling.requests().getFirst().target()).isEqualTo("/pasted");
			assertThat(bindingRepository.count()).isEqualTo(1);
		}
	}

	@Test
	void destination404_returns502OnceAndStoresNothing() throws Exception {
		enqueue(404);

		mockMvc.perform(postJson(longUrl))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.status").value(502));

		assertThat(bindingRepository.count()).isZero();
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void other4xx_returns502OnceAndStoresNothing() throws Exception {
		enqueue(403);

		mockMvc.perform(postJson(longUrl))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.status").value(502));

		assertThat(bindingRepository.count()).isZero();
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void transient5xxThen2xx_storesBinding() throws Exception {
		enqueue(500);
		enqueue(200);

		mockMvc.perform(postJson(longUrl))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.originalUrl").value(longUrl));

		assertThat(bindingRepository.count()).isEqualTo(1);
		assertThat(server.getRequestCount()).isEqualTo(2);
	}

	@Test
	void exhausted5xx_returns502AndStoresNothing() throws Exception {
		enqueue(500);
		enqueue(502);
		enqueue(503);

		mockMvc.perform(postJson(longUrl))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.status").value(502));

		assertThat(bindingRepository.count()).isZero();
		assertThat(server.getRequestCount()).isEqualTo(3);
	}

	@Test
	@Timeout(value = 8, unit = TimeUnit.SECONDS)
	void exhaustedTimeout_returns502AndStoresNothing() throws Exception {
		try (StallingBodyServer stalling = new StallingBodyServer()) {
			stalling.enqueue(200, new byte[0], true);
			stalling.enqueue(200, new byte[0], true);
			stalling.enqueue(200, new byte[0], true);
			Instant start = Instant.now();

			mockMvc.perform(postJson(stalling.url("/stall")))
					.andExpect(status().isBadGateway())
					.andExpect(jsonPath("$.status").value(502));

			assertThat(Duration.between(start, Instant.now())).isBetween(Duration.ofMillis(1000), Duration.ofMillis(4000));
			assertThat(stalling.requestCount()).isEqualTo(3);
			assertThat(bindingRepository.count()).isZero();
		}
	}

	@Test
	void trailingSlash_reusesTheExistingBinding() throws Exception {
		String bare = server.url("/checkout").toString();
		String slashed = server.url("/checkout/").toString();
		enqueue(200);
		enqueue(200);

		MvcResult first = mockMvc.perform(postJson(bare))
				.andExpect(status().isCreated())
				.andReturn();
		String tinyUrl = readTinyUrl(first);

		mockMvc.perform(postJson(slashed))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tinyUrl").value(tinyUrl))
				.andExpect(jsonPath("$.originalUrl").value(bare));

		assertThat(bindingRepository.count()).isEqualTo(1);
		assertThat(bindingRepository.findAll().getFirst().getOriginalUrl()).isEqualTo(bare);
		assertThat(server.getRequestCount()).isEqualTo(2);
	}

	@Test
	void idempotentCreate_returnsSameTinyUrlWithoutSecondBinding() throws Exception {
		enqueue(200);
		enqueue(200);
		MvcResult first = mockMvc.perform(postJson(longUrl))
				.andExpect(status().isCreated())
				.andReturn();

		String firstTinyUrl = readTinyUrl(first);
		Binding stored = bindingRepository.findAll().getFirst();
		UUID id = stored.getId();
		Instant createdAt = stored.getCreatedAt();
		Instant lastAccessedAt = stored.getLastAccessedAt();
		String shortCode = stored.getShortCode();

		mockMvc.perform(postJson(longUrl))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tinyUrl").value(firstTinyUrl))
				.andExpect(jsonPath("$.originalUrl").value(longUrl));

		assertThat(bindingRepository.count()).isEqualTo(1);
		Binding again = bindingRepository.findById(id).orElseThrow();
		assertThat(again.getCreatedAt()).isEqualTo(createdAt);
		assertThat(again.getLastAccessedAt()).isEqualTo(lastAccessedAt);
		assertThat(again.getShortCode()).isEqualTo(shortCode);
		assertThat(server.getRequestCount()).isEqualTo(2);
	}

	@Test
	void deadDestinationAfterLive_doesNotReturnExistingTinyUrl() throws Exception {
		enqueue(200);
		enqueue(404);
		mockMvc.perform(postJson(longUrl))
				.andExpect(status().isCreated());

		mockMvc.perform(postJson(longUrl))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.status").value(502));

		assertThat(bindingRepository.count()).isEqualTo(1);
		assertThat(server.getRequestCount()).isEqualTo(2);
	}

	@Test
	void differentUrls_receiveDistinctTinyUrlsWithoutEmbeddedDestination() throws Exception {
		enqueue(200);
		enqueue(200);
		MvcResult first = mockMvc.perform(postJson(longUrl))
				.andExpect(status().isCreated())
				.andReturn();

		MvcResult second = mockMvc.perform(postJson(otherUrl))
				.andExpect(status().isCreated())
				.andReturn();

		String firstTiny = readTinyUrl(first);
		String secondTiny = readTinyUrl(second);

		assertThat(firstTiny).isNotEqualTo(secondTiny);
		assertThat(firstTiny).doesNotContain("orderId");
		assertThat(secondTiny).doesNotContain("orderId");
		assertThat(firstTiny).doesNotContain(URI.create(longUrl).getHost());
		assertThat(secondTiny).doesNotContain(URI.create(otherUrl).getHost());
		assertThat(bindingRepository.count()).isEqualTo(2);
	}

	@Test
	void missingAuth_returns401AndStoresNothing() throws Exception {
		mockMvc.perform(post("/api/v1/urls")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(longUrl)))
				.andExpect(status().isUnauthorized());

		assertThat(bindingRepository.count()).isZero();
		assertThat(server.getRequestCount()).isZero();
	}

	@Test
	void invalidAuth_returns401AndStoresNothing() throws Exception {
		mockMvc.perform(post("/api/v1/urls")
						.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "wrong"))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(longUrl)))
				.andExpect(status().isUnauthorized());

		assertThat(bindingRepository.count()).isZero();
		assertThat(server.getRequestCount()).isZero();
	}

	@Test
	void blankUrl_returns400AndStoresNothing() throws Exception {
		mockMvc.perform(post("/api/v1/urls")
						.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"url":"   "}
								"""))
				.andExpect(status().isBadRequest());

		assertThat(bindingRepository.count()).isZero();
		assertThat(server.getRequestCount()).isZero();
	}

	@Test
	void missingUrl_returns400AndStoresNothing() throws Exception {
		mockMvc.perform(post("/api/v1/urls")
						.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{}
								"""))
				.andExpect(status().isBadRequest());

		assertThat(bindingRepository.count()).isZero();
	}

	@Test
	void invalidSchemeUrl_returns400AndStoresNothing() throws Exception {
		mockMvc.perform(post("/api/v1/urls")
						.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"url":"ftp://files.example.com/a"}
								"""))
				.andExpect(status().isBadRequest());

		assertThat(bindingRepository.count()).isZero();
		assertThat(server.getRequestCount()).isZero();
	}

	@Test
	void sanitizedUrlLongerThanColumn_returns400AndStoresNothing() throws Exception {
		String raw = "http://example.com/" + "ä".repeat(400);

		mockMvc.perform(postJson(raw))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("url is too long"));

		assertThat(bindingRepository.count()).isZero();
		assertThat(server.getRequestCount()).isZero();
	}

	@Test
	void messyEquivalentUrl_isIdempotentAfterSanitize() throws Exception {
		int port = server.getPort();
		String pasted = "http://LOCALHOST:" + port + "/path#frag";
		String equivalent = "  http://localhost:" + port + "/path  ";
		enqueue(200);
		enqueue(200);

		MvcResult first = mockMvc.perform(postJson(pasted))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.originalUrl").value(pasted))
				.andReturn();

		mockMvc.perform(postJson(equivalent))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tinyUrl").value(readTinyUrl(first)))
				.andExpect(jsonPath("$.tinyUrl").value(not(containsString("#frag"))))
				.andExpect(jsonPath("$.originalUrl").value(pasted));

		assertThat(bindingRepository.count()).isEqualTo(1);
		Binding binding = bindingRepository.findAll().getFirst();
		assertThat(binding.getOriginalUrl()).isEqualTo(pasted);
		assertThat(binding.getSanitizedUrl()).isEqualTo("http://localhost:" + port + "/path");
		assertThat(server.getRequestCount()).isEqualTo(2);
		RecordedRequest firstProbe = server.takeRequest();
		RecordedRequest secondProbe = server.takeRequest();
		assertThat(firstProbe.getMethod()).isEqualTo("GET");
		assertThat(secondProbe.getMethod()).isEqualTo("GET");
		assertThat(firstProbe.getPath()).isEqualTo("/path");
		assertThat(firstProbe.getHeader("Host")).startsWith("LOCALHOST:");
	}

	@Test
	void probesPastedPathRatherThanSanitizedKey() throws Exception {
		int port = server.getPort();
		String pasted = "http://127.0.0.1:" + port + "/a/../Live#Section";
		enqueue(200);

		mockMvc.perform(postJson(pasted))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.originalUrl").value(pasted));

		Binding binding = bindingRepository.findAll().getFirst();
		assertThat(binding.getOriginalUrl()).isEqualTo(pasted);
		assertThat(binding.getSanitizedUrl()).isEqualTo("http://127.0.0.1:" + port + "/Live");
		RecordedRequest request = server.takeRequest();
		assertThat(request.getMethod()).isEqualTo("GET");
		assertThat(request.getPath()).isEqualTo("/a/../Live");
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@Test
	void omitVanity_returnsOpaqueShortCode() throws Exception {
		enqueue(200);

		MvcResult result = mockMvc.perform(postJson(longUrl))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.originalUrl").value(longUrl))
				.andReturn();

		String tinyUrl = readTinyUrl(result);
		assertThat(shortCodeOf(tinyUrl)).matches("[A-Za-z0-9_-]{10}");
		assertThat(tinyUrl).doesNotContain("orderId");
		assertThat(tinyUrl).doesNotContain(URI.create(longUrl).getHost());
	}

	@Test
	void blankVanity_returnsOpaqueShortCode() throws Exception {
		enqueue(200);

		MvcResult result = mockMvc.perform(postVanity(longUrl, "   "))
				.andExpect(status().isCreated())
				.andReturn();

		assertThat(shortCodeOf(readTinyUrl(result))).matches("[A-Za-z0-9_-]{10}");
	}

	@Test
	void vanityCreate_storesVanityAndResolves() throws Exception {
		enqueue(200);
		String vanity = "my_sale-1";

		MvcResult result = mockMvc.perform(postVanity(longUrl, vanity))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.originalUrl").value(longUrl))
				.andReturn();

		assertThat(readTinyUrl(result)).isEqualTo("http://localhost:8080/" + vanity);
		assertThat(bindingRepository.count()).isEqualTo(1);
		assertThat(server.getRequestCount()).isEqualTo(1);
		assertThat(bindingRepository.findByShortCode(vanity).orElseThrow().getOriginalUrl()).isEqualTo(longUrl);

		mockMvc.perform(get("/" + vanity))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", longUrl));
	}

	@Test
	void vanityDifferentCaseFromReservedWord_isStored() throws Exception {
		enqueue(200);

		mockMvc.perform(postVanity(longUrl, "API"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.tinyUrl").value("http://localhost:8080/API"));

		assertThat(bindingRepository.findByShortCode("API")).isPresent();
		assertThat(bindingRepository.findByShortCode("api")).isEmpty();
	}

	@Test
	void vanityAtMaxLength_isStoredAndResolves() throws Exception {
		enqueue(200);
		String vanity = "a".repeat(64);

		MvcResult result = mockMvc.perform(postVanity(longUrl, vanity))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.originalUrl").value(longUrl))
				.andReturn();

		assertThat(readTinyUrl(result)).endsWith("/" + vanity);
		mockMvc.perform(get("/" + vanity))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", longUrl));
	}

	@Test
	void vanityTaken_returns409AndDoesNotProbe() throws Exception {
		seedBinding("taken-code", otherUrl);

		mockMvc.perform(postVanity(longUrl, "taken-code"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.title").value("Conflict"));

		assertThat(bindingRepository.count()).isEqualTo(1);
		assertThat(bindingRepository.findByShortCode("taken-code").orElseThrow().getOriginalUrl()).isEqualTo(otherUrl);
		assertThat(server.getRequestCount()).isZero();
	}

	@Test
	void existingLongUrl_doesNotApplyVanity() throws Exception {
		enqueue(200);
		enqueue(200);
		MvcResult first = mockMvc.perform(postJson(longUrl))
				.andExpect(status().isCreated())
				.andReturn();

		String tinyUrl = readTinyUrl(first);
		String shortCode = shortCodeOf(tinyUrl);
		Binding stored = bindingRepository.findByShortCode(shortCode).orElseThrow();
		Instant createdAt = stored.getCreatedAt();

		mockMvc.perform(postVanity(longUrl, "my-vanity"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tinyUrl").value(tinyUrl))
				.andExpect(jsonPath("$.originalUrl").value(longUrl));

		assertThat(bindingRepository.count()).isEqualTo(1);
		Binding again = bindingRepository.findByShortCode(shortCode).orElseThrow();
		assertThat(again.getShortCode()).isEqualTo(shortCode);
		assertThat(again.getCreatedAt()).isEqualTo(createdAt);
		assertThat(again.getLastAccessedAt()).isNull();
		assertThat(bindingRepository.findByShortCode("my-vanity")).isEmpty();
		assertThat(server.getRequestCount()).isEqualTo(2);
	}

	@Test
	void existingLongUrl_withTakenVanity_returnsExistingTinyUrl() throws Exception {
		seedBinding("other-vanity", otherUrl);
		enqueue(200);
		enqueue(200);
		MvcResult first = mockMvc.perform(postJson(longUrl))
				.andExpect(status().isCreated())
				.andReturn();
		String tinyUrl = readTinyUrl(first);

		mockMvc.perform(postVanity(longUrl, "other-vanity"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tinyUrl").value(tinyUrl));

		assertThat(bindingRepository.count()).isEqualTo(2);
		assertThat(bindingRepository.findByShortCode(shortCodeOf(tinyUrl))).isPresent();
		Binding taken = bindingRepository.findByShortCode("other-vanity").orElseThrow();
		assertThat(taken.getOriginalUrl()).isEqualTo(otherUrl);
	}

	@Test
	void existingLongUrl_withItsOwnVanity_returnsSameTinyUrl() throws Exception {
		enqueue(200);
		enqueue(200);
		MvcResult first = mockMvc.perform(postVanity(longUrl, "my_sale-1"))
				.andExpect(status().isCreated())
				.andReturn();
		String tinyUrl = readTinyUrl(first);

		mockMvc.perform(postVanity(longUrl, "my_sale-1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tinyUrl").value(tinyUrl));

		assertThat(bindingRepository.count()).isEqualTo(1);
		assertThat(bindingRepository.findByShortCode("my_sale-1")).isPresent();
	}

	@Test
	void vanityCreate_destination404_returns502AndStoresNothing() throws Exception {
		enqueue(404);

		mockMvc.perform(postVanity(longUrl, "my_sale-1"))
				.andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.status").value(502));

		assertThat(bindingRepository.count()).isZero();
		assertThat(bindingRepository.findByShortCode("my_sale-1")).isEmpty();
		assertThat(server.getRequestCount()).isEqualTo(1);
	}

	@ParameterizedTest
	@MethodSource("invalidVanities")
	void badVanity_returns400AndDoesNotProbe(String vanity) throws Exception {
		enqueue(200);

		mockMvc.perform(postVanity(longUrl, vanity))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.title").value("Bad Request"));

		assertThat(bindingRepository.count()).isZero();
		assertThat(server.getRequestCount()).isZero();
	}

	@Test
	void vanityCreateWithoutAuth_returns401AndStoresNothing() throws Exception {
		mockMvc.perform(post("/api/v1/urls")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"url":"%s","vanity":"open-vanity"}
								""".formatted(longUrl)))
				.andExpect(status().isUnauthorized());

		assertThat(bindingRepository.count()).isZero();
		assertThat(bindingRepository.findByShortCode("open-vanity")).isEmpty();
		assertThat(server.getRequestCount()).isZero();
	}

	private static Stream<String> invalidVanities() {
		return Stream.of("api", "error", "bad/code", "a".repeat(65));
	}

	private void seedBinding(String shortCode, String url) {
		Binding binding = new Binding();
		binding.setShortCode(shortCode);
		binding.setOriginalUrl(url);
		binding.setSanitizedUrl(url);
		binding.setCreatedAt(Instant.now());
		bindingRepository.saveAndFlush(binding);
	}

	private void enqueue(int status) {
		server.enqueue(new MockResponse().setResponseCode(status).setBody("ok"));
	}

	private org.springframework.test.web.servlet.RequestBuilder postJson(String url) {
		return post("/api/v1/urls")
				.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body(url));
	}

	private static String body(String url) {
		return """
				{"url":"%s"}
				""".formatted(url);
	}

	private org.springframework.test.web.servlet.RequestBuilder postVanity(String url, String vanity) {
		return post("/api/v1/urls")
				.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"url":"%s","vanity":"%s"}
						""".formatted(url, vanity));
	}

	private static byte[] body(int size) {
		byte[] bytes = new byte[size];
		Arrays.fill(bytes, (byte) 'x');
		return bytes;
	}

	private static String readTinyUrl(MvcResult result) throws Exception {
		String body = result.getResponse().getContentAsString();
		int start = body.indexOf("\"tinyUrl\":\"") + "\"tinyUrl\":\"".length();
		int end = body.indexOf('"', start);
		return body.substring(start, end);
	}

	private static String shortCodeOf(String tinyUrl) {
		return tinyUrl.substring(tinyUrl.lastIndexOf('/') + 1);
	}
}
