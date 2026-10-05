package com.shortyurl.web;

import com.shortyurl.service.UnusableDestinationException;
import com.shortyurl.service.UrlExpiredException;
import com.shortyurl.service.UrlNotFoundException;
import com.shortyurl.service.UrlResolveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.net.URI;

@Controller
public class ResolveUrlController {

	private static final Logger log = LoggerFactory.getLogger(ResolveUrlController.class);

	private final UrlResolveService urlResolveService;

	public ResolveUrlController(UrlResolveService urlResolveService) {
		this.urlResolveService = urlResolveService;
	}

	@GetMapping("/{shortCode:(?!(?:error|api)$)[A-Za-z0-9_-]{1,64}}")
	public ResponseEntity<Void> resolve(@PathVariable String shortCode) {
		try {
			String originalUrl = urlResolveService.resolve(shortCode);
			log.info("GET /{} redirected to {}", shortCode, originalUrl);
			return ResponseEntity.status(HttpStatus.FOUND)
					.location(URI.create(originalUrl))
					.build();
		}
		catch (UrlNotFoundException ex) {
			log.info("GET /{} not found", shortCode);
			throw ex;
		}
		catch (UrlExpiredException ex) {
			log.info("GET /{} expired", shortCode);
			throw ex;
		}
		catch (UnusableDestinationException ex) {
			log.warn("GET /{} destination is not a valid redirect", shortCode);
			throw ex;
		}
	}
}
