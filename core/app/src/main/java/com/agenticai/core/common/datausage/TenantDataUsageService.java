package com.agenticai.core.common.datausage;

import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.common.datausage.TenantDataUsageResponse.LiveStat;
import com.agenticai.core.common.datausage.TenantDataUsageResponse.SystemUsage;
import com.agenticai.core.eventbus.WorkflowEventOutboxRelay;
import com.agenticai.core.eventbus.WorkflowEventOutboxRepository;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.tenancy.Tenant;
import com.agenticai.core.tenancy.TenantDataErasureService;
import com.agenticai.core.tenancy.TenantRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds {@code GET /api/tenant/data-usage}'s response - a tenant-admin's own,
 * honest answer to "which of our backend systems touch our data, and how."
 * Same voice as {@code web/app/why-vegaduta/page.tsx}: plain, factual, and
 * comfortable admitting a limitation directly rather than overselling it (see
 * {@link #temporal()} in particular).
 *
 * <p><b>Why this lives in {@code core/app}, not {@code core/tenancy}
 * alongside {@link TenantDataErasureService}/{@code TenantDataExportService}.
 * </b> This feature deliberately reuses ALREADY-LIVE facts and counters from
 * several modules ({@code core/tenancy} for the Postgres schema/object-storage
 * facts, {@code core/chat} for the Langfuse/LLM-call-count fact, {@code
 * core/eventbus} for the Kafka outbox fact) rather than re-deriving any of
 * them. {@code core/tenancy} cannot be the home for that: {@code core/eventbus}
 * already depends on {@code core/tenancy} (for {@link TenantContext} et al.),
 * and {@code core/chat}'s own dependency chain (chat -&gt; providers -&gt;
 * sarvam -&gt; tenancy) means {@code core/tenancy} depending on either would
 * be a real Maven module cycle, not a style preference. {@code core/app} is
 * the one module that already depends on all three (and on {@code
 * core/security}/{@code core/workflow} for the remaining facts below) - the
 * exact same reason {@code SystemStatusController} (also a many-module
 * aggregator over live facts) already lives in this module's {@code
 * com.agenticai.core.common} package rather than in any one of the modules it
 * reads from.
 *
 * <p>Every {@code liveStat}/fact read is independently wrapped in {@link
 * #safe} - the same "one bad reading never blanks the panel" posture {@code
 * SystemStatusController#safe} already establishes for the adjacent System
 * Status panel - so one system's query failing degrades that one entry to a
 * {@code null} liveStat, never a 500 for the whole response.
 */
@Service
public class TenantDataUsageService {

    private static final Logger log = LoggerFactory.getLogger(TenantDataUsageService.class);

    private final TenantRepository tenantRepository;
    // Optional, not a hard dependency: TenantDataErasureService is
    // @Profile("security") (core/tenancy), and this service carries no such
    // restriction of its own - a plain `mvn test` context-load (no active
    // profiles, e.g. CoreApplicationTests) has no candidate bean to wire
    // here otherwise, which is a real, reproduced startup failure, not a
    // hypothetical. Same defensive-Optional pattern SecurityConfig#filterChain
    // already uses for TenantStatusGuard.
    private final Optional<TenantDataErasureService> tenantDataErasureService;
    private final LlmCallLogService llmCallLogService;
    private final WorkflowEventOutboxRepository outboxRepository;
    private final DataSource dataSource;

    /**
     * Same property/default {@code WorkflowService#executionEngineMode} reads
     * - deliberately re-read here rather than asking {@code WorkflowService}
     * for its own field (private, and this only needs the raw configured
     * value, not any of that service's run-orchestration behavior). Confirmed
     * by direct audit (2026-07-27) of {@code WorkflowService#resolveExecutionEngine}:
     * this defaults to, and today in this deployment actually is, {@code
     * "legacy"} - see {@link #temporal()}.
     */
    @Value("${app.workflow.execution-engine:legacy}")
    private String executionEngineMode;

    /**
     * Which of the listed systems this deployment actually runs. A personal install has no Kafka
     * (no {@code workflow} profile), no Temporal (no {@code temporal} profile), no Keycloak
     * ({@code app.identity.provider=local}), no tracing backend and files on local disk; listing
     * them as "active" told the owner about infrastructure that does not exist. Defaults keep the
     * full-stack answer (and the existing tests) unchanged.
     */
    @Value("${spring.profiles.active:}")
    private String activeProfiles = "";

    @Value("${app.identity.provider:keycloak}")
    private String identityProvider = "keycloak";

    @Value("${app.storage.type:s3}")
    private String storageType = "s3";

    @Value("${management.tracing.enabled:true}")
    private boolean tracingEnabled = true;

    public TenantDataUsageService(
            TenantRepository tenantRepository,
            Optional<TenantDataErasureService> tenantDataErasureService,
            LlmCallLogService llmCallLogService,
            WorkflowEventOutboxRepository outboxRepository,
            DataSource dataSource) {
        this.tenantRepository = tenantRepository;
        this.tenantDataErasureService = tenantDataErasureService;
        this.llmCallLogService = llmCallLogService;
        this.outboxRepository = outboxRepository;
        this.dataSource = dataSource;
    }

    public TenantDataUsageResponse currentTenantUsage() {
        Tenant tenant = requireCurrentTenant();
        List<SystemUsage> systems =
                new java.util.ArrayList<>(List.of(postgres(tenant), minio(), redis()));
        if (tracingEnabled) {
            systems.add(langfuse());
        }
        if (hasProfile("workflow")) {
            systems.add(kafka());
        }
        if (hasProfile("temporal")) {
            systems.add(temporal());
        }
        if ("keycloak".equalsIgnoreCase(identityProvider)) {
            systems.add(keycloak());
        }
        return new TenantDataUsageResponse(Instant.now(), tenant.getId().toString(), systems);
    }

    /**
     * Isolation fact source: {@code Tenant#getSchemaName()} (see that field's
     * own javadoc) - this tenant's rows live in a Postgres schema no other
     * tenant's queries ever touch, the same schema-per-tenant boundary {@code
     * TenantDataExportService}/{@code TenantDataErasureService} already build
     * on. {@code liveStat} counts real tables in exactly that schema via
     * {@code information_schema.tables}, scoped by a bind parameter (never
     * string-concatenated - {@code schemaName} here is only used as a query
     * VALUE, unlike the schema-qualified-identifier SQL {@code
     * TenantDataExportService}/{@code TenantDataErasureService} build for
     * their own, different raw-JDBC needs).
     */
    private SystemUsage postgres(Tenant tenant) {
        Integer tableCount = safe(() -> postgresTableCount(tenant.getSchemaName()));
        return new SystemUsage(
                "postgres",
                "Database",
                "PostgreSQL",
                "Primary storage",
                "Every agent, workflow, conversation, and audit record you create is stored here.",
                "Your workspace's data lives in its own dedicated Postgres schema ('"
                        + tenant.getSchemaName()
                        + "'), separate from every other tenant's schema - no query this platform runs for another "
                        + "tenant can read or write your tables.",
                tableCount == null ? null : new LiveStat("Tables in your workspace", tableCount),
                "active");
    }

    /**
     * Isolation fact source: {@code S3FileStorageService}'s tenant-key-prefix
     * convention (infra audit fix, 2026-07-27, {@code core/storage}) -
     * {@code S3FileStorageService#put} prefixes every new object's physical
     * key with {@code tenant/<tenantId>/}, and {@code #get}/{@code #exists}/
     * {@code #delete} refuse a caller-supplied key that already carries a
     * DIFFERENT tenant's prefix ({@code S3FileStorageService#verifyTenantOwnership}
     * - the class's own "defense-in-depth ownership check"). {@code liveStat}
     * reuses {@code TenantDataErasureService#countObjectStorageItemsForCurrentTenant}
     * - a read-only twin of that service's own erasure object-key enumeration
     * (same {@code OBJECT_KEY_TABLES} list plus avatars), added alongside this
     * feature specifically so this count and "how many objects erasure would
     * delete" are always computed the same way.
     */
    private boolean hasProfile(String profile) {
        // The default (unset) is the full stack, which runs everything.
        if (activeProfiles == null || activeProfiles.isBlank()) {
            return true;
        }
        for (String p : activeProfiles.split(",")) {
            if (p.trim().equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private SystemUsage minio() {
        Long objectCount =
                tenantDataErasureService
                        .map(service -> safe(service::countObjectStorageItemsForCurrentTenant))
                        .orElse(null);
        return new SystemUsage(
                "minio",
                "Object storage",
                "local".equalsIgnoreCase(storageType) ? "Local disk" : "MinIO (S3-compatible)",
                "File storage",
                "Documents you upload to your knowledge base, generated images, avatars, and generated "
                        + "audio clips are stored here as objects.",
                "Every object your workspace creates is written under its own 'tenant/<your-id>/...' key "
                        + "prefix, and a request to read, check, or delete an object is refused outright if the "
                        + "key belongs to a different tenant.",
                objectCount == null
                        ? null
                        : new LiveStat("Files stored for your workspace", objectCount),
                "active");
    }

    /**
     * Isolation fact source: {@code LlmGatewayRateLimiterService}'s Redis key
     * format - {@code KEY_PREFIX + tenantId}, i.e. {@code
     * "agentic:llm-gateway-rate:<your-tenant-id>"} (see that class's {@code
     * KEY_PREFIX} constant and {@code assertWithinRateLimit}). No {@code
     * liveStat}: a bare current counter value for a one-minute fixed window
     * isn't a meaningful standalone number to show a tenant-admin (it would
     * usually just read "0" or "3", changing every minute) - honestly left
     * {@code null} rather than built for the sake of filling the field.
     */
    private SystemUsage redis() {
        return new SystemUsage(
                "redis",
                "Cache & rate limiter",
                "Redis",
                "Caching and rate limiting",
                "Session state and short-lived counters - like how many LLM calls your workspace has made "
                        + "in the current minute - are cached here rather than hitting the database on every "
                        + "request.",
                "Every rate-limit counter is keyed to your tenant specifically ('agentic:llm-gateway-rate:"
                        + "<your-tenant-id>') - your usage is never combined with or throttled by another "
                        + "tenant's traffic.",
                null,
                "active");
    }

    /**
     * Isolation fact source: {@code LlmMetricsAdvisor#tagSpanAndExtractIds}
     * (see {@code core/chat}), which sets the OTel attributes {@code
     * vegaduta.tenant.id} (always the tenant id) and {@code langfuse.user.id}
     * (a pseudonymous user id, or a salted hash of it, per {@code
     * app.observability.langfuse.user-id-mode}) on every real LLM call's
     * trace before it's exported to Langfuse. {@code liveStat} reuses {@code
     * LlmCallLogService#dashboardMetrics()}'s already-computed {@code
     * callsLast24h} (the same durable, tenant-schema-scoped {@code
     * llm_call_log} table the AXON Dashboard telemetry strip and {@code
     * SystemStatusController#llmTelemetry} already read from) rather than
     * writing a new aggregation query.
     */
    private SystemUsage langfuse() {
        Long calls24h = safe(() -> llmCallLogService.dashboardMetrics().callsLast24h());
        return new SystemUsage(
                "langfuse",
                "AI call tracing",
                "Langfuse",
                "AI call observability",
                "Every call your agents make to an LLM is traced here for latency, cost, and quality "
                        + "monitoring.",
                "Every trace is tagged with your tenant id (the 'vegaduta.tenant.id' attribute, set on every "
                        + "LLM call), so traces are filterable to your workspace specifically inside Langfuse. "
                        + "A pseudonymous user identifier (never an email) separates one person's traces from "
                        + "another's; operators can have it sent as a salted hash instead.",
                calls24h == null ? null : new LiveStat("AI calls (last 24h)", calls24h),
                "active");
    }

    /**
     * Isolation fact source: {@code SystemStatusController#kafkaQueue()}'s
     * existing javadoc/query - "Live counts from this tenant's own {@code
     * workflow_event_outbox} table (schema-per-tenant multitenancy routes the
     * query automatically)." {@code liveStat} reuses the identical repository
     * method that panel already calls ({@code
     * WorkflowEventOutboxRepository#countByPublishedAtIsNullAndRetryCountLessThan},
     * bounded by the same {@code WorkflowEventOutboxRelay#MAX_RETRIES}), not a
     * new query.
     */
    private SystemUsage kafka() {
        Long pending =
                safe(
                        () ->
                                outboxRepository.countByPublishedAtIsNullAndRetryCountLessThan(
                                        WorkflowEventOutboxRelay.MAX_RETRIES));
        return new SystemUsage(
                "kafka",
                "Event queue",
                "Kafka / Redpanda",
                "Workflow event queue",
                "Workflow step events (a run starting, a step finishing, a run completing) pass through "
                        + "here on their way to being logged and, where configured, delivered to external "
                        + "subscribers.",
                "Pending events live in your own workspace's 'workflow_event_outbox' table, in your own "
                        + "Postgres schema - not a shared queue table other tenants' events pass through.",
                pending == null ? null : new LiveStat("Pending workflow events", pending),
                "active");
    }

    /**
     * Status is deliberately {@code "available_not_engaged"} for exactly one
     * reason: it's true. Confirmed by direct audit (2026-07-27) of {@code
     * WorkflowService#resolveExecutionEngine} - {@code
     * app.workflow.execution-engine} defaults to, and this deployment
     * currently is configured as, {@code "legacy"}: every workflow run today
     * executes directly in-process, synchronously, inside the request that
     * triggered it, with the retry/error handling {@code WorkflowExecutionEngine}
     * already provides - NOT durable across a server crash mid-run. {@code
     * TemporalWorkflowExecutionEngine} is fully built and would only engage
     * if this property were set to {@code "temporal"} AND the "temporal"
     * Spring profile were active; neither is true right now. This is the one
     * system in this whole view where honesty means admitting a limitation
     * rather than describing a capability as live.
     */
    private SystemUsage temporal() {
        boolean engaged = "temporal".equalsIgnoreCase(executionEngineMode);
        return new SystemUsage(
                "temporal",
                "Durable workflow engine",
                "Temporal",
                "Durable workflow execution",
                "Workflow runs currently execute directly within our application, with automatic retries "
                        + "on failure. Temporal, a durable workflow engine that would let a run survive a server "
                        + "crash mid-execution and resume exactly where it left off, is built into the platform "
                        + "but is not yet the default execution path.",
                "Not applicable today - no workflow run engine yet writes or reads Temporal-managed state "
                        + "for your workspace, so there is nothing there to isolate. This will be documented here "
                        + "the moment that changes.",
                null,
                engaged ? "active" : "available_not_engaged");
    }

    /**
     * Isolation fact source: {@code TenantContextFilter#doFilterInternal} -
     * every authenticated request's JWT is read for a {@code tenant_id}
     * claim, and a request from a non-platform-admin JWT that carries no
     * usable {@code tenant_id} is rejected outright with a 403 before it ever
     * reaches a controller (see that filter's {@code tenantMissing} branch).
     * This is the literal mechanism binding a signed-in user to their
     * tenant's data scope on every single request, not just at login.
     */
    private SystemUsage keycloak() {
        return new SystemUsage(
                "keycloak",
                "Identity & authentication",
                "Keycloak",
                "Identity provider",
                "Sign-in, session tokens, and user accounts for your workspace are managed here.",
                "Every authenticated request's token must carry your tenant's id as a claim - a request "
                        + "with no usable tenant claim is rejected before it reaches any of your data.",
                null,
                "active");
    }

    private Integer postgresTableCount(String schemaName) {
        String sql = "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = ?";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schemaName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : null;
            }
        } catch (SQLException e) {
            log.warn(
                    "Could not count tables in schema '{}' for /api/tenant/data-usage - {}",
                    schemaName,
                    e.getMessage());
            return null;
        }
    }

    private Tenant requireCurrentTenant() {
        String schemaName =
                TenantContext.current()
                        .map(TenantContext::getTenantId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "No TenantContext for an authenticated /api/tenant/data-usage request - SecurityConfig should have blocked this"));
        return tenantRepository
                .findBySchemaName(schemaName)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "No Tenant row for schema '" + schemaName + "'"));
    }

    /** Same "one bad reading never blanks the whole response" posture as {@code SystemStatusController#safe}. */
    private <T> T safe(Supplier<T> read) {
        try {
            return read.get();
        } catch (Exception e) {
            log.warn(
                    "A /api/tenant/data-usage live-stat read failed - degrading that field to null",
                    e);
            return null;
        }
    }
}
