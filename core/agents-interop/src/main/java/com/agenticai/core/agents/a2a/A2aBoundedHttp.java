package com.agenticai.core.agents.a2a;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Redirect-free, size-capped, deadline-bounded HTTP exchange for the outbound A2A client: the
 * response body is read at most {@code max-response-bytes} and for at most {@code request-timeout}
 * in total (the JDK request timeout only covers the headers, so a watchdog closes a slow-drip body).
 */
final class A2aBoundedHttp {

    /**
     * Closes response streams whose body read outlives the request deadline (a slow-drip remote).
     */
    private static final ScheduledExecutorService BODY_WATCHDOG =
            TenantAwareExecutors.newSingleThreadScheduledExecutor("a2a-client-body-watchdog", true);

    private final HttpClient http;
    private final A2aProperties.Client config;

    A2aBoundedHttp(HttpClient http, A2aProperties.Client config) {
        this.http = http;
        this.config = config;
    }

    String execute(HttpRequest request) {
        long deadlineNanos = System.nanoTime() + config.getRequestTimeout().toNanos();
        try {
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            InputStream body = response.body();
            // HttpRequest.timeout() only covers up to the response headers; this watchdog closes
            // the stream if the body is still being read when the same budget runs out.
            ScheduledFuture<?> watchdog =
                    BODY_WATCHDOG.schedule(
                            () -> closeQuietly(body),
                            Math.max(1, config.getRequestTimeout().toMillis()),
                            TimeUnit.MILLISECONDS);
            try (InputStream in = body) {
                int status = response.statusCode();
                if (status >= 300) {
                    throw new A2aClientException(
                            status < 400
                                    ? "the remote server tried to redirect (redirects are not followed)"
                                    : "the remote server answered HTTP " + status);
                }
                return readCapped(in, deadlineNanos);
            } finally {
                watchdog.cancel(false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new A2aClientException("the call was interrupted");
        } catch (IOException e) {
            throw new A2aClientException(
                    "could not reach the remote agent: " + e.getClass().getSimpleName());
        }
    }

    private String readCapped(InputStream in, long deadlineNanos) throws IOException {
        int max = config.getMaxResponseBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                if (out.size() > max) {
                    throw new A2aClientException("the remote response exceeded " + max + " bytes");
                }
                if (System.nanoTime() - deadlineNanos > 0) {
                    throw new A2aClientException(
                            "the remote response body was not received in time");
                }
            }
        } catch (IOException e) {
            // The watchdog closing the stream surfaces here as an IOException.
            if (System.nanoTime() - deadlineNanos > 0) {
                throw new A2aClientException("the remote response body was not received in time");
            }
            throw e;
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException | RuntimeException ignored) {
            // best effort
        }
    }
}
