package com.agenticai.core.tenancy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Platform-admin "delete tenant completely" control - best-effort purge of every Redis key this
 * platform's ~12 rate-limit/quota/session services scatter across a tenant's lifetime. None of
 * these keys are durable data (every family here carries its own TTL and would self-expire
 * eventually) so a miss on any one family never aborts the caller's larger deletion - same
 * fail-open posture {@link com.agenticai.core.agents.quota.FreeTierUsageLimiterService#increment}
 * already takes for this exact class of state on a Redis outage.
 *
 * <p><b>Deliberately excluded (researched, not forgotten):</b> {@code SignupRateLimiter} and {@code
 * DemoSessionRateLimiter}/{@code DemoChannelRateLimiter}'s six families are keyed by client IP or
 * an opaque demo-session/Telegram-chat bucket - none of them embed a tenant schema name, tenant
 * UUID, or user id in the key, so there is nothing here to target by tenant. Demo sandbox data also
 * has its own separate, already-running expiry job (see {@code DemoSessionExpiryJob}) and is
 * explicitly not part of a real tenant's erasure lifecycle.
 *
 * <p>Pattern families are found with ONE cursor-based {@code SCAN} over {@code agentic:*} (never
 * the blocking, O(N) {@code KEYS}, which many managed Redis services restrict), classified
 * client-side, and deleted in batches of {@value #SCAN_BATCH} as the cursor advances so memory
 * stays bounded. {@code SCAN} may return a key more than once; deleting an already-deleted key is a
 * no-op and the vision-session check re-reads the value (null after delete), so duplicates are
 * harmless. On Redis Cluster the scan visits every master node (see {@link RedisKeyspaceScanner};
 * verified by reading the library and with mocks, not on a real cluster). Mid-scan failures are
 * reported as a partial purge together with the number of keys already removed.
 */
@Service
public class RedisTenantDataPurgeService {

    private static final Logger log = LoggerFactory.getLogger(RedisTenantDataPurgeService.class);

    /** {@code COUNT} hint passed to SCAN, and the delete batch size. */
    static final int SCAN_BATCH = 500;

    private static final String KEY_NAMESPACE = "agentic:";
    private static final String VISION_SESSION_PREFIX = "agentic:vision-session:";

    private final StringRedisTemplate redisTemplate;
    private final RedisKeyspaceScanner scanner;
    private final ObjectMapper objectMapper;

    public RedisTenantDataPurgeService(
            StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.scanner = new RedisKeyspaceScanner(redisTemplate);
        this.objectMapper = objectMapper;
    }

    /**
     * @param tenantSchema the tenant's schema name (== the {@code tenant_id} claim every one of
     *     these services actually keys by - see {@code TenantProvisioningService}'s signup flow)
     * @param mcpServerGrantIds every {@code mcp_server_access_grant.id} this tenant ever had,
     *     collected by the caller BEFORE the tenant schema is dropped (that table lives in the
     *     tenant's own schema, and {@code McpServerRateLimiter}'s key is keyed by grant id alone,
     *     not tenant - there is no way to find these keys after the drop)
     * @param workflowRunIds every {@code workflow_run.id} this tenant ever had, collected the same
     *     way and for the same reason ({@code WorkflowRunCancellationService}'s key is keyed by run
     *     id alone)
     */
    public void purgeForTenant(
            String tenantSchema, List<String> mcpServerGrantIds, List<String> workflowRunIds) {
        purgeExactKeys(
                "free-tier usage counters",
                List.of(
                        "agentic:free-tier-usage:daily:" + tenantSchema,
                        "agentic:free-tier-usage:monthly:" + tenantSchema,
                        "agentic:free-tier-usage:daily-tokens:" + tenantSchema,
                        "agentic:free-tier-usage:monthly-tokens:" + tenantSchema));
        purgeExactKeys(
                "LLM gateway rate limit", List.of("agentic:llm-gateway-rate:" + tenantSchema));
        // PerUserBudgetService: modality comes BEFORE the tenant segment
        // ("agentic:per-user-budget:<modality>:<tenant>:<subject>"), so the
        // tenant can only be matched with a glob across that first segment,
        // not a literal prefix.
        purgeScannedFamilies(
                tenantSchema,
                List.of(
                        new Family(
                                "workflow-trigger rate limits",
                                "agentic:workflow-trigger-rate:" + tenantSchema + ":*"),
                        new Family(
                                "inbound message dedup markers",
                                "agentic:inbound-dedup:" + tenantSchema + ":*"),
                        new Family(
                                "vision frame/watch-minute counters",
                                "agentic:vision-frames:daily:" + tenantSchema + ":*"),
                        new Family(
                                "vision watch-minute counters",
                                "agentic:vision-watch-minutes:daily:" + tenantSchema + ":*"),
                        new Family(
                                "per-user budget counters",
                                "agentic:per-user-budget:*:" + tenantSchema + ":*"),
                        new Family(
                                "per-user budget credit counters",
                                "agentic:per-user-budget-credit:*:" + tenantSchema + ":*")));

        purgeById("MCP server rate limits", "agentic:mcpserver-rate:", mcpServerGrantIds);
        purgeById(
                "workflow-run cancellation flags", "agentic:workflow-run-cancel:", workflowRunIds);
    }

    private void purgeExactKeys(String description, List<String> keys) {
        try {
            Long deleted = redisTemplate.delete(keys);
            log.info(
                    "Purged {} {} for tenant deletion", deleted == null ? 0 : deleted, description);
        } catch (RuntimeException e) {
            log.warn("Failed to purge {} during tenant deletion - continuing", description, e);
        }
    }

    /** A key family to erase for the tenant, given as a Redis glob; matched client-side. */
    private static final class Family {
        final String description;
        final String glob;
        final Pattern regex;
        final Set<String> pending = new LinkedHashSet<>();
        long deleted;

        Family(String description, String glob) {
            this.description = description;
            this.glob = glob;
            this.regex = globToRegex(glob);
        }
    }

    /** Redis glob restricted to literals and {@code *} (the only metacharacter these use). */
    private static Pattern globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        boolean first = true;
        for (String literal : glob.split("\\*", -1)) {
            if (!first) {
                regex.append(".*");
            }
            regex.append(Pattern.quote(literal));
            first = false;
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    /**
     * ONE pass over the {@code agentic:*} keyspace (not one pass per family: SCAN cost is the whole
     * keyspace however narrow the MATCH, so seven passes were seven times the work). Every key is
     * classified client-side against the tenant's families; the matches are deleted in batches of
     * {@value #SCAN_BATCH} as the scan advances, and vision sessions (tenant only inside the JSON
     * value) are checked in the same pass.
     *
     * <p>If the scan fails midway, what was already collected is still deleted and the log says how
     * many keys were removed before the failure, so a partial erasure is reported as partial.
     */
    private void purgeScannedFamilies(String tenantSchema, List<Family> families) {
        int[] purgedSessions = {0};
        long[] scanned = {0};
        RuntimeException scanFailure = null;
        ScanOptions options =
                ScanOptions.scanOptions().match(KEY_NAMESPACE + "*").count(SCAN_BATCH).build();
        try {
            scanner.scan(
                    options,
                    key -> {
                        scanned[0]++;
                        for (Family family : families) {
                            if (family.regex.matcher(key).matches()) {
                                family.pending.add(key);
                                if (family.pending.size() >= SCAN_BATCH) {
                                    flush(family);
                                }
                                return;
                            }
                        }
                        if (key.startsWith(VISION_SESSION_PREFIX)
                                && purgeVisionSession(key, tenantSchema)) {
                            purgedSessions[0]++;
                        }
                    });
        } catch (RuntimeException e) {
            scanFailure = e;
        }
        long removed = purgedSessions[0];
        for (Family family : families) {
            flush(family);
            removed += family.deleted;
            if (family.deleted == 0) {
                log.info(
                        "No {} deleted for tenant deletion (pattern {})",
                        family.description,
                        family.glob);
            } else {
                log.info("Purged {} {} for tenant deletion", family.deleted, family.description);
            }
        }
        log.info("Purged {} vision sessions for tenant deletion", purgedSessions[0]);
        if (scanFailure != null) {
            log.warn(
                    "Redis scan aborted after {} keys during tenant deletion: the purge is PARTIAL"
                            + " ({} keys were removed before the failure, the rest of the keyspace"
                            + " was not examined) - continuing",
                    scanned[0],
                    removed,
                    scanFailure);
        }
    }

    private void flush(Family family) {
        if (family.pending.isEmpty()) {
            return;
        }
        List<String> batch = new ArrayList<>(family.pending);
        family.pending.clear();
        try {
            Long n = redisTemplate.delete(batch);
            family.deleted += n == null ? 0 : n;
        } catch (RuntimeException e) {
            log.warn(
                    "Failed to purge {} during tenant deletion (pattern {}) - continuing",
                    family.description,
                    family.glob,
                    e);
        }
    }

    private void purgeById(String description, String prefix, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.info("No {} to purge for tenant deletion (no ids collected)", description);
            return;
        }
        try {
            List<String> keys = ids.stream().map(id -> prefix + id).toList();
            Long deleted = redisTemplate.delete(keys);
            log.info(
                    "Purged {} {} for tenant deletion", deleted == null ? 0 : deleted, description);
        } catch (RuntimeException e) {
            log.warn("Failed to purge {} during tenant deletion - continuing", description, e);
        }
    }

    /**
     * {@code VisionSessionService}'s key is keyed purely by an opaque random {@code sessionId} -
     * the tenant is only present INSIDE the JSON value ({@code VisionSessionRecord.tenantId}), so
     * there is no pattern to glob against. Parses the value to decide. Deliberately does not
     * deserialize into {@code VisionSessionRecord} directly - that class lives in {@code
     * core/agents}, and adding a dependency from {@code core/tenancy} on it just for this one field
     * read isn't worth the new module coupling.
     *
     * @return true when the session belonged to the tenant and was deleted
     */
    private boolean purgeVisionSession(String key, String tenantSchema) {
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return false;
            }
            JsonNode node = objectMapper.readTree(json);
            if (tenantSchema.equals(node.path("tenantId").asString(null))) {
                redisTemplate.delete(key);
                return true;
            }
        } catch (Exception failure) {
            // One unreadable value must not abort the scan - skip it.
            log.warn("Could not purge vision-session key {} during tenant deletion", key, failure);
        }
        return false;
    }
}
