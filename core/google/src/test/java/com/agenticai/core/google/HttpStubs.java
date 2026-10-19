package com.agenticai.core.google;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/**
 * Test seam for this module's HTTP-calling classes.
 *
 * <p><b>Why reflection injection instead of MockRestServiceServer</b>: the
 * core/sarvam and core/providers tests bind {@code MockRestServiceServer} to
 * an injected {@code RestClient.Builder}, but every client in core/google
 * ({@link GoogleOAuthClient}, {@link GoogleGmailClient}, {@link
 * GoogleDriveClient}, {@link GoogleSheetsClient}, {@link
 * GoogleCalendarClient}, {@link YouTubeAnalyticsClient}) instead constructs
 * a raw {@code java.net.http.HttpClient} inline as a {@code private final}
 * field, with Google's real endpoints as hardcoded constants. There is no
 * injectable transport and no overridable base URL, so the only way to test
 * request mapping without touching main code (this task is test-files-only)
 * is to reflectively swap that field for a recording stub. Instance final
 * fields of ordinary classes are reflectively writable on JDK 17+ (only
 * records/hidden classes and static finals are protected), and the swap
 * happens before any request is sent, so no final-field-folding hazard
 * applies in practice. The cleaner long-term seam - constructor-injecting
 * {@code HttpClient} (or a base-URL + builder pair like Sarvam's clients
 * take) - is reported to the module owner rather than done here.
 *
 * <p>Hand-rolled rather than a Mockito mock of the abstract {@code
 * HttpClient} so these tests don't depend on the inline-mock-maker agent
 * being attachable to a JDK module class (a known point of friction on this
 * repo's JDK 24 toolchain - see the surefire argLine notes in the root pom).
 */
final class HttpStubs {

    private HttpStubs() {
    }

    /**
     * Replaces {@code target}'s single {@code HttpClient} field with a fresh
     * recording stub and returns it. Fails loudly if the class under test
     * ever gains a second HttpClient field, so the injection can't silently
     * bind to the wrong one.
     */
    static RecordingHttpClient install(Object target) {
        Field found = null;
        for (Field field : target.getClass().getDeclaredFields()) {
            if (field.getType() == HttpClient.class) {
                if (found != null) {
                    throw new IllegalStateException(
                        target.getClass().getSimpleName() + " has more than one HttpClient field - injection is ambiguous");
                }
                found = field;
            }
        }
        if (found == null) {
            throw new IllegalStateException(target.getClass().getSimpleName() + " has no HttpClient field to inject");
        }
        RecordingHttpClient client = new RecordingHttpClient();
        try {
            found.setAccessible(true);
            found.set(target, client);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not inject the stub HttpClient into " + target.getClass(), e);
        }
        return client;
    }

    /**
     * Drains an {@link HttpRequest}'s {@code BodyPublisher} into a UTF-8
     * string - the JDK offers no direct accessor, so this adapts the
     * publisher's {@code ByteBuffer} stream into {@code
     * BodySubscribers.ofString}'s {@code List<ByteBuffer>} contract.
     * Returns "" for bodiless requests ({@code noBody()} publishers complete
     * without emitting, which also yields "").
     */
    static String bodyOf(HttpRequest request) {
        return request.bodyPublisher().map(publisher -> {
            HttpResponse.BodySubscriber<String> collector = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
            publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    collector.onSubscribe(subscription);
                }

                @Override
                public void onNext(ByteBuffer item) {
                    collector.onNext(List.of(item));
                }

                @Override
                public void onError(Throwable throwable) {
                    collector.onError(throwable);
                }

                @Override
                public void onComplete() {
                    collector.onComplete();
                }
            });
            return collector.getBody().toCompletableFuture().join();
        }).orElse("");
    }

    /**
     * Records every request and answers from a FIFO queue of stubbed
     * outcomes. Synchronous {@link #send} only - the clients under test
     * never use {@code sendAsync}, so those fail fast rather than pretending
     * to work.
     */
    static final class RecordingHttpClient extends HttpClient {

        final List<HttpRequest> requests = new ArrayList<>();
        private final ArrayDeque<Object> outcomes = new ArrayDeque<>();

        void enqueue(int statusCode, String body) {
            outcomes.add(new StubResponse(statusCode, body));
        }

        /** The queued call will throw, exercising the "Could not reach ..." translation paths. */
        void enqueueNetworkFailure(String message) {
            outcomes.add(new IOException(message));
        }

        HttpRequest onlyRequest() {
            if (requests.size() != 1) {
                throw new AssertionError("Expected exactly one HTTP request, saw " + requests.size() + ": " + requests);
            }
            return requests.get(0);
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler)
            throws IOException {
            requests.add(request);
            Object next = outcomes.pollFirst();
            if (next == null) {
                throw new AssertionError(
                    "Unexpected HTTP call (no stubbed outcome left): " + request.method() + " " + request.uri());
            }
            if (next instanceof IOException failure) {
                throw failure;
            }
            // The clients all use BodyHandlers.ofString(), so the String stub matches T.
            return (HttpResponse<T>) next;
        }

        @Override
        public Optional<CookieHandler> cookieHandler() {
            return Optional.empty();
        }

        @Override
        public Optional<Duration> connectTimeout() {
            return Optional.empty();
        }

        @Override
        public Redirect followRedirects() {
            return Redirect.NEVER;
        }

        @Override
        public Optional<ProxySelector> proxy() {
            return Optional.empty();
        }

        @Override
        public SSLContext sslContext() {
            return null; // never consulted by the code under test
        }

        @Override
        public SSLParameters sslParameters() {
            return null; // never consulted by the code under test
        }

        @Override
        public Optional<Authenticator> authenticator() {
            return Optional.empty();
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }

        @Override
        public Optional<Executor> executor() {
            return Optional.empty();
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            throw new UnsupportedOperationException("The Google clients only use synchronous send()");
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> responseBodyHandler,
            HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException("The Google clients only use synchronous send()");
        }
    }

    /** Minimal {@link HttpResponse} - only status/body are read by the code under test. */
    static final class StubResponse implements HttpResponse<String> {

        private final int statusCode;
        private final String body;

        StubResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public int statusCode() {
            return statusCode;
        }

        @Override
        public String body() {
            return body;
        }

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public HttpHeaders headers() {
            return HttpHeaders.of(Map.of(), (name, value) -> true);
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public java.net.URI uri() {
            return null;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
