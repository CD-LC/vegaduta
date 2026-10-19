package com.agenticai.core.security.net;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.DnsResolver;
import org.springframework.lang.Nullable;

/**
 * The one outbound-request (SSRF) guard for tenant-, LLM- or internet-supplied
 * URLs (security review SEC-008/023/026/027/048).
 *
 * <p>Before this class, six guards each carried their own address list and
 * each validated a URL and then let the HTTP client resolve the host a second
 * time. That leaves two gaps this class closes:
 *
 * <ul>
 *   <li><b>Incomplete ranges.</b> {@link #isBlockedAddress} is the single
 *       classification: loopback, unspecified, RFC 1918, link-local
 *       (169.254/16, which holds every cloud metadata endpoint), multicast,
 *       everything in {@link ReservedAddressRanges} (100.64/10, 0/8,
 *       192.0.0/24, 198.18/15, 240/4, Azure WireServer 168.63.129.16,
 *       fc00::/7, NAT64), plus IPv6 forms that embed an IPv4 address
 *       (IPv4-mapped, IPv4-compatible, 6to4) which are decoded and classified
 *       as the IPv4 address they reach, and Teredo, which tunnels to an
 *       arbitrary IPv4 host and is refused outright.</li>
 *   <li><b>Validate-then-reconnect (DNS rebinding).</b> {@link #dnsResolver()}
 *       plugs the same check into Apache HttpClient 5 as its DNS resolver. The
 *       client then connects only to the addresses that were validated in that
 *       same lookup: the host is resolved once, every address is checked, and
 *       the socket goes to one of those checked addresses. A second, different
 *       answer from the DNS server never reaches a socket. Because every new
 *       connection goes through the resolver, a redirect to another host is
 *       re-validated as well. {@link SafeHttpClients} builds clients on top of
 *       this.</li>
 * </ul>
 *
 * <p>Callers that still use {@code java.net.http.HttpClient} (which has no
 * pluggable resolver) can call {@link #validate} before each request and
 * {@link #resolveRedirect} on each hop. That covers ranges and redirects but
 * not rebinding between the check and the connect; those call sites say so.
 *
 * <p><b>Operator escape hatch.</b> Self-hosted installs legitimately point a
 * BYOK Ollama base URL or an internal MCP server at a private host. The
 * operator (never a tenant) lists such hosts in
 * {@code app.security.outbound.allowed-private-hosts} ({@code host} or
 * {@code host:port}); see {@link SsrfGuardConfiguration}. A listed host skips
 * the address checks. Nothing is listed by default.
 */
public final class SsrfGuard {

    /** Looks up every address of a host name. A test seam; production uses the system resolver. */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** Refusal from the guard's DNS resolver. A subtype of {@link UnknownHostException} so HTTP clients fail the connect cleanly. */
    public static final class BlockedAddressException extends UnknownHostException {
        private static final long serialVersionUID = 1L;

        public BlockedAddressException(String message) {
            super(message);
        }
    }

    private static final int MAX_URL_LENGTH = 4_096;
    private static final Set<String> BLOCKED_HOST_NAMES = Set.of(
        "localhost", "localhost.localdomain", "ip6-localhost", "ip6-loopback",
        "host.docker.internal", "gateway.docker.internal", "metadata", "metadata.google.internal",
        "metadata.azure.com", "instance-data", "kubernetes", "kubernetes.default");
    private static final List<String> BLOCKED_HOST_SUFFIXES = List.of(
        ".localhost", ".local", ".internal", ".localdomain", ".home.arpa", ".cluster.local", ".svc");

    private static volatile SsrfGuard defaultGuard = new SsrfGuard(Set.of(), Set.of(), InetAddress::getAllByName);

    private final Set<String> allowedPrivateHosts;
    private final Set<String> egressAllowList;
    private final HostResolver resolver;

    private SsrfGuard(Set<String> allowedPrivateHosts, Set<String> egressAllowList, HostResolver resolver) {
        this.allowedPrivateHosts = allowedPrivateHosts;
        this.egressAllowList = egressAllowList;
        this.resolver = resolver;
    }

    /** The process-wide guard, carrying the operator's allow-list once {@link SsrfGuardConfiguration} has run. */
    public static SsrfGuard defaultGuard() {
        return defaultGuard;
    }

