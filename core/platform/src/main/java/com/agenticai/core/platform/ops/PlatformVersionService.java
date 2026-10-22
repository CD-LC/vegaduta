package com.agenticai.core.platform.ops;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.concurrent.TenantAwareExecutors;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What is deployed: {@code GET /api/platform/version}.
 *
 * <p><b>core</b> reports itself: {@code version} and fallback build time from Spring Boot's {@link
 * BuildProperties} ({@code build-info} goal in core/app/pom.xml), {@code gitSha} / {@code
 * buildTime} from the {@code GIT_SHA} / {@code BUILD_TIME} env vars core/Dockerfile bakes from
 * deploy-azure.yml's build args. Unstamped values are null, never guessed.
 *
 * <p><b>Other services</b> are probed on the internal URLs core already has (sidecar, code-intel)
 * or on optional {@code app.platform.ops.*-base-url} properties (academy, app-builder, web,
 * web-admin - all blank by default, no new required config). A blank URL reads {@code unknown}; an
 * answer below 500 reads {@code ok}; no answer within 3 s or a 5xx reads {@code unreachable}. None
 * of these services publishes a version/SHA today, so those fields stay null unless the health body
 * happens to carry {@code version} / {@code gitSha} / {@code buildTime} (read opportunistically, so
 * a service that starts publishing them shows up without a core change).
 *
 * <p>Answers are cached for 30 s per replica; probes run in parallel.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@Service
public class PlatformVersionService {

    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);
    static final Duration CACHE_TTL = Duration.ofSeconds(30);
    private static final ObjectMapper JSON = JsonMappers.plain();

    public record ServiceVersion(
            String name, String version, String gitSha, String buildTime, String status) {}

    public record VersionsResponse(List<ServiceVersion> services) {}

    /** One probed service: where its health endpoint is (null = not configured here). */
    record Target(String name, String url) {}

    @FunctionalInterface
    interface Prober {
        /** Status code and body of a GET, or throws on no answer. */
        HttpResult get(URI uri) throws IOException, InterruptedException;
    }

    record HttpResult(int status, String body) {}

    private final ServiceVersion core;
    private final List<Target> targets;
    private final Prober prober;
    private final Clock clock;
    private final ExecutorService executor =
            TenantAwareExecutors.newVirtualThreadPerTaskExecutor("platform-version-service-");

    private Instant cachedAt;
    private VersionsResponse cached;

    @Autowired
    public PlatformVersionService(
            ObjectProvider<BuildProperties> buildProperties,
            @Value("${GIT_SHA:}") String gitSha,
            @Value("${BUILD_TIME:}") String buildTime,
            @Value("${app.sidecar.base-url:}") String sidecarBaseUrl,
            @Value("${code-intel.enabled:false}") boolean codeIntelEnabled,
            @Value("${code-intel.base-url:}") String codeIntelBaseUrl,
            @Value("${app.platform.ops.academy-base-url:}") String academyBaseUrl,
            @Value("${app.platform.ops.app-builder-base-url:}") String appBuilderBaseUrl,
            @Value("${app.platform.ops.web-base-url:}") String webBaseUrl,
            @Value("${app.platform.ops.web-admin-base-url:}") String webAdminBaseUrl) {
        this(
                coreVersion(buildProperties.getIfAvailable(), gitSha, buildTime),
                List.of(
                        new Target("sidecar", join(sidecarBaseUrl, "/health")),
                        new Target(
                                "code-intel",
                                codeIntelEnabled ? join(codeIntelBaseUrl, "/health") : null),
                        new Target("academy", join(academyBaseUrl, "/api/health")),
                        new Target("app-builder", join(appBuilderBaseUrl, "/health")),
                        new Target("web", join(webBaseUrl, "/")),
                        new Target("web-admin", join(webAdminBaseUrl, "/"))),
                defaultProber(),
                Clock.systemUTC());
    }

    PlatformVersionService(ServiceVersion core, List<Target> targets, Prober prober, Clock clock) {
        this.core = core;
        this.targets = targets;
        this.prober = prober;
        this.clock = clock;
    }

    public synchronized VersionsResponse versions() {
        Instant now = clock.instant();
        if (cached != null && now.isBefore(cachedAt.plus(CACHE_TTL))) {
            return cached;
        }
        List<CompletableFuture<ServiceVersion>> futures =
                targets.stream()
                        .map(
                                t ->
                                        CompletableFuture.supplyAsync(() -> probe(t), executor)
                                                .completeOnTimeout(
                                                        new ServiceVersion(
                                                                t.name(),
                                                                null,
                                                                null,
                                                                null,
                                                                "unreachable"),
                                                        PROBE_TIMEOUT.toMillis() + 500,
                                                        TimeUnit.MILLISECONDS))
                        .toList();
        List<ServiceVersion> services = new ArrayList<>();
        services.add(core);
        futures.forEach(f -> services.add(f.join()));
        cached = new VersionsResponse(List.copyOf(services));
        cachedAt = now;
        return cached;
    }

    ServiceVersion probe(Target target) {
        if (target.url() == null) {
            return new ServiceVersion(target.name(), null, null, null, "unknown");
        }
        try {
            HttpResult result = prober.get(URI.create(target.url()));
            if (result.status() >= 500) {
                return new ServiceVersion(target.name(), null, null, null, "unreachable");
            }
            JsonNode body = parse(result.body());
            return new ServiceVersion(
                    target.name(),
                    field(body, "version"),
                    field(body, "gitSha", "git_sha", "commit"),
                    field(body, "buildTime", "build_time"),
                    "ok");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ServiceVersion(target.name(), null, null, null, "unreachable");
        } catch (IOException | RuntimeException e) {
            return new ServiceVersion(target.name(), null, null, null, "unreachable");
        }
    }

    static ServiceVersion coreVersion(BuildProperties build, String gitSha, String buildTime) {
        String version = build == null ? null : build.getVersion();
        String time = blankToNull(buildTime);
        if (time == null && build != null && build.getTime() != null) {
            time = build.getTime().toString();
        }
        return new ServiceVersion("core", version, blankToNull(gitSha), time, "ok");
    }

    /** {@code base + path} when base is an http(s) URL with a host; null otherwise. */
    static String join(String base, String path) {
        if (base == null || base.isBlank()) {
            return null;
        }
        String trimmed = base.trim().replaceAll("/+$", "");
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
        return trimmed + path;
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank() || body.length() > 65_536) {
            return null;
        }
        try {
            return JSON.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String field(JsonNode body, String... names) {
        if (body == null || !body.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode v = body.get(name);
            if (v != null && v.isValueNode() && !v.asString().isBlank()) {
                String s = v.asString();
                return s.length() > 100 ? s.substring(0, 100) : s;
            }
        }
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Prober defaultProber() {
        HttpClient http =
                HttpClient.newBuilder()
                        .connectTimeout(PROBE_TIMEOUT)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        return uri -> {
            HttpResponse<String> r =
                    http.send(
                            HttpRequest.newBuilder(uri).timeout(PROBE_TIMEOUT).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            return new HttpResult(r.statusCode(), r.body());
        };
    }
}
