package com.agenticai.core.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticai.core.agents.turnjournal.JdbcTurnJournal;
import com.agenticai.core.agents.turnjournal.TurnEvent;
import com.agenticai.core.agents.turnjournal.TurnEventType;
import com.agenticai.core.agents.turnjournal.TurnHashChain;
import com.agenticai.core.agents.turnjournal.TurnSummary;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The turn journal against a real Postgres: the tenant migrations (V498, then V666 which adds the session digest) apply,
 * the JDBC journal writes and reads a hash chain, a session's turns are found by their digest, and a row still cannot be
 * updated.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
class TurnJournalStoreIT {

    private static final String SCHEMA = "tenant_journal_it";

    private static DataSource base;
    private static SingleConnectionDataSource tenant;
    private static JdbcTemplate jdbc;
    private static JdbcTurnJournal journal;

    @BeforeAll
    static void migrate() throws Exception {
        base = IntegrationTestContainers.freshDatabase("turn_journal_it");
        PlatformMigrations.bootFlyway(base).migrate();
        PlatformMigrations.strictTenantFlyway(base, SCHEMA).migrate();
        tenant = new SingleConnectionDataSource(base.getConnection(), true);
        jdbc = new JdbcTemplate(tenant);
        jdbc.execute("SET search_path TO " + SCHEMA);
        journal = new JdbcTurnJournal(jdbc);
    }

    @AfterAll
    static void close() {
        tenant.destroy();
    }

    private static UUID turn(UUID agent, String session, String commitStatus) {
        UUID turnId = UUID.randomUUID();
        journal.append(agent, turnId, session, TurnEventType.INTAKE, "h".repeat(64), "messageChars=5");
        journal.append(agent, turnId, session, TurnEventType.TOOL_CALL, "h".repeat(64), "tool=lookup status=blocked");
        if (commitStatus != null) {
            journal.append(agent, turnId, session, TurnEventType.COMMIT, "h".repeat(64), "status=" + commitStatus + " answerChars=3");
        }
        return turnId;
    }

    @Test
    void theColumnAndTheIndexExist() {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM information_schema.columns WHERE table_schema = ? AND"
                                        + " table_name = 'turn_journal_event' AND column_name = 'session_hash'",
                                Integer.class,
                                SCHEMA))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND indexname ="
                                        + " 'idx_turn_journal_event_agent_session'",
                                Integer.class,
                                SCHEMA))
                .isEqualTo(1);
    }

    @Test
    void aTurnIsWrittenAsAnIntactChainAndReadBack() {
        UUID agent = UUID.randomUUID();
        UUID turnId = turn(agent, "sess-1", "completed");

        List<TurnEvent> events = journal.read(agent, turnId);

        assertThat(events).extracting(TurnEvent::type).containsExactly(TurnEventType.INTAKE, TurnEventType.TOOL_CALL, TurnEventType.COMMIT);
        assertThat(TurnHashChain.verify(events).intact()).isTrue();
        assertThat(journal.read(UUID.randomUUID(), turnId)).as("another agent").isEmpty();
    }

    @Test
    void aSessionsTurnsAreFoundNewestFirstWithTheirStatus() throws InterruptedException {
        UUID agent = UUID.randomUUID();
        UUID first = turn(agent, "sess-find", "completed");
        Thread.sleep(15);
        UUID second = turn(agent, "sess-find", "error:TimeoutException");
        Thread.sleep(15);
        UUID open = turn(agent, "sess-find", null);
        turn(agent, "sess-other", "completed");
        turn(UUID.randomUUID(), "sess-find", "completed");

        List<TurnSummary> found = journal.listBySession(agent, "sess-find", 10);

        assertThat(found).extracting(TurnSummary::turnId).containsExactly(open, second, first);
        assertThat(found).extracting(TurnSummary::status).containsExactly("open", "error:TimeoutException", "completed");
        assertThat(found.get(2).events()).isEqualTo(3);
        assertThat(journal.listBySession(agent, "sess-find", 2)).hasSize(2);
        assertThat(journal.listBySession(agent, "no-such-session", 10)).isEmpty();
    }

    @Test
    void onlyADigestOfTheSessionIdIsStored() {
        UUID agent = UUID.randomUUID();
        turn(agent, "client-chosen-session", "completed");

        List<String> stored =
                jdbc.queryForList("SELECT session_hash FROM turn_journal_event WHERE agent_id = ?", String.class, agent);

        assertThat(stored).hasSize(3).containsOnly(TurnHashChain.sha256("client-chosen-session"));
    }

    @Test
    void aRowStillCannotBeUpdated() {
        UUID agent = UUID.randomUUID();
        turn(agent, "sess-ro", "completed");

        assertThatThrownBy(() -> jdbc.update("UPDATE turn_journal_event SET session_hash = NULL WHERE agent_id = ?", agent))
                .hasMessageContaining("append-only");
    }

    @Test
    void anEventAppendedWithoutASessionIsStillWrittenAndNotListed() {
        UUID agent = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        journal.append(agent, turnId, TurnEventType.INTAKE, "h".repeat(64), "no session");

        assertThat(journal.read(agent, turnId)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM turn_journal_event WHERE turn_id = ? AND session_hash IS NULL", Integer.class, turnId))
                .isEqualTo(1);
    }

    @Test
    void retentionDeletesWholeOldTurnsOnlyAndLeavesAKeptChainIntact() throws InterruptedException {
        UUID agent = UUID.randomUUID();
        UUID old = turn(agent, "sess-old", "completed");
        Thread.sleep(30);
        java.time.Instant cutoff = java.time.Instant.now();
        Thread.sleep(30);
        UUID recent = turn(agent, "sess-recent", "completed");

        int removed = journal.purgeOlderThan(cutoff, 100);

        assertThat(removed).isGreaterThanOrEqualTo(3);
        assertThat(journal.read(agent, old)).isEmpty();
        List<TurnEvent> kept = journal.read(agent, recent);
        assertThat(kept).hasSize(3);
        assertThat(TurnHashChain.verify(kept).intact()).isTrue();
        assertThat(journal.purgeOlderThan(cutoff, 100)).as("nothing more is due").isZero();
    }
}
