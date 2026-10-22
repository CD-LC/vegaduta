package com.agenticai.core.common.systemstatus;

import com.agenticai.core.platform.ops.PlatformBroadcastService;
import com.agenticai.core.platform.ops.PlatformBroadcastService.PublicAnnouncement;
import com.agenticai.core.platform.ops.PlatformBroadcastService.PublicMaintenance;
import com.agenticai.core.security.edition.EditionInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
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
 * Public component-level status for the {@code /status} marketing page (Tier-4 "status page,
 * expected of a production platform"). {@code GET /api/public/status} is {@code permitAll} in
 * {@code SecurityConfig}, one line below the pre-existing {@code /api/system/status/live} probe.
 *
 * <p>Deliberately a SEPARATE controller from {@link SystemStatusController} rather than a new
 * mapping on it: the dashboard endpoint is an authenticated, tenant-internal surface whose exact
 * shape the HUD depends on, and it carries internals (Hikari pool counters, JVM heap, per-MCP
 * server names) that must never be published. This endpoint answers the only question a visitor has
 * - "is the platform up, and if not, which part?" - with component names and status codes and
 * nothing else: no versions, no pool sizes, no uptime internals beyond what UP/DOWN implies, no
 * tenant data.
 *
 * <p>Same defensive posture as the dashboard controller: each component is read independently via
 * {@link #safe} so one bad contributor degrades to its own {@code "UNKNOWN"} instead of 500-ing the
 * whole response. Overall roll-up: {@code DOWN} when the database itself is down (nothing works
 * without it), {@code DEGRADED} when any non-database component reports {@code DOWN}, {@code UP}
 * otherwise - {@code UNKNOWN} and {@code NOT_CONFIGURED} components never drag the banner red,
 * because both mean "not present in this deployment", not "failing".
 *
 * <p><b>Maintenance + announcement (2026-10-09).</b> The response also carries {@code
 * maintenance:{enabled,message,endsAt}} and {@code announcement:{enabled,
 * message,level,linkUrl,expiresAt}|null} (null when disabled or expired) from {@link
 * PlatformBroadcastService}, so every UI (web, academy, web-admin) learns about a maintenance
 * window or banner from this one unauthenticated call - additive fields, the original {@code
 * status/checkedAt/components} contract is unchanged. The whole answer is cached for {@link
 * #CACHE_TTL} (15 s) per replica: banner polling from every open tab must not turn into a Kafka
 * admin client and two HTTP probes per request. Per-IP throttled by {@code
 * PublicEndpointRateLimitFilter}; CORS is the global {@code /api/**} mapping in {@code WebConfig}
 * ({@code app.web.allowed-origins}).
 *
 * <p><b>Edition (lean-edition contract).</b> The response also carries {@code
 * edition:{tier,billingEnabled,authMode,features}} ({@link EditionInfo}) so the web console can
 * check that the edition it was built for matches the core it talks to. Fixed for the life of the
 * process, so it adds nothing to the 15 s cache cost, and it names no secret, version or tenant.
 *
 * <p>Not profile-gated, same as {@link SystemStatusController}: with the {@code security} profile
 * off, the main chain's {@code anyRequest().authenticated()} doesn't exist anyway and the permitAll
 * rule is a no-op.
 */
@RestController
@RequestMapping("/api/public/status")
public class PublicStatusController {

    /** Fail-fast probe budget, mirroring SystemStatusController's own rule. */
    private static final int HEALTH_PROBE_TIMEOUT_MS = 4000;

    public record Component(String name, String status) {}

    public record PublicStatusResponse(
            String status,
            Instant checkedAt,
            List<Component> components,
            PublicMaintenance maintenance,
            PublicAnnouncement announcement,
            EditionInfo edition) {}

    static final Duration CACHE_TTL = Duration.ofSeconds(15);

    private volatile PublicStatusResponse cached;
    private volatile Instant cachedAt;

    private final HealthContributorRegistry healthContributorRegistry;
    private final boolean workflowEventsEnabled;
    private final ObjectProvider<KafkaAdmin> kafkaAdmin;
    private final String sidecarBaseUrl;
    private final RestClient healthProbeClient;
    private final ObjectProvider<PlatformBroadcastService> broadcast;
    private final EditionInfo edition;

    public PublicStatusController(
            HealthContributorRegistry healthContributorRegistry,
            @Value("${app.workflow-events.enabled:false}") boolean workflowEventsEnabled,
            ObjectProvider<KafkaAdmin> kafkaAdmin,
            // app.sidecar.enabled=false reads as "no sidecar configured" (NOT_CONFIGURED /
            // UNKNOWN), not as an outage.
            @Value(
                            "#{'${app.sidecar.enabled:true}'.equalsIgnoreCase('false') ? '' : '${app.sidecar.base-url:}'}")
                    String sidecarBaseUrl,
            ObjectProvider<PlatformBroadcastService> broadcast,
            EditionInfo edition) {
        this.broadcast = broadcast;
        this.edition = edition;
        this.healthContributorRegistry = healthContributorRegistry;
        this.workflowEventsEnabled = workflowEventsEnabled;
        this.kafkaAdmin = kafkaAdmin;
        this.sidecarBaseUrl = sidecarBaseUrl;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(HEALTH_PROBE_TIMEOUT_MS);
        requestFactory.setReadTimeout(HEALTH_PROBE_TIMEOUT_MS);
        this.healthProbeClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @GetMapping
    public PublicStatusResponse get() {
        PublicStatusResponse hit = cached;
        Instant at = cachedAt;
        if (hit != null && at != null && Instant.now().isBefore(at.plus(CACHE_TTL))) {
            return hit;
        }
        synchronized (this) {
            if (cached != null
                    && cachedAt != null
                    && Instant.now().isBefore(cachedAt.plus(CACHE_TTL))) {
                return cached;
            }
            PublicStatusResponse fresh = compute();
            cached = fresh;
            cachedAt = Instant.now();
            return fresh;
        }
    }

    private PublicStatusResponse compute() {
        List<Component> components = new ArrayList<>();
        components.add(new Component("api", "UP")); // answering this request is itself the proof
        components.add(
                safe(
                        () -> new Component("database", contributorStatusCode("db")),
                        new Component("database", "UNKNOWN")));
        components.add(
                safe(
                        () -> new Component("redis", contributorStatusCode("redis")),
                        new Component("redis", "UNKNOWN")));
        components.add(safe(this::kafkaComponent, new Component("events", "UNKNOWN")));
        components.add(
                safe(
                        () -> new Component("disk", contributorStatusCode("diskSpace")),
                        new Component("disk", "UNKNOWN")));
        components.add(
                safe(
                        () -> microserviceComponent("agents-runtime", sidecarBaseUrl, "/health"),
                        new Component("agents-runtime", "UNKNOWN")));

        boolean dbDown =
                components.stream()
                        .anyMatch(c -> "database".equals(c.name()) && "DOWN".equals(c.status()));
        boolean anyOtherDown =
                components.stream()
                        .anyMatch(
                                c ->
                                        !"database".equals(c.name())
                                                && !"api".equals(c.name())
                                                && "DOWN".equals(c.status()));

        return new PublicStatusResponse(
                dbDown ? "DOWN" : (anyOtherDown ? "DEGRADED" : "UP"),
                Instant.now(),
                components,
                safe(this::maintenance, new PublicMaintenance(false, "", null)),
                safe(this::announcement, null),
                edition);
    }

    private PublicMaintenance maintenance() {
        PlatformBroadcastService service = broadcast.getIfAvailable();
        return service == null
                ? new PublicMaintenance(false, "", null)
                : service.publicMaintenance();
    }

    private PublicAnnouncement announcement() {
        PlatformBroadcastService service = broadcast.getIfAvailable();
        return service == null ? null : service.publicAnnouncement();
    }

    private <T> T safe(java.util.function.Supplier<T> read, T fallback) {
        try {
            return read.get();
        } catch (Exception e) {
            return fallback;
        }
    }

    private String contributorStatusCode(String contributorName) {
        HealthContributor contributor = healthContributorRegistry.getContributor(contributorName);
        if (contributor instanceof HealthIndicator indicator) {
            return indicator.health().getStatus().getCode();
        }
        return "UNKNOWN";
    }

    /**
     * Kafka has no built-in actuator indicator in Spring Boot 3 (see {@code
     * SystemStatusController#kafkaStatus}'s javadoc for the history), so this is the same live
     * {@code describeCluster()} probe that controller runs - "NOT_CONFIGURED" (not DOWN) when the
     * workflow-events feature is off in this deployment.
     */
    private Component kafkaComponent() {
        if (!workflowEventsEnabled) {
            return new Component("events", "NOT_CONFIGURED");
        }
        KafkaAdmin admin = kafkaAdmin.getIfAvailable();
        if (admin == null) {
            return new Component("events", "NOT_CONFIGURED");
        }
        try (AdminClient client = AdminClient.create(admin.getConfigurationProperties())) {
            client.describeCluster()
                    .clusterId()
                    .get(HEALTH_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return new Component("events", "UP");
        } catch (Exception e) {
            return new Component("events", "DOWN");
        }
    }

    /**
     * Short-timeout no-auth GET against an internal microservice's own health endpoint (same probe
     * shape as the dashboard controller's). An unconfigured base URL reports NOT_CONFIGURED rather
     * than DOWN - the service was never expected in this deployment.
     */
    private Component microserviceComponent(String name, String baseUrl, String healthPath) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new Component(name, "NOT_CONFIGURED");
        }
        try {
            var response =
                    healthProbeClient
                            .get()
                            .uri(baseUrl.replaceAll("/+$", "") + healthPath)
                            .retrieve()
                            .toBodilessEntity();
            return new Component(name, response.getStatusCode().is2xxSuccessful() ? "UP" : "DOWN");
        } catch (Exception e) {
            return new Component(name, "DOWN");
        }
    }
}
