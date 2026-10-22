package com.agenticai.core.platform.reliability;

import com.agenticai.core.platform.ops.LangfuseEndpoints;
import com.agenticai.core.platform.reliability.ProbeRunner.Outcome;
import com.agenticai.core.platform.reliability.ProbeRunner.Spec;
import com.agenticai.core.platform.reliability.ReliabilityApi.DependenciesResponse;
import com.agenticai.core.platform.reliability.ReliabilityApi.DependencyEntry;
import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.PlatformHostedLlmProviderRepository;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolKey;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Platform-admin dependency health: one row per dependency the platform actually leans on, each
 * measured by a real, cheap, non-billable probe. {@code GET /api/platform/health/dependencies}.
 * This is the per-dependency detail view; {@code /api/platform/health} (the older passthrough) and
 * the public {@code /api/public/status} stay as they are.
 *
 * <ul>
 *   <li><b>Postgres</b> - {@code SELECT 1} through the pool plus Hikari saturation (active/max,
 *       threads waiting).
 *   <li><b>Redis</b> - {@code PING}.
 *   <li><b>Keycloak</b> - the JWKS endpoint answers (reachability only).
 *   <li><b>Sidecar</b> - its unauthenticated {@code /health} (liveness only; the embedding model
 *       itself is not exercised).
 *   <li><b>Kafka</b> - TCP connect to the first bootstrap broker, only when workflow events are
 *       enabled.
 *   <li><b>Hosted-LLM pool</b> - each ENABLED row: an unauthenticated GET to its base URL. Any HTTP
 *       answer below 500 counts as reachable. NO credential is sent and NO completion is requested,
 *       so a revoked key or an exhausted quota is deliberately not detected here.
 *   <li><b>E2B / Brave / Meta credentials</b> - presence only, never called.
 *   <li><b>Temporal</b> - TCP connect to {@code app.workflow.temporal.target}, only when the {@code
 *       temporal} profile is active (otherwise UNKNOWN).
 *   <li><b>Code-intel</b> - its unauthenticated {@code /health}, only when {@code
 *       code-intel.enabled}.
 *   <li><b>Object storage</b> - MinIO's unauthenticated {@code /minio/health/live} on {@code
 *       app.storage.endpoint}; any non-5xx answer from a non-MinIO S3-compatible store counts as
 *       reachable (credentials not exercised).
 *   <li><b>Langfuse</b> - its unauthenticated {@code /api/public/health} on the base URL derived
 *       from the OTLP traces endpoint (or {@code app.langfuse.base-url}).
 *   <li><b>Academy / App Builder</b> - their health endpoints, only when the optional {@code
 *       app.platform.ops.academy-base-url} / {@code app-builder-base-url} are set; rows are omitted
 *       otherwise (core has no other address for either).
 * </ul>
 *
 * <p><b>Cannot be used to hammer anything.</b> Every probe has a hard timeout (default 2 s) and
 * runs in parallel; the finished response is cached in memory for {@code
 * app.platform.reliability.cache-ttl-seconds} (default 30) and calls are serialised, so any number
 * of callers cause at most one probe round per window per replica. There is no force-refresh
 * parameter.
 *
 * <p><b>Honest absence.</b> A dependency that is not configured here reads {@code UNKNOWN}, never
 * {@code DOWN}. Details are written by this class with no hostnames, URLs or secrets (exceptions
 * are reported by class only). Nothing here runs on the chat path.
 *
 * <p>Every probe added for the ops console (2026-10-09) is optional: an unset URL reads UNKNOWN
 * (or, for academy/app-builder, no row) and no new configuration is required.
 */
@Service
public class DependencyHealthService {

    private static final Logger log = LoggerFactory.getLogger(DependencyHealthService.class);

    static final String POSTGRES = "postgres";

    /**
     * A round with a failing stage is cached only this long (bounded by the normal TTL), so a
     * broken stage is not re-probed on every call.
     */
    static final long FAILURE_CACHE_SECONDS = 5;

    private record Cached(Instant at, DependenciesResponse response, long ttlSeconds) {}

    private final boolean enabled;
    private final long cacheTtlSeconds;
    private final Clock clock;
    private final ExecutorService executor;
    private final List<Supplier<List<DependencyEntry>>> stages;

    private Cached cache;
    private boolean warnedFailure;

