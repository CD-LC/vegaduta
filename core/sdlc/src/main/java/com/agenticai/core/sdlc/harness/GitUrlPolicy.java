package com.agenticai.core.sdlc.harness;

import com.agenticai.core.security.net.ReservedAddressRanges;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Egress guard for the repository a task asks the sandbox to clone: public {@code https} only, a DNS
 * name (no IP literal, no single-label host), default port, no credentials, a plain path, and every
 * address the name resolves to must be public ({@link ReservedAddressRanges} plus the JDK's own
 * loopback/site-local/link-local checks, the same predicate the A2A egress guard uses).
 *
 * <p>Accepted limitation, same as the other egress guards: the name is resolved here and again by
 * the sandbox, so a rebinding host could answer differently. The clone runs in a disposable sandbox
 * with no host secrets and no route to the platform's private network, which bounds what a rebinding
 * answer could reach; an optional allow-list ({@code app.coding-harness.git-allowed-hosts}) narrows
 * it further.
 */
public final class GitUrlPolicy {

    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final int MAX_URL_CHARS = 300;
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9._~\\-]+(/[A-Za-z0-9._~\\-]+){0,5}");
    private static final Pattern HOST = Pattern.compile("([a-z0-9]([a-z0-9\\-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}");

    private final List<String> allowedHosts;
    private final Resolver resolver;

    public GitUrlPolicy(Collection<String> allowedHosts) {
        this(allowedHosts, InetAddress::getAllByName);
    }

    public GitUrlPolicy(Collection<String> allowedHosts, Resolver resolver) {
        this.allowedHosts = allowedHosts == null ? List.of()
            : allowedHosts.stream().filter(h -> h != null && !h.isBlank())
                .map(h -> h.strip().toLowerCase(Locale.ROOT)).toList();
        this.resolver = resolver;
    }

    /**
     * @return the normalized https URL, safe to hand to {@code git clone}
     * @throws IllegalArgumentException with a user-safe reason
     */
    public String check(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("A repository URL is required.");
        }
        String trimmed = raw.strip();
        if (trimmed.length() > MAX_URL_CHARS) {
            throw new IllegalArgumentException("The repository URL is too long.");
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("The repository URL is not a valid web address.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Only public https repository URLs are allowed.");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("The repository URL must not contain credentials, a query or a fragment.");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw new IllegalArgumentException("Only the standard https port is allowed.");
        }
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("The repository host must be a public domain name.");
        }
        if (!allowedHosts.isEmpty() && allowedHosts.stream().noneMatch(a -> hostMatches(host, a))) {
            throw new IllegalArgumentException("That repository host is not on the allowed list.");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!PATH.matcher(path).matches() || path.contains("..")) {
            throw new IllegalArgumentException("The repository path is not valid.");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("The repository host could not be resolved.");
        }
        if (addresses == null || addresses.length == 0) {
            throw new IllegalArgumentException("The repository host could not be resolved.");
        }
        for (InetAddress address : addresses) {
            if (isNonPublic(address)) {
                throw new IllegalArgumentException("The repository URL points inside a private network.");
            }
        }
        return "https://" + host + path;
    }

    private static boolean hostMatches(String host, String entry) {
        if (entry.startsWith("*.")) {
            return host.endsWith(entry.substring(1));
        }
        return host.equals(entry);
    }

    static boolean isNonPublic(InetAddress address) {
        byte[] b = address.getAddress();
        return address.isLoopbackAddress()
            || address.isSiteLocalAddress()
            || address.isLinkLocalAddress()
            || address.isAnyLocalAddress()
            || address.isMulticastAddress()
            || ReservedAddressRanges.contains(address)
            || (b.length == 16 && (b[0] & 0xFE) == 0xFC);
    }
}
