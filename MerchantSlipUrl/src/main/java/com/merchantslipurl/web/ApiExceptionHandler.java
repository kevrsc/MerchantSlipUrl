package com.merchantslipurl.web;

import com.merchantslipurl.service.UnusableDestinationException;
import com.merchantslipurl.service.InvalidUrlException;
import com.merchantslipurl.service.InvalidVanityException;
import com.merchantslipurl.service.LivenessFailedException;
import com.merchantslipurl.service.UrlExpiredException;
import com.merchantslipurl.service.UrlNotFoundException;
import com.merchantslipurl.service.VanityTakenException;
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

	@ExceptionHandler(InvalidVanityException.class)
	ProblemDetail handleInvalidVanity(InvalidVanityException ex) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
		detail.setTitle("Bad Request");
		return detail;
	}

	@ExceptionHandler(VanityTakenException.class)
	ProblemDetail handleVanityTaken(VanityTakenException ex) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		detail.setTitle("Conflict");
		return detail;
	}

	@ExceptionHandler(UrlNotFoundException.class)
	ProblemDetail handleUrlNotFound(UrlNotFoundException ex) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		detail.setTitle("Not Found");
		return detail;
	}

	@ExceptionHandler(UrlExpiredException.class)
	ProblemDetail handleUrlExpired(UrlExpiredException ex) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.GONE, ex.getMessage());
		detail.setTitle("Gone");
		return detail;
	}

	@ExceptionHandler(UnusableDestinationException.class)
	ProblemDetail handleUnusableDestination(UnusableDestinationException ex) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
		detail.setTitle("Internal Server Error");
		return detail;
	}

	@ExceptionHandler(LivenessFailedException.class)
	ProblemDetail handleLivenessFailed(LivenessFailedException ex) {
		ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
		detail.setTitle("Bad Gateway");
		return detail;
	}
}
