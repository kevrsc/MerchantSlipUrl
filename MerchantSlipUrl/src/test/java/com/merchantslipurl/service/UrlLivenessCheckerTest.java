package com.merchantslipurl.service;

import com.merchantslipurl.support.StallingBodyServer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UrlLivenessCheckerTest {

	@Test
	void success2xx_probesWithGetOnce() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
			checker(3, Duration.ofMillis(500), 128).check(server.url("/live").toString());

			RecordedRequest request = server.takeRequest();
			assertThat(request.getMethod()).isEqualTo("GET");
			assertThat(request.getPath()).isEqualTo("/live");
			assertThat(request.getHeader("User-Agent")).isEqualTo(UrlLivenessChecker.USER_AGENT);
			assertThat(server.getRequestCount()).isEqualTo(1);
		}
	}

	@Test
	void redirect3xx_succeedsWithoutRequestingLocation() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse()
					.setResponseCode(302)
					.addHeader("Location", server.url("/elsewhere").toString())
					.setBody("go"));
			String pasted = server.url("/pasted").toString();
			checker(3, Duration.ofMillis(500), 128).check(pasted);

			assertThat(server.getRequestCount()).isEqualTo(1);
			assertThat(server.takeRequest().getPath()).isEqualTo("/pasted");
		}
	}

	@Test
	void clientError4xx_failsOnceWithoutRetry() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(404).setBody("missing"));
			server.enqueue(new MockResponse().setResponseCode(200).setBody("later"));
			String url = server.url("/missing").toString();

			assertThatThrownBy(() -> checker(3, Duration.ofMillis(500), 128).check(url))
					.isInstanceOf(LivenessFailedException.class);
			assertThat(server.getRequestCount()).isEqualTo(1);
		}
	}

	@Test
	void otherClientError_failsOnce() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(403).setBody("no"));
			String url = server.url("/denied").toString();

			assertThatThrownBy(() -> checker(3, Duration.ofMillis(500), 128).check(url))
					.isInstanceOf(LivenessFailedException.class);
			assertThat(server.getRequestCount()).isEqualTo(1);
		}
	}

	@Test
	void clientErrorBodyOverCap_stillFailsOnce() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(404).setBody("x".repeat(300)));
			String url = server.url("/missing").toString();

			assertThatThrownBy(() -> checker(3, Duration.ofMillis(500), 64).check(url))
					.isInstanceOf(LivenessFailedException.class);
			assertThat(server.getRequestCount()).isEqualTo(1);
		}
	}

	@Test
	void liveStatusWithNullBody_succeedsOnce() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(204));
			server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", server.url("/secret").toString()));
			RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) ->
					new NullBodyResponse(execution.execute(request, body)));
			UrlLivenessChecker checker = new UrlLivenessChecker(builder, 3, Duration.ofMillis(500), 128);

			checker.check(server.url("/empty").toString());
			checker.check(server.url("/redirect").toString());

			assertThat(server.getRequestCount()).isEqualTo(2);
			assertThat(server.takeRequest().getPath()).isEqualTo("/empty");
			assertThat(server.takeRequest().getPath()).isEqualTo("/redirect");
		}
	}

	@Test
	void clientErrorWithNullBody_failsOnceWithoutRetry() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(404));
			server.enqueue(new MockResponse().setResponseCode(200).setBody("later"));
			RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) ->
					new NullBodyResponse(execution.execute(request, body)));
			String url = server.url("/missing").toString();

			assertThatThrownBy(() -> new UrlLivenessChecker(builder, 3, Duration.ofMillis(500), 128).check(url))
					.isInstanceOf(LivenessFailedException.class);
			assertThat(server.getRequestCount()).isEqualTo(1);
		}
	}

	@Test
	void clientErrorWhenOpeningBodyFails_doesNotRetryIntoLaterSuccess() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(404).setBody("missing"));
			server.enqueue(new MockResponse().setResponseCode(200).setBody("later"));
			RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
				ClientHttpResponse response = execution.execute(request, body);
				if (response.getStatusCode().is4xxClientError()) {
					return new BodyOpenFailureResponse(response);
				}
				return response;
			});
			String url = server.url("/missing").toString();

			assertThatThrownBy(() -> new UrlLivenessChecker(builder, 3, Duration.ofMillis(500), 128).check(url))
					.isInstanceOf(LivenessFailedException.class);
			assertThat(server.getRequestCount()).isEqualTo(1);
		}
	}

	@Test
	void nonClientErrorWhenOpeningBodyFails_retriesAndSucceeds() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(200).setBody("first"));
			server.enqueue(new MockResponse().setResponseCode(200).setBody("second"));
			AtomicInteger opened = new AtomicInteger();
			RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
				ClientHttpResponse response = execution.execute(request, body);
				if (opened.incrementAndGet() == 1) {
					return new BodyOpenFailureResponse(response);
				}
				return response;
			});

			new UrlLivenessChecker(builder, 3, Duration.ofMillis(500), 128)
					.check(server.url("/live").toString());
			assertThat(server.getRequestCount()).isEqualTo(2);
		}
	}

	@Test
	@Timeout(value = 3, unit = TimeUnit.SECONDS)
	void clientErrorWhenBodyStalls_failsOnceWithoutRetry() throws Exception {
		try (StallingBodyServer server = new StallingBodyServer()) {
			server.enqueue(404, new byte[0], true);
			UrlLivenessChecker checker = checker(3, Duration.ofMillis(400), 128);
			Instant start = Instant.now();

			assertThatThrownBy(() -> checker.check(server.url("/missing")))
					.isInstanceOf(LivenessFailedException.class);

			Duration elapsed = Duration.between(start, Instant.now());
			assertThat(server.requestCount()).isEqualTo(1);
			assertThat(server.requests().getFirst().method()).isEqualTo("GET");
			assertThat(elapsed).isBetween(Duration.ofMillis(250), Duration.ofMillis(1200));
		}
	}

	@Test
	void serverErrorWithNullBody_retriesThenFails() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(500));
			server.enqueue(new MockResponse().setResponseCode(502));
			server.enqueue(new MockResponse().setResponseCode(503));
			RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) ->
					new NullBodyResponse(execution.execute(request, body)));
			String url = server.url("/down").toString();

			assertThatThrownBy(() -> new UrlLivenessChecker(builder, 3, Duration.ofMillis(500), 128).check(url))
					.isInstanceOf(LivenessFailedException.class);
			assertThat(server.getRequestCount()).isEqualTo(3);
		}
	}

	@Test
	void wrappedClientError_failsOnceWithoutRetry() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(200).setBody("later"));
			RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) -> {
				throw new IllegalStateException("wrapped", new LivenessFailedException("Destination failed the liveness check"));
			});
			String url = server.url("/missing").toString();

			assertThatThrownBy(() -> new UrlLivenessChecker(builder, 3, Duration.ofMillis(500), 128).check(url))
					.isInstanceOf(LivenessFailedException.class);
			assertThat(server.getRequestCount()).isZero();
		}
	}

	@Test
	void bodyCloseFailure_stillAcceptsLiveResponse() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
			RestClient.Builder builder = RestClient.builder().requestInterceptor((request, body, execution) ->
					new CloseFailureResponse(execution.execute(request, body)));

			new UrlLivenessChecker(builder, 3, Duration.ofMillis(500), 128)
					.check(server.url("/live").toString());

			assertThat(server.getRequestCount()).isEqualTo(1);
		}
	}

	@Test
	void serverError_retriesThenFails() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(500).setBody("a"));
			server.enqueue(new MockResponse().setResponseCode(502).setBody("b"));
			server.enqueue(new MockResponse().setResponseCode(503).setBody("c"));
			String url = server.url("/down").toString();

			assertThatThrownBy(() -> checker(3, Duration.ofMillis(500), 128).check(url))
					.isInstanceOf(LivenessFailedException.class);
			assertThat(server.getRequestCount()).isEqualTo(3);
		}
	}

	@Test
	void serverErrorBodyOverCap_stillRetries() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			for (int i = 0; i < 3; i++) {
				server.enqueue(new MockResponse().setResponseCode(500).setBody("x".repeat(300)));
			}
			String url = server.url("/down").toString();

			assertThatThrownBy(() -> checker(3, Duration.ofMillis(500), 64).check(url))
					.isInstanceOf(LivenessFailedException.class);
			assertThat(server.getRequestCount()).isEqualTo(3);
		}
	}

	@Test
	@Timeout(value = 5, unit = TimeUnit.SECONDS)
	void timeoutThen2xx_succeedsOnRetry() throws Exception {
		try (StallingBodyServer server = new StallingBodyServer()) {
			server.enqueue(200, new byte[0], true);
			server.enqueue(200, "ok".getBytes(), false);
			checker(3, Duration.ofMillis(400), 128).check(server.url("/live"));

			assertThat(server.requestCount()).isEqualTo(2);
			assertThat(server.requests()).allMatch(request -> request.method().equals("GET"));
		}
	}

	@Test
	@Timeout(value = 8, unit = TimeUnit.SECONDS)
	void stalledBody_failsWithinAttemptTimeoutAndRetries() throws Exception {
		try (StallingBodyServer server = new StallingBodyServer()) {
			server.enqueue(200, new byte[0], true);
			server.enqueue(200, new byte[0], true);
			server.enqueue(200, new byte[0], true);
			UrlLivenessChecker checker = checker(3, Duration.ofMillis(400), 128);
			Instant start = Instant.now();

			assertThatThrownBy(() -> checker.check(server.url("/stall")))
					.isInstanceOf(LivenessFailedException.class);

			Duration elapsed = Duration.between(start, Instant.now());
			assertThat(server.requestCount()).isEqualTo(3);
			assertThat(elapsed).isBetween(Duration.ofMillis(900), Duration.ofMillis(3500));
		}
	}

	@Test
	@Timeout(value = 3, unit = TimeUnit.SECONDS)
	void bodyCap_succeedsWhileRemainderStalls() throws Exception {
		try (StallingBodyServer server = new StallingBodyServer()) {
			byte[] prefix = body(32);
			server.enqueue(200, prefix, true);
			UrlLivenessChecker checker = checker(3, Duration.ofSeconds(2), 32);
			Instant start = Instant.now();

			checker.check(server.url("/big"));

			Duration elapsed = Duration.between(start, Instant.now());
			assertThat(server.requestCount()).isEqualTo(1);
			assertThat(elapsed).isLessThan(Duration.ofMillis(800));
		}
	}

	@Test
	@Timeout(value = 3, unit = TimeUnit.SECONDS)
	void redirectBodyOverCap_succeedsWithoutWaitingOrFollowing() throws Exception {
		try (StallingBodyServer server = new StallingBodyServer()) {
			server.enqueue(302, body(32), true, "http://127.0.0.1:" + server.port() + "/secret");
			UrlLivenessChecker checker = checker(3, Duration.ofSeconds(2), 32);
			Instant start = Instant.now();

			checker.check(server.url("/pasted"));

			Duration elapsed = Duration.between(start, Instant.now());
			assertThat(server.requestCount()).isEqualTo(1);
			assertThat(server.requests().getFirst().target()).isEqualTo("/pasted");
			assertThat(elapsed).isLessThan(Duration.ofMillis(800));
		}
	}

	@Test
	void probesPastedUrlRatherThanANormalizedPath() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setResponseCode(200).setBody("ok"));
			int port = server.getPort();
			String pasted = "http://127.0.0.1:" + port + "/a/../Live#Section";
			checker(3, Duration.ofMillis(500), 128).check(pasted);

			RecordedRequest request = server.takeRequest();
			assertThat(request.getMethod()).isEqualTo("GET");
			assertThat(request.getPath()).isEqualTo("/a/../Live");
			assertThat(server.getRequestCount()).isEqualTo(1);
		}
	}

	private static UrlLivenessChecker checker(int attempts, Duration timeout, int maxBodyBytes) {
		return new UrlLivenessChecker(RestClient.builder(), attempts, timeout, maxBodyBytes);
	}

	private static final class NullBodyResponse implements ClientHttpResponse {

		private final ClientHttpResponse delegate;

		private NullBodyResponse(ClientHttpResponse delegate) {
			this.delegate = delegate;
		}

		@Override
		public InputStream getBody() {
			return null;
		}

		@Override
		public HttpStatusCode getStatusCode() throws IOException {
			return this.delegate.getStatusCode();
		}

		@Override
		public String getStatusText() throws IOException {
			return this.delegate.getStatusText();
		}

		@Override
		public HttpHeaders getHeaders() {
			return this.delegate.getHeaders();
		}

		@Override
		public void close() {
			this.delegate.close();
		}
	}

	private static final class CloseFailureResponse implements ClientHttpResponse {

		private final ClientHttpResponse delegate;

		private CloseFailureResponse(ClientHttpResponse delegate) {
			this.delegate = delegate;
		}

		@Override
		public InputStream getBody() {
			return new InputStream() {
				@Override
				public int read() {
					return -1;
				}

				@Override
				public void close() throws IOException {
					CloseFailureResponse.this.delegate.close();
					throw new IOException("close failed");
				}
			};
		}

		@Override
		public HttpStatusCode getStatusCode() throws IOException {
			return this.delegate.getStatusCode();
		}

		@Override
		public String getStatusText() throws IOException {
			return this.delegate.getStatusText();
		}

		@Override
		public HttpHeaders getHeaders() {
			return this.delegate.getHeaders();
		}

		@Override
		public void close() {
			this.delegate.close();
		}
	}

	private static final class BodyOpenFailureResponse implements ClientHttpResponse {

		private final ClientHttpResponse delegate;

		private BodyOpenFailureResponse(ClientHttpResponse delegate) {
			this.delegate = delegate;
		}

		@Override
		public InputStream getBody() throws IOException {
			throw new IOException("failed to open response body");
		}

		@Override
		public HttpStatusCode getStatusCode() throws IOException {
			return this.delegate.getStatusCode();
		}

		@Override
		public String getStatusText() throws IOException {
			return this.delegate.getStatusText();
		}

		@Override
		public HttpHeaders getHeaders() {
			return this.delegate.getHeaders();
		}

		@Override
		public void close() {
			this.delegate.close();
		}
	}

	private static byte[] body(int size) {
		byte[] bytes = new byte[size];
		Arrays.fill(bytes, (byte) 'x');
		return bytes;
	}
}
