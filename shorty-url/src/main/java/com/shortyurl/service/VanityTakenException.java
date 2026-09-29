package com.shortyurl.service;

public class VanityTakenException extends RuntimeException {

	public VanityTakenException(String vanity) {
		super("vanity is already assigned to another destination: " + vanity);
	}
}
