package com.agenticai.core.tenancy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * Zero test coverage existed for {@link RedisTenantDataPurgeService} before this. Every key family
 * it purges is built by bare string concatenation, and each pattern either targets exactly the
 * deleted tenant or silently targets someone else's data - both failure directions are real: a
 * pattern that DROPS a segment (e.g. losing the leading {@code *:} on the per-user-budget families,
 * whose tenant segment is NOT first) silently becomes a no-op and the "deleted" tenant's counters
 * live on, while a pattern that WIDENS the tenant segment to {@code *} purges every other tenant's
 * budget/rate-limit state during one tenant's deletion. Neither direction would fail anywhere else
 * in the suite, and the second is a cross-tenant data-destruction bug. These tests pin every
 * pattern literally AND run the purge against a simulated mixed-tenant keyspace so a widened glob
 * fails on the surviving tenant's keys, not just on a string comparison.
 *
 * <p>Mockito on {@link StringRedisTemplate}/{@link ValueOperations} (the {@link
 * PlanLimitsServiceTest} pattern) with a real {@link ObjectMapper} (JSON parsing of vision-session
 * values is part of the behaviour under test). No live Redis.
 */
class RedisTenantDataPurgeServiceTest {

    private static final String SCHEMA = "acme";
    // A second tenant whose keys must SURVIVE acme's purge untouched.
    private static final String OTHER_TENANT = "globex";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private RedisTenantDataPurgeService service;

    // A tiny in-memory keyspace behind the mocked KEYS/DEL/GET: KEYS applies
    // real Redis-style glob matching against whatever keys a test seeded,
    // and every deletion (either overload) is recorded in deletedKeys. This
    // is what lets the mixed-tenant tests below fail against a broken
    // pattern (widened glob deletes globex keys; dropped segment deletes
    // nothing) instead of only against a changed literal.
    private final Set<String> keyspace = new LinkedHashSet<>();
    private final Map<String, String> values = new HashMap<>();
    private final List<String> deletedKeys = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        when(redisTemplate.scan(any(ScanOptions.class)))
                .thenAnswer(
                        invocation -> {
                            Pattern regex =
                                    globToRegex(
                                            invocation
                                                    .getArgument(0, ScanOptions.class)
                                                    .getPattern());
                            return new FakeCursor<>(
                                    keyspace.stream()
                                            .filter(key -> regex.matcher(key).matches())
                                            .toList());
                        });
        // Single-key overload - what purgeVisionSessions uses per session.
        when(redisTemplate.delete(anyString()))
                .thenAnswer(
                        invocation -> {
                            String key = invocation.getArgument(0, String.class);
                            deletedKeys.add(key);
                            return keyspace.remove(key);
                        });
        // Collection overload - what every other family uses.
        when(redisTemplate.delete(ArgumentMatchers.<String>anyCollection()))
                .thenAnswer(
                        invocation -> {
                            Collection<String> keys = invocation.getArgument(0);
                            deletedKeys.addAll(keys);
                            keyspace.removeAll(keys);
                            return (long) keys.size();
                        });
        when(valueOperations.get(anyString()))
                .thenAnswer(invocation -> values.get(invocation.getArgument(0, String.class)));

