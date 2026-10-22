package com.agenticai.core.common.systemstatus;

import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.chat.LlmDashboardMetrics;
import com.agenticai.core.chat.LlmOverallUsageSummary;
import com.agenticai.core.common.systemstatus.SystemStatusResponse.ComponentStatus;
import com.agenticai.core.common.systemstatus.SystemStatusResponse.DatabaseStatus;
import com.agenticai.core.common.systemstatus.SystemStatusResponse.JvmStatus;
import com.agenticai.core.common.systemstatus.SystemStatusResponse.KafkaStatus;
import com.agenticai.core.common.systemstatus.SystemStatusResponse.KafkaStatus.KafkaConfig;
import com.agenticai.core.common.systemstatus.SystemStatusResponse.KafkaStatus.KafkaDlq;
import com.agenticai.core.common.systemstatus.SystemStatusResponse.KafkaStatus.KafkaQueue;
import com.agenticai.core.common.systemstatus.SystemStatusResponse.LlmStatus;
import com.agenticai.core.common.systemstatus.SystemStatusResponse.McpServerSummary;
import com.agenticai.core.common.systemstatus.SystemStatusResponse.McpStatus;
import com.agenticai.core.eventbus.WorkflowEventLogger;
import com.agenticai.core.eventbus.WorkflowEventOutboxRelay;
import com.agenticai.core.eventbus.WorkflowEventOutboxRepository;
import com.agenticai.core.eventbus.WorkflowKafkaConfig;
import com.agenticai.core.mcp.McpRegistryService;
import com.agenticai.core.mcp.dto.McpServerRegistrationResponse;
import com.agenticai.core.security.HotpathCaches;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantScope;
import com.agenticai.core.security.TtlCache;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * "How does the ecosystem's engine room look right now" - the HUD dashboard rebuild's System Status
 * panel. See {@link SystemStatusResponse}'s javadoc for the no-fabricated-data rule this whole
 * controller follows: every field is read from a live source (Spring Boot's already-auto-configured
 * {@code db}/{@code redis}/{@code diskSpace} health contributors, Hikari's pool MXBean, the JVM's
 * own management beans, or {@link McpRegistryService}, which this tenant's other pages already use
 * for the MCP Tools catalog).
 *
 * <p>No new Maven dependency was needed for this - {@code spring-boot-starter-actuator} is already
 * on {@code core/app}'s classpath (see application.yml's {@code
 * management.endpoints.web.exposure.include: health,info}), which is what auto-configures the
 * {@link HealthContributorRegistry} bean and the {@code db}/{@code redis}/{@code diskSpace}
 * indicators this controller reads from directly, instead of going through the HTTP {@code
 * /actuator/health} endpoint a second time.
 *
 * <p>Deliberately NOT profile-gated (unlike most controllers here) - it works identically whether
 * the {@code security} profile is active or not, same as {@code ProviderController}; {@code
 * SecurityConfig}'s {@code anyRequest().authenticated()} catch-all already covers {@code
 * /api/system/status} with no extra rule needed.
 */
@RestController
@RequestMapping("/api/system/status")
public class SystemStatusController {

    private static final Logger log = LoggerFactory.getLogger(SystemStatusController.class);

    private final DataSource dataSource;
    private final HealthContributorRegistry healthContributorRegistry;
    private final McpRegistryService mcpRegistryService;
    private final LlmCallLogService llmCallLogService;
    private final String sidecarBaseUrl;
    // #912 ("dashboard always shows KAFKA/QUEUE not active") - see kafkaStatus()'s
    // javadoc for the root cause this pair of fields fixes.
    private final boolean workflowEventsEnabled;
    private final ObjectProvider<KafkaAdmin> kafkaAdmin;
    // #916 (Kafka dashboard wiring) - always a real bean regardless of
    // profile/feature-flag state (see WorkflowEventOutboxRepository's own
    // javadoc note added for this change): the workflow_event_outbox table
    // exists in every tenant schema via Flyway's tenant migrations
    // unconditionally, independent of whether the "workflow" Spring profile
    // or app.workflow-events.enabled happen to be on - queries against it
    // just return zero rows when the feature is unused.
    private final WorkflowEventOutboxRepository outboxRepository;

