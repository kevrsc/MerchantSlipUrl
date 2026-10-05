package com.shortyurl.web;

import com.shortyurl.persistence.Binding;
import com.shortyurl.persistence.BindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ShortyApiGapsIT {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BindingRepository bindingRepository;

	@BeforeEach
	void cleanStore() {
		bindingRepository.deleteAll();
	}

	@Test
	void missingAuth_returnsProblemJsonWithoutWwwAuthenticate() throws Exception {
		mockMvc.perform(post("/api/v1/urls")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"url":"https://orders.example.com/checkout"}
								"""))
				.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist("WWW-Authenticate"))
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.title").value("Unauthorized"))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.detail").value("Authentication required"));

		assertThat(bindingRepository.count()).isZero();
	}

	@Test
	void assignBlankUrl_returns400AndStoresNothing() throws Exception {
		mockMvc.perform(assign("""
						{"url":"   ","vanity":"spring-sale"}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Bad Request"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors[0]").value("url: url must not be blank"));

		assertThat(bindingRepository.count()).isZero();
		assertThat(bindingRepository.findByShortCode("spring-sale")).isEmpty();
	}

	@Test
	void assignBlankVanity_returns400AndStoresNothing() throws Exception {
		seed("kept-code");

		mockMvc.perform(assign("""
						{"url":"https://orders.example.com/checkout","vanity":"   "}
						"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Bad Request"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errors[0]").value("vanity: vanity must not be blank"));

		assertThat(bindingRepository.count()).isEqualTo(1);
		assertThat(bindingRepository.findByShortCode("kept-code")).isPresent();
	}

	@Test
	void malformedCreateJson_returns400AndStoresNothing() throws Exception {
		mockMvc.perform(post("/api/v1/urls")
						.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{"))
				.andExpect(status().isBadRequest());

		assertThat(bindingRepository.count()).isZero();
	}

	@Test
	void reservedApiAndErrorPaths_areNotShortCodeRedirects() throws Exception {
		seed("kept-code");

		mockMvc.perform(get("/api"))
				.andExpect(status().isNotFound())
				.andExpect(header().doesNotExist("Location"));

		mockMvc.perform(get("/error"))
				.andExpect(header().doesNotExist("Location"))
				.andExpect(result -> assertThat(result.getResponse().getStatus()).isNotIn(301, 302, 303, 307, 308));

		Binding stored = bindingRepository.findByShortCode("kept-code").orElseThrow();
		assertThat(stored.getLastAccessedAt()).isNull();
		assertThat(bindingRepository.count()).isEqualTo(1);
	}

	private org.springframework.test.web.servlet.RequestBuilder assign(String json) {
		return post("/api/v1/urls/vanity")
				.with(SecurityMockMvcRequestPostProcessors.httpBasic("merchant", "merchant"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(json);
	}

	private void seed(String shortCode) {
		Binding binding = new Binding();
		binding.setShortCode(shortCode);
		binding.setOriginalUrl("https://orders.example.com/checkout");
		binding.setSanitizedUrl("https://orders.example.com/checkout");
		binding.setCreatedAt(Instant.parse("2026-10-05T16:00:00Z"));
		bindingRepository.saveAndFlush(binding);
	}
}
