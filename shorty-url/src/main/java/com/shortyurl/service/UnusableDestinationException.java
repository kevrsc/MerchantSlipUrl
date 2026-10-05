package com.shortyurl.service;

public class UnusableDestinationException extends RuntimeException {

	public UnusableDestinationException() {
		super("Stored destination is not a valid redirect target");
	}
}
