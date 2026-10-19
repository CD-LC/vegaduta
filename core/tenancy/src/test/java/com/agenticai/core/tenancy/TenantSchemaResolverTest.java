package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * {@link TenantSchemaResolver} is the seam that turns "which tenant is this
 * request for" into "which Postgres schema does Hibernate query" - both via
 * the Hibernate SPI hook ({@link TenantSchemaResolver#resolveCurrentTenantIdentifier()})
 * and via the static {@link TenantSchemaResolver#currentSchemaName()} the
 * knowledge module's raw-JDBC queries call directly (see its own javadoc,
 * which documents a real historical cross-tenant leak that extraction
 * closed).
 *
 * <p>F-14 additions: the public-schema fallback is instrumented (counter
 * per resolution-path kind, fail-closed flag for the one reliably-suspicious
 * kind). These tests pin down the class javadoc's map - in particular that
 * the legitimate control-plane kinds keep working untouched even with
 * {@code app.tenancy.fail-on-missing-context=true}, since flipping that
 * flag in staging must never be able to take down health checks, the admin
 * console, or onboarding.
 *
 * <p>Uses the real {@link TenantContext} ThreadLocal rather than mocking it
 * - this class has no dependency-injection seam around TenantContext (it's
 * a static holder by design, see its own javadoc), so exercising the real
 * thing is both simpler and closer to what actually happens at runtime.
 * {@link #clearPerThreadState()} guards against one test's context (or the
 * fail-closed resolver a test registered - see {@link
 * TenantSchemaResolver#resetRegisteredInstanceForTests()}) leaking into the
 * next when tests run on the same thread/fork.
 */
class TenantSchemaResolverTest {

    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void freshRegistry() {
        meterRegistry = new SimpleMeterRegistry();
    }

    @AfterEach
    void clearPerThreadState() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        TenantSchemaResolver.resetRegisteredInstanceForTests();
    }

    private TenantSchemaResolver newResolver(boolean failOnMissingContext) {
        return newResolver(failOnMissingContext, meterRegistry);
    }

    private TenantSchemaResolver newResolver(boolean failOnMissingContext, MeterRegistry registry) {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        return new TenantSchemaResolver(provider, failOnMissingContext);
    }

    private double fallbackCount(String kind) {
        Counter counter = meterRegistry.find(TenantSchemaResolver.FALLBACK_METRIC).tag("kind", kind).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void noTenantContextResolvesToDefaultSchema() {
        TenantContext.clear();
        assertEquals(TenantSchemaResolver.DEFAULT_SCHEMA, TenantSchemaResolver.currentSchemaName());

        TenantSchemaResolver resolver = newResolver(false);
        assertEquals(TenantSchemaResolver.DEFAULT_SCHEMA, resolver.resolveCurrentTenantIdentifier());
    }

    @Test
    void tenantContextWithValidTenantIdResolvesToSanitizedSchema() {
        TenantContext.set(TenantContext.of("tenant_b", "user-sub-123", Set.of("ROLE_tenant-admin")));

        assertEquals("tenant_b", TenantSchemaResolver.currentSchemaName());

        TenantSchemaResolver resolver = newResolver(false);
        assertEquals("tenant_b", resolver.resolveCurrentTenantIdentifier());
    }

    @Test
    void tenantContextWithDirtyTenantIdIsSanitizedBeforeBecomingASchemaName() {
        // The exact scenario TenantSchemaResolver's javadoc warns about:
        // an admin (or a compromised/misconfigured IdP) setting a
        // Keycloak user's tenant_id attribute to something that is not
        // already a safe schema name. This resolver must never hand
        // Hibernate a raw, unsanitized claim value.
        TenantContext.set(TenantContext.of("Tenant B!; DROP", "user-sub-456", Set.of()));

        String resolved = TenantSchemaResolver.currentSchemaName();
        assertEquals(SchemaNameSanitizer.sanitize("Tenant B!; DROP"), resolved);
        // Never the raw claim value verbatim.
        assertEquals("tenant_b___drop", resolved);
    }

    @Test
    void blankTenantIdFallsBackToDefaultSchema() {
        TenantContext.set(TenantContext.of("", "user-sub-789", Set.of()));
        assertEquals(TenantSchemaResolver.DEFAULT_SCHEMA, TenantSchemaResolver.currentSchemaName());
    }

    @Test
    void nullTenantIdFallsBackToDefaultSchema() {
        // Realistic for an authenticated-but-non-tenant-scoped principal
        // (e.g. a platform-admin JWT with no tenant_id claim at all).
        TenantContext.set(TenantContext.of(null, "user-sub-000", Set.of("ROLE_platform-admin")));
        assertEquals(TenantSchemaResolver.DEFAULT_SCHEMA, TenantSchemaResolver.currentSchemaName());
    }

    @Test
    void validateExistingCurrentSessionsIsTrue() {
        // Hibernate's own contract: when true, an existing session must
        // belong to the tenant currently resolved, or Hibernate throws
        // rather than silently reusing a session scoped to a different
        // tenant. This resolver deliberately opts into that safety check
        // rather than the weaker default - worth pinning down explicitly
        // so a future edit can't silently flip it to false.
        assertEquals(true, newResolver(false).validateExistingCurrentSessions());
    }

    // ------------------------------------------------------------------
    // F-14: fallback observability
    // ------------------------------------------------------------------

    @Test
    void noContextFallbackIsCountedUnderTheNoContextKindWithTheCallingClassTagged() {
        TenantSchemaResolver resolver = newResolver(false);

        assertEquals(TenantSchemaResolver.DEFAULT_SCHEMA, resolver.resolveCurrentTenantIdentifier());

        Counter counter = meterRegistry.find(TenantSchemaResolver.FALLBACK_METRIC)
            .tag("kind", "no_context")
            .counter();
        assertNotNull(counter, "every public fallback must be counted");
        assertEquals(1.0, counter.count());
        // The caller tag is the staging triage handle (see the resolver's
        // F-14 javadoc): here the nearest non-infrastructure frame is this
        // test class itself.
        assertEquals("TenantSchemaResolverTest", counter.getId().getTag("caller"));
    }

    @Test
    void tenantResolutionNeverTouchesTheFallbackCounter() {
        TenantContext.set(TenantContext.of("tenant_b", "user-sub-123", Set.of()));
        TenantSchemaResolver resolver = newResolver(true);

        assertEquals("tenant_b", resolver.resolveCurrentTenantIdentifier());
        assertNull(meterRegistry.find(TenantSchemaResolver.FALLBACK_METRIC).counter());
    }

    @Test
    void platformAdminTenantlessContextIsCountedAsLegitimateAndUnaffectedByFailClosedMode() {
        // The admin console's whole /api/platform/** surface runs like this
        // (TenantContextFilter's documented exemption) - it must keep
        // working even with the fail-closed flag on, since every entity it
        // touches is control_plane-schema-qualified anyway.
        TenantContext.set(TenantContext.of(null, "admin-sub", Set.of("ROLE_platform-admin")));
        TenantSchemaResolver resolver = newResolver(true);

        assertEquals(TenantSchemaResolver.DEFAULT_SCHEMA, resolver.resolveCurrentTenantIdentifier());
        assertEquals(1.0, fallbackCount("platform_admin"));
    }

    @Test
    void onboardingShapedTenantlessContextIsCountedAndRoutedToTheEmptySentinelWhenFailClosed() {
        // Onboarding (authenticated, no tenant yet, no platform-admin role)
        // is the known-legitimate member of the tenantless_context kind and
        // the resolver cannot distinguish it from a hand-built degenerate
        // context - which is exactly why this kind is count-only and
        // excluded from fail-closed mode (see the resolver's javadoc for
        // the filter-level marker refactor that would change that).
        TenantContext.set(TenantContext.of(null, "new-user-sub", Set.of("ROLE_user")));
        TenantSchemaResolver resolver = newResolver(true);

        // SEC-017: never throws (onboarding only touches control_plane), but
        // never lands on public's tenant tables either.
        assertEquals(TenantSchemaResolver.NO_CONTEXT_SCHEMA, resolver.resolveCurrentTenantIdentifier());
        assertEquals(1.0, fallbackCount("tenantless_context"));
    }

    @Test
    void authenticatedJwtWithTenantClaimButNoTenantContextIsCountedAsTheSuspiciousKind() {
        // The propagation-bug signature: TenantContextFilter validated a
        // tenant JWT (it's still on SecurityContextHolder) but nothing set
        // TenantContext - ordering regression, or an async hop that
        // propagated the security context and dropped ours.
        SecurityContextHolder.getContext().setAuthentication(tenantJwtAuthentication("tenant_b"));
        TenantSchemaResolver resolver = newResolver(false);

        // Default (flag off): still falls back, so behavior is unchanged
        // in production today - but loudly counted.
        assertEquals(TenantSchemaResolver.DEFAULT_SCHEMA, resolver.resolveCurrentTenantIdentifier());
        assertEquals(1.0, fallbackCount("authenticated_no_context"));
    }

    @Test
    void failClosedModeThrowsOnTheSuspiciousKindInsteadOfFallingBack() {
        SecurityContextHolder.getContext().setAuthentication(tenantJwtAuthentication("tenant_b"));
        TenantSchemaResolver resolver = newResolver(true);

        assertThrows(MissingTenantContextException.class, resolver::resolveCurrentTenantIdentifier);
        // Counted BEFORE throwing - fail-closed mode must not blind the
        // metric that justifies (or retires) it.
        assertEquals(1.0, fallbackCount("authenticated_no_context"));
    }

    @Test
    void failClosedModeRoutesAPlainNoContextThreadToTheEmptySentinelNotPublic() {
        // SEC-017: health checks and public endpoints live in the no_context
        // kind and only touch control_plane-qualified tables, so they keep
        // working against the sentinel - but a context-less thread can no
        // longer read or write the shared public copy of the tenant tables.
        TenantSchemaResolver resolver = newResolver(true);
        assertEquals(TenantSchemaResolver.NO_CONTEXT_SCHEMA, resolver.resolveCurrentTenantIdentifier());
        assertEquals(1.0, fallbackCount("no_context"));
    }

    @Test
    void jwtWithoutTenantClaimOnThreadIsNotTreatedAsSuspicious() {
        // A platform-admin JWT has no tenant_id claim at all - there was
        // never a tenant to lose, so an empty TenantContext next to it is
        // not the propagation-bug signature.
        SecurityContextHolder.getContext().setAuthentication(tenantJwtAuthentication(null));
        TenantSchemaResolver resolver = newResolver(true);

        assertEquals(TenantSchemaResolver.NO_CONTEXT_SCHEMA, resolver.resolveCurrentTenantIdentifier());
        assertEquals(1.0, fallbackCount("no_context"));
        assertEquals(0.0, fallbackCount("authenticated_no_context"));
    }

    @Test
    void staticCurrentSchemaNameRoutesThroughTheRegisteredResolverInstrumentation() {
        // The knowledge module's raw-JDBC path calls the static method
        // directly; once the Spring bean exists, its fallbacks must be
        // counted exactly like Hibernate's.
        newResolver(false);

        assertEquals(TenantSchemaResolver.DEFAULT_SCHEMA, TenantSchemaResolver.currentSchemaName());
        assertEquals(1.0, fallbackCount("no_context"));
    }

    @Test
    void staticCurrentSchemaNameWithoutARegisteredResolverStillResolvesUninstrumented() {
        // Narrow test contexts that never construct the bean keep the exact
        // pre-F-14 behavior: resolve, no metrics, never throw.
        TenantSchemaResolver.resetRegisteredInstanceForTests();
        assertEquals(TenantSchemaResolver.DEFAULT_SCHEMA, TenantSchemaResolver.currentSchemaName());
    }

    @Test
    void missingMeterRegistryOnlySkipsCountingNeverResolution() {
        // The ObjectProvider seam exists because this bean initializes
        // before Boot's metrics post-processing (see the constructor
        // javadoc); a context with no MeterRegistry at all must degrade to
        // exactly the old behavior.
        TenantSchemaResolver resolver = newResolver(false, null);
        assertEquals(TenantSchemaResolver.DEFAULT_SCHEMA, resolver.resolveCurrentTenantIdentifier());
    }

    // ------------------------------------------------------------------
    // SEC-017: fail closed by default, explicit platform scope
    // ------------------------------------------------------------------

    @Test
    void springDefaultIsFailClosed_andTheSentinelIsNeverPublic() {
        MockEnvironment environment = new MockEnvironment();
        assertTrue(TenantSchemaResolver.effectiveFailClosed(true, environment));
        assertNotEquals(TenantSchemaResolver.DEFAULT_SCHEMA, TenantSchemaResolver.NO_CONTEXT_SCHEMA);
        // The sentinel is reserved - no tenant can ever be provisioned into it,
        // and no DROP may ever target it.
        assertTrue(TenantSchemaGuard.isProtectedSchema(TenantSchemaResolver.NO_CONTEXT_SCHEMA));
    }

    @Test
    void escapeHatchPropertyRestoresTheLegacyPublicFallback() {
        assertFalse(TenantSchemaResolver.effectiveFailClosed(false, new MockEnvironment()));
        TenantSchemaResolver resolver = newResolver(false);
        assertEquals(TenantSchemaResolver.DEFAULT_SCHEMA, resolver.resolveCurrentTenantIdentifier());
    }

    @Test
    void insecureDevProfileForcesLegacyMode() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("insecure");
        assertFalse(TenantSchemaResolver.effectiveFailClosed(true, environment));
    }

    @Test
    void failClosedWithoutContextNeverResolvesToPublic_viaHibernateOrTheStaticRawJdbcPath() {
        TenantSchemaResolver resolver = newResolver(true);
        assertEquals(TenantSchemaResolver.NO_CONTEXT_SCHEMA, resolver.resolveCurrentTenantIdentifier());
        assertEquals(TenantSchemaResolver.NO_CONTEXT_SCHEMA, TenantSchemaResolver.currentSchemaName());
    }

    @Test
    void failClosedWithTenantContextStillResolvesTheTenantSchema() {
        TenantContext.set(TenantContext.of("t_abcdefghijklmnopqrstuvwxyz", "sub", Set.of()));
        TenantSchemaResolver resolver = newResolver(true);
        assertEquals("t_abcdefghijklmnopqrstuvwxyz", resolver.resolveCurrentTenantIdentifier());
    }

    @Test
    void explicitPlatformScopeResolvesToPublicAndIsCountedAsSuch() {
        TenantSchemaResolver resolver = newResolver(true);
        String inside = PlatformScope.call(resolver::resolveCurrentTenantIdentifier);
        assertEquals(TenantSchemaResolver.DEFAULT_SCHEMA, inside);
        assertEquals(1.0, fallbackCount("platform_scope"));
        // The marker is removed afterwards - the very next call fails closed again.
        assertFalse(PlatformScope.isActive());
        assertEquals(TenantSchemaResolver.NO_CONTEXT_SCHEMA, resolver.resolveCurrentTenantIdentifier());
    }

    @Test
    void platformScopeNeverOverridesARealTenantContext() {
        TenantContext.set(TenantContext.of("tenant_b", "sub", Set.of()));
        TenantSchemaResolver resolver = newResolver(true);
        assertEquals("tenant_b", PlatformScope.call(resolver::resolveCurrentTenantIdentifier));
    }

    @Test
    void platformScopeDoesNotMaskTheLostTenantContextSignature() {
        SecurityContextHolder.getContext().setAuthentication(tenantJwtAuthentication("tenant_b"));
        TenantSchemaResolver resolver = newResolver(true);
        assertThrows(MissingTenantContextException.class,
            () -> PlatformScope.call(resolver::resolveCurrentTenantIdentifier));
    }

    @Test
    void aTenantIdNamingASystemSchemaIsRefusedEvenWithTheEscapeHatchOn() {
        TenantSchemaResolver resolver = newResolver(false);
        for (String hostile : List.of("control_plane", "ag_catalog", "pg_catalog", "information_schema",
            TenantSchemaResolver.NO_CONTEXT_SCHEMA)) {
            TenantContext.set(TenantContext.of(hostile, "sub", Set.of()));
            assertThrows(MissingTenantContextException.class, resolver::resolveCurrentTenantIdentifier, hostile);
        }
    }

    private static JwtAuthenticationToken tenantJwtAuthentication(String tenantId) {
        Jwt.Builder jwt = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject("user-sub-123");
        if (tenantId != null) {
            jwt.claim("tenant_id", tenantId);
        } else {
            // Jwt.Builder requires at least one claim; subject() above
            // already added one, so nothing extra needed here.
            jwt.claim("scope", "openid");
        }
        return new JwtAuthenticationToken(jwt.build(), List.of());
    }
}
