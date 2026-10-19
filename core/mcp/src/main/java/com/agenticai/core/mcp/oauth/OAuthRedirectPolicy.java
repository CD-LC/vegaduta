package com.agenticai.core.mcp.oauth;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The two redirect decisions of the provider OAuth flow, as pure functions:
 *
 * <ol>
 *   <li><b>The {@code redirect_uri} sent to the provider</b> - never taken from a
 *       request. It is this deployment's configured callback base plus a fixed
 *       path, and must be https (plain http only for a loopback host, so a
 *       developer machine works). A provider also enforces its own registered
 *       redirect-URI list; this is the platform-side half.</li>
 *   <li><b>Where the browser lands afterwards</b> (the console page). The
 *       authorize call may ask for a return URL; it is honoured only when its
 *       origin is on the allow-list (the configured console redirect's origin
 *       plus {@code app.mcp.oauth.provider-allowed-return-origins}). Anything
 *       else is rejected, so the callback can never become an open redirect.</li>
 * </ol>
 */
public final class OAuthRedirectPolicy {

    private final String defaultReturnUrl;
    private final List<String> allowedOrigins;

    /**
     * @param defaultReturnUrl   where the browser lands when the caller asks for nothing in particular
     * @param extraAllowedOrigins comma-separated origins (scheme://host[:port]) beyond the default's own; blank for none
     */
    public OAuthRedirectPolicy(String defaultReturnUrl, String extraAllowedOrigins) {
        this.defaultReturnUrl = defaultReturnUrl == null ? "" : defaultReturnUrl.trim();
        List<String> origins = new ArrayList<>();
        String own = originOf(this.defaultReturnUrl);
        if (own != null) {
            origins.add(own);
        }
        if (extraAllowedOrigins != null) {
            for (String raw : extraAllowedOrigins.split(",")) {
                String o = originOf(raw.trim());
                if (o != null && !origins.contains(o)) {
                    origins.add(o);
                }
            }
        }
        this.allowedOrigins = List.copyOf(origins);
    }

    public String defaultReturnUrl() {
        return defaultReturnUrl;
    }

    /**
     * @param requested the caller's requested return URL (may be null/blank)
     * @return the URL to store in the OAuth state: {@code requested} if allowed, else the default
     * @throws IllegalArgumentException if a return URL was requested but its origin is not allow-listed
     */
    public String resolveReturnUrl(String requested) {
        if (requested == null || requested.isBlank()) {
            return defaultReturnUrl;
        }
        String origin = originOf(requested.trim());
        if (origin == null || !allowedOrigins.contains(origin)) {
            throw new IllegalArgumentException("returnTo is not an allowed console URL.");
        }
        return requested.trim();
    }

    /** True for an https URL, or http on localhost / 127.0.0.1 / ::1 (developer machines only). */
    public static boolean isAcceptableRedirectUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return false;
        }
        URI parsed;
        try {
            parsed = URI.create(uri.trim());
        } catch (IllegalArgumentException e) {
            return false;
        }
        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        if (scheme == null || host == null || parsed.getUserInfo() != null || parsed.getFragment() != null) {
            return false;
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return true;
        }
        String h = host.toLowerCase(Locale.ROOT);
        return "http".equalsIgnoreCase(scheme) && ("localhost".equals(h) || "127.0.0.1".equals(h) || "[::1]".equals(h));
    }

    /** {@code scheme://host[:port]} lower-cased, or null if {@code url} is not an absolute http(s) URL without credentials. */
    static String originOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        URI parsed;
        try {
            parsed = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = parsed.getScheme();
        String host = parsed.getHost();
        if (scheme == null || host == null || parsed.getUserInfo() != null) {
            return null;
        }
        String s = scheme.toLowerCase(Locale.ROOT);
        if (!"https".equals(s) && !"http".equals(s)) {
            return null;
        }
        int port = parsed.getPort();
        return s + "://" + host.toLowerCase(Locale.ROOT) + (port == -1 ? "" : ":" + port);
    }
}