    @Autowired
    public DependencyHealthService(
            ObjectProvider<DataSource> dataSource,
            ObjectProvider<StringRedisTemplate> redis,
            ObjectProvider<PlatformHostedLlmProviderRepository> poolRepository,
            ObjectProvider<PlatformToolCredentialService> toolCredentials,
            @Value("${app.platform.reliability.enabled:true}") boolean enabled,
            @Value("${app.platform.reliability.cache-ttl-seconds:30}") long cacheTtlSeconds,
            @Value("${app.platform.reliability.probe-timeout-ms:2000}") long probeTimeoutMs,
            @Value("${app.keycloak.jwk-set-uri:}") String jwkSetUri,
            // app.sidecar.enabled=false reads as "no sidecar configured" (NOT_CONFIGURED /
            // UNKNOWN), not as an outage.
            @Value(
                            "#{'${app.sidecar.enabled:true}'.equalsIgnoreCase('false') ? '' : '${app.sidecar.base-url:}'}")
                    String sidecarBaseUrl,
            @Value("${app.workflow-events.enabled:false}") boolean workflowEventsEnabled,
            @Value("${spring.kafka.bootstrap-servers:}") String kafkaBootstrap,
            @Value("${app.meta.webhook-verify-token:}") String metaWebhookToken,
            @Value("${app.meta.embedded-signup.app-id:}") String metaAppId,
            @Value("${app.meta.embedded-signup.app-secret:}") String metaAppSecret,
            Environment environment,
            @Value("${app.workflow.temporal.target:}") String temporalTarget,
            @Value("${code-intel.enabled:false}") boolean codeIntelEnabled,
            @Value("${code-intel.base-url:}") String codeIntelBaseUrl,
            @Value("${app.storage.endpoint:}") String storageEndpoint,
            @Value("${app.langfuse.base-url:}") String langfuseBaseUrl,
            @Value("${management.opentelemetry.tracing.export.otlp.endpoint:}")
                    String otlpTracesEndpoint,
            @Value("${app.platform.ops.academy-base-url:}") String academyBaseUrl,
            @Value("${app.platform.ops.app-builder-base-url:}") String appBuilderBaseUrl) {
        this.enabled = enabled;
        this.cacheTtlSeconds = Math.max(0, cacheTtlSeconds);
        this.clock = Clock.systemUTC();
        this.executor =
                TenantAwareExecutors.newVirtualThreadPerTaskExecutor("dependency-health-service-");
        // Floor of 200 ms so a mis-set 0 cannot turn every probe into an instant "no answer".
        Duration probeTimeout = Duration.ofMillis(Math.max(200L, probeTimeoutMs));
        ProbeRunner runner = new ProbeRunner(executor, probeTimeout, clock);
        DependencyProbes probes =
                new DependencyProbes(
                        HttpClient.newBuilder().connectTimeout(probeTimeout).build(),
                        probeTimeout,
                        clock);

        // Only presence is kept; the secret values never leave this constructor.
        boolean metaTokenPresent = !metaWebhookToken.isBlank();
        boolean metaAppIdPresent = !metaAppId.isBlank();
        boolean metaAppSecretPresent = !metaAppSecret.isBlank();

        List<Supplier<List<DependencyEntry>>> built = new ArrayList<>();
        built.add(
                () ->
                        runner.runAll(
                                List.of(
                                        new Spec(
                                                POSTGRES,
                                                "database",
                                                () -> probes.checkPostgres(dataSource)),
                                        new Spec("redis", "cache", () -> probes.checkRedis(redis)),
                                        new Spec(
                                                "keycloak-jwks",
                                                "auth",
                                                () -> probes.checkJwks(jwkSetUri)),
                                        new Spec(
                                                "sidecar",
                                                "ai",
                                                () -> probes.checkSidecar(sidecarBaseUrl)),
                                        new Spec(
                                                "kafka",
                                                "messaging",
                                                () ->
                                                        probes.checkKafka(
                                                                workflowEventsEnabled,
                                                                kafkaBootstrap)))));
        built.add(() -> probes.poolStage(runner, enabledPoolRows(poolRepository)));
        built.add(
                () ->
                        runner.runAll(
                                List.of(
                                        new Spec(
                                                "e2b-credential",
                                                "credential",
                                                () ->
                                                        probes.toolCredentialOutcome(
                                                                toolCredentials,
                                                                PlatformToolKey.E2B)),
                                        new Spec(
                                                "brave-search-credential",
                                                "credential",
                                                () ->
                                                        probes.toolCredentialOutcome(
                                                                toolCredentials,
                                                                PlatformToolKey.BRAVE_SEARCH)),
                                        new Spec(
                                                "meta-whatsapp-credentials",
                                                "credential",
                                                () ->
                                                        metaOutcome(
                                                                metaTokenPresent,
                                                                metaAppIdPresent,
                                                                metaAppSecretPresent)))));
        boolean temporalProfile = environment != null && environment.matchesProfiles("temporal");
        String langfuseBase = LangfuseEndpoints.baseUrl(langfuseBaseUrl, otlpTracesEndpoint);
        List<Spec> platformServices =
                new ArrayList<>(
                        List.of(
                                new Spec(
                                        "temporal",
                                        "workflow",
                                        () ->
                                                probes.checkTemporal(
                                                        temporalProfile, temporalTarget)),
                                new Spec(
                                        "code-intel",
                                        "ai",
                                        () ->
                                                codeIntelEnabled
                                                        ? probes.checkHttp(
                                                                codeIntelBaseUrl,
                                                                "/health",
                                                                "code-intel")
                                                        : new Outcome(
                                                                DependencyStatus.UNKNOWN,
                                                                "code-intel is disabled in this"
                                                                        + " deployment")),
                                new Spec(
                                        "object-storage",
                                        "storage",
                                        () -> probes.checkStorage(storageEndpoint)),
                                new Spec(
                                        "langfuse",
                                        "observability",
                                        () ->
                                                langfuseBase == null
                                                        ? new Outcome(
                                                                DependencyStatus.UNKNOWN,
                                                                "no Langfuse URL is configured")
                                                        : probes.checkHttp(
                                                                langfuseBase,
                                                                "/api/public/health",
                                                                "Langfuse"))));
        if (academyBaseUrl != null && !academyBaseUrl.isBlank()) {
            platformServices.add(
                    new Spec(
                            "academy",
                            "app",
                            () -> probes.checkHttp(academyBaseUrl, "/api/health", "academy")));
        }
        if (appBuilderBaseUrl != null && !appBuilderBaseUrl.isBlank()) {
            platformServices.add(
                    new Spec(
                            "app-builder",
                            "app",
                            () -> probes.checkHttp(appBuilderBaseUrl, "/health", "app-builder")));
        }
        List<Spec> platformSpecs = List.copyOf(platformServices);
        built.add(() -> runner.runAll(platformSpecs));
        this.stages = List.copyOf(built);
    }

