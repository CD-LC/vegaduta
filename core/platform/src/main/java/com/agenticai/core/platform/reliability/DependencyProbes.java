package com.agenticai.core.platform.reliability;

import com.agenticai.core.platform.reliability.ProbeRunner.Outcome;
import com.agenticai.core.platform.reliability.ProbeRunner.Spec;
import com.agenticai.core.platform.reliability.ReliabilityApi.DependencyEntry;
import com.agenticai.core.providers.PlatformHostedLlmProvider;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The probe bodies and pure classification helpers behind {@link DependencyHealthService}: one
 * cheap, non-billable check per dependency (SELECT 1 + Hikari counters, PING, unauthenticated
 * health/JWKS GETs, TCP connects, credential presence, hosted-pool reachability), each bounded by
 * the probe timeout. Owns the shared {@link HttpClient}; created by the service's Spring
 * constructor, not a bean. Details never carry hostnames, URLs or secrets.
 */
final class DependencyProbes {

    static final int MAX_POOL_ROWS = 10;

    /**
     * Pool utilisation (active / max) at or above which Postgres reads DEGRADED even though SELECT
     * 1 passed.
     */
    static final double POOL_SATURATION_THRESHOLD = 0.9;

    private final HttpClient http;
    private final Duration probeTimeout;
    private final Clock clock;

    DependencyProbes(HttpClient http, Duration probeTimeout, Clock clock) {
        this.http = http;
        this.probeTimeout = probeTimeout;
        this.clock = clock;
    }

    /**
     * DEGRADED when callers are already queueing for a connection or the pool is nearly full; else
     * UP.
     */
    static DependencyStatus poolStatus(int active, int max, int awaiting) {
        if (awaiting > 0) {
            return DependencyStatus.DEGRADED;
        }
        if (max > 0 && (double) active / max >= POOL_SATURATION_THRESHOLD) {
            return DependencyStatus.DEGRADED;
        }
        return DependencyStatus.UP;
    }

    /**
     * DEGRADED outcome (without borrowing a connection) when every pooled connection is in use;
     * null otherwise. Only an exhausted pool counts: threads waiting while the pool still has room
     * means connections are failing to open, which is what the SELECT 1 probe is there to catch (it
     * then reads DOWN on timeout). Counters of -1 (not available) never count as exhausted.
     */
    static Outcome exhaustedPoolOutcome(int active, int max, int awaiting) {
        if (active < 0 || max <= 0 || active < max) {
            return null;
        }
        return new Outcome(
                DependencyStatus.DEGRADED,
                "connection pool is exhausted ("
                        + active
                        + "/"
                        + max
                        + " connections active, "
                        + Math.max(0, awaiting)
                        + " waiting); SELECT 1 was not attempted because it would queue behind"
                        + " them");
    }

    record Broker(String host, int port) {}

    /**
     * First broker of a bootstrap list ("PLAINTEXT://h:9092,h2:9092" or "h:9092"); null when
     * unusable.
     */
    static Broker firstBroker(String bootstrap) {
        return hostPort(bootstrap, 9092);
    }

