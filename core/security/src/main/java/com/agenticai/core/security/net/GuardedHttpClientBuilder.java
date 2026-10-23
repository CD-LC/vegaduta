package com.agenticai.core.security.net;

import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * An {@link HttpClient.Builder} whose {@link #build()} returns a {@link GuardedJdkHttpClient}: a
 * library that builds its own {@code java.net.http.HttpClient} but accepts a {@code
 * HttpClient.Builder} (the MCP SDK's {@code clientBuilder(..)}) can be handed this one, and every
 * connection it makes then resolves through {@link SsrfGuard#defaultGuard()} and connects to the
 * address that was validated. A raw JDK client resolves the name again itself, which leaves a
 * DNS-rebinding window after any URL check made beforehand.
 *
 * <p>What the guarded transport fixes cannot be overridden, so a setting that would weaken or
 * ignore it is refused instead of silently dropped: {@link #sslContext}, {@link #sslParameters},
 * {@link #proxy}, {@link #authenticator}, {@link #cookieHandler}, {@link #executor} and {@link
 * #localAddress} throw {@link UnsupportedOperationException}. The transport is HTTP/1.1, so {@link
 * #version} and {@link #priority} are accepted and have no effect. Redirects are off unless {@link
 * #followRedirects} asks for them.
 *
 * <p>{@link #build()} hands out one client per ({@code connectTimeout}, response timeout, redirect)
 * combination for the whole process: the client is a thread-safe wrapper over a pooled connection
 * manager, so components that connect repeatedly (an MCP registration reconnecting) reuse one pool
 * instead of leaking an unclosed pool per connection.
 */
public final class GuardedHttpClientBuilder implements HttpClient.Builder {

    private static final int MAX_PER_ROUTE = 100;
    private static final int MAX_TOTAL = 2_000;
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private record Key(
            Duration connectTimeout, Duration responseTimeout, boolean followRedirects) {}

    private static final Map<Key, GuardedJdkHttpClient> SHARED = new ConcurrentHashMap<>();

    private final Duration responseTimeout;
    private final SsrfGuard fixedGuard;
    private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private boolean followRedirects;

    /**
     * @param responseTimeout the longest a connection may sit silent between two packets before the
     *     read fails (the Apache socket timeout), for a long-lived stream and a slow reply alike
     */
    public GuardedHttpClientBuilder(Duration responseTimeout) {
        this(responseTimeout, null);
    }

    private GuardedHttpClientBuilder(Duration responseTimeout, SsrfGuard fixedGuard) {
        this.responseTimeout = java.util.Objects.requireNonNull(responseTimeout, "responseTimeout");
        this.fixedGuard = fixedGuard;
    }

    /**
     * A builder over an explicit guard instead of {@link SsrfGuard#defaultGuard()}, whose {@link
     * #build()} makes a new client each time instead of sharing one. For tests that need a fake
     * resolver or an operator allow-list.
     */
    public static GuardedHttpClientBuilder forGuard(SsrfGuard guard, Duration responseTimeout) {
        return new GuardedHttpClientBuilder(
                responseTimeout, java.util.Objects.requireNonNull(guard, "guard"));
    }

    @Override
    public HttpClient.Builder connectTimeout(Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("connect timeout must be positive");
        }
        this.connectTimeout = duration;
        return this;
    }

    @Override
    public HttpClient.Builder followRedirects(HttpClient.Redirect policy) {
        this.followRedirects = policy != null && policy != HttpClient.Redirect.NEVER;
        return this;
    }

    @Override
    public HttpClient.Builder version(HttpClient.Version version) {
        return this;
    }

    @Override
    public HttpClient.Builder priority(int priority) {
        return this;
    }

    @Override
    public HttpClient.Builder cookieHandler(CookieHandler cookieHandler) {
        throw unsupported("cookieHandler");
    }

    @Override
    public HttpClient.Builder sslContext(SSLContext sslContext) {
        throw unsupported("sslContext");
    }

    @Override
    public HttpClient.Builder sslParameters(SSLParameters sslParameters) {
        throw unsupported("sslParameters");
    }

    @Override
    public HttpClient.Builder executor(Executor executor) {
        throw unsupported("executor");
    }

    @Override
    public HttpClient.Builder proxy(ProxySelector selector) {
        throw unsupported("proxy");
    }

    @Override
    public HttpClient.Builder authenticator(Authenticator authenticator) {
        throw unsupported("authenticator");
    }

    @Override
    public HttpClient.Builder localAddress(InetAddress localAddr) {
        throw unsupported("localAddress");
    }

    @Override
    public HttpClient build() {
        if (fixedGuard != null) {
            return GuardedJdkHttpClient.create(
                    fixedGuard, connectTimeout, responseTimeout, followRedirects);
        }
        return SHARED.computeIfAbsent(
                new Key(connectTimeout, responseTimeout, followRedirects),
                k ->
                        GuardedJdkHttpClient.defaults(
                                k.connectTimeout(),
                                k.responseTimeout(),
                                k.followRedirects(),
                                MAX_PER_ROUTE,
                                MAX_TOTAL));
    }

    private static UnsupportedOperationException unsupported(String setting) {
        return new UnsupportedOperationException(
                "GuardedHttpClientBuilder does not support "
                        + setting
                        + ": the guarded transport owns it (see SafeHttpClients)");
    }
}
