package com.shortyurl.service;

public class UrlNotFoundException extends RuntimeException {

	public UrlNotFoundException(String shortCode) {
		super("No binding found for short code: " + shortCode);
	}
}