    /**
     * First "host:port" of a comma list (scheme prefix allowed), {@code defaultPort} when none;
     * null when unusable.
     */
    static Broker hostPort(String bootstrap, int defaultPort) {
        if (bootstrap == null || bootstrap.isBlank()) {
            return null;
        }
        String first = bootstrap.split(",")[0].trim();
        first = first.replaceFirst("^[A-Za-z_]+://", "");
        if (first.isEmpty()) {
            return null;
        }
        int colon = first.lastIndexOf(':');
        if (colon < 0) {
            return new Broker(first, defaultPort);
        }
        String host = first.substring(0, colon);
        try {
            int port = Integer.parseInt(first.substring(colon + 1));
            return host.isEmpty() || port <= 0 || port > 65535 ? null : new Broker(host, port);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 2xx UP; other answers below 500 DEGRADED (answering, but not healthy); 5xx DOWN. */
    static Outcome healthOutcome(int code) {
        if (code >= 200 && code < 300) {
            return new Outcome(DependencyStatus.UP, "health endpoint answered HTTP " + code);
        }
        return new Outcome(
                code >= 500 ? DependencyStatus.DOWN : DependencyStatus.DEGRADED,
                "health endpoint answered HTTP " + code);
    }

    /**
     * MinIO liveness 200 is UP; a non-MinIO S3 store answering below 500 is reachable (UP); 5xx
     * DOWN.
     */
    static Outcome storageOutcome(int code) {
        if (code >= 200 && code < 300) {
            return new Outcome(
                    DependencyStatus.UP,
                    "MinIO liveness answered HTTP " + code + " (credentials not exercised)");
        }
        if (code < 500) {
            return new Outcome(
                    DependencyStatus.UP,
                    "endpoint reachable (HTTP "
                            + code
                            + "; not MinIO's liveness path); credentials not exercised");
        }
        return new Outcome(DependencyStatus.DOWN, "endpoint answered HTTP " + code);
    }

    /** Parses an http(s) URL, or null when it is anything else (never throws). */
    static URI httpUri(String raw) {
        try {
            URI uri = URI.create(raw.trim());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null
                    ? uri
                    : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** An UNKNOWN row stamped {@code at} - "not configured / not checkable", never a failure. */
    static DependencyEntry unknownEntry(String name, String category, String detail, Instant at) {
        return new DependencyEntry(name, category, DependencyStatus.UNKNOWN, null, at, detail);
    }

    Outcome checkPostgres(ObjectProvider<DataSource> provider) throws Exception {
        DataSource ds = provider.getIfAvailable();
        if (ds == null) {
            return new Outcome(DependencyStatus.UNKNOWN, "no datasource is configured");
        }
        int active = -1;
        int max = -1;
        int awaiting = -1;
        HikariDataSource hikari = hikariOf(ds);
        if (hikari != null) {
            max = hikari.getMaximumPoolSize();
            HikariPoolMXBean pool = hikari.getHikariPoolMXBean();
            if (pool != null) {
                active = pool.getActiveConnections();
                awaiting = pool.getThreadsAwaitingConnection();
            }
        }
        // A pool with no free connection would make getConnection() block until the probe times
        // out, and a timeout
        // reads DOWN: a saturated pool must read as pool pressure (DEGRADED), not as a database
        // outage.
        Outcome exhausted = exhaustedPoolOutcome(active, max, awaiting);
        if (exhausted != null) {
            return exhausted;
        }
        try (Connection connection = ds.getConnection();
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout((int) Math.max(1, probeTimeout.toSeconds()));
            statement.execute("SELECT 1");
        }
        if (active >= 0) {
            DependencyStatus status = poolStatus(active, max, Math.max(0, awaiting));
            String pool =
                    "pool "
                            + active
                            + "/"
                            + max
                            + " connections active, "
                            + Math.max(0, awaiting)
                            + " waiting";
            return new Outcome(
                    status,
                    status == DependencyStatus.UP
                            ? "SELECT 1 succeeded; " + pool
                            : "SELECT 1 succeeded but the connection pool is under pressure; "
                                    + pool);
        }
        return new Outcome(DependencyStatus.UP, "SELECT 1 succeeded; pool counters not available");
    }

    private static HikariDataSource hikariOf(DataSource ds) {
        if (ds instanceof HikariDataSource h) {
            return h;
        }
        try {
            if (ds.isWrapperFor(HikariDataSource.class)) {
                return ds.unwrap(HikariDataSource.class);
            }
        } catch (SQLException | RuntimeException e) {
            // Pool counters are a bonus; SELECT 1 is the check.
        }
        return null;
    }

    Outcome checkRedis(ObjectProvider<StringRedisTemplate> provider) {
        StringRedisTemplate template = provider.getIfAvailable();
        if (template == null) {
            return new Outcome(DependencyStatus.UNKNOWN, "Redis is not configured");
        }
        String pong = template.execute((RedisCallback<String>) connection -> connection.ping());
        return "PONG".equalsIgnoreCase(pong)
                ? new Outcome(DependencyStatus.UP, "PING answered")
                : new Outcome(DependencyStatus.DEGRADED, "PING returned an unexpected reply");
    }

    Outcome checkJwks(String jwkSetUri) throws Exception {
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            return new Outcome(
                    DependencyStatus.UNKNOWN,
                    "no JWK set URI is configured (security profile off)");
        }
        URI uri = httpUri(jwkSetUri);
        if (uri == null) {
            return new Outcome(
                    DependencyStatus.UNKNOWN, "the JWK set URI is not a valid http(s) URL");
        }
        int code = get(uri);
        if (code == 200) {
            return new Outcome(DependencyStatus.UP, "JWKS endpoint answered HTTP 200");
        }
        return new Outcome(
                code >= 500 ? DependencyStatus.DOWN : DependencyStatus.DEGRADED,
                "JWKS endpoint answered HTTP " + code);
    }

    Outcome checkSidecar(String baseUrl) throws Exception {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new Outcome(DependencyStatus.UNKNOWN, "no sidecar base URL is configured");
        }
        URI uri = httpUri(baseUrl.replaceAll("/+$", "") + "/health");
        if (uri == null) {
            return new Outcome(
                    DependencyStatus.UNKNOWN, "the sidecar base URL is not a valid http(s) URL");
        }
        int code = get(uri);
        if (code >= 200 && code < 300) {
            return new Outcome(
                    DependencyStatus.UP,
                    "liveness endpoint answered HTTP " + code + " (model readiness not exercised)");
        }
        return new Outcome(
                code >= 500 ? DependencyStatus.DOWN : DependencyStatus.DEGRADED,
                "liveness endpoint answered HTTP " + code);
    }

    Outcome checkKafka(boolean workflowEventsEnabled, String bootstrap) throws Exception {
        if (!workflowEventsEnabled) {
            return new Outcome(
                    DependencyStatus.UNKNOWN, "workflow events are disabled in this deployment");
        }
        Broker broker = firstBroker(bootstrap);
        if (broker == null) {
            return new Outcome(
                    DependencyStatus.UNKNOWN, "no usable Kafka bootstrap address is configured");
        }
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(broker.host(), broker.port()),
                    (int) probeTimeout.toMillis());
        }
        return new Outcome(
                DependencyStatus.UP,
                "bootstrap broker accepts TCP connections (protocol not exercised)");
    }

