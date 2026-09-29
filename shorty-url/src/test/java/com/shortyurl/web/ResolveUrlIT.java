package com.shortyurl.web;

import com.jayway.jsonpath.JsonPath;
import com.shortyurl.persistence.Binding;
import com.shortyurl.persistence.BindingRepository;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResolveUrlIT {

	private static final String LONG_URL = "https://orders.example.com/checkout?orderId=42&token=abc";
	private static final String SHORT_CODE = "abc123XY";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BindingRepository bindingRepository;

	@BeforeEach
	void cleanStore() {
		bindingRepository.deleteAll();
	}

	@Test
	void happyResolve_redirectsToOriginalUrlAndSetsLastAccessedAt() throws Exception {
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL);

		Instant before = Instant.now().minusSeconds(1);
		mockMvc.perform(get("/" + SHORT_CODE))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", LONG_URL));

		Binding updated = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow();
		assertThat(updated.getLastAccessedAt()).isNotNull();
		assertThat(updated.getLastAccessedAt()).isAfter(before);
	}

	@Test
	void resolve_usesOriginalUrlNotSanitizedUrl() throws Exception {
		String originalUrl = "https://orders.example.com/checkout?orderId=42&raw=1";
		String sanitizedUrl = "https://orders.example.com/checkout?orderId=42";
		persistBinding(SHORT_CODE, originalUrl, sanitizedUrl);

		mockMvc.perform(get("/" + SHORT_CODE))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", originalUrl));
	}

	@Test
	void missingCode_returns404WithNoRedirect() throws Exception {
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL);

		mockMvc.perform(get("/unknown99"))
				.andExpect(status().isNotFound())
				.andExpect(header().doesNotExist("Location"));

		assertThat(bindingRepository.count()).isEqualTo(1);
		Binding seeded = bindingRepository.findByShortCode(SHORT_CODE).orElseThrow();
		assertThat(seeded.getLastAccessedAt()).isNull();
	}

	@Test
	void resolveWithoutAuth_doesNotReturn401() throws Exception {
		persistBinding(SHORT_CODE, LONG_URL, LONG_URL);

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
		assertThat(resolved.getLastAccessedAt()).isNotNull();
	}

	private void persistBinding(String shortCode, String originalUrl, String sanitizedUrl) {
		Binding binding = new Binding();
		binding.setShortCode(shortCode);
		binding.setOriginalUrl(originalUrl);
		binding.setSanitizedUrl(sanitizedUrl);
		bindingRepository.saveAndFlush(binding);
	}

	private static String readTinyUrl(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.tinyUrl");
	}
}
