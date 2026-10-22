package com.agenticai.core.platform;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.security.logging.LogSafe;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Operator view + reset for the TOOL circuit breaker ({@code ToolCircuitBreakerService}, {@code
 * /api/platform/rate-control/circuit-breakers}), gated by the blanket {@code /api/platform/** ->
 * ROLE_platform-admin} rule.
 *
 * <p>What is real: the breaker's whole state lives in three Redis key families ({@code
 * agentic:tool-circuit:open:<tenant>:<tool>} with a cooldown TTL, {@code
 * agentic:tool-circuit:failures:<tenant>:<tool>} with the window TTL, and {@code
 * agentic:tool-circuit:lasterror:<tenant>:<tool>} holding the redacted tripping error under the
 * same cooldown TTL). {@code isOpen} does a plain {@code hasKey} on EVERY tool call (no cache), so
 * deleting the open key un-trips the breaker on the very next call. That is the one control
 * offered: reset. It is reversible - if the tool is still broken the breaker simply trips again
 * after the configured number of consecutive failures.
 *
 * <p>What is NOT offered: editing the threshold/window/cooldown. They are {@code @Value} config
 * properties read once at boot, so there is no runtime knob; they are shown read-only (the values
 * this instance booted with). The hosted-provider breaker ({@code HostedProviderHealth}) keeps its
 * state in a private per-instance map with no accessor, so it is not readable from here.
 *
 * <p>The caller is tenant-less; this class touches only Redis, never JPA, so no tenant binding is
 * needed. Tenant and tool are just parts of the Redis key.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@RequestMapping("/api/platform/rate-control/circuit-breakers")
@Profile("security")
public class PlatformCircuitBreakerController {

    private static final Logger log =
            LoggerFactory.getLogger(PlatformCircuitBreakerController.class);

    // Must match ToolCircuitBreakerService's private constants.
    static final String FAILURE_KEY_PREFIX = "agentic:tool-circuit:failures:";
    static final String OPEN_KEY_PREFIX = "agentic:tool-circuit:open:";
    static final String LAST_ERROR_KEY_PREFIX = "agentic:tool-circuit:lasterror:";
    private static final int MAX_ENTRIES = 500;

    private final StringRedisTemplate redis;
    private final long failureThreshold;
    private final long windowMinutes;
    private final long cooldownMinutes;

    public PlatformCircuitBreakerController(
            StringRedisTemplate redis,
            @Value("${app.tool-circuit-breaker.failure-threshold:5}") long failureThreshold,
            @Value("${app.tool-circuit-breaker.window-minutes:5}") long windowMinutes,
            @Value("${app.tool-circuit-breaker.cooldown-minutes:10}") long cooldownMinutes) {
        this.redis = redis;
        this.failureThreshold = failureThreshold;
        this.windowMinutes = windowMinutes;
        this.cooldownMinutes = cooldownMinutes;
    }

    public record Config(long failureThreshold, long windowMinutes, long cooldownMinutes) {}

    /** One (tenant, tool) pair with an open circuit or a live failure streak. */
    public record Entry(
            String tenantId,
            String tool,
            boolean open,
            Long cooldownSecondsRemaining,
            Instant reopensAt,
            Long consecutiveFailures,
            String lastError) {}

    public record Listing(Config config, List<Entry> entries, int openCount, boolean truncated) {}

    public record ResetRequest(String tenantId, String tool) {}

    public record ResetResult(String tenantId, String tool, boolean wasOpen) {}

    @GetMapping
    public Listing list() {
        Map<String, Entry> byPair = new TreeMap<>();
        boolean truncated = false;
        try {
            truncated |= collect(OPEN_KEY_PREFIX, true, byPair);
            truncated |= collect(FAILURE_KEY_PREFIX, false, byPair);
        } catch (RuntimeException e) {
            log.warn("Circuit-breaker state read failed - Redis unreachable?", e);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Circuit-breaker state is unavailable (Redis unreachable).");
        }
        List<Entry> entries = new ArrayList<>(byPair.values());
        entries.sort(
                Comparator.comparing(Entry::open)
                        .reversed()
                        .thenComparing(Entry::tenantId)
                        .thenComparing(Entry::tool));
        int open = (int) entries.stream().filter(Entry::open).count();
        return new Listing(
                new Config(failureThreshold, windowMinutes, cooldownMinutes),
                entries,
                open,
                truncated);
    }

    @PostMapping("/reset")
    public ResetResult reset(@RequestBody ResetRequest request) {
        if (request == null || isBlank(request.tenantId()) || isBlank(request.tool())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "tenantId and tool are required.");
        }
        String suffix = request.tenantId().trim() + ":" + request.tool().trim();
        boolean wasOpen;
        try {
            wasOpen = Boolean.TRUE.equals(redis.delete(OPEN_KEY_PREFIX + suffix));
            redis.delete(FAILURE_KEY_PREFIX + suffix);
            redis.delete(LAST_ERROR_KEY_PREFIX + suffix);
        } catch (RuntimeException e) {
            log.warn("Circuit-breaker reset failed for {}", suffix, e);
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Could not reset the breaker (Redis unreachable).");
        }
        String operator = TenantContext.current().map(TenantContext::getSubject).orElse("unknown");
        log.info(
                "Platform operator {} reset tool circuit breaker {} (wasOpen={})",
                LogSafe.clean(operator),
                suffix,
                wasOpen);
        return new ResetResult(request.tenantId().trim(), request.tool().trim(), wasOpen);
    }

    /**
     * @return true when the entry cap was hit.
     */
    private boolean collect(String prefix, boolean open, Map<String, Entry> byPair) {
        try (Cursor<String> cursor =
                redis.scan(ScanOptions.scanOptions().match(prefix + "*").count(500).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String rest = key.substring(prefix.length());
                int sep = rest.indexOf(':');
                if (sep <= 0 || sep == rest.length() - 1) {
                    continue;
                }
                String tenant = rest.substring(0, sep);
                String tool = rest.substring(sep + 1);
                String pair = tenant + "\u0000" + tool;
                if (!byPair.containsKey(pair) && byPair.size() >= MAX_ENTRIES) {
                    return true;
                }
                if (open) {
                    Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
                    if (ttl == null || ttl < 0) {
                        continue; // expired between SCAN and TTL, or no expiry
                    }
                    String lastError = redis.opsForValue().get(LAST_ERROR_KEY_PREFIX + rest);
                    byPair.put(
                            pair,
                            new Entry(
                                    tenant,
                                    tool,
                                    true,
                                    ttl,
                                    Instant.now().plusSeconds(ttl),
                                    null,
                                    lastError));
                } else {
                    String raw = redis.opsForValue().get(key);
                    Long streak = parseLong(raw);
                    if (streak == null) {
                        continue;
                    }
                    Entry existing = byPair.get(pair);
                    if (existing != null) {
                        continue; // open circuit already reported; its streak was reset on trip
                    }
                    byPair.put(pair, new Entry(tenant, tool, false, null, null, streak, null));
                }
            }
        }
        return false;
    }

    private static Long parseLong(String raw) {
        try {
            return raw == null ? null : Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
