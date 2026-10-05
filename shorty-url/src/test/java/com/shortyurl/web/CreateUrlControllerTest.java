package com.shortyurl.web;

import com.shortyurl.persistence.Binding;
import com.shortyurl.service.CreateUrlResult;
import com.shortyurl.service.UrlAssignService;
import com.shortyurl.service.UrlCreateService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreateUrlControllerTest {

	@Test
	void publicBaseWithoutTrailingSlash_stillJoinsTheShortCode() {
		UrlCreateService urlCreateService = mock(UrlCreateService.class);
		CreateUrlController controller = new CreateUrlController(
				urlCreateService,
				mock(UrlAssignService.class),
				"http://localhost:8080");
		Binding binding = new Binding();
		binding.setShortCode("abc");
		binding.setOriginalUrl("https://example.com/orders/42");
		when(urlCreateService.create("https://example.com/orders/42", null))
				.thenReturn(new CreateUrlResult(binding, true));

		var response = controller.create(new CreateUrlRequest("https://example.com/orders/42", null));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody().tinyUrl()).isEqualTo("http://localhost:8080/abc");
		assertThat(response.getBody().originalUrl()).isEqualTo("https://example.com/orders/42");
	}
}
