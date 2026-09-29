package com.shortyurl.service;

public class UrlExpiredException extends RuntimeException {

	public UrlExpiredException(String shortCode) {
		super("Tiny URL has expired: " + shortCode);
	}
}