    /** Test seam: arbitrary stages, injected clock and executor. Not a Spring constructor. */
    DependencyHealthService(
            boolean enabled,
            long cacheTtlSeconds,
            Clock clock,
            ExecutorService executor,
            List<Supplier<List<DependencyEntry>>> stages) {
        this.enabled = enabled;
        this.cacheTtlSeconds = Math.max(0, cacheTtlSeconds);
        this.clock = clock;
        this.executor = executor;
        this.stages = stages;
    }

    /** The enabled hosted-pool rows in priority order, or null when the store is not available. */
    private static Callable<List<PlatformHostedLlmProvider>> enabledPoolRows(
            ObjectProvider<PlatformHostedLlmProviderRepository> provider) {
        PlatformHostedLlmProviderRepository repository = provider.getIfAvailable();
        return repository == null ? null : repository::findAllByEnabledTrueOrderByPriorityAsc;
    }

    /**
     * The dependency picture, from cache when younger than the TTL.
     *
     * @throws ResponseStatusException 404 when {@code app.platform.reliability.enabled=false}
     */
    public synchronized DependenciesResponse dependencies() {
        if (!enabled) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Reliability endpoints are disabled.");
        }
        Instant now = clock.instant();
        if (cache != null
                && cacheTtlSeconds > 0
                && now.isBefore(cache.at().plusSeconds(cache.ttlSeconds()))) {
            return cache.response();
        }
        Round round = compute(now);
        if (round != null) {
            cache =
                    new Cached(
                            now,
                            round.response(),
                            round.failedStage() ? failureTtl() : cacheTtlSeconds);
            return round.response();
        }
        // compute() failed outright: say so instead of 500-ing the page. The failure is cached only
        // briefly, so a
        // persistent fault is not re-probed by every caller (the calls are serialised) yet recovers
        // quickly.
        DependenciesResponse fallback =
                new DependenciesResponse(
                        now,
                        (int) cacheTtlSeconds,
                        DependencyStatus.UNKNOWN,
                        List.of(),
                        List.of(
                                "The dependency probes could not be run on this replica just now;"
                                        + " try again shortly."));
        cache = new Cached(now, fallback, failureTtl());
        return fallback;
    }

    /**
     * How long a round that had a failing stage (or failed outright) is served from cache: short,
     * and never longer than the normal TTL.
     */
    private long failureTtl() {
        return Math.min(cacheTtlSeconds, FAILURE_CACHE_SECONDS);
    }

    private record Round(DependenciesResponse response, boolean failedStage) {}

    private Round compute(Instant now) {
        try {
            List<CompletableFuture<List<DependencyEntry>>> futures =
                    stages.stream()
                            .map(stage -> CompletableFuture.supplyAsync(stage, executor))
                            .toList();
            List<DependencyEntry> entries = new ArrayList<>();
            List<String> notes = new ArrayList<>(notes());
            boolean failedStage = false;
            for (int i = 0; i < futures.size(); i++) {
                try {
                    entries.addAll(futures.get(i).join());
                } catch (RuntimeException e) {
                    // One stage failing must not blank the readings of the others.
                    failedStage = true;
                    if (!warnedFailure) {
                        warnedFailure = true;
                        log.warn(
                                "Dependency health stage {} failed ({}); its rows read UNKNOWN"
                                        + " until it recovers",
                                i + 1,
                                ProbeRunner.describe(
                                        e instanceof java.util.concurrent.CompletionException
                                                        && e.getCause() != null
                                                ? e.getCause()
                                                : e));
                    }
                    entries.add(
                            DependencyProbes.unknownEntry(
                                    "probe-stage-" + (i + 1),
                                    "internal",
                                    "this group of probes could not be run on this replica",
                                    clock.instant()));
                }
            }
            if (failedStage) {
                notes.add(
                        "One group of probes could not be run on this replica just now; its row"
                                + " reads UNKNOWN. The other rows are unaffected.");
            }
            return new Round(
                    new DependenciesResponse(
                            now,
                            (int) cacheTtlSeconds,
                            rollup(entries),
                            List.copyOf(entries),
                            notes),
                    failedStage);
        } catch (RuntimeException e) {
            if (!warnedFailure) {
                warnedFailure = true;
                log.warn(
                        "Dependency health sweep failed ({}); serving an UNKNOWN answer until it"
                                + " recovers",
                        ProbeRunner.describe(e));
            }
            return null;
        }
    }

    // ----------------------------------------------------------------- pure helpers

    /**
     * Overall verdict: Postgres DOWN is DOWN (nothing works without it); any other DOWN or DEGRADED
     * entry is DEGRADED; UNKNOWN entries (not configured) never drag the verdict down; with no UP
     * entry at all the answer is UNKNOWN.
     */
    static DependencyStatus rollup(List<DependencyEntry> entries) {
        boolean anyUp = false;
        boolean anyBad = false;
        for (DependencyEntry e : entries) {
            if (e.status() == DependencyStatus.UP) {
                anyUp = true;
            } else if (e.status() == DependencyStatus.DOWN
                    || e.status() == DependencyStatus.DEGRADED) {
                if (POSTGRES.equals(e.name()) && e.status() == DependencyStatus.DOWN) {
                    return DependencyStatus.DOWN;
                }
                anyBad = true;
            }
        }
        if (anyBad) {
            return DependencyStatus.DEGRADED;
        }
        return anyUp ? DependencyStatus.UP : DependencyStatus.UNKNOWN;
    }

    /**
     * Outcome of the Meta credential row. The embedded-signup app id and secret are one unit (one
     * without the other is a broken setup); the webhook verify token stands alone, because tenants
     * can still bring their own Meta credentials without the guided flow.
     */
    static Outcome metaOutcome(boolean webhookToken, boolean appId, boolean appSecret) {
        if (appId != appSecret) {
            return new Outcome(
                    DependencyStatus.DEGRADED,
                    "embedded-signup app id and app secret are not both set; the guided WhatsApp"
                            + " connect cannot work");
        }
        List<String> present = new ArrayList<>();
        if (webhookToken) {
            present.add("webhook verify token");
        }
        if (appId) {
            present.add("embedded-signup app credentials");
        }
        if (present.isEmpty()) {
            return new Outcome(
                    DependencyStatus.UNKNOWN,
                    "not configured (tenants can still bring their own Meta credentials)");
        }
        return new Outcome(
                DependencyStatus.UP,
                "present: " + String.join(", ", present) + "; not live-checked");
    }

    private static List<String> notes() {
        return List.of(
                "Each dependency is probed in parallel with a hard timeout and the result is cached"
                        + " for a short time on this replica, so reloading the page does not re-probe"
                        + " more often than that.",
                "Hosted-LLM pool rows are checked for reachability only (an unauthenticated request"
                        + " to the provider's base URL; any HTTP answer below 500 counts as reachable)."
                        + " No credential is sent and no completion is requested, so a revoked key, an"
                        + " exhausted quota or a failing model is not detected here.",
                "E2B, Brave and Meta rows show that a credential is present; they are never"
                        + " called.",
                "Sidecar is checked through its liveness endpoint only; the embedding model itself"
                        + " is not exercised.",
                "Keycloak is checked for JWKS reachability; signing keys are cached by the API, so"
                        + " already-seen keys keep validating if Keycloak is briefly unreachable.",
                "Kafka is checked with a TCP connect to the first bootstrap broker, and only when"
                        + " workflow events are enabled.",
                "Temporal is checked with a TCP connect to its frontend address, and only when the"
                        + " temporal profile is active.",
                "Code-intel, Langfuse, academy and app-builder are checked through their"
                        + " unauthenticated health endpoints; object storage through MinIO's liveness"
                        + " endpoint (credentials are not exercised).",
                "UNKNOWN means not configured in this deployment (or not checkable), not a"
                        + " failure.");
    }
}
