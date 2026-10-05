package com.merchantslipurl.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class StallingBodyServer implements AutoCloseable {

	private final ServerSocket serverSocket;
	private final ExecutorService executor;
	private final BlockingQueue<Plan> plans = new LinkedBlockingQueue<>();
	private final List<ObservedRequest> observed = new ArrayList<>();
	private final AtomicBoolean running = new AtomicBoolean(true);

	public StallingBodyServer() throws IOException {
		this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		this.executor = Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "stalling-body");
			thread.setDaemon(true);
			return thread;
		});
		this.executor.submit(this::acceptLoop);
	}

	public int port() {
		return this.serverSocket.getLocalPort();
	}

	public String url(String path) {
		return "http://127.0.0.1:" + port() + path;
	}

	public void enqueue(int status, byte[] prefix, boolean stallAfterPrefix) {
		this.plans.add(new Plan(status, prefix, stallAfterPrefix, null));
	}

	public void enqueue(int status, byte[] prefix, boolean stallAfterPrefix, String location) {
		this.plans.add(new Plan(status, prefix, stallAfterPrefix, location));
	}

	public int requestCount() {
		synchronized (this.observed) {
			return this.observed.size();
		}
	}

	public List<ObservedRequest> requests() {
		synchronized (this.observed) {
			return List.copyOf(this.observed);
		}
	}

	private void acceptLoop() {
		while (this.running.get()) {
			try {
				Socket socket = this.serverSocket.accept();
				this.executor.submit(() -> handle(socket));
			}
			catch (IOException ex) {
				return;
			}
		}
	}

	private void handle(Socket socket) {
		try (socket) {
			socket.setSoTimeout(2_000);
			byte[] requestBytes = readHeaders(socket.getInputStream());
			ObservedRequest observedRequest = ObservedRequest.parse(requestBytes);
			synchronized (this.observed) {
				this.observed.add(observedRequest);
			}
			Plan plan = this.plans.poll(2, TimeUnit.SECONDS);
			if (plan == null) {
				return;
			}
			socket.setSoTimeout(0);
			writeResponse(socket.getOutputStream(), plan);
			if (plan.stallAfterPrefix()) {
				Thread.sleep(5_000);
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (IOException ignored) {
			// Client closed the attempt when its timeout or body cap fired.
		}
	}

	private static byte[] readHeaders(InputStream in) throws IOException {
		ByteArrayOutputStream seen = new ByteArrayOutputStream();
		int previous = -1;
		int matched = 0;
		int value;
		while ((value = in.read()) != -1) {
			seen.write(value);
			if (previous == '\r' && value == '\n') {
				matched++;
				if (matched == 2) {
					return seen.toByteArray();
				}
			}
			else if (value != '\r') {
				matched = 0;
			}
			previous = value;
			if (seen.size() > 8192) {
				return seen.toByteArray();
			}
		}
		return seen.toByteArray();
	}

	private static void writeResponse(OutputStream out, Plan plan) throws IOException {
		int contentLength = plan.stallAfterPrefix() ? plan.prefix().length + 1_000_000 : plan.prefix().length;
		StringBuilder headers = new StringBuilder();
		headers.append("HTTP/1.1 ").append(plan.status()).append(' ').append(reason(plan.status())).append("\r\n");
		headers.append("Content-Length: ").append(contentLength).append("\r\n");
		headers.append("Connection: close\r\n");
		if (plan.location() != null) {
			headers.append("Location: ").append(plan.location()).append("\r\n");
		}
		headers.append("\r\n");
		out.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
		if (plan.prefix().length > 0) {
			out.write(plan.prefix());
		}
		out.flush();
	}

	private static String reason(int status) {
		return switch (status) {
			case 200 -> "OK";
			case 302 -> "Found";
			case 403 -> "Forbidden";
			case 404 -> "Not Found";
			case 500 -> "Internal Server Error";
			default -> "Status";
		};
	}

	@Override
	public void close() {
		this.running.set(false);
		try {
			this.serverSocket.close();
		}
		catch (IOException ignored) {
			// Already closed.
		}
		this.executor.shutdownNow();
	}

	private record Plan(int status, byte[] prefix, boolean stallAfterPrefix, String location) {
	}

	public record ObservedRequest(String method, String target, String host) {

		private static ObservedRequest parse(byte[] rawBytes) {
			String raw = new String(rawBytes, StandardCharsets.US_ASCII);
			int lineEnd = raw.indexOf("\r\n");
			String requestLine = lineEnd >= 0 ? raw.substring(0, lineEnd) : raw;
			String[] parts = requestLine.split(" ");
			String method = parts.length > 0 ? parts[0] : "";
			String target = parts.length > 1 ? parts[1] : "";
			return new ObservedRequest(method, target, header(raw, "Host"));
		}

		private static String header(String raw, String name) {
			String prefix = name + ":";
			for (String line : raw.split("\r\n")) {
				if (line.regionMatches(true, 0, prefix, 0, prefix.length())) {
					return line.substring(prefix.length()).trim();
				}
			}
			return "";
		}
	}
}
