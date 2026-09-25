package com.shortyurl.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

@Component
public class ShortCodeGenerator {

	private static final int ENTROPY_BYTES = 7;
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

	private final SecureRandom secureRandom = new SecureRandom();

	public String generate() {
		byte[] bytes = new byte[ENTROPY_BYTES];
		secureRandom.nextBytes(bytes);
		return ENCODER.encodeToString(bytes);
	}
}
