package com.merchantslipurl.service;

import java.util.regex.Pattern;

final class Vanity {

	static final Pattern SHORT_CODE = Pattern.compile("(?!(?:error|api)$)[A-Za-z0-9_-]{1,64}");

	private static final String INVALID_MESSAGE =
			"vanity must be 1-64 characters of A-Z, a-z, 0-9, underscore, or hyphen, and must not be api or error";

	private Vanity() {
	}

	static String optional(String vanity) {
		if (vanity == null || vanity.isBlank()) {
			return null;
		}
		return require(vanity);
	}

	static String require(String vanity) {
		if (vanity == null || !SHORT_CODE.matcher(vanity).matches()) {
			throw new InvalidVanityException(INVALID_MESSAGE);
		}
		return vanity;
	}
}
