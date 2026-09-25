package com.shortyurl.web;

import com.shortyurl.service.CreateUrlResult;
import com.shortyurl.service.UrlCreateService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/urls")
public class CreateUrlController {

	private final UrlCreateService urlCreateService;
	private final String publicBaseUrl;

	public CreateUrlController(
			UrlCreateService urlCreateService,
			@Value("${shortyurl.public-base-url}") String publicBaseUrl) {
		this.urlCreateService = urlCreateService;
		this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl : publicBaseUrl + "/";
	}

	@PostMapping
	public ResponseEntity<CreateUrlResponse> create(@Valid @RequestBody CreateUrlRequest request) {
		CreateUrlResult result = urlCreateService.create(request.url());
		CreateUrlResponse body = new CreateUrlResponse(
				publicBaseUrl + result.binding().getShortCode(),
				result.binding().getOriginalUrl()
		);
		HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return ResponseEntity.status(status).body(body);
	}
}
