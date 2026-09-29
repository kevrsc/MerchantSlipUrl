package com.shortyurl.web;

import com.shortyurl.service.InvalidUrlException;
import com.shortyurl.service.LivenessFailedException;
import com.shortyurl.service.UrlNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail handleValidation(MethodArgumentNotValidException ex) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST,
				"Request validation failed"
		);
		detail.setTitle("Bad Request");
		detail.setProperty(
				"errors",
				ex.getBindingResult().getFieldErrors().stream()
						.map(error -> error.getField() + ": " + error.getDefaultMessage())
						.toList()
		);
		return detail;
	}

	@ExceptionHandler(InvalidUrlException.class)
	ProblemDetail handleInvalidUrl(InvalidUrlException ex) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		detail.setTitle("Bad Request");
		return detail;
	}

	@ExceptionHandler(UrlNotFoundException.class)
	ProblemDetail handleUrlNotFound(UrlNotFoundException ex) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		detail.setTitle("Not Found");
		return detail;
	}

	@ExceptionHandler(LivenessFailedException.class)
	ProblemDetail handleLivenessFailed(LivenessFailedException ex) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
		detail.setTitle("Bad Gateway");
		return detail;
	}
}
