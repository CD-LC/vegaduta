package com.agenticai.core.agents.a2a;

import com.agenticai.core.security.net.ReservedAddressRanges;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * Egress policy for the outbound A2A client: an operator allow-list FIRST, then
 * an SSRF check on what the host actually resolves to.
 *
 * <ul>
 *   <li>https only. Plain http is accepted solely for a loopback host and only
 *       when {@code app.a2a.client.allow-insecure-loopback} is on (tests/dev).</li>
 *   <li>No userinfo ({@code https://trusted@evil} confusion).</li>
 *   <li>The host must match {@code allowed-hosts} (exact, or {@code *.suffix}
 *       for subdomains only). An empty list allows NOTHING - this is an
 *       allow-list, not a deny-list: the model chooses the URL, so the operator
 *       chooses the universe.</li>
 *   <li>Every resolved address must be public (loopback, RFC1918, link-local,
 *       CGNAT, metadata ranges, v6 unique-local all refuse) - an allow-listed
 *       name that DNS points inside the network is still refused.</li>
 *   <li>Accepted limitation, same as {@code fetch_web_page}: DNS is resolved
 *       separately from the connection, so a rebinding host could answer
 *       differently at connect time. Redirects are never followed (the client
 *       sets {@code Redirect.NEVER}), which removes the cheap variant.</li>
 * </ul>
 */
final class A2aEgressGuard {

    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final int MAX_URL_LENGTH = 4_096;

    private final A2aProperties.Client config;
    private final Resolver resolver;

    A2aEgressGuard(A2aProperties.Client config) {
        this(config, InetAddress::getAllByName);
    }

    A2aEgressGuard(A2aProperties.Client config, Resolver resolver) {
        this.config = config;
        this.resolver = resolver;
    }

    /** Validates and returns the URI, or throws a reasoned {@link A2aClientException}. */
    URI check(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new A2aClientException("no URL was given");
        }
        if (raw.length() > MAX_URL_LENGTH) {
            throw new A2aClientException("the URL is too long");
        }
        URI uri;
        try {
            uri = new URI(raw.strip());
        } catch (URISyntaxException e) {
            throw new A2aClientException("the URL is not a valid web address");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new A2aClientException("the URL has no host");
        }
        if (uri.getUserInfo() != null) {
            throw new A2aClientException("URLs with embedded credentials are not allowed");
        }
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new A2aClientException("only https URLs are allowed");
        }
        if (!hostAllowed(host)) {
            throw new A2aClientException("the host \"" + host
                + "\" is not on the allowed A2A hosts list (app.a2a.client.allowed-hosts)");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new A2aClientException("the host \"" + host + "\" could not be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw new A2aClientException("the host \"" + host + "\" could not be resolved");
        }
        boolean allLoopback = true;
        for (InetAddress address : addresses) {
            allLoopback &= address.isLoopbackAddress();
            if (isNonPublic(address) && !(config.isAllowInsecureLoopback() && address.isLoopbackAddress())) {
                throw new A2aClientException("the URL points inside the platform's own network");
            }
        }
        if (scheme.equals("http") && !(config.isAllowInsecureLoopback() && allLoopback)) {
            throw new A2aClientException("only https URLs are allowed");
        }
        return uri;
    }

    boolean hostAllowed(String host) {
        List<String> allowed = config.getAllowedHosts();
        if (allowed == null || allowed.isEmpty()) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        for (String entry : allowed) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String e = entry.strip().toLowerCase(Locale.ROOT);
            if (e.startsWith("*.")) {
                // "*.example.com" matches "a.example.com", never "example.com" itself.
                if (h.endsWith(e.substring(1))) {
                    return true;
                }
            } else if (h.equals(e)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isNonPublic(InetAddress address) {
        return address.isLoopbackAddress()
            || address.isSiteLocalAddress()
            || address.isLinkLocalAddress()
            || address.isAnyLocalAddress()
            || address.isMulticastAddress()
            || ReservedAddressRanges.contains(address)
            || (address.getAddress().length == 16 && (address.getAddress()[0] & 0xFE) == 0xFC);
    }
}
