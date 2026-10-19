package com.agenticai.core.security.net;

import java.time.Duration;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.DefaultRedirectStrategy;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultRoutePlanner;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * HTTP clients whose every connection goes through {@link SsrfGuard#dnsResolver()}:
 * the host is resolved once, every address is validated, and the socket goes to
 * one of the validated addresses (no DNS-rebinding window). No proxy is used,
 * so a system {@code HTTP(S)_PROXY} cannot route around the check.
 *
 * <p>Redirects are OFF unless the caller asks for them. When they are on, each
 * hop to a new host opens a new connection and so is validated by the same
 * resolver; callers that send credentials must keep them off and follow
 * redirects by hand so they can drop credential headers when the origin
 * changes.
 */
public final class SafeHttpClients {

    private SafeHttpClients() {
    }

    /** A pooled client guarded by {@code guard}. The caller owns and closes it. */
    public static CloseableHttpClient create(SsrfGuard guard, Duration connectTimeout,
                                             Duration responseTimeout, boolean followRedirects) {
        return create(guard.dnsResolver(), connectTimeout, responseTimeout, followRedirects);
    }

    /**
     * A pooled client whose connections resolve through {@code resolver}. Used by
     * transports that must read {@link SsrfGuard#defaultGuard()} lazily (a field
     * or bean-ctor initializer would otherwise capture the pre-configuration
     * strict guard and never see the operator's allow-list).
     */
    public static CloseableHttpClient create(
            org.apache.hc.client5.http.DnsResolver resolver, Duration connectTimeout,
            Duration responseTimeout, boolean followRedirects) {
        return create(resolver, connectTimeout, responseTimeout, followRedirects, 20, 200, null);
    }

    /**
     * Same, with an explicitly sized pool and optional idle-connection eviction -
     * for callers like the LLM factory that need their own pool numbers.
     */
    public static CloseableHttpClient create(
            org.apache.hc.client5.http.DnsResolver resolver, Duration connectTimeout,
            Duration responseTimeout, boolean followRedirects,
            int maxPerRoute, int maxTotal, Duration idleEvict) {
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(resolver)
            .setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(connectTimeout))
                .setSocketTimeout(Timeout.of(responseTimeout))
                .build())
            .setMaxConnTotal(maxTotal)
            .setMaxConnPerRoute(maxPerRoute)
            .build();
        HttpClientBuilder builder = HttpClients.custom()
            .setConnectionManager(connectionManager)
            // Direct connections only: never a system/environment proxy.
            .setRoutePlanner(new DefaultRoutePlanner(null))
            .setDefaultRequestConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.of(responseTimeout))
                .setMaxRedirects(5)
                .build())
            .disableAutomaticRetries();
        if (followRedirects) {
            builder.setRedirectStrategy(DefaultRedirectStrategy.INSTANCE);
        } else {
            builder.disableRedirectHandling();
        }
        if (idleEvict != null && !idleEvict.isZero() && !idleEvict.isNegative()) {
            builder.evictIdleConnections(org.apache.hc.core5.util.TimeValue.ofMilliseconds(idleEvict.toMillis()));
        }
        return builder.build();
    }

    /** A Spring request factory (for {@code RestClient} / {@code RestTemplate}) over a guarded client. */
    public static ClientHttpRequestFactory requestFactory(SsrfGuard guard, Duration connectTimeout,
                                                          Duration responseTimeout, boolean followRedirects) {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(
            create(guard, connectTimeout, responseTimeout, followRedirects));
        factory.setConnectionRequestTimeout(connectTimeout);
        return factory;
    }
}
