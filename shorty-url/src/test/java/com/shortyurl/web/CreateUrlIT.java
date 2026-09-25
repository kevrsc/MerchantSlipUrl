package com.shortyurl.web;

import com.shortyurl.persistence.BindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CreateUrlIT {

	private static final String LONG_URL = "https://orders.example.com/checkout?orderId=42&token=abc";
	private static final String OTHER_URL = "https://orders.example.com/checkout?orderId=99&token=xyz";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BindingRepository bindingRepository;

	@BeforeEach
	void cleanStore() {
		bindingRepository.deleteAll();
	}

	@Test
	void happyCreate_returnsTinyAndOriginalAndPersistsBinding() throws Exception {
		mockMvc.perform(post("/api/v1/urls")
						.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"url":"%s"}
								""".formatted(LONG_URL)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.tinyUrl").value(containsString("http://localhost:8080/")))
				.andExpect(jsonPath("$.originalUrl").value(LONG_URL));

		assertThat(bindingRepository.count()).isEqualTo(1);
		assertThat(bindingRepository.findAll().getFirst().getOriginalUrl()).isEqualTo(LONG_URL);
	}

	@Test
	void idempotentCreate_returnsSameTinyUrlWithoutSecondBinding() throws Exception {
		MvcResult first = mockMvc.perform(post("/api/v1/urls")
						.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"url":"%s"}
								""".formatted(LONG_URL)))
				.andExpect(status().isCreated())
				.andReturn();

		String firstTinyUrl = readTinyUrl(first);

		mockMvc.perform(post("/api/v1/urls")
						.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"url":"%s"}
								""".formatted(LONG_URL)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tinyUrl").value(firstTinyUrl))
				.andExpect(jsonPath("$.originalUrl").value(LONG_URL));

		assertThat(bindingRepository.count()).isEqualTo(1);
	}

	@Test
	void differentUrls_receiveDistinctTinyUrlsWithoutEmbeddedDestination() throws Exception {
		MvcResult first = mockMvc.perform(post("/api/v1/urls")
						.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"url":"%s"}
								""".formatted(LONG_URL)))
				.andExpect(status().isCreated())
				.andReturn();

		MvcResult second = mockMvc.perform(post("/api/v1/urls")
						.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"url":"%s"}
								""".formatted(OTHER_URL)))
				.andExpect(status().isCreated())
				.andReturn();

		String firstTiny = readTinyUrl(first);
		String secondTiny = readTinyUrl(second);

		assertThat(firstTiny).isNotEqualTo(secondTiny);
		assertThat(firstTiny).doesNotContain("orderId");
		assertThat(secondTiny).doesNotContain("orderId");
		assertThat(firstTiny).doesNotContain("orders.example.com");
		assertThat(bindingRepository.count()).isEqualTo(2);
	}

	@Test
	void missingAuth_returns401AndStoresNothing() throws Exception {
		mockMvc.perform(post("/api/v1/urls")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"url":"%s"}
								""".formatted(LONG_URL)))
				.andExpect(status().isUnauthorized());

		assertThat(bindingRepository.count()).isZero();
	}

	@Test
	void invalidAuth_returns401AndStoresNothing() throws Exception {
		mockMvc.perform(post("/api/v1/urls")
						.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "wrong"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"url":"%s"}
								""".formatted(LONG_URL)))
				.andExpect(status().isUnauthorized());

		assertThat(bindingRepository.count()).isZero();
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
	}

	@Test
	void messyEquivalentUrl_isIdempotentAfterSanitize() throws Exception {
		MvcResult first = mockMvc.perform(post("/api/v1/urls")
						.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"url":"https://Orders.Example.com/path#frag"}
								"""))
				.andExpect(status().isCreated())
				.andReturn();

		mockMvc.perform(post("/api/v1/urls")
						.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"url":"  https://orders.example.com/path  "}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tinyUrl").value(readTinyUrl(first)))
				.andExpect(jsonPath("$.tinyUrl").value(not(containsString("#frag"))));

		assertThat(bindingRepository.count()).isEqualTo(1);
	}

	private static String readTinyUrl(MvcResult result) throws Exception {
		String body = result.getResponse().getContentAsString();
		int start = body.indexOf("\"tinyUrl\":\"") + "\"tinyUrl\":\"".length();
		int end = body.indexOf('"', start);
		return body.substring(start, end);
	}
}
