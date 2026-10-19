package com.agenticai.core.tenancy.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.PlatformSettingsService;
import com.agenticai.core.tenancy.TenantRepository;
import com.agenticai.core.tenancy.TenantSchemaMigrator;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The demo sandbox is the platform's only anonymous, unauthenticated surface
 * that touches real tenant Postgres schemas, which makes two small pieces of
 * {@link DemoSessionService} disproportionately risk-bearing:
 *
 * <p><b>{@code inDemoTenantContext}</b> - every demo request that reads or
 * writes tenant-scoped data goes through this one scope. If the binding were
 * ever lost, Hibernate's {@code TenantSchemaResolver} silently falls back to
 * the {@code public} schema and the sandbox "works" while writing into the
 * SHARED schema; if the finally-clear were ever lost, a demo request's
 * context could leak onto a pooled thread later serving a real tenant. Both
 * failure modes are silent cross-tenant exposure, not exceptions - exactly
 * the class of regression only a test can catch.
 *
 * <p><b>{@code resolve}</b> - the token lookup MUST go through {@code
 * DemoTokens#hash}, never the raw value (a raw-token lookup would only ever
 * match if the column stored plaintext secrets, so a "fixed" lookup that
 * compares raw values is really a plaintext-storage regression); and the
 * null/blank/oversized guards must short-circuit BEFORE hashing, because an
 * unauthenticated endpoint that hashes attacker-sized input on every probe
 * is a free DoS lever.
 */
class DemoSessionServiceTest {

    private DemoSessionRepository demoSessionRepository;
    private DemoSessionService service;

    @BeforeEach
    void setUp() {
        demoSessionRepository = mock(DemoSessionRepository.class);
        // The constructor is wide, but the two behaviours under test only
        // ever touch demoSessionRepository (resolve) and the static
        // TenantContext (inDemoTenantContext) - every other collaborator
        // stays a deliberately unstubbed mock so any unexpected interaction
        // with infrastructure would fail loudly (null Optional NPEs) rather
        // than silently succeed.
        service = new DemoSessionService(
            mock(TenantRepository.class),
            demoSessionRepository,
            mock(DemoChatMessageRepository.class),
            mock(DemoSessionRecorder.class),
            mock(TenantSchemaMigrator.class),
            mock(PlatformSettingsService.class),
            mock(DemoImageService.class),
            Optional.empty(), // no seeder on the classpath - irrelevant to these paths
            mock(DataSource.class),
            "agentic-ai",
            "demo_shared",
            5,   // lifetime-days
            20,  // message-limit
            2,   // max-live-sessions-per-ip
            24,  // inactivity-timeout-hours
            24   // editable-hours
        );
    }

    @AfterEach
    void clearTenantContext() {
        // The real static ThreadLocal is exercised, never mocked - clear it so
        // nothing leaks into other tests running in the same fork.
        TenantContext.clear();
    }

    private static DemoSession sessionForSchema(String schema) {
        Instant createdAt = Instant.parse("2026-08-01T00:00:00Z");
        return new DemoSession(
            DemoTokens.hash("irrelevant"), UUID.randomUUID(), schema, null, "203.0.113.9",
            null, createdAt, createdAt.plus(Duration.ofHours(24))
        );
    }

    // -- inDemoTenantContext: the tenant-isolation scope --

