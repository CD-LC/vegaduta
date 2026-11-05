package com.agenticai.core.agents.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The SQL shape and rules of {@link FactSupersession} against a mocked JdbcTemplate: what may be replaced (live, TRUSTED),
 * by whom (a typed or pinned row only by a person), in which order a named fact is resolved, and that a parked
 * (quarantined) write changes nothing until approval. The behaviour against a real database is
 * {@code FactSupersessionIT} (core/app).
 */
class FactSupersessionTest {

    private static final String TABLE = "t_x.agent_memory";
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final MemorySupersessionProperties settings = new MemorySupersessionProperties();
    private final FactSupersession sup = new FactSupersession(jdbc, settings);
    private final UUID agent = UUID.randomUUID();
    private final UUID newId = UUID.randomUUID();
    private final FactSupersession.Scope scope = new FactSupersession.Scope(TABLE, "agent_id = ? AND subject_key = ?", agent, "alice");

    private List<Invocation> calls(String method) {
        return mockingDetails(jdbc).getInvocations().stream().filter(i -> i.getMethod().getName().equals(method)).toList();
    }

    private static String sql(Invocation i) {
        return String.valueOf(i.getArguments()[0]);
    }

    private static List<Object> args(Invocation i) {
        List<Object> all = new ArrayList<>();
        for (int k = 1; k < i.getArguments().length; k++) {
            Object a = i.getArguments()[k];
            if (a instanceof Object[] arr) {
                all.addAll(Arrays.asList(arr));
            } else if (!(a instanceof Class<?>)) {
                all.add(a);
            }
        }
        return all;
    }

    @Test
    void normalizeFoldsCaseAndWhitespace() {
        assertThat(FactSupersession.normalize("  Lives   in\tPune \n")).isEqualTo("lives in pune");
        assertThat(FactSupersession.normalize(null)).isEmpty();
    }

    @Test
    void sameTextToldAgainIsReconfirmedNotStoredTwice() {
        UUID existing = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(List.of(existing));

        Optional<UUID> same = sup.reconfirm(scope, "I live in  PUNE", NOW);

        assertThat(same).contains(existing);
        Invocation lookup = calls("queryForList").get(0);
        assertThat(sql(lookup)).contains("trust = 'TRUSTED'").contains("superseded_by IS NULL");
        assertThat(args(lookup)).contains(agent, "alice", "i live in pune");
        assertThat(sql(calls("update").get(0))).contains("SET updated_at = ?");
    }

    @Test
    void noSameTextMeansNoReconfirm() {
        assertThat(sup.reconfirm(scope, "new", NOW)).isEmpty();
        assertThat(calls("update")).isEmpty();
    }

    @Test
    void anAgentWriteNeverReplacesATypedOrPinnedRowButAPersonMay() {
        sup.candidates(scope, newId, "[1,0]", null, t -> "[1,0]", false);
        sup.candidates(scope, newId, "[1,0]", null, t -> "[1,0]", true);

        List<Invocation> queries = calls("queryForList");
        assertThat(sql(queries.get(0))).contains("NOT person_authored AND NOT pinned");
        assertThat(sql(queries.get(1))).doesNotContain("person_authored");
        // both only ever look at live TRUSTED rows other than the new one
        queries.forEach(q -> assertThat(sql(q)).contains("trust = 'TRUSTED'").contains("superseded_by IS NULL").contains("id <> ?"));
    }

    @Test
    void theAutomaticRuleUsesTheConfiguredSimilarityAsADistanceBound() {
        settings.setMinSimilarity(0.8);
        sup.candidates(scope, newId, "[1,0]", null, t -> "[1,0]", false);

        Invocation auto = calls("queryForList").get(0);
        assertThat(sql(auto)).contains("OPERATOR(public.<=>)").contains("<= ?");
        assertThat(args(auto).get(args(auto).size() - 1)).isEqualTo(1.0 - 0.8);
    }