    /**
     * Whole-response cache for {@link #get()} (2026-09-24 scale work): the admin console hits this
     * endpoint from three components on mount and polls it every 15s, and the tenant web app's
     * SystemHealthProvider polls it from every route - yet a single call can spend up to ~8s on two
     * sequential external probes plus a fresh Kafka AdminClient plus DB/outbox/LLM reads. A status
     * answer is point-in-time by definition, so a short TTL is honest (stale-by-construction either
     * way) and collapses N concurrent pollers into one fan-out per window. Global key - the payload
     * is platform-wide, never tenant data. {@code app.system.status-cache-ttl}, default 10s (one
     * poll interval); {@code 0s} restores the previous always-fresh behavior.
     */
    private final TtlCache<SystemStatusResponse> statusCache;

    // Short-timeout, no-auth GET client used only for liveness probes below
    // (the sidecar's /health) - an already real, existing endpoint (see its
    // own source), not something added just for this. A dead/unreachable
    // microservice must still fail fast rather than hang this whole
    // response, matching every other section's "one bad reading never
    // blanks the panel" rule - but the original 1.5s was too tight for a
    // TLS request crossing an internal ingress (false DOWN tiles against a
    // service whose own health checks were green). 4s keeps the fail-fast
    // intent while absorbing ordinary event-loop stalls.
    private static final int HEALTH_PROBE_TIMEOUT_MS = 4000;
    private final RestClient healthProbeClient;

