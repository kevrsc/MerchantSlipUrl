package com.shortyurl.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

@Component
public class UrlSanitizer {

	public String sanitize(String rawUrl) {
		if (rawUrl == null || rawUrl.isBlank()) {
			throw new InvalidUrlException("url must not be blank");
		}

		String trimmed = rawUrl.trim();
		URI uri;
		try {
			uri = new URI(trimmed);
		}
		catch (URISyntaxException ex) {
			throw new InvalidUrlException("url is not a valid URI");
		}

		String scheme = uri.getScheme();
		if (scheme == null
				|| !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			throw new InvalidUrlException("url must use http or https");
		}

		String host = uri.getHost();
		if (host == null || host.isBlank()) {
			throw new InvalidUrlException("url must include a host");
		}

		try {
			URI normalized = new URI(
					scheme.toLowerCase(Locale.ROOT),
					uri.getUserInfo(),
					host.toLowerCase(Locale.ROOT),
					uri.getPort(),
					uri.getPath(),
					uri.getQuery(),
					null
			).normalize();
			return normalized.toASCIIString();
		}
		catch (URISyntaxException ex) {
			throw new InvalidUrlException("url could not be normalized");
		}
	}
}
