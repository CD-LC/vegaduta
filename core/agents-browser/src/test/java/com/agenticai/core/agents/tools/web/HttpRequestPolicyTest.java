package com.agenticai.core.agents.tools.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link HttpRequestPolicy}, {@link HttpRequestSafetyConfig} and {@link HttpRequestCredential} -
 * pure logic, no I/O.
 */
class HttpRequestPolicyTest {

    private static HttpRequestPolicy policy(
            List<String> global,
            List<String> agent,
            List<String> contentTypes,
            Set<String> denied) {
        return new HttpRequestPolicy(
                Set.of(), false, 1024, 4096, 3, contentTypes, denied, global, agent, true);
    }

    // ------------------------------------------------------------------ host allow-list

    @Test
    void noListsMeansAnyHostAndIsReportedAsNotConfigured() {
        HttpRequestPolicy p = policy(List.of(), List.of(), List.of(), Set.of());
        assertFalse(p.allowlistConfigured());
        assertTrue(p.hostAllowed("api.example.com"));
        assertFalse(p.hostAllowed(null));
        assertFalse(p.hostAllowed("  "));
    }

    @Test
    void exactAndWildcardEntriesMatchCaseInsensitivelyAndWildcardsNeverMatchTheApex() {
        HttpRequestPolicy p =
                policy(List.of(), List.of("api.stripe.com", "*.github.com"), List.of(), Set.of());
        assertTrue(p.allowlistConfigured());
        assertTrue(p.hostAllowed("API.Stripe.com"));
        assertTrue(p.hostAllowed("api.github.com"));
        assertTrue(p.hostAllowed("a.b.github.com"));
        assertFalse(p.hostAllowed("github.com"), "*.x matches subdomains only");
        assertFalse(p.hostAllowed("evilgithub.com"));
        assertFalse(p.hostAllowed("api.stripe.com.evil.example"));
        assertFalse(p.hostAllowed("stripe.com"));
    }

    @Test
    void anIpv6LiteralHostIsComparedWithoutItsBrackets() {
        HttpRequestPolicy p = policy(List.of(), List.of("2001:db8::1"), List.of(), Set.of());
        assertTrue(p.hostAllowed("[2001:db8::1]"));
        assertFalse(p.hostAllowed("[2001:db8::2]"));
    }

    @Test
    void theOperatorListIsACeilingAndTheAgentListANarrowing() {
        HttpRequestPolicy p =
                policy(
                        List.of("*.example.com"),
                        List.of("a.example.com", "evil.org"),
                        List.of(),
                        Set.of());
        assertTrue(p.hostAllowed("a.example.com"));
        assertFalse(
                p.hostAllowed("b.example.com"), "inside the ceiling but not on the agent's list");
        assertFalse(
                p.hostAllowed("evil.org"), "on the agent's list but outside the operator ceiling");
    }

    @Test
    void anOperatorOnlyListAppliesToEveryAgent() {
        HttpRequestPolicy p = policy(List.of("api.example.com"), List.of(), List.of(), Set.of());
        assertTrue(p.hostAllowed("api.example.com"));
        assertFalse(p.hostAllowed("other.example.com"));
    }

    // ------------------------------------------------------------------ content types and headers

    @Test
    void contentTypePatternsSupportExactPrefixWildcardAndSuffixWildcard() {
        HttpRequestPolicy p =
                policy(
                        List.of(),
                        List.of(),
                        List.of("application/json", "text/*", "application/*+json"),
                        Set.of());
        assertTrue(p.contentTypeAllowed("application/json"));
        assertTrue(p.contentTypeAllowed("Application/JSON; charset=utf-8"));
        assertTrue(p.contentTypeAllowed("text/csv"));
        assertTrue(p.contentTypeAllowed("application/problem+json"));
        assertFalse(p.contentTypeAllowed("application/xml"));
        assertFalse(p.contentTypeAllowed("application/jsonp"));
        assertFalse(p.contentTypeAllowed("image/png"));
        assertTrue(
                p.contentTypeAllowed(""),
                "a response without a Content-Type is not rejected on this ground");
        assertTrue(p.contentTypeAllowed(null));
    }

    @Test
    void noContentTypePatternsMeansAnyType() {
        assertTrue(
                policy(List.of(), List.of(), List.of(), Set.of())
                        .contentTypeAllowed("application/x-anything"));
    }

    @Test
    void deniedHeadersMatchCaseInsensitively() {
        HttpRequestPolicy p =
                policy(List.of(), List.of(), List.of(), Set.of("authorization", "cookie"));
        assertTrue(p.headerDenied("Authorization"));
        assertTrue(p.headerDenied("  COOKIE "));
        assertFalse(p.headerDenied("X-Request-Id"));
        assertFalse(p.headerDenied(null));
    }

    @Test
    void methodsCanBeRestrictedPerToolAndWithMethodsKeepsEverythingElse() {
        HttpRequestPolicy any =
                policy(List.of("h.example"), List.of(), List.of("text/*"), Set.of("cookie"));
        assertTrue(any.methodAllowed("DELETE"));
        HttpRequestPolicy reads = any.withMethods(HttpRequestSafetyConfig.READ_METHODS);
        assertTrue(reads.methodAllowed("GET"));
        assertFalse(reads.methodAllowed("POST"));
        assertEquals(any.globalAllowedHosts(), reads.globalAllowedHosts());
        assertEquals(any.allowedContentTypes(), reads.allowedContentTypes());
        assertEquals(any.deniedHeaders(), reads.deniedHeaders());
    }

