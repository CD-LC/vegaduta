package com.agenticai.core.security.net;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.ProtocolVersion;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.net.URIBuilder;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;

/**
 * A {@link java.net.http.HttpClient} whose transport is {@link SafeHttpClients}:
 * every connection resolves through {@link SsrfGuard#dnsResolver()}, so the address
 * validated is the address connected to - no DNS-rebinding window between a
 * guard check and the socket, which is the hole a raw {@code HttpClient.newBuilder()}
 * leaves even when every URL was checked first.
 *
 * <p>This exists so call sites built on the JDK request/response API
 * ({@code HttpRequest.newBuilder}, {@code BodyHandlers.ofString}, ...) keep their
 * code unchanged: swap the client construction, keep everything else. The ArchUnit
 * rule in {@code ModuleBoundaryRulesTest} accepts this class because the socket is
 * guarded - it bans the unguarded builder call, not the API surface.
 *
 * <p><b>Streaming semantics.</b> {@code BodyHandlers.ofInputStream} is honoured for
 * real: the response body feeds the subscriber on a virtual thread, gated by the
 * subscriber's {@code request(n)} demand, so a reader that stops consuming applies
 * TCP back-pressure instead of the whole body being buffered in memory - the same
 * shape the JDK client has. {@code send()} returns when the handler's body
 * {@code CompletableFuture} completes (immediately for {@code ofInputStream}, at
 * end-of-body for {@code ofString}). A body stream the caller abandons is aborted
 * when the underlying response is closed or the Apache socket timeout fires.
 *
 * <p><b>Behaviour notes vs. the JDK client.</b>
 * <ul>
 *   <li>Transport is HTTP/1.1 (Apache HC5 classic). Requests that must speak
 *       HTTP/2 cannot use this class.</li>
 *   <li>Redirect policy is fixed at construction; a redirect to a new origin
 *       re-resolves through the guard anyway.</li>
 *   <li>Proxy, SSL context/parameters and cookies are {@link SafeHttpClients}'
 *       choices (no proxy, platform TLS) - the JDK accessors report the logical
 *       setting, the socket ignores proxies by design.</li>
 * </ul>
 */
public final class GuardedJdkHttpClient extends HttpClient {

    private final CloseableHttpClient delegate;
    private final Duration connectTimeout;
    private final Redirect redirectPolicy;
    private final Executor executor;

    private GuardedJdkHttpClient(CloseableHttpClient delegate, Duration connectTimeout,
            Redirect redirectPolicy, Executor executor) {
        this.delegate = delegate;
        this.connectTimeout = connectTimeout;
        this.redirectPolicy = redirectPolicy;
        this.executor = executor;
    }

    /**
     * A guarded client. {@code responseTimeout} covers stalled reads between
     * packets (Apache socket timeout), not the whole exchange - same contract as
     * {@link SafeHttpClients#create}.
     */
    public static GuardedJdkHttpClient create(SsrfGuard guard, Duration connectTimeout,
            Duration responseTimeout, boolean followRedirects) {
        return create(guard, connectTimeout, responseTimeout, followRedirects,
            TenantAwareExecutors.newVirtualThreadPerTaskExecutor("guarded-http-"));
    }

    /** Same, with an explicit executor for {@link #sendAsync}. */
    public static GuardedJdkHttpClient create(SsrfGuard guard, Duration connectTimeout,
            Duration responseTimeout, boolean followRedirects, Executor executor) {
        return new GuardedJdkHttpClient(
            SafeHttpClients.create(guard, connectTimeout, responseTimeout, followRedirects),
            connectTimeout, followRedirects ? Redirect.NORMAL : Redirect.NEVER, executor);
    }

