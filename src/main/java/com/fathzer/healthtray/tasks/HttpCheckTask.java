package com.fathzer.healthtray.tasks;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.function.Function;

/** A {@link CheckTask} that verifies an HTTP(S) URL returns an accepted status code.
 * <BR>Instances are created via the {@link #builder(String, String, long)} method, which returns a
 * {@link Builder} allowing full configuration of the check:
 * <ul>
 *   <li>connect/request timeout</li>
 *   <li>redirect-following policy</li>
 *   <li>set of accepted HTTP status codes (default: {@code 200})</li>
 *   <li>a verification function called when the status code is accepted, for additional checks</li>
 *   <li>any other {@link HttpClient.Builder} setting (proxy, authenticator, etc.)</li>
 * </ul>
 * <BR>Example:
 * <pre>{@code
 * HttpCheckTask.builder("My site", "https://example.com", 60)
 *     .okCodes(Set.of(200, 204))
 *     .verify(response -> new TaskResult(Status.OK, "All good"))
 *     .build();
 * }</pre>
 */
public final class HttpCheckTask extends CheckTask {
	private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
	private static final Set<Integer> DEFAULT_OK_CODES = Set.of(200);
	private static final Function<HttpResponse<Void>, TaskResult> DEFAULT_VERIFY =
			response -> new TaskResult(Status.OK, "HTTP " + response.statusCode());

	private final URI uri;
	private final HttpClient client;
	private final Duration requestTimeout;
	private final Set<Integer> okCodes;
	private final Function<HttpResponse<Void>, TaskResult> verify;

	private HttpCheckTask(String name, long periodSeconds, URI uri, HttpClient client,
			Duration requestTimeout, Set<Integer> okCodes,
			Function<HttpResponse<Void>, TaskResult> verify) {
		super(name, periodSeconds);
		this.uri = uri;
		this.client = client;
		this.requestTimeout = requestTimeout;
		this.okCodes = okCodes;
		this.verify = verify;
	}

	/** Creates a builder for a new HTTP check task.
	 * @param name the task name (displayed in notifications).
	 * @param url the URL to poll (HTTP or HTTPS).
	 * @param periodSeconds the period in seconds between two checks.
	 * @return a {@link Builder} to configure and create the task.
	 */
	public static Builder builder(String name, String url, long periodSeconds) {
		return new Builder(name, url, periodSeconds);
	}

	@Override
	protected TaskResult doRun() {
		HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri).GET();
		if (requestTimeout != null) {
			requestBuilder.timeout(requestTimeout);
		}
		HttpRequest request = requestBuilder.build();
		try {
			HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
			int code = response.statusCode();
			if (okCodes.contains(code)) {
				return verify.apply(response);
			}
			return new TaskResult(Status.ERROR, "HTTP " + code);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new TaskResult(Status.ERROR, "Interrupted");
		} catch (IOException e) {
			return new TaskResult(Status.ERROR, e.getClass().getSimpleName() + ": " + e.getMessage());
		}
	}

	/** Builder for {@link HttpCheckTask}.
	 * <BR>Provides sensible defaults (10s timeout, redirects always followed, only 200 accepted,
	 * no extra verification). Use the {@code set*} methods to override, then call {@link #build()}.
	 */
	public static final class Builder {
		private final String name;
		private final String url;
		private final long periodSeconds;
		private final HttpClient.Builder clientBuilder = HttpClient.newBuilder()
				.connectTimeout(DEFAULT_TIMEOUT)
				.followRedirects(HttpClient.Redirect.ALWAYS);
		private Duration requestTimeout = DEFAULT_TIMEOUT;
		private Set<Integer> okCodes = DEFAULT_OK_CODES;
		private Function<HttpResponse<Void>, TaskResult> verify = DEFAULT_VERIFY;

		private Builder(String name, String url, long periodSeconds) {
			this.name = name;
			this.url = url;
			this.periodSeconds = periodSeconds;
		}

		/** Sets the connect timeout (default: 10 seconds). */
		public Builder connectTimeout(Duration timeout) {
			this.clientBuilder.connectTimeout(timeout);
			return this;
		}

		/** Sets the request timeout (default: 10 seconds). Use {@code null} for no request timeout. */
		public Builder requestTimeout(Duration timeout) {
			this.requestTimeout = timeout;
			return this;
		}

		/** Sets the redirect-following policy (default: {@link HttpClient.Redirect#ALWAYS}). */
		public Builder redirect(HttpClient.Redirect redirect) {
			this.clientBuilder.followRedirects(redirect);
			return this;
		}

		/** Sets the accepted HTTP status codes (default: {@code Set.of(200)}).
		 * Any code not in this set is reported as ERROR.
		 */
		public Builder okCodes(Set<Integer> codes) {
			this.okCodes = Set.copyOf(codes);
			return this;
		}

		/** Sets the verification function called when the status code is accepted.
		 * <BR>This replaces subclassing: the function receives the {@link HttpResponse} and returns
		 * the final {@link TaskResult}. The default returns {@code OK} with message {@code "HTTP <code>"}.
		 */
		public Builder verify(Function<HttpResponse<Void>, TaskResult> verify) {
			this.verify = verify;
			return this;
		}

		/** Exposes the underlying {@link HttpClient.Builder} for advanced configuration
		 * (proxy, authenticator, SSL context, etc.).
		 */
		public HttpClient.Builder httpClientBuilder() {
			return clientBuilder;
		}

		/** Builds the {@link HttpCheckTask}. */
		public HttpCheckTask build() {
			return new HttpCheckTask(name, periodSeconds, URI.create(url), clientBuilder.build(),
					requestTimeout, okCodes, verify);
		}
	}
}
