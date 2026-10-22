package com.agenticai.core.mcp;

import com.agenticai.core.security.net.SsrfGuard;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SSRF guard for remote (SSE / streamable-HTTP) MCP registrations (MCP-DOCTOR hardening,
 * 2026-07-25). A remote registration's URL is tenant-typed free text that this platform then
 * connects to FROM INSIDE the compose network - without this check a tenant could point a "remote
 * MCP server" at {@code http://postgres:5432}, {@code http://keycloak:8080}, the cloud metadata
 * endpoint, or core's own actuator, and use the MCP client as a proxy into infrastructure the
 * tenant must never reach.
 *
 * <p>Enforced at BOTH seams, fail-closed: registration save time ({@link
 * McpRegistryService#register}) and connect time ({@code DynamicMcpConnectionManager} in
 * core/agents - so a row written before this guard existed, or by any path that skips the service,
 * still can't be connected to).
 *
 * <p>{@link #check} is deliberately DNS-free (a resolution check at SAVE time would add TOCTOU
 * confusion and per-save latency for a check a re-resolving attacker could dodge anyway) and
 * covers:
 *
 * <ul>
 *   <li>non-http(s) schemes ({@code file:}, {@code ftp:}, {@code gopher:} ...)
 *   <li>URLs with userinfo ({@code http://safe@evil.example} confusion)
 *   <li>literal IPs in loopback / private / link-local (incl. cloud metadata 169.254.169.254) /
 *       unspecified ranges, both IPv4 and IPv6 (::1, fc00::/7 unique-local, fe80::/10 link-local) -
 *       including alternate numeral encodings of the same IPv4 addresses (decimal 32-bit integer,
 *       {@code 0x}-hex, 2-/3-part BSD shorthand - pentest M2)
 *   <li>hostnames on a configurable denylist seeded with every compose service name in this stack
 *       plus {@code localhost}/{@code *.localhost} - the "can't dodge by name instead of IP" half
 *       for the network this platform actually runs in
 * </ul>
 *
 * Legitimate public remote servers (e.g. {@code https://mcp.example.com/mcp} with an Authorization
 * env-header) pass untouched.
 *
 * <p><b>{@link #checkResolved} (pentest H2, DNS rebinding)</b> is the CONNECT/PROBE-time
 * counterpart: a public-looking hostname that {@link #check} allowed at save time can have its DNS
 * record repointed at a private IP by the time this platform actually connects - {@link #check}
 * alone cannot catch that since it never resolves anything. {@code DynamicMcpConnectionManager}
 * calls {@link #checkResolved} instead of {@link #check} at every connect/probe seam, and
 * re-invokes it again immediately before the real SDK connect for a readable error. The socket
 * itself is pinned separately: the SDK transports run on the SSRF-guarded client (see that method's
 * javadoc).
 */
@Component
public class McpRemoteUrlGuard {

    /**
     * Compose service names in deploy/docker-compose.yml - a URL naming any of these points inside
     * the platform's own network.
     */
    static final List<String> DEFAULT_DENYLIST =
            List.of(
                    "localhost",
                    "postgres",
                    "pgbouncer",
                    "redis",
                    "keycloak",
                    "core",
                    "sidecar",
                    "minio",
                    "redpanda",
                    "temporal",
                    "langfuse",
                    "langfuse-web",
                    "langfuse-worker",
                    "loki",
                    "grafana",
                    "prometheus",
                    "academy",
                    "caddy",
                    "code-intel",
                    "web",
                    "web-admin",
                    "clickhouse",
                    "cloudflared",
                    "metadata",
                    "metadata.google.internal",
                    // Pentest M3: Docker-Desktop host-gateway names reach the host.
                    "host.docker.internal",
                    "gateway.docker.internal");

    private final List<String> denylist;

    /** Built-in denylist only (manual construction; Spring uses the property constructor below). */
    public McpRemoteUrlGuard() {
        this("");
    }

    /**
     * Spring wiring - denylist extendable via {@code app.mcp.remote-url-denylist} (comma-separated
     * hostnames, ADDED to the built-in compose-service set).
     */
    // @Autowired is required: with a no-arg constructor also present Spring would pick THAT one
    // and silently ignore app.mcp.remote-url-denylist.
    @Autowired
    public McpRemoteUrlGuard(@Value("${app.mcp.remote-url-denylist:}") String extraDenylist) {
        List<String> extras =
                extraDenylist == null || extraDenylist.isBlank()
                        ? List.of()
                        : Arrays.stream(extraDenylist.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList();
        this.denylist = concat(DEFAULT_DENYLIST, extras);
    }

    /** Test seam - exact denylist, no property resolution. */
    public static McpRemoteUrlGuard withDenylist(List<String> denylist) {
        return new McpRemoteUrlGuard(denylist, true);
    }

    private McpRemoteUrlGuard(List<String> denylist, boolean exact) {
        this.denylist = List.copyOf(denylist);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        java.util.ArrayList<String> all = new java.util.ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    /**
     * Empty when the URL is allowed; otherwise a clear, user-facing reason it is blocked. Never
     * throws.
     */
    public Optional<String> check(String url) {
        if (url == null || url.isBlank()) {
            return Optional.of("Remote server URL is empty.");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            return Optional.of("Remote server URL is not a valid URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Optional.of(
                    "Only http and https URLs are allowed for remote MCP servers (got scheme '"
                            + (scheme.isEmpty() ? "none" : scheme)
                            + "').");
        }
        if (uri.getRawUserInfo() != null) {
            return Optional.of(
                    "URLs with embedded credentials (user@host) are not allowed - put credentials in the server's env entries as headers instead.");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return Optional.of("Remote server URL has no hostname.");
        }
        // URI#getHost keeps the brackets on an IPv6 literal - strip for checks.
        String bareHost =
                host.startsWith("[") && host.endsWith("]")
                        ? host.substring(1, host.length() - 1)
                        : host;
        String lowerHost = bareHost.toLowerCase(Locale.ROOT);
        // Pentest M1: a single trailing dot (FQDN root, `postgres.`) commonly
        // resolves to the same target but slips an equals/endsWith denylist.
        while (lowerHost.endsWith(".")) {
            lowerHost = lowerHost.substring(0, lowerHost.length() - 1);
        }

        for (String denied : denylist) {
            String d = denied.toLowerCase(Locale.ROOT);
            if (lowerHost.equals(d) || lowerHost.endsWith("." + d)) {
                return Optional.of(
                        "This URL points inside the platform's own network ('"
                                + bareHost
                                + "' is a platform-internal hostname) and cannot be used as a remote MCP server.");
            }
        }

        byte[] ipv4 = parseIpv4Literal(lowerHost);
        if (ipv4 != null && isBlockedIpv4(ipv4)) {
            return Optional.of(
                    "This URL points at a private, loopback, link-local, or metadata IP address ("
                            + bareHost
                            + ") inside the platform's own network and cannot be used as a remote MCP server.");
        }
        if (lowerHost.contains(":") && isBlockedIpv6Literal(lowerHost)) {
            return Optional.of(
                    "This URL points at a private, loopback, or link-local IPv6 address ("
                            + bareHost
                            + ") and cannot be used as a remote MCP server.");
        }
        return Optional.empty();
    }

    /**
     * Save/connect-time enforcement - throws with the user-facing reason (mapped to HTTP 400 by
     * GlobalExceptionHandler).
     */
    public void requireAllowed(String url) {
        check(url)
                .ifPresent(
                        reason -> {
                            throw new IllegalArgumentException(reason);
                        });
    }

    /**
     * Testable DNS seam for {@link #checkResolved} - production always resolves through real DNS
     * ({@link #SYSTEM_RESOLVER}); H2 regression tests inject a fake mapping (e.g. "a public-looking
     * hostname resolves to 169.254.169.254") to simulate a DNS-rebinding attacker without needing
     * real, attacker-controlled DNS infrastructure in a unit test.
     */
    interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final HostResolver SYSTEM_RESOLVER = InetAddress::getAllByName;

    /**
     * Pentest H2 (2026-07-25, DNS rebinding): {@link #check} is deliberately DNS-free (see class
     * javadoc) - a tenant can register a public-looking hostname whose DNS record is later pointed
     * at a private/metadata IP, defeating the entire string-based guard at the moment this platform
     * actually connects (the guard passed the public-looking name both at save time AND at a naive
     * connect-time re-check of the same string). This method runs {@link #check} first (still
     * rejects scheme abuse / userinfo / literal-IP / denylisted hostnames with no DNS lookup needed
     * for those), then - only for a real hostname that passed - resolves it and checks EVERY
     * returned A/AAAA address against the identical private-range logic used for literals ({@link
     * #isBlockedInetAddress}), so a rebind to ANY of the resolved addresses is caught, not just the
     * first.
     *
     * <p><b>Callers MUST use this (not {@link #check}) at connect/probe time</b> - see {@code
     * DynamicMcpConnectionManager}, which also re-invokes this again immediately before the real
     * SDK connect (after the diagnostic probe), specifically to shrink the remaining rebinding
     * window as much as this code can.
     *
     * <p>This is the early, user-friendly refusal. It does not itself pin the address a connection
     * goes to - the MCP SDK's transports open the socket later - but the transports are built on
     * the address-pinned client ({@code GuardedHttpClientBuilder}, wired in {@code
     * McpTransportFactory}), so the resolution that decides where the socket goes is itself
     * validated by {@code SsrfGuard} and a DNS answer that flips after this check cannot reach a
     * private address. Re-invoking this method just before the connect (as the caller does) stays
     * as the readable error.
     */
    public Optional<String> checkResolved(String url) {
        return checkResolved(url, SYSTEM_RESOLVER);
    }

    /** Test seam - see {@link HostResolver}. */
    Optional<String> checkResolved(String url, HostResolver resolver) {
        Optional<String> literal = check(url);
        if (literal.isPresent()) {
            return literal;
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            return Optional.of("Remote server URL is not a valid URL.");
        }
        String host = uri.getHost();
        String bareHost =
                host.startsWith("[") && host.endsWith("]")
                        ? host.substring(1, host.length() - 1)
                        : host;
        String lowerHost = bareHost.toLowerCase(Locale.ROOT);
        while (lowerHost.endsWith(".")) {
            lowerHost = lowerHost.substring(0, lowerHost.length() - 1);
        }
        // check() above already fully vetted any literal IP form (IPv4, including M2's
        // numeral-encoding bypasses, and IPv6) - nothing left to resolve for those; only a
        // real hostname needs a DNS lookup here.
        if (parseIpv4Literal(lowerHost) != null || lowerHost.contains(":")) {
            return Optional.empty();
        }
        try {
            InetAddress[] resolved = resolver.resolve(bareHost);
            for (InetAddress addr : resolved) {
                if (isBlockedInetAddress(addr)) {
                    // The resolved address is deliberately not echoed (SEC-027): the
                    // reason text must not map the internal network for the caller.
                    return Optional.of(
                            "This URL's hostname ('"
                                    + bareHost
                                    + "') resolves to a private, loopback, "
                                    + "link-local, or metadata address and cannot be used as a "
                                    + "remote MCP server (DNS rebinding protection).");
                }
            }
        } catch (UnknownHostException e) {
            // Fail-closed, same posture as an unparseable IPv6 literal elsewhere in this
            // class: if this platform can't verify where a hostname actually goes, it must
            // not connect to it.
            return Optional.of("This URL's hostname ('" + bareHost + "') could not be resolved.");
        }
        return Optional.empty();
    }

    /**
     * Literal IPv4 parse - no DNS is ever consulted here, only numeral parsing of the string
     * itself. Tries the canonical strict 4-octet dotted-decimal form first, then (pentest M2) the
     * alternate BSD {@code inet_aton}-style numeral encodings attackers use to smuggle a private IP
     * past a naive "must be exactly 4 small decimal octets" parser: a bare decimal or {@code
     * 0x}-hex 32-bit integer ({@code 2130706433} / {@code 0x7f000001} == 127.0.0.1), and 2-/3-part
     * dotted shorthand where the last part absorbs the remaining bytes ({@code 10.1} == 10.0.0.1,
     * {@code 172.16.5} == 172.16.0.5). Once {@link #checkResolved} (H2) is used at every
     * connect/probe seam this stops being the only line of defense for these forms - real DNS
     * resolution of a non-IP hostname would itself fail closed - but it still matters as
     * defense-in-depth for {@link #check}'s save-time-only callers (this class is deliberately
     * DNS-free at save time; see class javadoc), where no resolution ever happens to catch these
     * otherwise.
     */
    private static byte[] parseIpv4Literal(String host) {
        byte[] strict = parseStrictDottedQuad(host);
        if (strict != null) {
            return strict;
        }
        return parseAlternateIpv4Encoding(host);
    }

    private static byte[] parseStrictDottedQuad(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3 || !p.chars().allMatch(Character::isDigit)) {
                return null;
            }
            int v = Integer.parseInt(p);
            if (v > 255) {
                return null;
            }
            out[i] = (byte) v;
        }
        return out;
    }

    /**
     * Pentest M2 - see {@link #parseIpv4Literal}'s javadoc for exactly which alternate forms this
     * recognizes.
     */
    private static byte[] parseAlternateIpv4Encoding(String host) {
        String[] parts = host.split("\\.", -1);
        if (parts.length < 1 || parts.length > 3) {
            return null;
        }
        long[] vals = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            Long v = parseUnsignedNumeral(parts[i]);
            if (v == null) {
                return null;
            }
            vals[i] = v;
        }
        long addr;
        switch (parts.length) {
            case 1:
                if (vals[0] > 0xFFFFFFFFL) {
                    return null;
                }
                addr = vals[0];
                break;
            case 2:
                if (vals[0] > 0xFF || vals[1] > 0xFFFFFFL) {
                    return null;
                }
                addr = (vals[0] << 24) | vals[1];
                break;
            default: // 3 parts
                if (vals[0] > 0xFF || vals[1] > 0xFF || vals[2] > 0xFFFFL) {
                    return null;
                }
                addr = (vals[0] << 24) | (vals[1] << 16) | vals[2];
                break;
        }
        return new byte[] {
            (byte) ((addr >>> 24) & 0xFF), (byte) ((addr >>> 16) & 0xFF),
            (byte) ((addr >>> 8) & 0xFF), (byte) (addr & 0xFF)
        };
    }

    /**
     * One numeral segment of an alternate IPv4 encoding: plain decimal, or a {@code 0x}/{@code
     * 0X}-prefixed hex literal (the two forms pentest M2 calls out by name). Deliberately does NOT
     * reinterpret a bare leading zero as octal (unlike real {@code inet_aton}) - that would change
     * how already-covered 4-octet dotted-quad hosts with leading zeros are read (handled
     * separately, decimal-only, by {@link #parseStrictDottedQuad}) and isn't needed to close the
     * concrete bypasses this method exists for. Null for anything else - a real hostname label
     * ("example", "e1") must never be treated as a numeral.
     */
    private static Long parseUnsignedNumeral(String p) {
        if (p.isEmpty()) {
            return null;
        }
        try {
            if (p.length() > 2 && (p.startsWith("0x") || p.startsWith("0X"))) {
                return Long.parseUnsignedLong(p.substring(2), 16);
            }
            if (!p.chars().allMatch(Character::isDigit)) {
                return null;
            }
            return Long.parseUnsignedLong(p, 10);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * SEC-027: range classification is delegated to the one shared list in {@link
     * SsrfGuard#isBlockedAddress} (which adds Azure WireServer 168.63.129.16, 192.0.0.0/24,
     * 198.18.0.0/15, multicast, 240.0.0.0/4, NAT64 and IPv6 forms embedding any of them). This
     * class keeps only its own literal-PARSING defences (alternate numeral encodings, bracketed and
     * zone-indexed IPv6), which are what its tests pin.
     */
    private static boolean isBlockedIpv4(byte[] ip) {
        try {
            return SsrfGuard.isBlockedAddress(InetAddress.getByAddress(ip));
        } catch (UnknownHostException e) {
            return true; // wrong length - cannot happen for a parsed literal; fail closed
        }
    }

    /**
     * IPv6 literal check without DNS. The caller only invokes this for hosts containing ':', which
     * are always IPv6 literals - so {@link java.net.InetAddress#getByName} decodes them WITHOUT a
     * DNS lookup, and (unlike the previous hand-rolled group parser) it handles EVERY encoding of a
     * mapped address, not just the dotted tail. Pentest H1: the old parser early-returned {@code
     * false} on hex-group mapped forms like {@code ::ffff:7f00:1} (= 127.0.0.1), leaving the entire
     * IPv4 denylist bypassable. Now: decode, collapse a v4-mapped address to its IPv4 and run
     * {@link #isBlockedIpv4}, otherwise classify the v6 ranges (loopback, unspecified, link-local,
     * site-local/ULA). Unparseable -&gt; blocked.
     */
    private static boolean isBlockedIpv6Literal(String host) {
        String h = host;
        int pct = h.indexOf('%'); // zone index - InetAddress rejects some, strip it
        if (pct >= 0) {
            h = h.substring(0, pct);
        }
        InetAddress addr;
        try {
            addr = InetAddress.getByName(h); // no DNS: h is a ':' IPv6 literal
        } catch (Exception e) {
            return true; // malformed literal - fail closed
        }
        return isBlockedInetAddress(addr);
    }

    /**
     * Range classification shared by the no-DNS literal path above (an already-parsed IP literal,
     * IPv4 or IPv6) and {@link #checkResolved}'s real DNS-resolved addresses (H2) - one source of
     * truth for "is this address private/loopback/link-local/metadata", so a future range addition
     * (or an as-yet-unnoticed gap in it) only ever has to be fixed once.
     */
    private static boolean isBlockedInetAddress(InetAddress addr) {
        // SEC-027: the shared classification - see isBlockedIpv4's javadoc.
        return SsrfGuard.isBlockedAddress(addr);
    }

    /**
     * SEC-026: the legacy SSE transport learns where to POST messages from the server's {@code
     * endpoint} event, and MCP SDK 0.18.2's {@code Utils.resolveUri} only rejects an ABSOLUTE
     * out-of-base endpoint - a scheme-relative {@code //core:8080/x} resolves against the base and
     * switches host. Every request URI the transport is about to send must therefore be same-origin
     * (scheme, host, effective port) with the registered base URL, which itself passed {@link
     * #checkResolved}. Being same-origin with a validated base is what "validated" means for the
     * endpoint: it can only reach the host that was already checked.
     *
     * @return empty when {@code requestUri} may be sent; otherwise the refusal reason (never the
     *     resolved address).
     */
    public static Optional<String> checkSameOrigin(String baseUrl, URI requestUri) {
        URI base;
        try {
            base = new URI(baseUrl == null ? "" : baseUrl.trim());
        } catch (Exception e) {
            return Optional.of("Remote server URL is not a valid URL.");
        }
        if (requestUri == null || requestUri.getHost() == null || base.getHost() == null) {
            return Optional.of(
                    "The MCP server asked this platform to send messages to an invalid address.");
        }
        if (requestUri.getRawUserInfo() != null || !SsrfGuard.sameOrigin(base, requestUri)) {
            return Optional.of(
                    "The MCP server asked this platform to send messages to a different host than "
                            + "the registered server URL; refused (a remote MCP server may only use its own origin).");
        }
        return Optional.empty();
    }
}
