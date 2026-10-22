package com.agenticai.core.tenancy;

import com.agenticai.core.security.JwtClaimPaths;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.core.env.Environment;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Phase 0 Stage 4 - tells Hibernate which Postgres schema to use for the current request, sourced
 * from {@link TenantContext} (populated by {@code TenantContextFilter} from the JWT's {@code
 * tenant_id} claim, Stage 3).
 *
 * <p>Registers itself via {@link HibernatePropertiesCustomizer} rather than a {@code
 * spring.jpa.properties.hibernate.*} YAML property - Spring Boot's auto-configured {@code
 * EntityManagerFactory} does not automatically discover {@code CurrentTenantIdentifierResolver}
 * beans on its own (open Hibernate issue HHH-15422); this is the officially documented way to wire
 * one in - see Spring's own blog post, "How to integrate Hibernate's Multitenant feature with
 * Spring Data JPA in a Spring Boot application" (spring.io/blog/2022/07/31/...), which this class's
 * shape follows.
 *
 * <p><b>Defaults to the {@value #DEFAULT_SCHEMA} schema</b> whenever there is no {@link
 * TenantContext} (health checks, the "security" profile off, or any request Spring Security let
 * through without a JWT) - {@code public} is exactly where every table already lived from the
 * pre-Stage-4 baseline (see {@code V1__legacy_mvp_baseline.sql}, which never specified a schema),
 * so this stage introduces multi-tenancy without requiring any data migration for whatever existed
 * before it, and the "security" profile being off continues to behave exactly as it always has.
 *
 * <h2>F-14 (2026-08-07) - the public fallback is instrumented, not silent</h2>
 *
 * The fallback used to be invisible, and it has hidden at least one real cross-tenant bug (see
 * {@code TenantContextPropagatingToolCallback}'s javadoc: approval-policy checks silently read from
 * {@code public}, so a tenant-admin's "require approval" setting was never enforced). Every
 * fallback now increments the {@value #FALLBACK_METRIC} counter (rendered by the Prometheus
 * exporter as {@code tenant_context_public_fallback_total}) tagged with {@code kind} (which
 * resolution path, below) and {@code caller} (nearest non-infrastructure class on the stack). The
 * map of who legitimately resolves tenant-less, established by walking every {@code
 * TenantContext.set} call site and the control-plane entity set:
 *
 * <ul>
 *   <li><b>Hibernate bootstrap/metadata</b> - goes through {@link
 *       SchemaMultiTenantConnectionProvider#getAnyConnection()}, never reaches this resolver at
 *       all. Legitimate, uninstrumented.
 *   <li><b>{@code kind=platform_admin}</b> (legitimate) - platform-admin console traffic. A {@code
 *       ROLE_platform-admin} JWT carries no {@code tenant_id} BY DESIGN ({@code
 *       TenantContextFilter}'s documented exemption), and every entity the {@code /api/platform/**}
 *       surface touches is {@code @Table(schema = "control_plane")}-qualified (all 27 control-plane
 *       entities are - {@code Tenant}, {@code AppUser}, {@code PlanLimits}, {@code
 *       PlatformSetting}, {@code FeatureFlag}, the demo sandbox set, the {@code Platform*} provider
 *       set, ...), so the {@code public} search_path this resolver hands back is never actually
 *       consulted for table resolution. Counted, never logged.
 *   <li><b>{@code kind=tenantless_context}</b> (ambiguous, counted only) - a {@link TenantContext}
 *       exists but its tenantId is null/blank and the principal is not a platform admin. The one
 *       known-legitimate member is onboarding ({@code POST /api/onboarding}: authenticated, no
 *       tenant yet, touches control_plane only - the filter's second documented exemption);
 *       anything else means someone hand-built a degenerate context. The resolver cannot tell those
 *       apart, so per the F-14 brief this kind counts but does NOT log - the {@code caller} tag is
 *       the staging triage handle (a caller that is not the onboarding/provisioning path is a bug).
 *   <li><b>{@code kind=no_context}</b> (ambiguous, counted only) - no {@link TenantContext} and no
 *       authenticated JWT on the thread. The legitimate populations: unauthenticated public
 *       endpoints (signup, demo sandbox, actuator/health, public feature reads - the filter never
 *       runs a set) and background/scheduled jobs' control-plane phases (listing {@code
 *       control_plane.tenant} before setting a per-tenant context, {@code
 *       TenantSchemaIterator}-style). The bug population: a thread doing TENANT work whose context
 *       was lost on an async hop that also lost the Spring Security context - which is exactly what
 *       Reactor scheduler hops do, i.e. the historical tool-callback bug would land HERE, not in
 *       the suspicious kind below. Indistinguishable at the resolver, so: counted, never logged (a
 *       WARN here would fire on every health check), and the {@code caller} tag is again the triage
 *       handle - {@code ApprovalPolicyService} as a caller is a propagation bug, {@code
 *       TenantProvisioningService} is not.
 *   <li><b>{@code kind=authenticated_no_context}</b> (reliably suspicious) - the thread's {@code
 *       SecurityContextHolder} still holds a validated JWT WITH a non-blank {@code tenant_id}
 *       claim, yet {@link TenantContext} is empty. {@code TenantContextFilter} should have
 *       populated it before any downstream code ran, so this means filter ordering regressed or an
 *       executor propagated the security context without propagating {@code TenantContext} (e.g.
 *       {@code DelegatingSecurityContextExecutor} without the tenant equivalent). This is the ONLY
 *       kind with no legitimate member: rate-limited WARN (max one per minute across all kinds'
 *       worth of suspicious hits) and, when {@code app.tenancy.fail-on-missing-context=true}, a
 *       {@link MissingTenantContextException} instead of the fallback.
 * </ul>
 *
 * <h2>SEC-017 (2026-09-30) - fail closed by default</h2>
 *
 * {@code app.tenancy.fail-on-missing-context} now defaults to {@code true} (and is forced off only
 * under the dev-only {@code insecure} profile, which has no tenants at all). In that mode:
 *
 * <ul>
 *   <li>a real tenant context resolves exactly as before;
 *   <li>{@code kind=authenticated_no_context} throws {@link MissingTenantContextException};
 *   <li>{@code kind=no_context} and {@code kind=tenantless_context} resolve to {@value
 *       #NO_CONTEXT_SCHEMA} - a schema that is never created and holds no tables. Every {@code
 *       control_plane}-qualified entity keeps working (they never consult {@code search_path}), so
 *       signup, onboarding, webhooks, health and every job's control-plane phase are unaffected,
 *       but any tenant-table read or write on a context-less thread now fails ({@code relation ...
 *       does not exist}, or "no schema has been selected to create in") instead of silently using
 *       the shared {@code public} copy of the tenant tables. That is the same search_path shape a
 *       real tenant session has (only its own schema), so extension/function resolution behaves
 *       identically;
 *   <li>{@code kind=platform_admin} (a {@code ROLE_platform-admin} context) and {@code
 *       kind=platform_scope} (code wrapped in {@link PlatformScope}) are the explicit
 *       platform-scoped paths and still resolve to {@code public}.
 * </ul>
 *
 * Setting {@code app.tenancy.fail-on-missing-context=false} is the documented escape hatch: it
 * restores the pre-SEC-017 behaviour (public fallback for every kind except the suspicious one,
 * count-only). Independently of the flag, a tenant id that sanitises to a platform/system schema
 * ({@code control_plane}, {@code pg_*}, {@code information_schema}, {@code ag_catalog}) is refused.
 *
 * <p><b>Historical note (pre-SEC-017):</b> {@code app.tenancy.fail-on-missing-context} stayed
 * {@code false} until the {@code kind=authenticated_no_context} counter proves empty over a real
 * staging soak - flipping it converts a data-integrity bug into a hard 500, which is the right
 * trade only once we know it never fires on traffic we forgot about. It deliberately does NOT throw
 * on the two ambiguous kinds: {@code tenantless_context} contains onboarding and {@code no_context}
 * contains every health check, so failing closed there would take down legitimate traffic. Making
 * those kinds fail-closable requires a refactor this change deliberately avoids: an explicit
 * "tenantless by design" marker on {@link TenantContext} itself, set by {@code TenantContextFilter}
 * (platform-admin/onboarding), the webhook controllers, and every background job's control-plane
 * phase - so that plain absence becomes a hard signal. That touches the filter and every background
 * entry point, i.e. several other agents' scopes.
 *
 * <p>The {@code caller} tag costs a {@link StackWalker} walk capped at {@value #MAX_WALK_FRAMES}
 * frames - only ever paid on the fallback path (a request that resolves a real tenant schema never
 * walks), whose volume is health checks + public endpoints + admin traffic, all of which are about
 * to open a database connection anyway; microseconds against milliseconds.
 *
 * <p><b>Deliberately no database lookup here</b> (e.g. resolving {@code
 * control_plane.tenant.schema_name} by the JWT's {@code tenant_id} claim) - this method runs on
 * every Hibernate session's tenant resolution, a hot path not worth a query against yet. Stage 4's
 * own scope is a direct, sanitized mapping from the claim value to a schema name (an admin setting
 * a Keycloak user's {@code tenant_id} attribute to, say, {@code tenant_b} should choose a value
 * that is already a valid, intentional schema name). Stage 5's {@code TenantProvisioningService}
 * now shares this exact convention via {@link SchemaNameSanitizer} - extracted so "how does a raw
 * string become a schema name" can never quietly drift between the two call sites (this resolver's
 * hot-path JWT claim lookup, and Stage 5's one-time org-name-to-schema-name decision at signup).
 */
@Component
public class TenantSchemaResolver
        implements CurrentTenantIdentifierResolver<String>, HibernatePropertiesCustomizer {

    /**
     * Where every table already lived before Stage 4, and the fallback for any unauthenticated /
     * no-tenant-context request.
     */
    public static final String DEFAULT_SCHEMA = SchemaNameSanitizer.DEFAULT_SCHEMA;

    /**
     * Micrometer counter name for every public-schema fallback - dotted per this codebase's
     * convention ({@code vision.rejections}, {@code llm.gateway.rejections}); the Prometheus
     * exporter renders it as {@code tenant_context_public_fallback_total}. Tags: {@code kind} (see
     * class javadoc's map) and {@code caller}.
     */
    public static final String FALLBACK_METRIC = "tenant.context.public.fallback";

    /**
     * SEC-017 - what a context-less, non-platform-scoped thread resolves to in fail-closed mode: a
     * schema name that is never created (and is reserved by {@link TenantSchemaGuard}, so no tenant
     * can ever be given it).
     */
    public static final String NO_CONTEXT_SCHEMA = "tenant_context_missing";

    private static final Logger log = LoggerFactory.getLogger(TenantSchemaResolver.class);

    private static final long WARN_INTERVAL_MILLIS = 60_000;
    private static final int MAX_WALK_FRAMES = 64;

    /**
     * Bridges the static {@link #currentSchemaName()} entry point (kept static for the knowledge
     * module's raw-JDBC call sites - see that method's javadoc) to this bean's instrumentation.
     * Last-constructed instance wins, which in production is simply "the one singleton Spring
     * built"; unit tests that construct their own instances must reset via {@link
     * #resetRegisteredInstanceForTests()} so a fail-closed test instance can't leak into an
     * unrelated suite running in the same fork.
     */
    private static volatile TenantSchemaResolver registered;

    private final ObjectProvider<MeterRegistry> meterRegistries;
    private final boolean failOnMissingContext;
    private final String tenantClaim;
    private final AtomicLong lastSuspiciousWarnAtMillis = new AtomicLong();
    private volatile MeterRegistry cachedRegistry;

    /**
     * {@link ObjectProvider} rather than a direct {@link MeterRegistry} dependency because this
     * bean is a {@link HibernatePropertiesCustomizer} and is therefore instantiated unusually
     * early, while the {@code EntityManagerFactory} is being built - eagerly pulling the registry
     * in at that point would force it to initialize before Boot's meter-binder post-processing runs
     * ("bean is not eligible for getting processed by all BeanPostProcessors"). Resolved lazily on
     * the first fallback instead, and a missing registry (narrow test contexts) just means counting
     * is skipped - resolution behavior is never affected.
     */
    public TenantSchemaResolver(
            ObjectProvider<MeterRegistry> meterRegistries, boolean failOnMissingContext) {
        this(meterRegistries, failOnMissingContext, JwtClaimPaths.DEFAULT_TENANT_CLAIM);
    }

    /**
     * @param tenantClaim the JWT claim carrying the tenant/schema id - {@code
     *     app.security.oidc.tenant-claim} (generic-OIDC claim mapping, see {@code OidcClaimMapping}
     *     in core/security); blank falls back to the Keycloak default {@code tenant_id}. Used only
     *     to classify the {@code authenticated_no_context} fallback kind - the presence check must
     *     look where the deployment actually puts the tenant id.
     */
    public TenantSchemaResolver(
            ObjectProvider<MeterRegistry> meterRegistries,
            boolean failOnMissingContext,
            String tenantClaim) {
        this.meterRegistries = meterRegistries;
        this.failOnMissingContext = failOnMissingContext;
        this.tenantClaim =
                (tenantClaim == null || tenantClaim.isBlank())
                        ? JwtClaimPaths.DEFAULT_TENANT_CLAIM
                        : tenantClaim;
        registered = this;
    }

    /**
     * Spring's constructor. SEC-017: {@code app.tenancy.fail-on-missing-context} defaults to {@code
     * true}; the dev-only {@code insecure} profile (no authentication, no tenants, everything in
     * {@code public}) forces it off, loudly, because fail-closed there would just break every
     * request.
     */
    @Autowired
    public TenantSchemaResolver(
            ObjectProvider<MeterRegistry> meterRegistries,
            @Value("${app.tenancy.fail-on-missing-context:true}") boolean failOnMissingContext,
            Environment environment,
            @Value("${app.security.oidc.tenant-claim:tenant_id}") String tenantClaim) {
        this(meterRegistries, effectiveFailClosed(failOnMissingContext, environment), tenantClaim);
    }

    static boolean effectiveFailClosed(boolean configured, Environment environment) {
        if (configured && environment != null && environment.matchesProfiles("insecure")) {
            log.warn(
                    "app.tenancy.fail-on-missing-context forced to false: the dev-only 'insecure' profile has no "
                            + "tenant contexts, so every query legitimately runs against the public schema.");
            return false;
        }
        if (!configured) {
            log.warn(
                    "app.tenancy.fail-on-missing-context=false - context-less threads fall back to the public "
                            + "schema (pre-SEC-017 behaviour). This is an escape hatch, not a supported production setting.");
        }
        return configured;
    }

    @Override
    public String resolveCurrentTenantIdentifier() {
        return resolveSchema();
    }

    /**
     * The same tenant-id-claim-to-schema-name algorithm Hibernate uses for every JPA query, exposed
     * as a reusable static method for the {@code knowledge} module's raw-JDBC queries against
     * {@code knowledge_chunks} (pgvector has no Hibernate {@code UserType} for the {@code vector}
     * column type here, so that module can't just go through the normal tenant-routed {@code
     * EntityManager} - see {@code KnowledgeSearchService}/{@code KnowledgeIngestionService}).
     * Extracted after finding those two services were querying an entirely schema-unqualified
     * {@code knowledge_chunks} table - every tenant's uploaded documents were visible to every
     * other tenant's searches, since Spring's autoconfigured {@code JdbcTemplate} pulls a
     * connection straight from the shared {@code DataSource} bean and never goes through this
     * class's Hibernate SPI hook at all. One algorithm, one place, so the raw-JDBC call sites can
     * never quietly drift from what Hibernate actually does for everything else.
     *
     * <p>F-14: routes through the Spring-built singleton when one exists, so the knowledge module's
     * fallbacks are counted (and fail closed) exactly like Hibernate's. When no instance has been
     * constructed (narrow test contexts that never touch this bean), behaves exactly as it did
     * before F-14: resolve, uninstrumented.
     */
    public static String currentSchemaName() {
        TenantSchemaResolver resolver = registered;
        if (resolver != null) {
            return resolver.resolveSchema();
        }
        return TenantContext.current()
                .map(
                        ctx ->
                                refuseSystemSchema(
                                        SchemaNameSanitizer.sanitize(
                                                ctx.getTenantId(), DEFAULT_SCHEMA)))
                .orElse(DEFAULT_SCHEMA);
    }

    /** See {@link #registered}'s javadoc - test-fork hygiene only. */
    static void resetRegisteredInstanceForTests() {
        registered = null;
    }

    private String resolveSchema() {
        Optional<TenantContext> current = TenantContext.current();
        if (current.isPresent()) {
            String tenantId = current.get().getTenantId();
            if (tenantId != null && !tenantId.isBlank()) {
                return refuseSystemSchema(SchemaNameSanitizer.sanitize(tenantId, DEFAULT_SCHEMA));
            }
        }
        FallbackKind kind = classifyFallback(current.orElse(null));
        // An explicit PlatformScope marks a deliberately tenant-less path. It
        // never masks the propagation-bug signature (a tenant JWT on the
        // thread with no TenantContext) - that stays the suspicious kind.
        if (PlatformScope.isActive()
                && (kind == FallbackKind.NO_CONTEXT || kind == FallbackKind.TENANTLESS_CONTEXT)) {
            kind = FallbackKind.PLATFORM_SCOPE;
        }
        return publicFallback(kind);
    }

    /**
     * SEC-001/SEC-017 hardening: a tenant id (JWT claim / explicit context) that sanitises to a
     * platform or system schema is never a tenant. The legacy {@code public} value is left alone -
     * a few webhook paths set it explicitly for unmapped senders (a separate, known finding).
     */
    private static String refuseSystemSchema(String schema) {
        if (!DEFAULT_SCHEMA.equals(schema) && TenantSchemaGuard.isProtectedSchema(schema)) {
            throw new MissingTenantContextException(
                    "Tenant id resolves to the reserved schema '"
                            + schema
                            + "' - refusing to route tenant queries there");
        }
        return schema;
    }

    /**
     * The class javadoc's map, in code. {@code context} is the present-but- tenantless context, or
     * null when there was none at all.
     */
    private FallbackKind classifyFallback(TenantContext context) {
        if (context != null) {
            boolean platformAdmin =
                    context.getRoles() != null && context.hasRole("ROLE_platform-admin");
            return platformAdmin ? FallbackKind.PLATFORM_ADMIN : FallbackKind.TENANTLESS_CONTEXT;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            String claim = JwtClaimPaths.valueAsString(jwtAuth.getToken(), tenantClaim);
            if (claim != null && !claim.isBlank()) {
                return FallbackKind.AUTHENTICATED_NO_CONTEXT;
            }
        }
        return FallbackKind.NO_CONTEXT;
    }

    private String publicFallback(FallbackKind kind) {
        String caller = walkbackCaller();
        count(kind, caller);
        if (failOnMissingContext
                && (kind == FallbackKind.NO_CONTEXT || kind == FallbackKind.TENANTLESS_CONTEXT)) {
            // SEC-017: fail closed without breaking control-plane access (see
            // the SEC-017 section of the class javadoc).
            return NO_CONTEXT_SCHEMA;
        }
        if (kind == FallbackKind.AUTHENTICATED_NO_CONTEXT) {
            maybeWarn(caller);
            if (failOnMissingContext) {
                // Counted and warned BEFORE throwing - the whole point of
                // fail-closed mode is that the metric/log trail survives it.
                throw new MissingTenantContextException(
                        "TenantContext is empty but the current thread carries an authenticated JWT with a "
                                + "tenant_id claim (nearest caller: "
                                + caller
                                + "). TenantContextFilter should have "
                                + "populated it - filter ordering regressed, or an async hop propagated the Spring "
                                + "Security context without propagating TenantContext. Failing closed "
                                + "(app.tenancy.fail-on-missing-context=true) instead of silently querying the "
                                + "public schema.");
            }
        }
        return DEFAULT_SCHEMA;
    }

    private void count(FallbackKind kind, String caller) {
        MeterRegistry registry = meterRegistry();
        if (registry == null) {
            return;
        }
        Counter.builder(FALLBACK_METRIC)
                .description(
                        "Tenant-schema resolutions that fell back to the public schema, by resolution "
                                + "path (kind) and nearest non-infrastructure caller. See TenantSchemaResolver's javadoc "
                                + "for which kinds are legitimate.")
                .tag("kind", kind.tag)
                .tag("caller", caller)
                .register(registry)
                .increment();
    }

    private MeterRegistry meterRegistry() {
        MeterRegistry registry = cachedRegistry;
        if (registry == null) {
            registry = meterRegistries.getIfAvailable();
            cachedRegistry = registry;
        }
        return registry;
    }

    private void maybeWarn(String caller) {
        long now = System.currentTimeMillis();
        long last = lastSuspiciousWarnAtMillis.get();
        if (now - last >= WARN_INTERVAL_MILLIS
                && lastSuspiciousWarnAtMillis.compareAndSet(last, now)) {
            log.warn(
                    "Tenant-schema resolution fell back to the public schema on a thread that still carries "
                            + "an authenticated JWT with a tenant_id claim (nearest caller: {}). This is the "
                            + "TenantContext-propagation bug signature - see TenantSchemaResolver's F-14 javadoc. "
                            + "Rate-limited to one warning per minute; the {} counter (kind=authenticated_no_context) "
                            + "has the true rate.",
                    caller,
                    FALLBACK_METRIC);
        }
    }

    /**
     * Nearest class on the stack that is not this resolver, Hibernate, Spring, the JDK, or the
     * connection pool - i.e. the repository/service that opened the session. Simple name only, to
     * keep the metric tag readable; cardinality is bounded by the number of distinct call-site
     * classes in the codebase.
     */
    private static String walkbackCaller() {
        return StackWalker.getInstance()
                .walk(
                        frames ->
                                frames.limit(MAX_WALK_FRAMES)
                                        .map(StackWalker.StackFrame::getClassName)
                                        .filter(TenantSchemaResolver::isApplicationFrame)
                                        .findFirst())
                .map(className -> className.substring(className.lastIndexOf('.') + 1))
                .orElse("unknown");
    }

    private static boolean isApplicationFrame(String className) {
        return !className.equals(TenantSchemaResolver.class.getName())
                && !className.startsWith("org.hibernate")
                && !className.startsWith("org.springframework")
                && !className.startsWith("java.")
                && !className.startsWith("jdk.")
                && !className.startsWith("sun.")
                && !className.startsWith("com.zaxxer");
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return true;
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }

    private enum FallbackKind {
        PLATFORM_ADMIN("platform_admin"),
        TENANTLESS_CONTEXT("tenantless_context"),
        AUTHENTICATED_NO_CONTEXT("authenticated_no_context"),
        NO_CONTEXT("no_context"),
        PLATFORM_SCOPE("platform_scope");

        private final String tag;

        FallbackKind(String tag) {
            this.tag = tag;
        }
    }
}
