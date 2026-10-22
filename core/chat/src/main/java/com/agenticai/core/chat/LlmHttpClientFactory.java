package com.agenticai.core.chat;

import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;

import jakarta.annotation.PreDestroy;

import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The one place outbound HTTP timeouts, pooling and SSRF pinning are configured for the LLM
 * provider calls that go through the official OpenAI / Anthropic Java SDKs (Spring AI 2, ADR-0012
 * P3).
 *
 * <p><b>History.</b> Until Spring AI 1.x every provider factory built its {@code OpenAiApi} /
 * {@code AnthropicApi} on Spring {@code RestClient} (blocking) and {@code WebClient} (streaming),
 * and this class handed out builders for both, backed by one Apache HttpClient 5 pool and a JDK
 * connector. Spring AI 2 removed those APIs: the models sit on the vendor SDKs, which sit on
 * OkHttp. Two 2026 audit findings must survive that move, so they are re-implemented here for
 * OkHttp:
 *
 * <ul>
 *   <li><b>Bounded clocks</b> (2026-07-29 security audit, section 2 item 4): a provider that
 *       accepts the connection and goes silent must not park a turn forever. The connect timeout is
 *       {@code app.llm.http.connect-timeout}; the read timeout ({@code app.llm.http.read-timeout},
 *       default 10m) is OkHttp's per-read inactivity timeout, so a long streamed answer is not cut
 *       off - only a provider that stops sending bytes for that long is. The overall call timeout
 *       is disabled on purpose (a token stream that runs for an hour is legitimate).
 *   <li><b>A pool that is not the bottleneck</b> (2026-09-28 Spring AI audit): idle connections up
 *       to {@code app.llm.http.max-connections-per-route}, evicted after {@code
 *       app.llm.http.idle-connection-timeout} (below Azure's ~4 minute NAT idle window, see {@link
 *       #applyIdleConnectionBound}). OkHttp does not cap concurrent connections, so the old {@code
 *       max-connections-total} now sizes the dispatcher only.
 * </ul>
 *
 * <p><b>Why an interceptor and not the SDK's own client.</b> The SDK models build a fresh OkHttp
 * client (and pool) every time they are built, and this platform builds a model per chat turn.
 * Handing every SDK client the {@link #transport(boolean) transport interceptor} makes each of them
 * a thin shell that runs the request on ONE long-lived, pooled client owned here, so connections
 * are reused across turns and tenants, and the SSRF-guarded variant ({@link #guardedClient()}) can
 * be address-pinned (a custom {@code Dns}) and can refuse redirects - neither of which the SDK's
 * customizer exposes. Retries ({@link LlmRetryInterceptor}) and URL/body rewriting sit in front of
 * it as further interceptors, wired by {@link OpenAiCompatModelBuilder} / {@link
 * AnthropicModelBuilder}.
 */
@Component
public class LlmHttpClientFactory {

    private final Duration readTimeout;
    private final Duration connectTimeout;
    private final Duration idleConnectionTimeout;
    private final int maxConnectionsPerRoute;
    private final int maxConnectionsTotal;
    private final boolean streamTimeoutsEnabled;
    private final ExecutorService dispatcherExecutor;
    private final OkHttpClient client;
    private final OkHttpClient clientWithoutStreamReadTimeout;
    private SsrfGuard ssrfGuard;
    private OkHttpClient guardedClient;

    public LlmHttpClientFactory(
            @Value("${app.llm.http.connect-timeout:10s}") Duration connectTimeout,
            @Value("${app.llm.http.read-timeout:10m}") Duration readTimeout,
            @Value("${app.llm.http.stream-timeouts-enabled:true}") boolean streamTimeoutsEnabled,
            @Value("${app.llm.http.idle-connection-timeout:120s}") Duration idleConnectionTimeout,
            @Value("${app.llm.http.max-connections-per-route:200}") int maxConnectionsPerRoute,
            @Value("${app.llm.http.max-connections-total:400}") int maxConnectionsTotal) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.streamTimeoutsEnabled = streamTimeoutsEnabled;
        this.idleConnectionTimeout = idleConnectionTimeout;
        this.maxConnectionsPerRoute = Math.max(1, maxConnectionsPerRoute);
        this.maxConnectionsTotal = Math.max(this.maxConnectionsPerRoute, maxConnectionsTotal);
        applyIdleConnectionBound(idleConnectionTimeout);
        this.dispatcherExecutor = newDispatcherExecutor();
        this.client = baseBuilder().build();
        this.clientWithoutStreamReadTimeout =
                streamTimeoutsEnabled
                        ? this.client
                        : this.client.newBuilder().readTimeout(Duration.ZERO).build();
    }

    /**
     * The shared, pooled client builder both the plain and the guarded variants start from:
     * timeouts, pool sizing and idle eviction. Package-private so the settings are unit-testable.
     */
    OkHttpClient.Builder baseBuilder() {
        Dispatcher dispatcher = new Dispatcher(dispatcherExecutor);
        dispatcher.setMaxRequests(maxConnectionsTotal);
        dispatcher.setMaxRequestsPerHost(maxConnectionsPerRoute);
        long keepAliveNanos =
                idleConnectionTimeout == null
                                || idleConnectionTimeout.isZero()
                                || idleConnectionTimeout.isNegative()
                        ? TimeUnit.MINUTES.toNanos(5)
                        : idleConnectionTimeout.toNanos();
        return new OkHttpClient.Builder()
                .connectTimeout(connectTimeout)
                .readTimeout(readTimeout)
                .writeTimeout(readTimeout)
                // No overall deadline: a legitimate token stream may run for an hour. Silence is
                // bounded by readTimeout.
                .callTimeout(Duration.ZERO)
                .dispatcher(dispatcher)
                .connectionPool(
                        new ConnectionPool(
                                maxConnectionsPerRoute, keepAliveNanos, TimeUnit.NANOSECONDS))
                // Retries are ours (LlmRetryInterceptor); OkHttp's silent one-shot connection retry
                // stays on
                // because it only fires for a stale pooled connection, before any bytes were sent.
                .retryOnConnectionFailure(true);
    }

    /**
     * Bound how long an IDLE pooled JDK-HttpClient connection may live, below Azure's NAT idle
     * window.
     *
     * <p>Production runs on Azure Container Apps, and Azure silently drops an idle outbound flow
     * after roughly 4 minutes - with no FIN and no RST. The JDK's HttpClient keeps an idle pooled
     * connection for {@code jdk.httpclient.keepalive.timeout} seconds (default 1200), so for ~16
     * minutes of every quiet period it hands out dead sockets. The LLM traffic itself is on OkHttp
     * now (its pool eviction is {@code idle-connection-timeout} above), but other JDK-HttpClient
     * users in the process - the MCP Streamable-HTTP transports, the Jev decision client - still
     * depend on this property, so it is kept. An operator-set value always wins; {@code
     * idle-connection-timeout=0s} opts out.
     */
    private static void applyIdleConnectionBound(Duration idleConnectionTimeout) {
        if (idleConnectionTimeout == null
                || idleConnectionTimeout.isZero()
                || idleConnectionTimeout.isNegative()) {
            return;
        }
        if (System.getProperty(JDK_KEEPALIVE_PROPERTY) != null) {
            return; // operator override already in place - do not second-guess it
        }
        System.setProperty(
                JDK_KEEPALIVE_PROPERTY, Long.toString(idleConnectionTimeout.toSeconds()));
    }

    /** JDK HttpClient's idle-pool lifetime, in SECONDS. Default 1200. */
    static final String JDK_KEEPALIVE_PROPERTY = "jdk.httpclient.keepalive.timeout";

    /**
     * Shared dispatcher threads (daemon, cached) so per-turn SDK clients do not each spawn their
     * own pool.
     */
    private static ExecutorService newDispatcherExecutor() {
        return TenantAwareExecutors.newCachedThreadPool(
                TenantAwareExecutors.numberedThreadFactory("llm-http-dispatcher-", true));
    }

    /** Releases the pools and dispatcher threads on context shutdown. */
    @PreDestroy
    void close() {
        client.connectionPool().evictAll();
        if (guardedClient != null) {
            guardedClient.connectionPool().evictAll();
        }
        dispatcherExecutor.shutdown();
    }

    /**
     * The terminal interceptor for an SDK client: runs the SDK's request on the shared pooled
     * client (or the SSRF-guarded one) and returns that response. It never calls {@code
     * chain.proceed}, so it must be the LAST interceptor added; the SDK's own OkHttp client is only
     * a shell around it.
     *
     * @param guarded {@code true} for tenant-supplied base URLs (BYOK Ollama / Azure OpenAI)
     */
    public Interceptor transport(boolean guarded) {
        OkHttpClient target = guarded ? guardedClient() : clientWithoutStreamReadTimeout;
        return chain -> execute(target, chain.request());
    }

    private Response execute(OkHttpClient target, Request request) throws IOException {
        return target.newCall(request).execute();
    }

    /**
     * A blocking {@link RestClient} builder for the few callers that talk plain JSON to a model
     * server outside the SDK models (the tool-call capability probe), with the configured connect
     * and read timeouts. Spring AI 2's models do not use it: they run on {@link
     * #transport(boolean)}.
     */
    public RestClient.Builder restClientBuilder() {
        // Spring Boot's request-factory builder (JDK HttpClient underneath) instead of a hand-built
        // client; redirects stay off, as with a bare JDK client.
        ClientHttpRequestFactory factory =
                ClientHttpRequestFactoryBuilder.jdk()
                        .build(
                                HttpClientSettings.defaults()
                                        .withConnectTimeout(connectTimeout)
                                        .withReadTimeout(readTimeout)
                                        .withRedirects(HttpRedirects.DONT_FOLLOW));
        return RestClient.builder().requestFactory(factory);
    }

    /**
     * {@link #restClientBuilder()} for a TENANT-supplied base URL: the JDK client resolves every
     * host through {@link SsrfGuard#dnsResolver()} (the socket connects only to validated
     * addresses) and follows no redirects.
     */
    public RestClient.Builder guardedRestClientBuilder() {
        return restClientOver(
                GuardedJdkHttpClient.create(ssrfGuard(), connectTimeout, readTimeout, false));
    }

    private RestClient.Builder restClientOver(HttpClient jdk) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdk);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory);
    }

    /**
     * The configured LLM read timeout, for providers whose SDK is not built on OkHttp through this
     * factory and therefore cannot take the interceptor above.
     *
     * <p>Google's GenAI SDK ({@code GeminiAgentChatClientFactory}/{@code
     * VertexAiChatClientFactory}) is the case this exists for. It has exactly ONE knob - {@code
     * HttpOptions#timeout}, applied to OkHttp's connect, read AND write timeouts alike - so the
     * split connect/read pair cannot be expressed there and the read value is used for all three.
     * {@code com.google.genai.ApiClient} explicitly calls {@code connectTimeout(0)} / {@code
     * readTimeout(0)} / {@code writeTimeout(0)} on its OkHttp client before applying {@code
     * HttpOptions}, and zero means "no timeout" in OkHttp - so with {@code HttpOptions} unset,
     * Gemini and Vertex were genuinely unbounded.
     */
    public Duration readTimeout() {
        return readTimeout;
    }

    // ---- SSRF-guarded transport for TENANT-supplied base URLs (security review SEC-023) ----

    /**
     * Spring injects the configured guard (operator allow-list); unset means the process-wide
     * default.
     */
    @Autowired(required = false)
    void setSsrfGuard(SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
    }

    /** The guard every tenant-supplied LLM base URL is checked against. */
    public SsrfGuard ssrfGuard() {
        return ssrfGuard != null ? ssrfGuard : SsrfGuard.defaultGuard();
    }

    /**
     * Use-time check of a tenant-supplied base URL (BYOK Ollama / Azure OpenAI endpoint): resolves
     * the host and refuses any internal address, compose name or reserved range. Throws {@link
     * IllegalArgumentException} with the guard's caller-safe reason (never the resolved address).
     */
    public void requireAllowedTenantBaseUrl(String baseUrl) {
        try {
            ssrfGuard().validate(baseUrl);
        } catch (SsrfBlockedException e) {
            throw new ModelServerUrlNotAllowedException(
                    "This model server URL is not allowed: " + e.getMessage(), e);
        }
    }

    /**
     * The SSRF guard refused a tenant-supplied model server URL. Still an {@link
     * IllegalArgumentException} (the REST layer maps it to 400 as before), but a distinct type so
     * callers can tell this deterministic configuration error from a transient failure: it is never
     * worth a retry or a fallback provider, and a background job fails fast with a clear code.
     */
    public static final class ModelServerUrlNotAllowedException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public ModelServerUrlNotAllowedException(String message, Throwable cause) {
            super(message, cause);
        }

        /**
         * The first {@code ModelServerUrlNotAllowedException} in {@code failure}'s cause chain, or
         * null.
         */
        public static ModelServerUrlNotAllowedException find(Throwable failure) {
            int depth = 0;
            for (Throwable t = failure;
                    t != null && depth++ < 16;
                    t = t.getCause() == t ? null : t.getCause()) {
                if (t instanceof ModelServerUrlNotAllowedException blocked) {
                    return blocked;
                }
            }
            return null;
        }
    }

    /**
     * The client for a TENANT-supplied base URL. Same timeouts, pool size and idle eviction as the
     * shared client, but every host is resolved through {@link SsrfGuard#dnsResolver()} - the
     * socket connects only to addresses validated in that same lookup, so a DNS answer that changes
     * after {@link #requireAllowedTenantBaseUrl} cannot move the connection inside the network - it
     * uses no system proxy, and it follows NO redirects (a 3xx is returned as an error, never a hop
     * that carries the tenant's key to another host). Unlike the Spring AI 1.x streaming path this
     * pins BOTH the blocking and the streaming call (they now share this client), closing the
     * residual the old JDK streaming connector had.
     */
    synchronized OkHttpClient guardedClient() {
        if (guardedClient == null) {
            SsrfGuard guard = ssrfGuard();
            guardedClient = buildGuardedClient(baseBuilder(), guard);
        }
        return guardedClient;
    }

    /** The guarded client behind {@link #guardedClient()}. Package-private for tests. */
    static OkHttpClient buildGuardedClient(OkHttpClient.Builder base, SsrfGuard guard) {
        return base.dns(
                        hostname -> {
                            InetAddress[] addresses = guard.dnsResolver().resolve(hostname);
                            return List.copyOf(Arrays.asList(addresses));
                        })
                .proxy(Proxy.NO_PROXY)
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }
}
