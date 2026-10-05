package com.merchantslipurl.web;

import com.merchantslipurl.persistence.Binding;
import com.merchantslipurl.service.CreateUrlResult;
import com.merchantslipurl.service.UrlAssignService;
import com.merchantslipurl.service.UrlCreateService;
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
	private final UrlAssignService urlAssignService;
	private final String publicBaseUrl;

	public CreateUrlController(
			UrlCreateService urlCreateService,
			UrlAssignService urlAssignService,
			@Value("${merchantslipurl.public-base-url}") String publicBaseUrl) {
		this.urlCreateService = urlCreateService;
		this.urlAssignService = urlAssignService;
		this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl : publicBaseUrl + "/";
	}

	@PostMapping
	public ResponseEntity<CreateUrlResponse> create(@Valid @RequestBody CreateUrlRequest request) {
		CreateUrlResult result = urlCreateService.create(request.url(), request.vanity());
		HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
		return respond(result.binding(), status);
	}

	@PostMapping("/vanity")
	public ResponseEntity<CreateUrlResponse> assign(@Valid @RequestBody AssignVanityRequest request) {
		Binding binding = urlAssignService.assign(request.url(), request.vanity());
		return respond(binding, HttpStatus.OK);
	}

	private ResponseEntity<CreateUrlResponse> respond(Binding binding, HttpStatus status) {
		CreateUrlResponse body = new CreateUrlResponse(
				publicBaseUrl + binding.getShortCode(),
				binding.getOriginalUrl()
		);
		return ResponseEntity.status(status).body(body);
	}
}
