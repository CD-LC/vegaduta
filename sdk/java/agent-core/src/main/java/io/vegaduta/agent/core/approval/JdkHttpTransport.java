package io.vegaduta.agent.core.approval;

import io.vegaduta.agent.api.Experimental;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

/** Posts the body with {@link HttpClient}. Any non-2xx reply is a failure. */
@Experimental
public final class JdkHttpTransport implements Transport {

  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

  private final HttpClient client;
  private final Duration timeout;

  public JdkHttpTransport() {
    this(DEFAULT_TIMEOUT);
  }

  public JdkHttpTransport(Duration timeout) {
    this(HttpClient.newBuilder().connectTimeout(timeout).build(), timeout);
  }

  public JdkHttpTransport(HttpClient client, Duration timeout) {
    this.client = Objects.requireNonNull(client, "client");
    this.timeout = Objects.requireNonNull(timeout, "timeout");
  }

  @Override
  public void send(URI url, String contentType, String body) {
    HttpRequest request =
        HttpRequest.newBuilder(url)
            .timeout(timeout)
            .header("Content-Type", contentType)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build();
    HttpResponse<Void> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.discarding());
    } catch (IOException e) {
      throw new UncheckedIOException("Notification to " + redact(url) + " failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Notification to " + redact(url) + " interrupted", e);
    }
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "Notification to " + redact(url) + " returned HTTP " + response.statusCode());
    }
  }

  /**
   * Scheme and host only: Slack and Teams incoming-webhook URLs carry a bearer secret in the path,
   * which must never reach a log line.
   */
  static String redact(URI url) {
    String scheme = url.getScheme() == null ? "" : url.getScheme() + "://";
    String host = url.getHost() == null ? "<no host>" : url.getHost();
    return scheme + host + "/...";
  }
}