    /**
     * A guarded client on the process-wide guard, resolved at LOOKUP time: every
     * DNS resolution calls {@link SsrfGuard#defaultGuard()} afresh, so a client
     * built before {@code SsrfGuardConfiguration} installs the operator's
     * allow-list still picks it up. This is the right factory for field
     * initializers and constructors that run early in startup.
     */
    public static GuardedJdkHttpClient defaults(Duration connectTimeout,
            Duration responseTimeout, boolean followRedirects) {
        // Both DnsResolver methods delegate to whatever defaultGuard() returns at
        // THAT lookup, so a client built before SsrfGuardConfiguration installs the
        // operator allow-list still picks it up.
        org.apache.hc.client5.http.DnsResolver lazyGuardResolver = new org.apache.hc.client5.http.DnsResolver() {
            @Override
            public java.net.InetAddress[] resolve(String host) throws java.net.UnknownHostException {
                return SsrfGuard.defaultGuard().dnsResolver().resolve(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) throws java.net.UnknownHostException {
                return SsrfGuard.defaultGuard().dnsResolver().resolveCanonicalHostname(host);
            }
        };
        return new GuardedJdkHttpClient(
            SafeHttpClients.create(lazyGuardResolver, connectTimeout, responseTimeout, followRedirects),
            connectTimeout, followRedirects ? Redirect.NORMAL : Redirect.NEVER,
            TenantAwareExecutors.newVirtualThreadPerTaskExecutor("guarded-http-"));
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        ClassicHttpRequest apache = toApache(request);
        ClassicHttpResponse response = delegate.execute(apache);
        // No response handler: keep the response open so ofInputStream can stream.
        // The feeder owns the response lifecycle once it starts; if subscribing or
        // the body future fails before/while the feeder runs, we close it here or
        // the feeder does.
        FlowExchange<T> exchange = new FlowExchange<>(request, response, bodyHandler);
        try {
            T body = exchange.awaitBody();
            if (!exchange.streaming()) {
                exchange.closeQuietly();
            }
            return new HttpResponseImpl<>(request, exchange.info, body);
        } catch (InterruptedException e) {
            exchange.cancel();
            Thread.currentThread().interrupt();
            throw e;
        } catch (RuntimeException | IOException | Error e) {
            exchange.cancel();
            throw e;
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
            BodyHandler<T> bodyHandler) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return send(request, bodyHandler);
            } catch (IOException | InterruptedException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, executor);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
            BodyHandler<T> bodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
        // HTTP/1.1 has no server push; the handler is never invoked.
        return sendAsync(request, bodyHandler);
    }

    /**
     * One in-flight exchange: subscription + feeder thread + body future. The
     * feeder drains the Apache entity into the subscriber, paced by {@code
     * request(n)} items, on a dedicated virtual thread (never the common pool -
     * a blocked feeder would starve it).
     */
    private static final class FlowExchange<T> {
        private final ClassicHttpResponse response;
        private final HttpResponse.BodySubscriber<T> subscriber;
        private final ResponseInfo info;
        private final CompletableFuture<Void> fed = new CompletableFuture<>();
        private final CompletableFuture<T> bodyFuture;
        private final Demand demand = new Demand();