    public SystemStatusController(
            DataSource dataSource,
            HealthContributorRegistry healthContributorRegistry,
            McpRegistryService mcpRegistryService,
            LlmCallLogService llmCallLogService,
            // app.sidecar.enabled=false reads as "no sidecar configured" (NOT_CONFIGURED /
            // UNKNOWN), not as an outage.
            @Value(
                            "#{'${app.sidecar.enabled:true}'.equalsIgnoreCase('false') ? '' : '${app.sidecar.base-url:}'}")
                    String sidecarBaseUrl,
            @Value("${app.workflow-events.enabled:false}") boolean workflowEventsEnabled,
            @Value("${app.system.status-cache-ttl:10s}") Duration statusCacheTtl,
            ObjectProvider<KafkaAdmin> kafkaAdmin,
            WorkflowEventOutboxRepository outboxRepository) {
        this.dataSource = dataSource;
        this.healthContributorRegistry = healthContributorRegistry;
        this.mcpRegistryService = mcpRegistryService;
        this.llmCallLogService = llmCallLogService;
        this.sidecarBaseUrl = sidecarBaseUrl;
        this.workflowEventsEnabled = workflowEventsEnabled;
        this.kafkaAdmin = kafkaAdmin;
        this.outboxRepository = outboxRepository;
        this.statusCache = new TtlCache<>(HotpathCaches.SYSTEM_STATUS, statusCacheTtl, 4);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(HEALTH_PROBE_TIMEOUT_MS);
        requestFactory.setReadTimeout(HEALTH_PROBE_TIMEOUT_MS);
        this.healthProbeClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    /**
     * Every section below is read independently and defensively - a single misbehaving contributor
     * (e.g. an MCP call that throws because a tenant hasn't finished provisioning yet, or a health
     * indicator that throws instead of returning DOWN) must never blank the whole panel. Each
     * section that fails degrades to its own "UNKNOWN"/null shape (still a real, honest "couldn't
     * determine this" - never a fabricated value) instead of letting the exception propagate into a
     * 500 that would take every other, perfectly healthy reading down with it. This is the direct
     * fix for "HUD dashboard metrics not displaying."
     */
    @GetMapping
    public SystemStatusResponse get() {
        // The reading includes this tenant's own outbox depth, so it is cached per tenant, never
        // shared.
        return statusCache.get(
                TtlCache.key("platform", "status", currentTenantId()), this::compute);
    }

    /**
     * The uncached fan-out behind {@link #get()} - the cache only wraps, never rewrites, the
     * response.
     */
    private SystemStatusResponse compute() {
        // PROBE_EXECUTOR threads carry no TenantContext; without re-applying it the Kafka section's
        // outbox counts query the public schema ("relation workflow_event_outbox does not exist").
        TenantScope tenant = TenantScope.capture();
        String tenantId = currentTenantId();
        DatabaseStatus database =
                safe(
                        this::databaseStatus,
                        new DatabaseStatus("UNKNOWN", null, null, null, null, null, null));
        ComponentStatus redis =
                safe(() -> componentStatus("redis"), new ComponentStatus("UNKNOWN"));
        // The network probes (Kafka AdminClient, sidecar) are
        // platform-level, not tenant-specific, and used to run one after another
        // on the request thread - a fresh AdminClient every call plus up to 4 s
        // read timeouts each. They
        // now run in parallel and each result is reused for PROBE_TTL_NANOS.
        java.util.concurrent.CompletableFuture<KafkaStatus> kafkaFuture =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () ->
                                tenant.get(
                                        () ->
                                                safe(
                                                        () ->
                                                                cachedProbe(
                                                                        "kafka:" + tenantId,
                                                                        this::kafkaStatus),
                                                        new KafkaStatus(
                                                                "UNKNOWN", null, null, null))),
                        PROBE_EXECUTOR);
        java.util.concurrent.CompletableFuture<ComponentStatus> sidecarFuture =
                java.util.concurrent.CompletableFuture.supplyAsync(
                        () ->
                                safe(
                                        () ->
                                                cachedProbe(
                                                        "sidecar",
                                                        () ->
                                                                microserviceStatus(
                                                                        sidecarBaseUrl, "/health")),
                                        new ComponentStatus("UNKNOWN")),
                        PROBE_EXECUTOR);
        ComponentStatus diskSpace =
                safe(() -> componentStatus("diskSpace"), new ComponentStatus("UNKNOWN"));
        JvmStatus jvm = safe(this::jvmStatus, null);
        McpStatus mcp = safe(this::mcpStatus, null);
        LlmStatus llm = safe(this::llmStatus, null);
        KafkaStatus kafka = kafkaFuture.join();
        ComponentStatus sidecar = sidecarFuture.join();

        return new SystemStatusResponse(
                overallStatus(database.status(), redis.status()),
                database,
                redis,
                kafka,
                diskSpace,
                jvm,
                mcp,
                llm,
                sidecar);
    }

    /**
     * Bare liveness for the web app's LOGGED-OUT probe (permitAll - see SecurityConfig).
     * Deliberately answers NOTHING but "the process is up": no component detail, no versions, no
     * tenant data - it is reachable by anyone on the internet. Exists because SystemHealthProvider
     * polls from every route including public ones (/login, /signup, /docs), and its only
     * unauthenticated options before this were a guaranteed 401 (from {@link #get()}) - which
     * browsers print as a console error on every public page load (2026-07-28 full-app audit) - or
     * actuator health, which this deployment configures with full component details and so must
     * stay blocked at the edge.
     */
    @GetMapping("/live")
    public java.util.Map<String, String> live() {
        return java.util.Map.of("status", "UP");
    }

    /**
     * Dashboard telemetry strip (direct feedback: "expectation is to really plug the live data") -
     * a separate endpoint rather than folding into {@link #get()}'s response, since {@link
     * SystemStatusResponse}'s exact shape is already depended on by the Platform Maintenance panel
     * and this is a materially different, Dashboard-specific aggregate (see {@link
     * LlmDashboardMetrics}'s javadoc for what each field means and which window it covers).
     */
    @GetMapping("/llm-telemetry")
    public LlmDashboardMetrics llmTelemetry() {
        return llmCallLogService.dashboardMetrics();
    }

    private static final long PROBE_TTL_NANOS = TimeUnit.SECONDS.toNanos(15);
    private final java.util.concurrent.ExecutorService PROBE_EXECUTOR =
            TenantAwareExecutors.newVirtualThreadPerTaskExecutor("system-status-probe-");

