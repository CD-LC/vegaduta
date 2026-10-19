package com.agenticai.core.chat;

import com.agenticai.core.security.net.GuardedJdkHttpClient;
import com.agenticai.core.security.net.SafeHttpClients;
import com.agenticai.core.security.net.SsrfBlockedException;
import com.agenticai.core.security.net.SsrfGuard;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.ClientHttpConnector;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The one place outbound HTTP timeouts are configured for LLM provider calls.
 *
 * <p><b>Why this exists at all</b> (2026-07-29 security audit, §2 item 4):
 * {@code grep -r timeout core/chat} returned zero hits. Every provider factory
 * in this package builds its Spring AI {@code *Api} object with that API's own
 * default HTTP clients - {@code RestClient.builder()} for the blocking
 * {@code call()} path and {@code WebClient.builder()} for the streaming
 * {@code stream()} path - and BOTH of those defaults carry no connect timeout
 * and no read timeout whatsoever. A provider that accepted the connection and
 * then went silent parked the chat turn forever: no exception, no metric, no
 * recovery, just a leaked connection plus whatever tenant-scoped context the
 * turn was holding.
 *
 * <p><b>Why not just {@code spring.http.client.*}</b>: that property block
 * (added to core/app's application.yml alongside this class) only reaches
 * clients built from the AUTO-CONFIGURED {@code RestClient.Builder} bean, and
 * every factory here builds its own. Even if they did use the shared builder,
 * they must not use the shared VALUES - the global read timeout is a bounded
 * 120s backstop appropriate for control-plane REST calls, and a single
 * completion from a reasoning model legitimately exceeds that. Pointing the
 * LLM clients at the global setting would have "fixed" the audit finding by
 * breaking working chat.
 *
 * <p><b>What the read timeout actually measures.</b> It is time-to-response,
 * not time-to-last-token. On the blocking path Spring's request factory
 * arms the timer until response headers arrive ({@code
 * HttpRequest.Builder#timeout} with a streaming body handler on the JDK
 * client; {@code RequestConfig#responseTimeout} on Apache HttpClient 5) and
 * disarms it once they do. On the streaming path {@link JdkClientHttpConnector}
 * does the same. So a token stream that runs for an hour is not cut off by
 * a 10-minute read timeout - only a provider that never starts answering is.
 * That is exactly the failure this class is here to bound, and it is why the
 * default is generous rather than aggressive.
 *
 * <p><b>Lifecycle.</b> Provider factories call {@code create(...)} once per
 * chat turn, so the underlying {@link ClientHttpRequestFactory} and {@link
 * ClientHttpConnector} - each of which owns a connection pool - are built
 * ONCE here and shared. Only the cheap, mutable builder wrappers are handed
 * out fresh per call, because Spring AI's {@code *Api} builders mutate what
 * they are given (default headers, status handlers) and must not scribble on
 * a shared instance.
 */
@Component
public class LlmHttpClientFactory {

    private final RestClient.Builder autoConfiguredRestClientBuilder;
    private final ClientHttpRequestFactory requestFactory;
    /** Null when {@code app.llm.http.stream-timeouts-enabled} is false - see that property's comment. */
    private final ClientHttpConnector streamingConnector;
    private final Duration readTimeout;
    // Kept for the lazily-built SSRF-guarded transports (tenant-supplied base URLs) - see guardedRestClientBuilder().
    private final Duration connectTimeout;
    private final Duration idleConnectionTimeout;
    private final int maxConnectionsPerRoute;
    private final int maxConnectionsTotal;
    private SsrfGuard ssrfGuard;
    private ClientHttpRequestFactory guardedRequestFactory;
    private ClientHttpConnector guardedStreamingConnector;

    public LlmHttpClientFactory(
        RestClient.Builder autoConfiguredRestClientBuilder,
        ObjectProvider<ClientHttpRequestFactorySettings> baseSettings,
        @Value("${app.llm.http.connect-timeout:10s}") Duration connectTimeout,
        @Value("${app.llm.http.read-timeout:10m}") Duration readTimeout,
        @Value("${app.llm.http.stream-timeouts-enabled:true}") boolean streamTimeoutsEnabled,
        @Value("${app.llm.http.idle-connection-timeout:120s}") Duration idleConnectionTimeout,
        @Value("${app.llm.http.max-connections-per-route:200}") int maxConnectionsPerRoute,
        @Value("${app.llm.http.max-connections-total:400}") int maxConnectionsTotal
    ) {
        this.autoConfiguredRestClientBuilder = autoConfiguredRestClientBuilder;
        this.readTimeout = readTimeout;
        this.connectTimeout = connectTimeout;
        this.idleConnectionTimeout = idleConnectionTimeout;
        this.maxConnectionsPerRoute = maxConnectionsPerRoute;
        this.maxConnectionsTotal = maxConnectionsTotal;
        applyIdleConnectionBound(idleConnectionTimeout);
        // Start from the app-wide settings bean so redirect handling and any
        // configured SSL bundle are inherited, then override ONLY the two
        // timeouts - the LLM path differs from the rest of the app in how long
        // it may wait, not in how it talks TLS.
        ClientHttpRequestFactorySettings settings = llmSettings(
            baseSettings.getIfAvailable(ClientHttpRequestFactorySettings::defaults), connectTimeout, readTimeout
        );
        this.requestFactory = buildRequestFactory(
            settings, maxConnectionsPerRoute, maxConnectionsTotal, idleConnectionTimeout
        );
        this.streamingConnector = streamTimeoutsEnabled ? buildStreamingConnector(connectTimeout, readTimeout) : null;
    }

    /**
     * The blocking-path transport: Apache HttpClient 5 with an explicitly sized
     * connection pool.
     *
     * <p>THE BUG THIS FIXES (2026-09-28 Spring AI audit). This used to be
     * {@code ClientHttpRequestFactoryBuilder.detect()}, which in the assembled
     * app picks Apache HttpClient 5 (it is on core/app's classpath) - with
     * HttpClient's own pool defaults of 5 connections per route and 25 in
     * total, shared by every provider and every tenant. An LLM call holds its
     * connection for the whole generation (seconds to minutes), so the sixth
     * concurrent turn against the same provider queued for a free connection
     * while looking, from the outside, like a slow model.
     *
     * <p>Built through Boot's {@code httpComponents()} builder rather than by
     * hand so the connect/read timeouts, redirect policy and SSL bundle from
     * {@code settings} are applied exactly as {@code detect()} applied them -
     * only the pool size and idle eviction are added.
     *
     * <p>Idle eviction mirrors {@link #applyIdleConnectionBound} for this
     * transport: Azure's NAT drops an idle flow after ~4 minutes without a
     * FIN/RST, so a pooled connection left idle longer is dead but looks
     * reusable. Evicting at {@code app.llm.http.idle-connection-timeout}
     * keeps the pool below that window.
     */
    static ClientHttpRequestFactory buildRequestFactory(
        ClientHttpRequestFactorySettings settings, int maxPerRoute, int maxTotal, Duration idleConnectionTimeout
    ) {
        return ClientHttpRequestFactoryBuilder.httpComponents()
            .withConnectionManagerCustomizer(pool -> configurePool(pool, maxPerRoute, maxTotal))
            .withHttpClientCustomizer(client -> {
                if (idleConnectionTimeout != null && !idleConnectionTimeout.isZero() && !idleConnectionTimeout.isNegative()) {
                    client.evictIdleConnections(TimeValue.ofMilliseconds(idleConnectionTimeout.toMillis()));
                }
            })
            .build(settings);
    }

    /** Pool sizing, extracted so the numbers are unit-testable against a real connection manager. */
    static PoolingHttpClientConnectionManagerBuilder configurePool(
        PoolingHttpClientConnectionManagerBuilder pool, int maxPerRoute, int maxTotal
    ) {
        int perRoute = Math.max(1, maxPerRoute);
        return pool.setMaxConnPerRoute(perRoute).setMaxConnTotal(Math.max(perRoute, maxTotal));
    }

    /** Releases the pool and its idle-evictor thread on context shutdown. */
    @PreDestroy
    void close() throws Exception {
        if (requestFactory instanceof DisposableBean disposable) {
            disposable.destroy();
        }
        if (guardedRequestFactory instanceof DisposableBean guardedDisposable) {
            guardedDisposable.destroy();
        }
    }

    /**
     * The timeout override applied on top of the app-wide HTTP client settings.
     * Extracted as a pure function so the "LLM keeps the shared transport
     * config but gets its own, longer clock" decision is unit-testable without
     * standing up a context - same seam-for-testability shape as {@code
     * HostedLlmChatClientFactory#resolveEndpoint}.
     */
    static ClientHttpRequestFactorySettings llmSettings(
        ClientHttpRequestFactorySettings base, Duration connectTimeout, Duration readTimeout
    ) {
        return base.withConnectTimeout(connectTimeout).withReadTimeout(readTimeout);
    }

    /**
     * Bound how long an IDLE pooled connection to an LLM provider may live,
     * below Azure's NAT idle window.
     *
     * <p>THE BUG THIS FIXES. Production runs on Azure Container Apps, and Azure
     * silently drops an idle outbound flow after roughly 4 minutes - with no
     * FIN and no RST. The client never learns the connection is gone. The JDK's
     * HttpClient, meanwhile, keeps an idle pooled connection for
     * {@code jdk.httpclient.keepalive.timeout} seconds, which defaults to 1200
     * (20 minutes). So for ~16 minutes of every quiet period the pool happily
     * hands out sockets that Azure has already discarded: the request goes out,
     * nothing comes back, and the caller waits on a dead connection instead of
     * opening a live one. On a chat turn that reads as the model "being slow",
     * and it is worst exactly where it is most visible - the first message
     * after a lull. The same mechanism is already on record here as the cause
     * of the MCP relay flap.
     *
     * <p>THE FIX. Set the JDK's keepalive timeout BELOW the NAT window, so the
     * client retires an idle connection before Azure kills it and the next
     * request pays one fresh TLS handshake (tens of ms) instead of a stall.
     * 120s default leaves generous margin under ~4 minutes.
     *
     * <p>WHY A SYSTEM PROPERTY. The JDK HttpClient exposes no builder API for
     * pool idle lifetime; this property is the only supported control, and it
     * is read when the connection pool initialises, so it must be set before
     * the first client is built - hence the constructor, not a
     * {@code @PostConstruct}. An operator-set value always wins: if the
     * property is already present (e.g. via JAVA_TOOL_OPTIONS) it is left
     * alone, so this can be tuned or disabled without a rebuild.
     *
     * <p>Set {@code app.llm.http.idle-connection-timeout=0s} to opt out
     * entirely and keep the JDK default.
     */
    private static void applyIdleConnectionBound(Duration idleConnectionTimeout) {
        if (idleConnectionTimeout == null || idleConnectionTimeout.isZero() || idleConnectionTimeout.isNegative()) {
            return;
        }
        if (System.getProperty(JDK_KEEPALIVE_PROPERTY) != null) {
            return; // operator override already in place - do not second-guess it
        }
        System.setProperty(JDK_KEEPALIVE_PROPERTY, Long.toString(idleConnectionTimeout.toSeconds()));
    }

    /** JDK HttpClient's idle-pool lifetime, in SECONDS. Default 1200. */
    static final String JDK_KEEPALIVE_PROPERTY = "jdk.httpclient.keepalive.timeout";

    private static ClientHttpConnector buildStreamingConnector(Duration connectTimeout, Duration readTimeout) {
        // The JDK's own HttpClient rather than whatever WebClient would
        // auto-detect: it is the only connector guaranteed to be present
        // (no optional Netty/Jetty/HttpComponents dependency), and its
        // connect timeout is set on the client while its read timeout is
        // applied per-request by the connector below.
        JdkClientHttpConnector connector = new JdkClientHttpConnector(
            GuardedJdkHttpClient.defaults(connectTimeout, readTimeout, false)
        );
        connector.setReadTimeout(readTimeout);
        return connector;
    }

    /**
     * A fresh {@link RestClient.Builder} for Spring AI's blocking path,
     * carrying the app's auto-configured message converters/observability plus
     * the LLM timeouts. Feed to {@code OpenAiApi.Builder#restClientBuilder} /
     * {@code AnthropicApi.Builder#restClientBuilder}.
     */
    public RestClient.Builder restClientBuilder() {
        return autoConfiguredRestClientBuilder.clone().requestFactory(requestFactory);
    }

    /**
     * A fresh {@link WebClient.Builder} for Spring AI's streaming path. Feed to
     * {@code OpenAiApi.Builder#webClientBuilder} / {@code
     * AnthropicApi.Builder#webClientBuilder}. When streaming timeouts are
     * disabled this returns a plain builder, i.e. exactly what Spring AI would
     * have created for itself.
     */
    public WebClient.Builder webClientBuilder() {
        WebClient.Builder builder = WebClient.builder().filter(OllamaToolCallParseRetry.filter());
        if (streamingConnector != null) {
            builder.clientConnector(streamingConnector);
        }
        return builder;
    }

    /**
     * The configured LLM read timeout, for providers whose SDK is not built on
     * Spring's HTTP clients and therefore cannot take the builders above.
     *
     * <p>Google's GenAI SDK ({@code GeminiAgentChatClientFactory}/{@code
     * VertexAiChatClientFactory}) is the case this exists for. It has exactly
     * ONE knob - {@code HttpOptions#timeout}, applied to OkHttp's connect,
     * read AND write timeouts alike - so the split connect/read pair above
     * cannot be expressed there and the read value is used for all three.
     * Worth knowing why that is not merely a nicety: {@code
     * com.google.genai.ApiClient} explicitly calls {@code connectTimeout(0)} /
     * {@code readTimeout(0)} / {@code writeTimeout(0)} on its OkHttp client
     * before applying {@code HttpOptions}, and zero means "no timeout" in
     * OkHttp - so with {@code HttpOptions} unset, Gemini and Vertex were not
     * falling back to OkHttp's usual 10s defaults, they were genuinely
     * unbounded. Verified against google-genai 1.37.0's bytecode, 2026-07-29.
     */
    public Duration readTimeout() {
        return readTimeout;
    }

    // ---- SSRF-guarded transports for TENANT-supplied base URLs (security review SEC-023) ----

    /** Spring injects the configured guard (operator allow-list); unset means the process-wide default. */
    @Autowired(required = false)
    void setSsrfGuard(SsrfGuard ssrfGuard) {
        this.ssrfGuard = ssrfGuard;
    }

    /** The guard every tenant-supplied LLM base URL is checked against. */
    public SsrfGuard ssrfGuard() {
        return ssrfGuard != null ? ssrfGuard : SsrfGuard.defaultGuard();
    }

    /**
     * Use-time check of a tenant-supplied base URL (BYOK Ollama / Azure OpenAI
     * endpoint): resolves the host and refuses any internal address, compose
     * name or reserved range. Throws {@link IllegalArgumentException} with the
     * guard's caller-safe reason (never the resolved address).
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
     * callers can tell this deterministic configuration error from a transient failure: it is
     * never worth a retry or a fallback provider, and a background job fails fast with a clear code.
     */
    public static final class ModelServerUrlNotAllowedException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public ModelServerUrlNotAllowedException(String message, Throwable cause) {
            super(message, cause);
        }

        /** The first {@code ModelServerUrlNotAllowedException} in {@code failure}'s cause chain, or null. */
        public static ModelServerUrlNotAllowedException find(Throwable failure) {
            int depth = 0;
            for (Throwable t = failure; t != null && depth++ < 16; t = t.getCause() == t ? null : t.getCause()) {
                if (t instanceof ModelServerUrlNotAllowedException blocked) {
                    return blocked;
                }
            }
            return null;
        }
    }

    /**
     * {@link #restClientBuilder()} for a TENANT-supplied base URL. Same
     * timeouts, pool size and idle eviction, but the Apache client resolves
     * every host through {@link SsrfGuard#dnsResolver()} - it connects only to
     * addresses validated in that same lookup, so a DNS answer that changes
     * after {@link #requireAllowedTenantBaseUrl} cannot move the socket inside
     * the network - uses no system proxy, and follows NO redirects (a 3xx is
     * returned as an error, never a hop that carries the tenant's key to
     * another host).
     */
    public RestClient.Builder guardedRestClientBuilder() {
        return autoConfiguredRestClientBuilder.clone().requestFactory(guardedRequestFactory());
    }

    /**
     * {@link #webClientBuilder()} for a TENANT-supplied base URL: redirects
     * NEVER, no proxy, and - unlike the pre-{@code GuardedJdkHttpClient}
     * JDK-only transport - the socket resolves through {@link
     * SsrfGuard#dnsResolver()}, so a DNS answer that flips after
     * {@link #requireAllowedTenantBaseUrl} cannot move the stream inside the
     * network. Callers must still run the pre-check so a refused host fails
     * fast with the caller-safe reason instead of a connect error.
     */
    public WebClient.Builder guardedWebClientBuilder() {
        return WebClient.builder().filter(OllamaToolCallParseRetry.filter()).clientConnector(guardedStreamingConnector());
    }

    private synchronized ClientHttpRequestFactory guardedRequestFactory() {
        if (guardedRequestFactory == null) {
            guardedRequestFactory = new HttpComponentsClientHttpRequestFactory(buildGuardedClient(
                ssrfGuard(), connectTimeout, readTimeout, maxConnectionsPerRoute, maxConnectionsTotal,
                idleConnectionTimeout));
        }
        return guardedRequestFactory;
    }

    private synchronized ClientHttpConnector guardedStreamingConnector() {
        if (guardedStreamingConnector == null) {
            JdkClientHttpConnector connector = new JdkClientHttpConnector(
                GuardedJdkHttpClient.create(ssrfGuard(), connectTimeout, readTimeout, false));
            connector.setReadTimeout(readTimeout);
            guardedStreamingConnector = connector;
        }
        return guardedStreamingConnector;
    }

    /** The guarded Apache client behind {@link #guardedRestClientBuilder()}. Package-private for tests. */
    static CloseableHttpClient buildGuardedClient(
        SsrfGuard guard, Duration connectTimeout, Duration readTimeout, int maxPerRoute, int maxTotal,
        Duration idleConnectionTimeout
    ) {
        return SafeHttpClients.create(guard.dnsResolver(), connectTimeout, readTimeout, false,
            maxPerRoute, maxTotal, idleConnectionTimeout);
    }
}
