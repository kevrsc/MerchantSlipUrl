package com.shortyurl.web;

import com.shortyurl.service.UrlResolveService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.net.URI;

@Controller
public class ResolveUrlController {

	private final UrlResolveService urlResolveService;

	public ResolveUrlController(UrlResolveService urlResolveService) {
		this.urlResolveService = urlResolveService;
	}

	@GetMapping("/{shortCode:(?!(?:error|api)$)[A-Za-z0-9_-]{1,64}}")
	public ResponseEntity<Void> resolve(@PathVariable String shortCode) {
		String originalUrl = urlResolveService.resolve(shortCode);
		return ResponseEntity.status(HttpStatus.FOUND)
				.location(URI.create(originalUrl))
				.build();
	}
}
