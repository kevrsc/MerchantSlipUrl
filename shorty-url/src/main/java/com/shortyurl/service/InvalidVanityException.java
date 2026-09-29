package com.shortyurl.service;

public class InvalidVanityException extends RuntimeException {

	public InvalidVanityException(String message) {
		super(message);
	}
}
