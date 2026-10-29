package com.agenticai.core.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * {@link JwtClaimPaths}'s two rules in isolation: reads try the whole path as a literal top-level
 * claim BEFORE splitting on {@code '.'} (so namespaced IdP claims survive), and writes mirror that
 * shape. Everything malformed resolves to {@code null} - the fail-closed signal callers key on.
 */
class JwtClaimPathsTest {

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder =
                Jwt.withTokenValue("token-value")
                        .header("alg", "none")
                        .issuedAt(Instant.now())
                        .expiresAt(Instant.now().plusSeconds(300));
        builder.claims(c -> c.putAll(claims));
        return builder.build();
    }

    @Test
    void aLiteralTopLevelClaimWinsOverDotPathSplitting() {
        // Both shapes present at once: the literal "realm_access.roles" claim is NOT the
        // same thing as the nested {"realm_access": {"roles": ...}} map, and the literal
        // must be found first.
        Jwt jwt =
                jwt(
                        Map.of(
                                "realm_access.roles", List.of("literal-role"),
                                "realm_access", Map.of("roles", List.of("nested-role"))));

        assertThat(JwtClaimPaths.value(jwt, "realm_access.roles"))
                .isEqualTo(List.of("literal-role"));
    }

    @Test
    void aDotPathWalksNestedMapsSegmentBySegment() {
        Jwt jwt = jwt(Map.of("a", Map.of("b", Map.of("c", "deep"))));

        assertThat(JwtClaimPaths.value(jwt, "a.b.c")).isEqualTo("deep");
    }

    @Test
    void missingSegmentsNonMapIntermediatesAndBlankPathsAllResolveNull() {
        Jwt jwt = jwt(Map.of("realm_access", Map.of("roles", List.of("admin")), "scalar", 42));

        assertThat(JwtClaimPaths.value(jwt, "realm_access.groups")).isNull();
        assertThat(JwtClaimPaths.value(jwt, "absent.value")).isNull();
        assertThat(JwtClaimPaths.value(jwt, "scalar.deeper")).isNull();
        assertThat(JwtClaimPaths.value(jwt, "")).isNull();
        assertThat(JwtClaimPaths.value(jwt, "   ")).isNull();
        assertThat(JwtClaimPaths.value(jwt, null)).isNull();
        assertThat(JwtClaimPaths.value(null, "realm_access.roles")).isNull();
    }

    @Test
    void valueAsStringReturnsNullForNonStringClaimsInsteadOfStringifying() {
        Jwt jwt = jwt(Map.of("tenant_id", "tenant_acme", "roles", List.of("a"), "count", 7));

        assertThat(JwtClaimPaths.valueAsString(jwt, "tenant_id")).isEqualTo("tenant_acme");
        assertThat(JwtClaimPaths.valueAsString(jwt, "roles")).isNull();
        assertThat(JwtClaimPaths.valueAsString(jwt, "count")).isNull();
        assertThat(JwtClaimPaths.valueAsString(jwt, "missing")).isNull();
    }

    @Test
    void putNestedWritesUriStylePathsAsOneLiteralClaim() {
        Map<String, Object> claims = new LinkedHashMap<>();

        JwtClaimPaths.putNested(claims, "https://example.com/roles", List.of("x"));
        JwtClaimPaths.putNested(claims, "custom:claim.with.dots", "v");
        JwtClaimPaths.putNested(claims, "plain", "p");

        assertThat(claims.get("https://example.com/roles")).isEqualTo(List.of("x"));
        assertThat(claims.get("custom:claim.with.dots")).isEqualTo("v");
        assertThat(claims.get("plain")).isEqualTo("p");
    }

    @Test
    void putNestedExpandsDotPathsIntoMapsReadableByValue() {
        Map<String, Object> claims = new LinkedHashMap<>();

        JwtClaimPaths.putNested(claims, "a.b.c", "deep");

        assertThat(claims.get("a")).isEqualTo(Map.of("b", Map.of("c", "deep")));
        assertThat(JwtClaimPaths.value(jwt(claims), "a.b.c")).isEqualTo("deep");
    }

    @Test
    void putNestedFallsBackToLiteralWhenAnIntermediateSegmentIsNotAMap() {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("a", "not-a-map");

        JwtClaimPaths.putNested(claims, "a.b.c", "deep");

        // The value stays reachable under the literal path rather than being dropped or
        // clobbering the scalar at "a".
        assertThat(claims.get("a")).isEqualTo("not-a-map");
        assertThat(claims.get("a.b.c")).isEqualTo("deep");
        assertThat(JwtClaimPaths.value(jwt(claims), "a.b.c")).isEqualTo("deep");
    }

    @Test
    void putNestedIsANoOpForBlankPathsAndNullMaps() {
        Map<String, Object> claims = new LinkedHashMap<>();

        JwtClaimPaths.putNested(claims, null, "v");
        JwtClaimPaths.putNested(claims, "  ", "v");
        JwtClaimPaths.putNested(null, "a.b", "v");

        assertThat(claims).isEmpty();
    }

    @Test
    void putNestedMergesIntoAnExistingIntermediateMap() {
        Map<String, Object> claims = new LinkedHashMap<>();
        Map<String, Object> existing = new LinkedHashMap<>();
        existing.put("first", 1);
        claims.put("realm_access", existing);

        JwtClaimPaths.putNested(claims, "realm_access.roles", List.of("admin"));

        assertThat(existing.get("first")).isEqualTo(1);
        assertThat(existing.get("roles")).isEqualTo(List.of("admin"));
    }
}
