package com.merchantslipurl.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

@Component
public class UrlLivenessChecker {

	static final String FAILURE_MESSAGE = "Destination failed the liveness check";

	private final RestClient restClient;
	private final int maxAttempts;
	private final int maxBodyBytes;

	public UrlLivenessChecker(
			RestClient.Builder restClientBuilder,
			@Value("${merchantslipurl.liveness.max-attempts}") int maxAttempts,
			@Value("${merchantslipurl.liveness.timeout}") Duration timeout,
			@Value("${merchantslipurl.liveness.max-body-bytes}") int maxBodyBytes) {
		this.maxAttempts = maxAttempts;
		this.maxBodyBytes = maxBodyBytes;
		HttpClient httpClient = HttpClient.newBuilder()
				.version(HttpClient.Version.HTTP_1_1)
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(timeout)
				.build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		// Closes the response stream when the attempt deadline passes, including a stall after headers.
		requestFactory.setReadTimeout(timeout);
		requestFactory.enableCompression(false);
		this.restClient = restClientBuilder.requestFactory(requestFactory).build();
	}

	public void check(String originalUrl) {
		RuntimeException lastFailure = null;
		for (int attempt = 1; attempt <= this.maxAttempts; attempt++) {
			try {
				probe(originalUrl);
				return;
			}
			catch (LivenessFailedException ex) {
				throw ex;
			}
			catch (RuntimeException ex) {
				LivenessFailedException clientError = findClientError(ex);
				if (clientError != null) {
					throw clientError;
				}
				lastFailure = ex;
			}
		}
		throw new LivenessFailedException(FAILURE_MESSAGE, lastFailure);
	}

	private void probe(String originalUrl) {
		this.restClient.get()
				.uri(URI.create(originalUrl))
				.exchange((request, response) -> {
					HttpStatusCode status = response.getStatusCode();
					InputStream body = openBody(response, status);
					IOException bodyFailure = null;
					try {
						readAtMost(body);
					}
					catch (IOException ex) {
						bodyFailure = ex;
					}
					finally {
						closeQuietly(body);
					}
					if (status.is4xxClientError()) {
						throw new LivenessFailedException(FAILURE_MESSAGE);
					}
					if (bodyFailure != null || !isLive(status)) {
						throw new TransientLivenessException(status, bodyFailure);
					}
					return null;
				});
	}

	private static InputStream openBody(RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse response,
			HttpStatusCode status) {
		try {
			InputStream body = response.getBody();
			if (body != null) {
				return body;
			}
		}
		catch (IOException ex) {
			if (status.is4xxClientError()) {
				throw new LivenessFailedException(FAILURE_MESSAGE);
			}
			throw new UncheckedIOException(ex);
		}
		if (status.is4xxClientError()) {
			throw new LivenessFailedException(FAILURE_MESSAGE);
		}
		if (isLive(status)) {
			return InputStream.nullInputStream();
		}
		throw new TransientLivenessException(status, null);
	}

	private void readAtMost(InputStream body) throws IOException {
		byte[] buffer = new byte[8192];
		int remaining = this.maxBodyBytes;
		while (remaining > 0) {
			int read = body.read(buffer, 0, Math.min(buffer.length, remaining));
			if (read < 0) {
				return;
			}
			remaining -= read;
		}
	}

	private static boolean isLive(HttpStatusCode status) {
		return status.is2xxSuccessful() || status.is3xxRedirection();
	}

	private static void closeQuietly(InputStream body) {
		if (body == null) {
			return;
		}
		try {
			body.close();
		}
		catch (IOException ignored) {
			// Closing stops the client from draining unread bytes.
		}
	}

	private static LivenessFailedException findClientError(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof LivenessFailedException failed) {
				return failed;
			}
			current = current.getCause();
		}
		return null;
	}

	private static final class TransientLivenessException extends RuntimeException {

		private TransientLivenessException(HttpStatusCode status, Throwable cause) {
			super("Transient liveness failure HTTP " + status.value(), cause);
		}
	}
}