    Outcome checkTemporal(boolean profileActive, String target) throws Exception {
        if (!profileActive) {
            return new Outcome(
                    DependencyStatus.UNKNOWN,
                    "the temporal profile is not active in this deployment");
        }
        Broker frontend = hostPort(target, 7233);
        if (frontend == null) {
            return new Outcome(
                    DependencyStatus.UNKNOWN, "no usable Temporal address is configured");
        }
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(frontend.host(), frontend.port()),
                    (int) probeTimeout.toMillis());
        }
        return new Outcome(
                DependencyStatus.UP, "frontend accepts TCP connections (gRPC not exercised)");
    }

    Outcome checkHttp(String baseUrl, String path, String what)
            throws Exception {
        if (baseUrl == null || baseUrl.isBlank()) {
            return new Outcome(DependencyStatus.UNKNOWN, "no " + what + " URL is configured");
        }
        URI uri = httpUri(baseUrl.trim().replaceAll("/+$", "") + path);
        if (uri == null) {
            return new Outcome(
                    DependencyStatus.UNKNOWN, "the " + what + " URL is not a valid http(s) URL");
        }
        return healthOutcome(get(uri));
    }

    Outcome checkStorage(String endpoint) throws Exception {
        if (endpoint == null || endpoint.isBlank()) {
            return new Outcome(
                    DependencyStatus.UNKNOWN, "no object-storage endpoint is configured");
        }
        URI uri = httpUri(endpoint.trim().replaceAll("/+$", "") + "/minio/health/live");
        if (uri == null) {
            return new Outcome(
                    DependencyStatus.UNKNOWN,
                    "the object-storage endpoint is not a valid http(s) URL");
        }
        return storageOutcome(get(uri));
    }

    Outcome toolCredentialOutcome(
            ObjectProvider<PlatformToolCredentialService> provider, String toolKey) {
        PlatformToolCredentialService service = provider.getIfAvailable();
        if (service == null) {
            return new Outcome(DependencyStatus.UNKNOWN, "the credential store is not available");
        }
        return service.status(toolKey).configured()
                ? new Outcome(
                        DependencyStatus.UP, "credential present and enabled; not live-checked")
                : new Outcome(DependencyStatus.UNKNOWN, "no enabled credential configured");
    }

    // ----------------------------------------------------------------- hosted-LLM pool

    private record PoolTarget(String label, String baseUrl) {}

    /**
     * One row per enabled hosted-pool provider (at most {@link #MAX_POOL_ROWS}).
     *
     * @param enabledRows lists the enabled rows in priority order; {@code null} when the
     *     hosted-pool store is not available. The repository itself stays with {@link
     *     DependencyHealthService}.
     */
    List<DependencyEntry> poolStage(
            ProbeRunner runner, Callable<List<PlatformHostedLlmProvider>> enabledRows) {
        if (enabledRows == null) {
            return List.of(
                    unknownEntry(
                            "llm-pool",
                            "ai",
                            "the hosted-pool store is not available",
                            clock.instant()));
        }
        List<PoolTarget> targets;
        try {
            targets =
                    runner.call(
                            () -> {
                                List<PoolTarget> rows = new ArrayList<>();
                                for (PlatformHostedLlmProvider row : enabledRows.call()) {
                                    if (rows.size() >= MAX_POOL_ROWS) {
                                        break;
                                    }
                                    rows.add(new PoolTarget(labelOf(row), row.getBaseUrl()));
                                }
                                return rows;
                            });
        } catch (Exception e) {
            return List.of(
                    unknownEntry(
                            "llm-pool",
                            "ai",
                            "could not list the hosted-pool rows: " + ProbeRunner.describe(e),
                            clock.instant()));
        }
        if (targets.isEmpty()) {
            return List.of(
                    unknownEntry("llm-pool", "ai", "no enabled hosted-pool rows", clock.instant()));
        }
        List<Spec> specs = new ArrayList<>();
        for (PoolTarget target : targets) {
            specs.add(
                    new Spec(
                            "llm-pool: " + target.label(),
                            "ai",
                            () -> checkPoolRow(target.baseUrl())));
        }
        return runner.runAll(specs);
    }

    private static String labelOf(PlatformHostedLlmProvider row) {
        String label =
                row.getLabel() == null ? "" : row.getLabel().replaceAll("[\\r\\n\\t]", " ").trim();
        if (label.isEmpty()) {
            return "row " + row.getId();
        }
        return label.length() > 48 ? label.substring(0, 48) : label;
    }

    Outcome checkPoolRow(String baseUrl) throws Exception {
        URI uri = baseUrl == null ? null : httpUri(baseUrl);
        if (uri == null) {
            return new Outcome(
                    DependencyStatus.UNKNOWN,
                    "no http(s) base URL on this row (credential-based provider)");
        }
        int code = get(uri);
        String note = "reachability only: no credential sent, no completion requested";
        if (code >= 500) {
            return new Outcome(DependencyStatus.DEGRADED, "answered HTTP " + code + "; " + note);
        }
        return new Outcome(DependencyStatus.UP, "answered HTTP " + code + "; " + note);
    }

    /**
     * GET with the probe timeout, body discarded, redirects not followed; returns the status code.
     */
    private int get(URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(probeTimeout).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