    /** Instance-owned (was static) so it is shut down with the bean (JAVA-026). */
    @jakarta.annotation.PreDestroy
    void shutdownProbeExecutor() {
        PROBE_EXECUTOR.shutdown();
    }

    private record CachedProbe(Object value, long atNanos) {}

    private final java.util.concurrent.ConcurrentHashMap<String, CachedProbe> probeCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static String currentTenantId() {
        return TenantContext.current().map(TenantContext::getTenantId).orElse("");
    }

    /**
     * Reuses a platform-level probe result for {@link #PROBE_TTL_NANOS}; a DOWN reading is cached
     * too.
     */
    @SuppressWarnings("unchecked")
    private <T> T cachedProbe(String key, java.util.function.Supplier<T> read) {
        CachedProbe hit = probeCache.get(key);
        long now = System.nanoTime();
        if (hit != null && now - hit.atNanos() < PROBE_TTL_NANOS) {
            return (T) hit.value();
        }
        T fresh = read.get();
        probeCache.put(key, new CachedProbe(fresh, System.nanoTime()));
        return fresh;
    }

    private <T> T safe(java.util.function.Supplier<T> read, T fallback) {
        try {
            return read.get();
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * "UP" from the actuator health contributor, enriched with Hikari's own live pool counters when
     * the pool is a {@link HikariDataSource} (it always is here - Spring Boot's default, and
     * nothing in this project's pom overrides it - but this stays defensive rather than assuming a
     * concrete class unconditionally). The product/version string comes from a real JDBC {@code
     * DatabaseMetaData} call on a connection Hikari hands back from its existing pool (not a new
     * physical connection), released immediately in the try-with-resources below.
     */
    private DatabaseStatus databaseStatus() {
        String status = contributorStatusCode("db");
        String product = null;
        try (Connection connection = dataSource.getConnection()) {
            var meta = connection.getMetaData();
            product = meta.getDatabaseProductName() + " " + meta.getDatabaseProductVersion();
        } catch (SQLException e) {
            // Leave product null - the status code above already reflects
            // a DB problem in this case; no need to fabricate a name.
        }

        Integer active = null;
        Integer idle = null;
        Integer total = null;
        Integer maxPool = null;
        Integer awaiting = null;
        if (dataSource instanceof HikariDataSource hikari) {
            maxPool = hikari.getMaximumPoolSize();
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            // Null until the pool has lazily initialized (e.g. immediately
            // after startup, before the first real query) - left null
            // rather than reported as zero, which would misleadingly read
            // as "confirmed empty" instead of "not yet known".
            if (pool != null) {
                active = pool.getActiveConnections();
                idle = pool.getIdleConnections();
                total = pool.getTotalConnections();
                awaiting = pool.getThreadsAwaitingConnection();
            }
        }

        return new DatabaseStatus(status, product, active, idle, total, maxPool, awaiting);
    }

    private ComponentStatus componentStatus(String contributorName) {
        return new ComponentStatus(contributorStatusCode(contributorName));
    }

    /**
     * Looks up an already-auto-configured actuator health contributor by name (e.g. "db", "redis",
     * "diskSpace").
     */
    private String contributorStatusCode(String contributorName) {
        HealthContributor contributor = healthContributorRegistry.getContributor(contributorName);
        if (contributor instanceof HealthIndicator indicator) {
            return indicator.health().getStatus().getCode();
        }
        // Contributor not registered in this environment (e.g. redis
        // disabled) or not a simple indicator - "UNKNOWN" is itself a real,
        // meaningful actuator status code, not a fabricated placeholder.
        return "UNKNOWN";
    }

    private JvmStatus jvmStatus() {
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
        MemoryUsage heap = memory.getHeapMemoryUsage();
        long usedMb = heap.getUsed() / (1024 * 1024);
        Long maxMb = heap.getMax() > 0 ? heap.getMax() / (1024 * 1024) : null;
        long uptimeSeconds = runtime.getUptime() / 1000;
        return new JvmStatus(
                usedMb, maxMb, uptimeSeconds, Runtime.getRuntime().availableProcessors());
    }

    private McpStatus mcpStatus() {
        List<McpServerRegistrationResponse> servers = mcpRegistryService.listAll();
        List<McpServerSummary> summaries =
                servers.stream()
                        .map(s -> new McpServerSummary(s.name(), s.type().name(), s.enabled()))
                        .toList();
        long enabledCount = servers.stream().filter(McpServerRegistrationResponse::enabled).count();
        return new McpStatus(servers.size(), (int) enabledCount, summaries);
    }

    /**
     * Real totals from the durable {@link LlmCallLog} table (via {@link LlmCallLogService}) -
     * direct feedback: "after restart always LLM calls 0". This used to read {@code
     * LlmCallMetricsService}, an in-process counter that's deliberately reset every restart; that's
     * a defensible "since this process started" number in isolation, but looked broken to someone
     * restarting the app and immediately sending a message. The durable log survives restarts and
     * is the same source the Platform Maintenance "LLM usage &amp; cost" panel already reads, so
     * the two no longer show different totals for the same underlying calls.
     */
    private LlmStatus llmStatus() {
        LlmOverallUsageSummary summary = llmCallLogService.overallSummary();
        return new LlmStatus(summary.totalCalls(), summary.errorCount(), summary.avgLatencyMs());
    }

    /**
     * #912 ("dashboard always shows KAFKA/QUEUE not active" - regardless of whether Redpanda/Kafka
     * was actually up). Root cause: {@link #componentStatus} (used for "redis"/"diskSpace" above)
     * works by looking up an already-auto-configured Spring Boot Actuator {@link HealthIndicator}
     * bean under a well-known name - that pattern relies on Spring Boot itself shipping a built-in
     * indicator for the thing being checked. Redis and disk space both get one out of the box.
     * Kafka does NOT: Spring Boot Actuator had a {@code KafkaHealthIndicator} pre-2.x/early-3.x,
     * but it was removed from core Actuator (Kafka health checking today is left to Spring Cloud
     * Stream's binder health, which this platform doesn't use - it talks to Kafka directly via
     * {@code core/eventbus}, not through a Stream binder). So {@code
     * healthContributorRegistry.getContributor("kafka")} was ALWAYS {@code null} in this codebase,
     * on every deployment, whether or not Redpanda was actually reachable - {@link
     * #contributorStatusCode} silently fell through to its "not registered" branch and returned
     * "UNKNOWN" every single time, which the admin dashboard renders as "not active" regardless of
     * the real broker state underneath.
     *
     * <p>The fix: a real, live probe of our own, same shape as {@link #microserviceStatus} below -
     * a direct {@code AdminClient.describeCluster()} call against the broker(s) {@link KafkaAdmin}
     * (Spring Boot's own auto-configured bean, built from {@code spring.kafka.bootstrap-servers})
     * is already configured to reach, with the same short {@link #HEALTH_PROBE_TIMEOUT_MS}
     * fail-fast budget every other liveness check here uses. "NOT_CONFIGURED" (not "DOWN") when
     * {@code app.workflow-events.enabled} is off - matching {@code WorkflowEventPublisher}'s own
     * flag-gate javadoc: with the feature intentionally off, this platform never attempts to reach
     * Kafka at all, so probing Spring Boot's default {@code localhost:9092} would just report a
     * false "DOWN" for a broker nothing is even trying to use.
     *
     * <p>#916 ("wire the kafka metrics to dashboard... how kafka is configured and queues, DLQ"):
     * the {@code UP}/{@code DOWN}/{@code NOT_CONFIGURED} probe above is unchanged - this just
     * attaches three more things onto the same reading. {@link #kafkaConfig()} is static (topic
     * names, partition count, consumer group, retry policy - always buildable, even when the broker
     * itself is unreachable, since it just reflects how this deployment is wired). {@link
     * #kafkaQueue()} reads live counts off THIS tenant's own {@code workflow_event_outbox} table.
     * {@link #dlqMessageCount} is the one piece that genuinely needs a live broker connection (an
     * offset read against the real {@code workflow.events.DLT} topic), so it's only attempted once
     * {@code describeCluster()} has already succeeded below.
     */
    private KafkaStatus kafkaStatus() {
        KafkaConfig config = kafkaConfig();
        KafkaQueue queue = kafkaQueue();

        if (!workflowEventsEnabled) {
            return new KafkaStatus(
                    "NOT_CONFIGURED",
                    config,
                    queue,
                    new KafkaDlq(WorkflowKafkaConfig.DLT_TOPIC, null));
        }
        KafkaAdmin admin = kafkaAdmin.getIfAvailable();
        if (admin == null) {
            return new KafkaStatus(
                    "NOT_CONFIGURED",
                    config,
                    queue,
                    new KafkaDlq(WorkflowKafkaConfig.DLT_TOPIC, null));
        }
        try (AdminClient client = AdminClient.create(admin.getConfigurationProperties())) {
            client.describeCluster()
                    .clusterId()
                    .get(HEALTH_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            KafkaDlq dlq = dlqMessageCount(client);
            return new KafkaStatus("UP", config, queue, dlq);
        } catch (Exception e) {
            return new KafkaStatus(
                    "DOWN", config, queue, new KafkaDlq(WorkflowKafkaConfig.DLT_TOPIC, null));
        }
    }

    /**
     * Static config, not a live reading - reflects how {@code WorkflowKafkaConfig}/{@code
     * application-workflow.yml} actually wire this deployment (topic/partitions/replicas from
     * {@code WorkflowKafkaConfig}, the consumer group {@code WorkflowEventLogger} really uses, and
     * the retry cap/backoff {@code WorkflowEventOutboxRelay} and the consumer error handler
     * enforce). {@code bootstrapServers} is the one field that needs a live {@link KafkaAdmin} bean
     * to read (its own {@code spring.kafka.bootstrap-servers}-derived config map) - null when that
     * bean was never created (feature never turned on).
     */
    private KafkaConfig kafkaConfig() {
        KafkaAdmin admin = kafkaAdmin.getIfAvailable();
        String bootstrapServers =
                admin == null
                        ? null
                        : String.valueOf(
                                admin.getConfigurationProperties().get("bootstrap.servers"));
        return new KafkaConfig(
                bootstrapServers,
                WorkflowEventOutboxRelay.TOPIC,
                WorkflowKafkaConfig.DLT_TOPIC,
                WorkflowKafkaConfig.PARTITION_COUNT,
                WorkflowKafkaConfig.REPLICATION_FACTOR,
                WorkflowEventLogger.CONSUMER_GROUP,
                WorkflowEventOutboxRelay.MAX_RETRIES,
                WorkflowKafkaConfig.RETRY_BACKOFF_MS);
    }

    /**
     * Live counts from this tenant's own {@code workflow_event_outbox} table (schema-per-tenant
     * multitenancy routes the query automatically - see {@code TenantContext}). {@code
     * pendingCount} is rows still waiting for {@code WorkflowEventOutboxRelay} to publish them
     * (retry count below the cap); {@code deadLetteredCount} is rows that have exhausted their
     * retries and already surfaced as a {@code PlatformIssue} - see that relay's javadoc for the
     * full mechanics. Falls back to 0 (not null) on any read failure, same "one bad reading never
     * blanks the panel" rule {@link #safe} already gives every other section.
     */
    private KafkaQueue kafkaQueue() {
        long pending =
                safe(
                        () ->
                                outboxRepository.countByPublishedAtIsNullAndRetryCountLessThan(
                                        WorkflowEventOutboxRelay.MAX_RETRIES),
                        0L);
        long deadLettered =
                safe(
                        () ->
                                outboxRepository
                                        .countByPublishedAtIsNullAndRetryCountGreaterThanEqual(
                                                WorkflowEventOutboxRelay.MAX_RETRIES),
                        0L);
        return new KafkaQueue(pending, deadLettered);
    }

    /**
     * Best-effort message count for the real {@code workflow.events.DLT} Kafka topic - sums, across
     * every partition, (latest offset - earliest offset), the standard AdminClient technique for
     * "how many records are currently retained on this topic." This is deliberately a different
     * number from {@link KafkaQueue#deadLetteredCount()}: that one counts this tenant's own outbox
     * rows that gave up on publishing; this one counts whatever actually landed on the DLT topic,
     * which a {@code DefaultErrorHandler} on the *consumer* side (see {@code WorkflowKafkaConfig})
     * can also populate independent of the outbox relay. Bound by the DLT topic's own retention
     * window, not a lifetime total. Returns a null count (not zero) if the topic can't be described
     * at all (e.g. it hasn't been created yet on a brand-new broker) - a real "unknown," not a
     * fabricated "empty."
     */
    private KafkaDlq dlqMessageCount(AdminClient client) {
        try {
            TopicDescription description =
                    client.describeTopics(List.of(WorkflowKafkaConfig.DLT_TOPIC))
                            .topicNameValues()
                            .get(WorkflowKafkaConfig.DLT_TOPIC)
                            .get(HEALTH_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            List<TopicPartition> partitions =
                    description.partitions().stream()
                            .map(
                                    p ->
                                            new TopicPartition(
                                                    WorkflowKafkaConfig.DLT_TOPIC, p.partition()))
                            .toList();

            Map<TopicPartition, OffsetSpec> earliestSpecs =
                    partitions.stream()
                            .collect(Collectors.toMap(p -> p, p -> OffsetSpec.earliest()));
            Map<TopicPartition, OffsetSpec> latestSpecs =
                    partitions.stream().collect(Collectors.toMap(p -> p, p -> OffsetSpec.latest()));

            var earliest =
                    client.listOffsets(earliestSpecs)
                            .all()
                            .get(HEALTH_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            var latest =
                    client.listOffsets(latestSpecs)
                            .all()
                            .get(HEALTH_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);

            long total = 0;
            for (TopicPartition partition : partitions) {
                long lo = earliest.get(partition).offset();
                long hi = latest.get(partition).offset();
                total += Math.max(0, hi - lo);
            }
            return new KafkaDlq(WorkflowKafkaConfig.DLT_TOPIC, total);
        } catch (Exception e) {
            return new KafkaDlq(WorkflowKafkaConfig.DLT_TOPIC, null);
        }
    }

    /**
     * Live GET probe against a microservice's own real health endpoint (the sidecar's {@code
     * /health}) - "NOT_CONFIGURED" (not "DOWN") when the base-url property is blank, since that's
     * this platform's normal "feature absent" state, not an outage worth alarming an admin over.
     */
    private ComponentStatus microserviceStatus(String baseUrl, String healthPath) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new ComponentStatus("NOT_CONFIGURED");
        }
        try {
            healthProbeClient.get().uri(baseUrl + healthPath).retrieve().toBodilessEntity();
            return new ComponentStatus("UP");
        } catch (Exception e) {
            // DOWN is a real outage signal, not an optional-feature probe (the blank-base-url
            // case already short-circuits to NOT_CONFIGURED above). Reporting it with zero log
            // output means an admin sees a red tile and the logs say nothing at all about why -
            // exactly the position the Aug 2026 prod stall left us in. WARN, not ERROR: the
            // status endpoint is polled, so this can repeat.
            log.warn("Health probe to {}{} failed - reporting DOWN", baseUrl, healthPath, e);
            return new ComponentStatus("DOWN");
        }
    }

    /**
     * DB down is treated as CRITICAL (nothing in this platform works without it); anything else
     * short of both core contributors being UP is DEGRADED rather than CRITICAL - e.g. Redis being
     * briefly unreachable disables rate-limiting/session caching, which is degraded operation, not
     * a full outage.
     */
    private String overallStatus(String dbStatus, String redisStatus) {
        if (!"UP".equals(dbStatus)) return "CRITICAL";
        if (!"UP".equals(redisStatus)) return "DEGRADED";
        return "NOMINAL";
    }
}
