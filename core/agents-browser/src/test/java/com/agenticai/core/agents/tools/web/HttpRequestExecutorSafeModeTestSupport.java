package com.agenticai.core.agents.tools.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.secrets.SecretRedactor;
import com.agenticai.core.secrets.SecretReferenceResolver;
import com.agenticai.core.secrets.SecretVaultService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Shared doubles and builders for the {@link HttpRequestExecutor} safe-mode tests. Split from the
 * former HttpRequestExecutorSafeModeTest.
 */
abstract class HttpRequestExecutorSafeModeTestSupport {

    static final String HOST = "203.0.113.10";

    static final String OTHER_HOST = "203.0.113.11";

    static final String SECRET = "sk_live_9f8e7d6c5b4a3210";

    static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------ test doubles

    record Sent(URI uri, String method, Map<String, String> headers, String body) {
        String header(String name) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }
    }

    /** Records every request and answers from a queue; an unexpected request is a test failure. */
    static class RecordingExecutor extends HttpRequestExecutor {
        final List<Sent> sent = new ArrayList<>();
        final Deque<Canned> responses = new ArrayDeque<>();

        RecordingExecutor(SecretReferenceResolver resolver) {
            super(
                    provider(resolver),
                    provider(new SecretRedactor()),
                    provider(null),
                    provider(null));
        }

        @Override
        protected RawResponse send(
                URI uri,
                String method,
                Map<String, String> headers,
                String body,
                Duration timeout) {
            sent.add(new Sent(uri, method, new LinkedHashMap<>(headers), body));
            Canned next = responses.poll();
            if (next == null) {
                throw new AssertionError("unexpected outbound request: " + method + " " + uri);
            }
            return next.toRaw();
        }
    }

    @SuppressWarnings("unchecked")
    static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    /**
     * Mirrors the real {@code send}'s transport cap: at most this many bytes are read off the wire.
     */
    static final int TRANSPORT_CAP_BYTES = 2 * 1024 * 1024;

    /**
     * A canned upstream answer. {@link #toRaw} turns it into what the real {@code send} would hand
     * back: header names lower-cased, the body read up to {@link #TRANSPORT_CAP_BYTES} (and {@code
     * truncated} when it hit it).
     */
    record Canned(int status, Map<String, List<String>> headers, InputStream body) {
        HttpRequestExecutor.RawResponse toRaw() {
            Map<String, List<String>> lower = new LinkedHashMap<>();
            headers.forEach((name, values) -> lower.put(name.toLowerCase(Locale.ROOT), values));
            byte[] bytes;
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8_192];
                int read;
                while (out.size() < TRANSPORT_CAP_BYTES
                        && (read =
                                        body.read(
                                                buffer,
                                                0,
                                                Math.min(
                                                        buffer.length,
                                                        TRANSPORT_CAP_BYTES - out.size())))
                                != -1) {
                    out.write(buffer, 0, read);
                }
                bytes = out.toByteArray();
            } catch (IOException e) {
                throw new WebPageFetchException("the server stopped responding mid-download");
            }
            return new HttpRequestExecutor.RawResponse(
                    status, lower, bytes, bytes.length >= TRANSPORT_CAP_BYTES);
        }
    }

    static Canned response(
            int status, String contentType, InputStream body, String... extraHeaderPairs) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        if (contentType != null) {
            headers.put("Content-Type", List.of(contentType));
        }
        for (int i = 0; i + 1 < extraHeaderPairs.length; i += 2) {
            headers.put(extraHeaderPairs[i], List.of(extraHeaderPairs[i + 1]));
        }
        return new Canned(status, headers, body);
    }

    static Canned json(String body) {
        return response(
                200,
                "application/json",
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    static Canned redirect(int status, String location) {
        return response(status, null, new ByteArrayInputStream(new byte[0]), "Location", location);
    }

    static HttpRequestPolicy policy(
            Set<String> methods, boolean allowHttp, List<String> global, List<String> agent) {
        return new HttpRequestPolicy(
                methods,
                allowHttp,
                1024,
                1_048_576,
                3,
                List.of("application/json", "application/*+json", "text/*", "application/xml"),
                Set.of("authorization", "proxy-authorization", "cookie", "x-api-key"),
                global,
                agent,
                true);
    }

    static HttpRequestPolicy readPolicy() {
        return policy(HttpRequestSafetyConfig.READ_METHODS, false, List.of(), List.of());
    }

    static HttpRequestPolicy writePolicy() {
        return policy(HttpRequestSafetyConfig.WRITE_METHODS, false, List.of(), List.of());
    }

    /**
     * The real resolver over a mocked vault: exercises the production reference syntax, not a stub
     * of it.
     */
    static SecretReferenceResolver resolverWith(String name, String value) {
        SecretVaultService vault = mock(SecretVaultService.class);
        when(vault.resolveForRun(eq(name), any())).thenReturn(Optional.ofNullable(value));
        // OSS (SEC-006): every vault read on this class is bound to the destination host; this
        // vault allows any host.
        when(vault.resolveForDestination(eq(name), any(), any()))
                .thenReturn(Optional.ofNullable(value));
        return new SecretReferenceResolver(vault);
    }

    static HttpRequestSpec spec(
            String method, String url, Map<String, String> headers, String body) {
        return new HttpRequestSpec(method, url, headers, Map.of(), body, null, null, null);
    }

    // ------------------------------------------------------------------ response shaping

    /**
     * Counts bytes actually pulled from the stream, to prove the cap bounds consumption and not
     * just the result.
     */
    static final class CountingStream extends InputStream {
        private final int total;
        int consumed;

        CountingStream(int total) {
            this.total = total;
        }

        @Override
        public int read() {
            return consumed >= total ? -1 : (consumed++ >= 0 ? 'a' : -1);
        }

        @Override
        public int read(byte[] buffer, int off, int len) {
            if (consumed >= total) {
                return -1;
            }
            int n = Math.min(len, total - consumed);
            Arrays.fill(buffer, off, off + n, (byte) 'a');
            consumed += n;
            return n;
        }
    }
}
