package com.shortyurl.service;

public class UrlNotFoundException extends RuntimeException {

	public UrlNotFoundException(String message) {
		super(message);
	}

	public static UrlNotFoundException forShortCode(String shortCode) {
		return new UrlNotFoundException("No binding found for short code: " + shortCode);
	}

	public static UrlNotFoundException missingTarget() {
		return new UrlNotFoundException("No binding found for url");
	}
}
