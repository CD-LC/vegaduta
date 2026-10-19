package com.agenticai.core.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * SSRF-guard table (MCP-DOCTOR hardening): every blocked range/scheme/name
 * gets a case, plus the legitimate-public-URL cases that must keep working -
 * a remote MCP registration with an Authorization env-header pointed at a
 * real public endpoint is the feature, the guard must never break it.
 */
class McpRemoteUrlGuardTest {

    private final McpRemoteUrlGuard guard = new McpRemoteUrlGuard("");

    // --- allowed: legitimate public remotes -----------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "https://mcp.example.com/mcp",
        "http://mcp.example.com:8443/sse",
        "https://androidtv-mcp.fly.dev/mcp",
        "https://8.8.8.8/mcp",              // public literal IP is fine
        "https://api.githubcopilot.com/mcp/"
    })
    void allowsPublicHttpAndHttpsUrls(String url) {
        assertTrue(guard.check(url).isEmpty(), url + " should be allowed");
    }

    // --- blocked: schemes and URL shapes ---------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "file:///etc/passwd",
        "ftp://example.com/x",
        "gopher://example.com/x",
        "mcp.example.com/no-scheme"
    })
    void blocksNonHttpSchemes(String url) {
        assertTrue(guard.check(url).isPresent(), url + " should be blocked");
    }

    @Test
    void blocksUserinfoUrls() {
        assertTrue(guard.check("https://trusted.example.com@evil.example.net/mcp").isPresent());
    }

    @Test
    void blocksBlankAndGarbageUrls() {
        assertTrue(guard.check(null).isPresent());
        assertTrue(guard.check("  ").isPresent());
        assertTrue(guard.check("ht!tp://???").isPresent());
    }

    // --- blocked: private/loopback/link-local/metadata IPv4 ranges -------------

    @ParameterizedTest
    @ValueSource(strings = {
        "http://127.0.0.1:8080/mcp",       // loopback /8
        "http://127.8.9.10/mcp",
        "http://10.0.0.5/mcp",             // 10/8
        "http://172.16.0.1/mcp",           // 172.16/12 low edge
        "http://172.31.255.254/mcp",       // 172.16/12 high edge
        "http://192.168.1.10:9000/mcp",    // 192.168/16
        "http://169.254.169.254/latest/meta-data", // link-local + cloud metadata
        "http://100.64.0.1/mcp",           // CGNAT /10 (overlay networks)
        "http://0.0.0.0:8080/mcp"          // unspecified
    })
    void blocksPrivateAndSpecialIpv4Literals(String url) {
        assertTrue(guard.check(url).isPresent(), url + " should be blocked");
    }

    @Test
    void allowsPublicNeighborsOfBlockedRanges() {
        // Range-boundary checks - the /12 and /10 masks must not overreach.
        assertTrue(guard.check("http://172.15.255.255/mcp").isEmpty());
        assertTrue(guard.check("http://172.32.0.1/mcp").isEmpty());
        assertTrue(guard.check("http://100.128.0.1/mcp").isEmpty());
        assertTrue(guard.check("http://11.0.0.1/mcp").isEmpty());
    }

    // --- blocked: IPv6 loopback/unique-local/link-local -------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "http://[::1]:8080/mcp",
        "http://[fc00::1]/mcp",
        "http://[fdab:1234::9]/mcp",
        "http://[fe80::1]/mcp",
        "http://[::ffff:127.0.0.1]/mcp",   // IPv4-mapped loopback
        "http://[::ffff:192.168.0.7]/mcp"
    })
    void blocksPrivateIpv6Literals(String url) {
        assertTrue(guard.check(url).isPresent(), url + " should be blocked");
    }

    @Test
    void allowsPublicIpv6Literals() {
        assertTrue(guard.check("https://[2001:4860:4860::8888]/mcp").isEmpty());
    }

    // --- blocked: platform-internal hostnames (compose service names) ----------

    @ParameterizedTest
    @ValueSource(strings = {
        "http://localhost:8080/mcp",
        "http://sub.localhost/mcp",
        "http://postgres:5432/",
        "http://keycloak:8080/realms/agentic-ai",
        "http://core:8080/actuator/env",
        "http://redis:6379/",
        "http://minio:9000/",
        "http://sidecar:8000/",
        "http://code-intel:8080/",
        "http://grafana:3000/"
    })
    void blocksComposeServiceHostnames(String url) {
        String reason = guard.check(url).orElse("");
        assertTrue(reason.contains("platform's own network"), url + " should be blocked with the internal-network message, got: " + reason);
    }

    @Test
    void denylistIsCaseInsensitiveAndConfigExtendable() {
        McpRemoteUrlGuard extended = new McpRemoteUrlGuard("internal.corp, Vault");
        assertTrue(extended.check("http://INTERNAL.CORP/mcp").isPresent());
        assertTrue(extended.check("http://vault:8200/mcp").isPresent());
        assertTrue(extended.check("http://api.internal.corp/mcp").isPresent(), "subdomains of a denied name are denied too");
    }

    @Test
    void requireAllowedThrowsWithTheUserFacingReason() {
        IllegalArgumentException e = assertThrows(
            IllegalArgumentException.class, () -> guard.requireAllowed("http://postgres:5432/"));
        assertTrue(e.getMessage().contains("platform's own network"));
        assertEquals(true, guard.check("https://mcp.example.com/mcp").isEmpty());
    }

    // --- Pentest H1: IPv4-mapped IPv6 in hex-group notation must not bypass ----------

    @Test
    void blocksHexFormIpv4MappedIpv6() {
        // ::ffff:7f00:1 == 127.0.0.1 ; ::ffff:a00:1 == 10.0.0.1 (the whole
        // IPv4 denylist was bypassable via this encoding before the fix).
        assertTrue(guard.check("http://[::ffff:7f00:1]/mcp").isPresent(), "::ffff:7f00:1 is 127.0.0.1");
        assertTrue(guard.check("http://[0:0:0:0:0:ffff:7f00:1]/mcp").isPresent(), "expanded mapped loopback");
        assertTrue(guard.check("http://[::ffff:a00:1]/mcp").isPresent(), "::ffff:a00:1 is 10.0.0.1");
        assertTrue(guard.check("http://[::ffff:a9fe:a9fe]/mcp").isPresent(), "mapped 169.254.169.254 metadata");
    }

    @Test
    void stillAllowsGlobalIpv6() {
        assertTrue(guard.check("http://[2606:4700:4700::1111]/mcp").isEmpty(), "global unicast v6 is allowed");
        // SEC-027: 2001:db8::/32 is the documentation range - never routable, now refused
        // by the shared SsrfGuard classification.
        assertTrue(guard.check("http://[2001:db8::1]/mcp").isPresent(), "documentation-range v6 is refused");
    }

    // --- Pentest M1/M3: trailing-dot + docker-host names -----------------------------

    @Test
    void blocksTrailingDotInternalName() {
        assertTrue(guard.check("http://postgres./mcp").isPresent(), "FQDN-root trailing dot still points internal");
    }

    @Test
    void blocksDockerHostGateways() {
        assertTrue(guard.check("http://host.docker.internal:8080/mcp").isPresent());
        assertTrue(guard.check("http://gateway.docker.internal/mcp").isPresent());
    }

    // --- Pentest M2: numeric IPv4 encodings must not bypass check() ------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "http://2130706433/mcp",        // decimal 32-bit int == 127.0.0.1
        "http://0x7f000001/mcp",        // hex 32-bit int == 127.0.0.1
        "http://0X7F000001/mcp",        // hex, uppercase prefix+digits
        "http://127.1/mcp",             // 2-part shorthand == 127.0.0.1
        "http://10.1/mcp",              // 2-part shorthand == 10.0.0.1
        "http://172.16.5/mcp",          // 3-part shorthand == 172.16.0.5
        "http://0xA9FEA9FE/mcp",        // hex == 169.254.169.254 (cloud metadata)
        "http://2852039166/mcp"         // decimal == 169.254.169.254
    })
    void blocksNumericIpv4Encodings(String url) {
        assertTrue(guard.check(url).isPresent(), url + " should be blocked (M2 numeral encoding of a private/metadata IP)");
    }

    @Test
    void allowsNumericEncodingsOfPublicAddresses() {
        // 8.8.8.8 as a decimal 32-bit integer and as 2-/3-part shorthand - must NOT be
        // over-blocked just for being numeral-shaped; only the private/special ranges
        // are denied.
        assertTrue(guard.check("http://134744072/mcp").isEmpty(), "134744072 == 8.8.8.8, a public address");
        assertTrue(guard.check("http://8.8.8.8/mcp").isEmpty(), "canonical dotted-quad still allowed (sanity check)");
    }

    @Test
    void realHostnamesAreNeverMisreadAsNumericIpv4() {
        // Guards against the numeral parser over-firing on ordinary hostnames that
        // happen to look vaguely numeral-ish in one label.
        assertTrue(guard.check("https://8.example.com/mcp").isEmpty());
        assertTrue(guard.check("https://example.com/mcp").isEmpty());
    }

    // --- Pentest H2: DNS rebinding - checkResolved() must catch a hostname whose ----
    // --- DNS now points at a private/metadata address, even though check() (string- ---
    // --- only, no DNS) would allow it. A fake HostResolver simulates the rebind -------
    // --- deterministically, without needing real attacker-controlled DNS. -------------

    @Test
    void checkResolvedBlocksAHostnameThatRebindsToLoopback() throws UnknownHostException {
        String url = "https://rebind.attacker.example/mcp";
        // The plain string check must pass - "rebind.attacker.example" is not on any
        // denylist and isn't a literal IP.
        assertTrue(guard.check(url).isEmpty(), "the bare hostname string must look legitimate to check()");

        McpRemoteUrlGuard.HostResolver rebindsToLoopback = host -> new InetAddress[] {
            InetAddress.getByName("127.0.0.1")
        };

        assertTrue(guard.checkResolved(url, rebindsToLoopback).isPresent(),
            "checkResolved must catch the rebind even though check() alone would not");
    }

    @Test
    void checkResolvedBlocksWhenAnyOfMultipleResolvedAddressesIsPrivate() throws UnknownHostException {
        // Round-robin DNS: a hostname resolving to several addresses is blocked if ANY
        // one of them is private/loopback/metadata - not just the first.
        String url = "https://multi.attacker.example/mcp";
        McpRemoteUrlGuard.HostResolver mixedAddresses = host -> new InetAddress[] {
            InetAddress.getByName("8.8.8.8"),
            InetAddress.getByName("169.254.169.254") // cloud metadata - must be caught
        };

        assertTrue(guard.checkResolved(url, mixedAddresses).isPresent());
    }

    @Test
    void checkResolvedBlocksAHostnameThatRebindsToCloudMetadataOverIpv6() throws UnknownHostException {
        String url = "https://ipv6-rebind.attacker.example/mcp";
        McpRemoteUrlGuard.HostResolver rebindsToUniqueLocal = host -> new InetAddress[] {
            InetAddress.getByName("fd00::1")
        };

        assertTrue(guard.checkResolved(url, rebindsToUniqueLocal).isPresent());
    }

    @Test
    void checkResolvedAllowsAHostnameThatGenuinelyResolvesToAPublicAddress() throws UnknownHostException {
        String url = "https://mcp.example.com/mcp";
        McpRemoteUrlGuard.HostResolver realisticPublicResolver = host -> new InetAddress[] {
            InetAddress.getByName("93.184.216.34")
        };

        assertTrue(guard.checkResolved(url, realisticPublicResolver).isEmpty(),
            "a legitimate public remote server must still connect - the guard must not become a false-positive trap");
    }

    @Test
    void checkResolvedFailsClosedWhenTheHostnameDoesNotResolveAtAll() {
        String url = "https://nonexistent.attacker.example/mcp";
        McpRemoteUrlGuard.HostResolver alwaysUnknownHost = host -> {
            throw new UnknownHostException(host);
        };

        assertTrue(guard.checkResolved(url, alwaysUnknownHost).isPresent(),
            "an unresolvable hostname must fail closed, not be silently allowed through");
    }

    @Test
    void checkResolvedStillRunsTheOrdinaryStringChecksFirst() {
        // A URL that's already blocked by check() (denylisted compose hostname) must be
        // blocked by checkResolved() too, WITHOUT ever invoking the resolver - proves
        // checkResolved delegates to check() rather than skipping it.
        McpRemoteUrlGuard.HostResolver shouldNeverBeCalled = host -> {
            throw new AssertionError("resolver must not be invoked for a URL check() already blocks: " + host);
        };

        assertTrue(guard.checkResolved("http://postgres:5432/", shouldNeverBeCalled).isPresent());
    }

    @Test
    void checkResolvedSkipsDnsEntirelyForLiteralIpUrls() {
        // A literal IP URL has no hostname to resolve - checkResolved must not attempt a
        // DNS lookup for it at all (the resolver would throw if invoked).
        McpRemoteUrlGuard.HostResolver shouldNeverBeCalled = host -> {
            throw new AssertionError("resolver must not be invoked for a literal-IP URL: " + host);
        };

        assertFalse(guard.checkResolved("https://8.8.8.8/mcp", shouldNeverBeCalled).isPresent());
        assertTrue(guard.checkResolved("http://127.0.0.1/mcp", shouldNeverBeCalled).isPresent());
    }
}
