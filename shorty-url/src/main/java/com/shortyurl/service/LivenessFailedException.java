package com.shortyurl.service;

public class LivenessFailedException extends RuntimeException {

	public LivenessFailedException(String message) {
		super(message);
	}

	public LivenessFailedException(String message, Throwable cause) {
		super(message, cause);
	}
}
