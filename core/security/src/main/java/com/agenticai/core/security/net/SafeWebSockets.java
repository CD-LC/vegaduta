package com.agenticai.core.security.net;

import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * WebSocket plumbing for outbound connections to a URL an operator or tenant typed. The JDK's
 * {@link HttpClient} is the only WebSocket client in the platform, and it resolves names itself, so
 * this is the one place that builds it (the raw-client ban in {@code RawHttpClientBanRulesTest}
 * allows the {@code security.net} package only) and the one place that closes the DNS-rebinding
 * window around it:
 *
 * <ul>
 *   <li>{@link #client} has no proxy and never follows redirects;
 *   <li>{@link #pin} rewrites a cleartext {@code ws://} URI to the literal address {@link SsrfGuard}
 *       already validated, so the socket cannot go anywhere the guard did not see. A {@code wss://}
 *       URI keeps its host name: the TLS handshake then verifies the server's certificate for that
 *       name, which a rebinding answer cannot satisfy for an internal service.
 * </ul>
 */
public final class SafeWebSockets {

    private SafeWebSockets() {}

    /** A client for WebSocket connections: direct (no proxy), no redirects. The caller shuts it down. */
    public static HttpClient client(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.of(null))
                .build();
    }

    /**
     * The URI to connect to for {@code wsUri} (same host as {@code target}). For {@code ws://} the host
     * becomes the first address the guard validated; for {@code wss://} it is returned unchanged.
     */
    public static URI pin(SsrfGuard.Target target, URI wsUri) {
        if (!"ws".equalsIgnoreCase(wsUri.getScheme()) || target.addresses() == null || target.addresses().isEmpty()) {
            return wsUri;
        }
        InetAddress address = target.addresses().get(0);
        String literal = address.getHostAddress();
        int zone = literal.indexOf('%');
        if (zone >= 0) {
            literal = literal.substring(0, zone);
        }
        if (literal.indexOf(':') >= 0) {
            literal = "[" + literal + "]";
        }
        StringBuilder out = new StringBuilder("ws://");
        if (wsUri.getRawUserInfo() != null) {
            out.append(wsUri.getRawUserInfo()).append('@');
        }
        out.append(literal);
        if (wsUri.getPort() >= 0) {
            out.append(':').append(wsUri.getPort());
        }
        if (wsUri.getRawPath() != null) {
            out.append(wsUri.getRawPath());
        }
        if (wsUri.getRawQuery() != null) {
            out.append('?').append(wsUri.getRawQuery());
        }
        try {
            return new URI(out.toString());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Could not pin the WebSocket address", e);
        }
    }
}