    @Test
    void limitsAreClampedToSaneBounds() {
        HttpRequestPolicy p =
                new HttpRequestPolicy(Set.of(), false, -5, 3, 99, null, null, null, null, true);
        assertEquals(0, p.maxRequestBodyBytes());
        assertEquals(1_024, p.maxResponseBytes());
        assertEquals(5, p.maxRedirects());
        assertTrue(p.allowedContentTypes().isEmpty());
    }

    // ------------------------------------------------------------------ config

    private static HttpRequestSafetyConfig config(String agentHosts) {
        return new HttpRequestSafetyConfig(
                true,
                true,
                true,
                false,
                65_536,
                1_048_576,
                3,
                "application/json,text/*",
                "authorization, cookie",
                "*.example.com",
                agentHosts,
                true);
    }

    @Test
    void configBuildsAPolicyFromItsValuesAndTheAgentsOwnHostList() {
        UUID agent = UUID.randomUUID();
        HttpRequestSafetyConfig config =
                config(agent + "=api.example.com, b.example.com;not-a-uuid=x;" + UUID.randomUUID());

        HttpRequestPolicy p = config.policyFor(agent, HttpRequestSafetyConfig.WRITE_METHODS);

        assertEquals(List.of("*.example.com"), p.globalAllowedHosts());
        assertEquals(List.of("api.example.com", "b.example.com"), p.agentAllowedHosts());
        assertEquals(List.of("application/json", "text/*"), p.allowedContentTypes());
        assertEquals(Set.of("authorization", "cookie"), p.deniedHeaders());
        assertFalse(p.allowHttp());
        assertTrue(p.methodAllowed("POST"));
        assertFalse(p.methodAllowed("GET"));
        assertEquals(65_536, p.maxRequestBodyBytes());
    }

    @Test
    void anAgentWithoutAnEntryGetsOnlyTheOperatorCeiling() {
        HttpRequestSafetyConfig config = config(UUID.randomUUID() + "=x.example.com");
        HttpRequestPolicy p = config.policyFor(UUID.randomUUID(), Set.of());
        assertTrue(p.agentAllowedHosts().isEmpty());
        assertEquals(List.of("*.example.com"), p.globalAllowedHosts());
        assertTrue(config.policyFor(null, Set.of()).agentAllowedHosts().isEmpty());
    }

    @Test
    void malformedAgentHostEntriesAreSkippedNotFatal() {
        Map<UUID, List<String>> parsed =
                HttpRequestSafetyConfig.parseAgentHosts("garbage;;=x;" + "zzz=host");
        assertTrue(parsed.isEmpty());
        assertTrue(HttpRequestSafetyConfig.parseAgentHosts(null).isEmpty());
        assertTrue(HttpRequestSafetyConfig.parseAgentHosts("   ").isEmpty());
    }

    @Test
    void theDefaultsPreserveTodaysBehaviour() {
        // The same literals as the @Value defaults / application.yml: the tool stays offered, safe
        // mode stays off.
        HttpRequestSafetyConfig defaults =
                new HttpRequestSafetyConfig(
                        true, false, true, false, 65_536, 1_048_576, 3, "", "", "", "", true);
        assertTrue(defaults.isEnabled());
        assertFalse(defaults.isSafeMode());
    }

    // ------------------------------------------------------------------ credential

    @Test
    void credentialsAreBuiltFromTheNameAndAnOptionalScheme() {
        assertEquals(null, HttpRequestCredential.fromArguments(null, "bearer", null));
        assertEquals(null, HttpRequestCredential.fromArguments("  ", null, null));
        HttpRequestCredential bearer =
                HttpRequestCredential.fromArguments("STRIPE_KEY", null, null);
        assertEquals(HttpRequestCredential.Type.BEARER, bearer.type());
        assertEquals("Authorization", bearer.headerName());
        assertEquals(
                HttpRequestCredential.Type.BASIC,
                HttpRequestCredential.fromArguments("SVC", "Basic", "ignored").type());
        HttpRequestCredential header =
                HttpRequestCredential.fromArguments("K", "api-key", "X-API-Key");
        assertEquals(HttpRequestCredential.Type.HEADER, header.type());
        assertEquals("X-API-Key", header.headerName());
    }

    @Test
    void aCredentialThatLooksLikeASecretValueIsRefusedWithoutEchoingIt() {
        for (String notAName :
                List.of(
                        "sk_live_abc-123",
                        "Bearer abc",
                        "has space",
                        "9starts_with_digit",
                        "a".repeat(65),
                        "k=v")) {
            WebPageFetchException e =
                    assertThrows(
                            WebPageFetchException.class,
                            () -> HttpRequestCredential.fromArguments(notAName, null, null),
                            notAName);
            assertFalse(
                    e.getMessage().contains(notAName),
                    "the offending value must never be echoed: " + e.getMessage());
        }
    }

    @Test
    void headerCredentialsNeedAValidHeaderNameAndUnknownTypesFail() {
        assertThrows(
                WebPageFetchException.class,
                () -> HttpRequestCredential.fromArguments("K", "header", null));
        assertThrows(
                WebPageFetchException.class,
                () -> HttpRequestCredential.fromArguments("K", "header", "Bad Header"));
        assertThrows(
                WebPageFetchException.class,
                () -> HttpRequestCredential.fromArguments("K", "digest", null));
    }
}