    /** Installs the process-wide guard. Called once at startup by {@link SsrfGuardConfiguration}. */
    static void installDefault(SsrfGuard guard) {
        defaultGuard = Objects.requireNonNull(guard);
    }

    /** A guard with no operator allow-list and the system resolver. */
    public static SsrfGuard strict() {
        return new SsrfGuard(Set.of(), Set.of(), InetAddress::getAllByName);
    }

    /** A guard with an explicit operator allow-list and resolver (tests, or callers with their own config). */
    public static SsrfGuard of(Set<String> allowedPrivateHosts, HostResolver resolver) {
        Set<String> normalised = allowedPrivateHosts.stream()
            .filter(s -> s != null && !s.isBlank())
            .map(s -> stripBrackets(s.strip().toLowerCase(Locale.ROOT)))
            .collect(Collectors.toUnmodifiableSet());
        return new SsrfGuard(normalised, Set.of(), Objects.requireNonNull(resolver));
    }

    /**
     * Like {@link #of(Set, HostResolver)} plus an operator EGRESS allow-list
     * (OWASP ASI02/LLM06: an injected agent can only call out to hosts the
     * operator named). Entries are {@code host} (exact) or {@code *.suffix}
     * (any subdomain, not the apex). Empty means "any public host", the
     * default. Operator-allowed private hosts are exempt: the operator already
     * vouched for them. The private-address checks still apply to everything
     * that passes the list.
     */
    public static SsrfGuard of(Set<String> allowedPrivateHosts, Set<String> egressAllowList, HostResolver resolver) {
        SsrfGuard base = of(allowedPrivateHosts, resolver);
        Set<String> egress = egressAllowList.stream()
            .filter(s -> s != null && !s.isBlank())
            .map(s -> s.strip().toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
        return new SsrfGuard(base.allowedPrivateHosts, egress, base.resolver);
    }

    /** The operator egress allow-list, sorted; empty when none is configured (any public host). */
    public List<String> egressAllowList() {
        return egressAllowList.stream().sorted().toList();
    }

    /** The operator's private-host exceptions, sorted. */
    public List<String> allowedPrivateHosts() {
        return allowedPrivateHosts.stream().sorted().toList();
    }

    /** True when {@code host} may be reached under the egress allow-list (always true when none is configured). */
    boolean egressPermits(String host) {
        if (egressAllowList.isEmpty()) {
            return true;
        }
        for (String entry : egressAllowList) {
            if (entry.startsWith("*.")) {
                if (host.endsWith(entry.substring(1)) && host.length() > entry.length() - 1) {
                    return true;
                }
            } else if (entry.equals(host)) {
                return true;
            }
        }
        return false;
    }

    private void requireEgressPermitted(String host) {
        if (!egressPermits(host)) {
            throw new SsrfBlockedException("the host is not on the outbound allow-list");
        }
    }

    /** Same guard, different resolver. */
    public SsrfGuard withResolver(HostResolver otherResolver) {
        return new SsrfGuard(allowedPrivateHosts, egressAllowList, Objects.requireNonNull(otherResolver));
    }

    /** A validated destination: the parsed URI plus the exact addresses that passed the check. */
    public record Target(URI uri, String host, int port, List<InetAddress> addresses, boolean operatorAllowed) {
    }

    /** Parses and fully validates {@code rawUrl}. Throws {@link SsrfBlockedException} with a caller-safe reason. */
    public Target validate(@Nullable String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new SsrfBlockedException("no URL was given");
        }
        if (rawUrl.length() > MAX_URL_LENGTH) {
            throw new SsrfBlockedException("the URL is too long");
        }
        URI uri;
        try {
            uri = new URI(rawUrl.strip());
        } catch (URISyntaxException e) {
            throw new SsrfBlockedException("the URL is not a valid web address");
        }
        return validate(uri);
    }

    /** Validates an already-parsed URI (scheme, user-info, host name rules, every resolved address). */
    public Target validate(URI uri) {
        return validate(uri, true);
    }