    @Test
    void aNamedFactIsResolvedByExactTextBeforeAnyEmbeddingIsAsked() {
        UUID named = UUID.randomUUID();
        AtomicInteger embedded = new AtomicInteger();
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class)))
                .thenReturn(List.of(named)) // the exact-text lookup
                .thenReturn(List.of()); // the automatic rule

        List<UUID> out = sup.candidates(scope, newId, "[1,0]", "User lives in Pune", t -> {
            embedded.incrementAndGet();
            return "[0,1]";
        }, false);

        assertThat(out).containsExactly(named);
        assertThat(embedded).hasValue(0);
        assertThat(args(calls("queryForList").get(0))).contains("user lives in pune");
    }

    @Test
    void aNamedFactThatIsNotWordForWordFallsBackToTheClosestFactAboveTheReplaceSimilarity() {
        UUID close = UUID.randomUUID();
        AtomicInteger embedded = new AtomicInteger();
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class)))
                .thenReturn(List.of()) // no exact text
                .thenReturn(List.of(close)) // the closest fact
                .thenReturn(List.of()); // the automatic rule

        List<UUID> out = sup.candidates(scope, newId, "[1,0]", "lives somewhere", t -> {
            embedded.incrementAndGet();
            return "[0,1]";
        }, false);

        assertThat(out).containsExactly(close);
        assertThat(embedded).hasValue(1);
        Invocation fallback = calls("queryForList").get(1);
        assertThat(sql(fallback)).contains("LIMIT 1");
        assertThat(args(fallback)).contains(1.0 - settings.getReplaceMinSimilarity());
    }

    @Test
    void aFactBothNamedAndNearIsReportedOnce() {
        UUID both = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(List.of(both));

        assertThat(sup.candidates(scope, newId, "[1,0]", "x", t -> "[1,0]", false)).containsExactly(both);
    }

    @Test
    void aTrustedWriteSupersedesNowAndMarksOnlyLiveTrustedRows() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(2);

        assertThat(sup.supersede(TABLE, newId, List.of(a, b), NOW)).isEqualTo(2);

        Invocation update = calls("update").get(0);
        assertThat(sql(update)).contains("SET superseded_by = ?, superseded_at = ?")
                .contains("superseded_by IS NULL").contains("trust = 'TRUSTED'").contains("id IN (?,?)");
        assertThat(args(update)).contains(newId, a, b);
    }

    @Test
    void nothingToReplaceIssuesNoStatement() {
        assertThat(sup.supersede(TABLE, newId, List.of(), NOW)).isZero();
        sup.park(TABLE, newId, List.of());
        assertThat(mockingDetails(jdbc).getInvocations()).isEmpty();
    }

    @Test
    void aQuarantinedWriteOnlyParksWhatItWouldReplace() {
        UUID a = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(List.of(a));

        sup.afterInsert(scope, newId, "[1,0]", null, t -> "[1,0]", false, false, NOW);

        List<String> updates = calls("update").stream().map(FactSupersessionTest::sql).toList();
        assertThat(updates).hasSize(1);
        assertThat(updates.get(0)).contains("SET supersedes_ids = ?::uuid[]").doesNotContain("superseded_by");
        assertThat(args(calls("update").get(0))).contains("{" + a + "}", newId);
    }

    @Test
    void aTrustedWriteSupersedesInThatSamePass() {
        UUID a = UUID.randomUUID();
        when(jdbc.queryForList(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(List.of(a));

        sup.afterInsert(scope, newId, "[1,0]", null, t -> "[1,0]", false, true, NOW);

        assertThat(calls("update").stream().map(FactSupersessionTest::sql).toList())
                .anyMatch(s -> s.contains("SET superseded_by = ?"))
                .noneMatch(s -> s.contains("supersedes_ids = ?"));
    }

    @Test
    void approvalAppliesTheParkedListToRowsThatAreStillLiveAndClearsIt() {
        sup.applyPending(TABLE, newId, NOW);

        List<Invocation> updates = calls("update");
        assertThat(sql(updates.get(0))).contains("SET superseded_by = ?, superseded_at = ?")
                .contains("superseded_by IS NULL").contains("trust = 'TRUSTED'")
                .contains("unnest(supersedes_ids)");
        assertThat(sql(updates.get(1))).contains("SET supersedes_ids = NULL");
    }
}