        service = new RedisTenantDataPurgeService(redisTemplate, new ObjectMapper());
    }

    /**
     * Redis glob {@code *} matches ANY run of characters INCLUDING {@code :} - which is exactly why
     * a tenant segment widened to {@code *} would cross tenant boundaries, and why this simulation
     * must model it faithfully ({@code .*}, not a colon-excluding class).
     */
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
        return Pattern.compile(regex.toString());
    }

    // ------------------------------------------------------------------
    // Every pattern, pinned literally
    // ------------------------------------------------------------------

    @Test
    void freeTierAndLlmGatewayCountersAreDeletedByTheirExactTenantSuffixedKeys() {
        service.purgeForTenant(SCHEMA, List.of(), List.of());

        // Exact keys, exact order - FreeTierUsageLimiterService's four
        // window counters plus the LLM gateway rate bucket, all keyed by
        // the tenant schema as the FINAL segment.
        verify(redisTemplate)
                .delete(
                        List.of(
                                "agentic:free-tier-usage:daily:" + SCHEMA,
                                "agentic:free-tier-usage:monthly:" + SCHEMA,
                                "agentic:free-tier-usage:daily-tokens:" + SCHEMA,
                                "agentic:free-tier-usage:monthly-tokens:" + SCHEMA));
        verify(redisTemplate).delete(List.of("agentic:llm-gateway-rate:" + SCHEMA));
    }

    @Test
    void everyGlobFamilyIsMatchedIncludingTheNonLeadingTenantSegmentOnes() {
        // One tenant key per family, each next to near-misses that must survive: a different
        // tenant, and keys with the tenant in the wrong segment.
        List<String> mine =
                List.of(
                        "agentic:workflow-trigger-rate:" + SCHEMA + ":wf",
                        "agentic:inbound-dedup:" + SCHEMA + ":m1",
                        "agentic:vision-frames:daily:" + SCHEMA + ":2026-01-01",
                        "agentic:vision-watch-minutes:daily:" + SCHEMA + ":2026-01-01",
                        // PerUserBudgetService puts the modality BEFORE the tenant.
                        "agentic:per-user-budget:chat:" + SCHEMA + ":u1",
                        "agentic:per-user-budget-credit:image:" + SCHEMA + ":u1");
        List<String> others =
                List.of(
                        "agentic:workflow-trigger-rate:" + OTHER_TENANT + ":wf",
                        "agentic:workflow-trigger-rate:x" + SCHEMA + ":wf",
                        "agentic:inbound-dedup:" + OTHER_TENANT + ":m1",
                        "agentic:vision-frames:daily:" + OTHER_TENANT + ":2026-01-01",
                        "agentic:vision-watch-minutes:daily:" + OTHER_TENANT + ":2026-01-01",
                        "agentic:per-user-budget:chat:" + OTHER_TENANT + ":u1",
                        // tenant name in the subject slot, not the tenant slot
                        "agentic:per-user-budget:chat:" + OTHER_TENANT + ":" + SCHEMA,
                        "agentic:per-user-budget-credit:image:" + OTHER_TENANT + ":u1",
                        "agentic:unrelated:" + SCHEMA + ":k");
        keyspace.addAll(mine);
        keyspace.addAll(others);

        service.purgeForTenant(SCHEMA, List.of(), List.of());

        List<String> scanDeleted =
                deletedKeys.stream()
                        .filter(
                                k ->
                                        !k.startsWith("agentic:free-tier")
                                                && !k.startsWith("agentic:llm-gateway"))
                        .toList();
        assertTrue(
                scanDeleted.containsAll(mine) && scanDeleted.size() == mine.size(),
                "deleted: " + scanDeleted);
        assertTrue(keyspace.containsAll(others));
    }

    @Test
    void theKeyspaceIsScannedOnceWithTheAgenticNamespaceAndNeverWithKeys() {
        service.purgeForTenant(SCHEMA, List.of(), List.of());

        verify(redisTemplate, times(1)).scan(any(ScanOptions.class));
        verify(redisTemplate).scan(argThat((ScanOptions o) -> "agentic:*".equals(o.getPattern())));
        verify(redisTemplate, never()).keys(anyString());
    }

    @Test
    void mcpGrantAndWorkflowRunKeysAreDeletedUnderTheirIdKeyedPrefixes() {
        service.purgeForTenant(SCHEMA, List.of("grant-1", "grant-2"), List.of("run-1"));

        // These two families are keyed by grant/run id ALONE (no tenant
        // segment anywhere) - the caller collected the ids before the schema
        // drop precisely because there is no pattern to find them by after.
        verify(redisTemplate)
                .delete(
                        List.of(
                                "agentic:mcpserver-rate:grant-1",
                                "agentic:mcpserver-rate:grant-2"));
        verify(redisTemplate).delete(List.of("agentic:workflow-run-cancel:run-1"));
    }

    // ------------------------------------------------------------------
    // Mixed-tenant keyspace: the other tenant must survive
    // ------------------------------------------------------------------

    @Test
    void purgingOneTenantDeletesItsKeysAndLeavesEveryOtherTenantsKeysUntouched() {
        // Seed both tenants side by side in the families where a broken glob
        // would cross over - especially per-user-budget, where the tenant
        // segment sits in the MIDDLE of the key.
        keyspace.add("agentic:workflow-trigger-rate:" + SCHEMA + ":wf-1");
        keyspace.add("agentic:workflow-trigger-rate:" + OTHER_TENANT + ":wf-1");
        keyspace.add("agentic:per-user-budget:chat:" + SCHEMA + ":user-1");
        keyspace.add("agentic:per-user-budget:chat:" + OTHER_TENANT + ":user-1");
        keyspace.add("agentic:per-user-budget-credit:image:" + SCHEMA + ":user-1");
        keyspace.add("agentic:per-user-budget-credit:image:" + OTHER_TENANT + ":user-1");

        service.purgeForTenant(SCHEMA, List.of(), List.of());

        // acme's keys are gone - this half fails if a segment got dropped
        // and the glob stopped matching anything (silent no-op direction).
        assertTrue(deletedKeys.contains("agentic:workflow-trigger-rate:" + SCHEMA + ":wf-1"));
        assertTrue(deletedKeys.contains("agentic:per-user-budget:chat:" + SCHEMA + ":user-1"));
        assertTrue(
                deletedKeys.contains("agentic:per-user-budget-credit:image:" + SCHEMA + ":user-1"));
        // globex's keys were never deleted - this half fails if the tenant
        // segment got widened to * (cross-tenant destruction direction).
        assertTrue(
                deletedKeys.stream().noneMatch(key -> key.contains(OTHER_TENANT)),
                "purging tenant '" + SCHEMA + "' deleted another tenant's keys: " + deletedKeys);
        assertTrue(keyspace.contains("agentic:per-user-budget:chat:" + OTHER_TENANT + ":user-1"));
    }

    // ------------------------------------------------------------------
    // Vision sessions: tenant lives in the VALUE, not the key
    // ------------------------------------------------------------------

    @Test
    void
            visionSessionsAreDeletedOnlyWhenTheirJsonValueNamesThisTenantAndUnparsableValuesAreSkippedNotFatal() {
        // Seeded with the garbage value FIRST (LinkedHashSet preserves
        // insertion order through the mocked KEYS), so surviving it and
        // STILL purging acme's session afterwards is what's proven - not
        // just "garbage last didn't hurt".
        keyspace.add("agentic:vision-session:garbled");
        values.put("agentic:vision-session:garbled", "not-json{{{");
        keyspace.add("agentic:vision-session:s1");
        values.put(
                "agentic:vision-session:s1",
                "{\"tenantId\":\"" + SCHEMA + "\",\"agentId\":\"a-1\"}");
        keyspace.add("agentic:vision-session:s2");
        values.put(
                "agentic:vision-session:s2",
                "{\"tenantId\":\"" + OTHER_TENANT + "\",\"agentId\":\"a-2\"}");

        assertDoesNotThrow(() -> service.purgeForTenant(SCHEMA, List.of(), List.of()));

        // Only the session whose VALUE names acme goes; the other tenant's
        // session and the unparsable one both survive.
        assertTrue(deletedKeys.contains("agentic:vision-session:s1"));
        assertFalse(deletedKeys.contains("agentic:vision-session:s2"));
        assertFalse(deletedKeys.contains("agentic:vision-session:garbled"));
        assertTrue(keyspace.contains("agentic:vision-session:s2"));
    }

    // ------------------------------------------------------------------
    // Fail-open: one family's failure never aborts the rest
    // ------------------------------------------------------------------

    @Test
    void oneFamilysRedisFailureIsSwallowedAndEveryRemainingFamilyStillRuns() {
        // Every Collection-delete now blows up - starting with the very
        // first family (free-tier counters). All of these keys are TTL'd
        // ephemera; a Redis hiccup here must never abort the caller's
        // larger, irreversible tenant deletion.
        when(redisTemplate.delete(ArgumentMatchers.<String>anyCollection()))
                .thenThrow(new RuntimeException("connection reset"));
        keyspace.add("agentic:workflow-trigger-rate:" + SCHEMA + ":wf-1");
        keyspace.add("agentic:vision-session:s1");
        values.put("agentic:vision-session:s1", "{\"tenantId\":\"" + SCHEMA + "\"}");

        assertDoesNotThrow(
                () -> service.purgeForTenant(SCHEMA, List.of("grant-1"), List.of("run-1")));

        // Iteration reached the LAST glob family and the vision-session
        // sweep (which uses the still-working single-key delete) - proof the
        // earlier failures were swallowed per-family, not rethrown.
        verify(redisTemplate, times(1)).scan(any(ScanOptions.class));
        assertTrue(deletedKeys.contains("agentic:vision-session:s1"));
    }
}