        FlowExchange(HttpRequest request, ClassicHttpResponse response, BodyHandler<T> handler) {
            this.response = response;
            this.info = new ResponseInfoImpl(response);
            this.subscriber = handler.apply(info);
            this.bodyFuture = subscriber.getBody().toCompletableFuture();
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { demand.add(n); }
                @Override public void cancel() { demand.cancel(); }
            });
            TenantAwareExecutors.startVirtualThread("guarded-http-feeder", this::feed);
        }

        /** Blocks until the handler's body future completes; for ofInputStream that is right away. */
        T awaitBody() throws IOException, InterruptedException {
            try {
                return bodyFuture.get();
            } catch (ExecutionException e) {
                Throwable c = e.getCause();
                if (c instanceof RuntimeException re) {
                    throw re;
                }
                if (c instanceof IOException ioe) {
                    throw ioe;
                }
                throw new IOException(c == null ? e : c);
            }
        }

        /** True while the feeder still owns the response (a live streamed body). */
        boolean streaming() {
            return !fed.isDone();
        }

        void cancel() {
            demand.cancel();
            closeQuietly();
        }

        void closeQuietly() {
            try {
                response.close();
            } catch (IOException | RuntimeException ignored) {
                // best effort
            }
        }

        private void feed() {
            HttpEntity entity = response.getEntity();
            try (InputStream in = entity == null ? InputStream.nullInputStream() : entity.getContent()) {
                byte[] buf = new byte[16 * 1024];
                while (true) {
                    // Demand is measured in onNext items, not bytes - one item per chunk.
                    if (!demand.await()) {
                        return; // cancelled
                    }
                    int n = in.read(buf);
                    if (n < 0) {
                        subscriber.onComplete();
                        return;
                    }
                    // Copy: buf is reused; a subscriber may retain the buffer past onNext.
                    demand.use();
                    subscriber.onNext(List.of(ByteBuffer.wrap(java.util.Arrays.copyOf(buf, n))));
                }
            } catch (IOException e) {
                subscriber.onError(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                subscriber.onError(e);
            } catch (RuntimeException e) {
                // A throwing onNext/onComplete must not kill the feeder's cleanup.
                subscriber.onError(e);
            } finally {
                closeQuietly();
                fed.complete(null);
            }
        }
    }

    /** Flow-control for {@link FlowExchange#feed}: an item-count latch with cancellation. */
    private static final class Demand {
        private long items;
        private boolean cancelled;

        synchronized void add(long n) {
            if (n <= 0 || cancelled) {
                return;
            }
            items = items > Long.MAX_VALUE - n ? Long.MAX_VALUE : items + n;
            notifyAll();
        }

        synchronized void use() {
            if (items > 0 && items != Long.MAX_VALUE) {
                items--;
            }
        }

        /** False once cancelled; waits until at least one item is wanted. */
        synchronized boolean await() throws InterruptedException {
            while (items == 0 && !cancelled) {
                wait();
            }
            return !cancelled;
        }

        synchronized void cancel() {
            cancelled = true;
            notifyAll();
        }
    }

    // ---------------------------------------------------------- request conversion

    private static ClassicHttpRequest toApache(HttpRequest req) throws IOException {
        HttpUriRequestBase apache;
        try {
            apache = new HttpUriRequestBase(req.method(), new URIBuilder(req.uri()).build());
        } catch (java.net.URISyntaxException e) {
            throw new IOException("bad request URI: " + req.uri(), e);
        }
        req.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            // Forbidden headers: Apache derives host/length/connection itself.
            if (lower.equals("host") || lower.equals("content-length") || lower.equals("connection")) {
                return;
            }
            for (String v : values) {
                apache.addHeader(name, v);
            }
        });
        req.bodyPublisher().ifPresent(pub -> apache.setEntity(new ByteArrayEntity(
            collect(pub),
            contentTypeOf(req).map(ContentType::parse).orElse(ContentType.APPLICATION_OCTET_STREAM))));
        // A per-request timeout() maps to Apache's response timeout (time waiting
        // for a packet), which is the closest available knob - it does not cap the
        // whole exchange the way JDK's does.
        req.timeout().ifPresent(t -> apache.setConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
            .setResponseTimeout(org.apache.hc.core5.util.Timeout.of(t)).build()));
        return apache;
    }

    private static Optional<String> contentTypeOf(HttpRequest req) {
        return req.headers().firstValue("Content-Type");
    }

    /** Collects a JDK BodyPublisher into a byte[] (request bodies are already materialised strings/bytes). */
    private static byte[] collect(HttpRequest.BodyPublisher publisher) {
        List<ByteBuffer> parts = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer buf) { parts.add(buf); }
            @Override public void onError(Throwable t) { failed.set(true); done.countDown(); }
            @Override public void onComplete() { done.countDown(); }
        });
        try {
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted reading the request body", e);
        }
        if (failed.get()) {
            throw new IllegalStateException("request body publisher failed");
        }
        int total = 0;
        for (ByteBuffer b : parts) {
            total += b.remaining();
        }
        byte[] out = new byte[total];
        int off = 0;
        for (ByteBuffer b : parts) {
            int n = b.remaining();
            b.get(out, off, n);
            off += n;
        }
        return out;
    }

    // ---------------------------------------------------------- response conversion

    private static final class ResponseInfoImpl implements ResponseInfo {
        private final int status;
        private final HttpHeaders headers;
        private final Version version;

        ResponseInfoImpl(ClassicHttpResponse response) {
            this.status = response.getCode();
            Map<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Header h : response.getHeaders()) {
                map.computeIfAbsent(h.getName().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(h.getValue());
            }
            this.headers = HttpHeaders.of(map, (k, v) -> true);
            ProtocolVersion pv = response.getVersion();
            this.version = pv != null && pv.getMajor() >= 2 ? Version.HTTP_2 : Version.HTTP_1_1;
        }

        @Override public int statusCode() { return status; }
        @Override public HttpHeaders headers() { return headers; }
        @Override public Version version() { return version; }
    }

    private static final class HttpResponseImpl<T> implements HttpResponse<T> {
        private final HttpRequest request;
        private final ResponseInfo info;
        private final T body;

        HttpResponseImpl(HttpRequest request, ResponseInfo info, T body) {
            this.request = request;
            this.info = info;
            this.body = body;
        }

        @Override public int statusCode() { return info.statusCode(); }
        @Override public HttpRequest request() { return request; }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public HttpHeaders headers() { return info.headers(); }
        @Override public T body() { return body; }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public Version version() { return info.version(); }
    }

    // ---------------------------------------------------------- HttpClient abstract members

    @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
    @Override public Optional<Duration> connectTimeout() { return Optional.ofNullable(connectTimeout); }
    @Override public Redirect followRedirects() { return redirectPolicy; }
    @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
    @Override public SSLContext sslContext() { throw new UnsupportedOperationException("managed by SafeHttpClients"); }
    @Override public SSLParameters sslParameters() { throw new UnsupportedOperationException("managed by SafeHttpClients"); }
    @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
    @Override public Version version() { return Version.HTTP_1_1; }
    @Override public Optional<Executor> executor() { return Optional.of(executor); }

    /**
     * Fail closed: the JDK's default implementation would open an UNGUARDED
     * websocket (the Apache delegate only carries HTTP). Websocket callers
     * must use {@link SafeWebSockets}, which has its own guarded transport.
     */
    @Override
    public java.net.http.WebSocket.Builder newWebSocketBuilder() {
        throw new UnsupportedOperationException(
            "GuardedJdkHttpClient does not support WebSocket - use SafeWebSockets");
    }
}