    /**
     * The same checks as {@link #validate(String)} except that a host NAME is
     * never looked up: IP literals are still classified, names get only the
     * host-name rules (internal suffixes, single-label compose names,
     * localhost, metadata names). For hot paths that must not fail on a DNS
     * hiccup (for example re-checking a stored row on every request) where the
     * connection itself is separately guarded or was validated at save time.
     * Never a substitute for {@link #validate} or a guarded client on its own.
     */
    public Target validateWithoutDns(@Nullable String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new SsrfBlockedException("no URL was given");
        }
        if (rawUrl.length() > MAX_URL_LENGTH) {
            throw new SsrfBlockedException("the URL is too long");
        }
        URI uri;
        try {
            uri = new URI(rawUrl.strip());
        } catch (URISyntaxException e) {
            throw new SsrfBlockedException("the URL is not a valid web address");
        }
        return validate(uri, false);
    }

    private Target validate(URI uri, boolean resolveNames) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new SsrfBlockedException("only http and https URLs are allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw new SsrfBlockedException("URLs with embedded credentials are not allowed");
        }
        String rawHost = uri.getHost();
        if (rawHost == null || rawHost.isBlank()) {
            throw new SsrfBlockedException("the URL has no host");
        }
        int port = uri.getPort() >= 0 ? uri.getPort() : (scheme.equals("https") ? 443 : 80);
        if (port < 1 || port > 65_535) {
            throw new SsrfBlockedException("the URL has an invalid port");
        }
        String host = normaliseHost(rawHost);
        if (isOperatorAllowed(host, port)) {
            return new Target(uri, host, port, List.of(), true);
        }
        requireEgressPermitted(host);
        List<InetAddress> addresses = resolveAndCheck(host, resolveNames);
        return new Target(uri, host, port, addresses, false);
    }

    /**
     * Resolves a redirect {@code Location} against the hop it came from and
     * fully re-validates the result. Scheme-relative locations
     * ({@code //other-host/x}) are resolved like a browser would and then
     * validated like any other URL.
     */
    public Target resolveRedirect(URI from, @Nullable String location) {
        if (location == null || location.isBlank()) {
            throw new SsrfBlockedException("the server redirected without saying where to");
        }
        URI next;
        try {
            next = from.resolve(new URI(location.strip()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            throw new SsrfBlockedException("the server redirected to an invalid address");
        }
        return validate(next);
    }

    /** True when both URIs share scheme, host and effective port. */
    public static boolean sameOrigin(URI a, URI b) {
        String sa = a.getScheme() == null ? "" : a.getScheme().toLowerCase(Locale.ROOT);
        String sb = b.getScheme() == null ? "" : b.getScheme().toLowerCase(Locale.ROOT);
        String ha = a.getHost() == null ? "" : normaliseHost(a.getHost());
        String hb = b.getHost() == null ? "" : normaliseHost(b.getHost());
        return sa.equals(sb) && ha.equals(hb) && effectivePort(a) == effectivePort(b);
    }

    /**
     * An Apache HttpClient 5 resolver that applies this guard at connect time.
     * The client connects only to the addresses returned here, so validation
     * and connection use the same lookup.
     */
    public DnsResolver dnsResolver() {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                String h = normaliseHost(host);
                // The resolver sees no port, so a "host:port" allow-list entry
                // admits the host here; the port half is enforced by
                // validate(), which callers run on the URL before connecting.
                if (isOperatorAllowedHost(h)) {
                    return resolver.resolve(h);
                }
                try {
                    requireEgressPermitted(h);
                    return resolveAndCheck(h, true).toArray(InetAddress[]::new);
                } catch (SsrfBlockedException e) {
                    throw new BlockedAddressException("refused outbound connection: " + e.getMessage());
                }
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                // No reverse lookup: the canonical name is never used for a trust decision here.
                return host;
            }
        };
    }

    // ------------------------------------------------------------------

    private List<InetAddress> resolveAndCheck(String host, boolean resolveNames) {
        boolean literal = isIpLiteral(host);
        if (!literal) {
            checkHostName(host);
            if (!resolveNames) {
                return List.of();
            }
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new SsrfBlockedException("the host could not be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw new SsrfBlockedException("the host could not be resolved");
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new SsrfBlockedException("the URL points to a private or internal network address");
            }
        }
        return List.of(addresses);
    }

    private static void checkHostName(String host) {
        if (BLOCKED_HOST_NAMES.contains(host)) {
            throw new SsrfBlockedException("the URL points to an internal host name");
        }
        for (String suffix : BLOCKED_HOST_SUFFIXES) {
            if (host.endsWith(suffix)) {
                throw new SsrfBlockedException("the URL points to an internal host name");
            }
        }
        if (host.indexOf('.') < 0) {
            // Single-label names ("core", "keycloak", "postgres") only resolve
            // inside a container or cluster network.
            throw new SsrfBlockedException("the URL points to an internal host name");
        }
    }

    private boolean isOperatorAllowedHost(String host) {
        if (allowedPrivateHosts.contains(host)) {
            return true;
        }
        for (String entry : allowedPrivateHosts) {
            int colon = entry.lastIndexOf(':');
            // host:port entries only (a bare IPv6 literal has several colons)
            if (colon > 0 && entry.indexOf(':') == colon && entry.substring(0, colon).equals(host)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOperatorAllowed(String host, int port) {
        return !allowedPrivateHosts.isEmpty()
            && (allowedPrivateHosts.contains(host) || allowedPrivateHosts.contains(host + ":" + port));
    }

    /**
     * The shared classification. True for every address an outbound request on
     * behalf of a tenant, an LLM or the internet must never reach.
     */
    public static boolean isBlockedAddress(InetAddress address) {
        if (address instanceof Inet6Address) {
            byte[] b = address.getAddress();
            InetAddress embedded = embeddedIpv4(b);
            if (embedded != null && isBlockedAddress(embedded)) {
                return true;
            }
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            int b2 = b[2] & 0xff;
            int b3 = b[3] & 0xff;
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00) {
                return true; // 2001::/32 Teredo: tunnels to an arbitrary IPv4 host
            }
            if (b0 == 0x20 && b1 == 0x01 && b2 == 0x0d && b3 == 0xb8) {
                return true; // 2001:db8::/32 documentation
            }
            if (b0 == 0x00 && b1 == 0x64 && b2 == 0xff && b3 == 0x9b) {
                return true; // 64:ff9b::/96 and 64:ff9b:1::/48 NAT64
            }
            if (b0 == 0x01 && b1 == 0x00 && allZero(b, 2, 8)) {
                return true; // 100::/64 discard
            }
            if ((b0 & 0xff) == 0xfe && (b1 & 0xc0) == 0xc0) {
                return true; // fec0::/10 deprecated site-local
            }
        }
        return address.isLoopbackAddress()
            || address.isAnyLocalAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()
            || ReservedAddressRanges.contains(address);
    }

    /** The IPv4 address an IPv6 address embeds (mapped, compatible or 6to4), or null. */
    static @Nullable InetAddress embeddedIpv4(byte[] b) {
        if (b.length != 16) {
            return null;
        }
        try {
            // ::ffff:a.b.c.d (mapped) and ::a.b.c.d (compatible, deprecated)
            if (allZero(b, 0, 10)
                && (((b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) || (b[10] == 0 && b[11] == 0))) {
                if (b[10] == 0 && allZero(b, 12, 15) && ((b[15] & 0xff) <= 1)) {
                    return null; // :: and ::1 are classified natively
                }
                return Inet4Address.getByAddress(new byte[] {b[12], b[13], b[14], b[15]});
            }
            // 2002:AABB:CCDD::/48 (6to4)
            if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) {
                return Inet4Address.getByAddress(new byte[] {b[2], b[3], b[4], b[5]});
            }
        } catch (UnknownHostException e) {
            return null;
        }
        return null;
    }

    private static boolean allZero(byte[] b, int from, int toExclusive) {
        for (int i = from; i < toExclusive; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        // Dotted, decimal, octal or hex IPv4 spellings are all handed to the
        // resolver, which parses them locally; the resulting address is what
        // gets classified. Anything made only of digits, dots and x/a-f hex
        // digits with no letters beyond that is treated as a literal.
        return host.chars().allMatch(c -> Character.isDigit(c) || c == '.')
            || host.toLowerCase(Locale.ROOT).matches("(0x[0-9a-f]+\\.?)+");
    }

    static String normaliseHost(String rawHost) {
        String host = stripBrackets(rawHost.strip()).toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (!isIpLiteral(host)) {
            try {
                host = IDN.toASCII(host, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                throw new SsrfBlockedException("the URL host is not a valid host name");
            }
        }
        return host;
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        return scheme.equals("https") ? 443 : scheme.equals("http") ? 80 : -1;
    }
}