    @Test
    void supplierRunsWithTenantContextBoundToTheSandboxSchemaAndAnEmptyRoleSet() {
        DemoSession session = sessionForSchema("demo_ab12cd34");
        AtomicReference<TenantContext> observedInsideSupplier = new AtomicReference<>();

        String result = service.inDemoTenantContext(session, () -> {
            observedInsideSupplier.set(TenantContext.current().orElse(null));
            return "supplier-result";
        });

        // The supplier's return value passes through untouched.
        assertThat(result).isEqualTo("supplier-result");

        TenantContext bound = observedInsideSupplier.get();
        // Without this binding, Hibernate falls back to the public schema and
        // the sandbox silently writes into shared tables - THE failure mode
        // this scope exists to prevent.
        assertThat(bound).isNotNull();
        assertThat(bound.getTenantId()).isEqualTo("demo_ab12cd34");
        // An anonymous demo caller must hold NO roles - anything role-gated
        // (tenant-admin surfaces) has to refuse it even if a demo token ever
        // reached such an endpoint.
        assertThat(bound.getRoles()).isEmpty();
        // Forensics label, not an identity - pinned loosely so the prefix
        // convention survives refactors without over-specifying the id part.
        assertThat(bound.getSubject()).startsWith("demo:");

        // And after a normal return, the binding is gone from this thread.
        assertThat(TenantContext.current()).isEmpty();
    }

    @Test
    void tenantContextIsClearedEvenWhenTheSupplierThrows() {
        DemoSession session = sessionForSchema("demo_ab12cd34");

        assertThatThrownBy(() ->
            service.inDemoTenantContext(session, () -> {
                throw new IllegalStateException("simulated downstream failure");
            })
        ).isInstanceOf(IllegalStateException.class);

        // The finally-clear is the leak guard: this (pooled) thread will
        // serve some real tenant's request next, and a lingering demo
        // context would route that request into the demo schema.
        assertThat(TenantContext.current()).isEmpty();
    }

    // -- resolve: token guards and hashed lookup --

    @Test
    void nullBlankAndOversizedTokensShortCircuitWithoutHashingOrTouchingTheRepository() {
        assertThat(service.resolve(null)).isEmpty();
        assertThat(service.resolve("")).isEmpty();
        assertThat(service.resolve("   ")).isEmpty();
        // Just past the guard: 513 chars. Dropping this guard turns an
        // unauthenticated endpoint into "SHA-256 whatever size body the
        // attacker likes, on every probe".
        assertThat(service.resolve("x".repeat(513))).isEmpty();

        // Not merely empty results - the repository must never even be asked,
        // so malformed probes cost nothing beyond the length check.
        verify(demoSessionRepository, never()).findByTokenHash(anyString());
    }

    @Test
    void tokenAtExactlyTheFiveTwelveCharBoundaryIsStillLookedUp() {
        // Boundary pin so an off-by-one ("> 512" vs ">= 512") can't silently
        // reject the longest legitimate value the guard was sized for.
        String maxLengthToken = "x".repeat(512);
        when(demoSessionRepository.findByTokenHash(DemoTokens.hash(maxLengthToken)))
            .thenReturn(Optional.empty());

        service.resolve(maxLengthToken);

        verify(demoSessionRepository).findByTokenHash(DemoTokens.hash(maxLengthToken));
    }

    @Test
    void validTokenIsLookedUpByItsTrimmedSha256HashNeverByTheRawValue() {
        DemoSession session = sessionForSchema("demo_ab12cd34");
        // Whitespace-padded on purpose: a token copy-pasted out of
        // localStorage/devtools often arrives with stray whitespace, and the
        // stored hash was computed over the exact token - so the lookup must
        // hash the TRIMMED value to match.
        String rawToken = "  demo-token-raw-value  ";
        String expectedHash = DemoTokens.hash("demo-token-raw-value");
        when(demoSessionRepository.findByTokenHash(expectedHash)).thenReturn(Optional.of(session));

        Optional<DemoSession> resolved = service.resolve(rawToken);

        assertThat(resolved).containsSame(session);
        // A regression that queries by the raw token would only ever match a
        // column storing plaintext secrets - the hashed lookup IS the
        // hashed-at-rest guarantee, so pin both directions explicitly.
        verify(demoSessionRepository).findByTokenHash(expectedHash);
        verify(demoSessionRepository, never()).findByTokenHash(rawToken);
        verify(demoSessionRepository, never()).findByTokenHash("demo-token-raw-value");
    }
}
