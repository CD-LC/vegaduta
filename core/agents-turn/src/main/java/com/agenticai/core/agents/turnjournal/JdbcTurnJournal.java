package com.agenticai.core.agents.turnjournal;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Postgres journal over the tenant table {@code turn_journal_event} (V498). The tenant is the
 * schema selected by the caller's search_path; callers must hold a tenant scope. A concurrent
 * appender that loses the (turn_id, seq) primary-key race re-reads the head and retries.
 */
public class JdbcTurnJournal implements TurnJournal {

    private static final int MAX_ATTEMPTS = 6;

    private static final RowMapper<TurnEvent> MAPPER =
            (rs, i) ->
                    new TurnEvent(
                            rs.getObject("turn_id", UUID.class),
                            rs.getObject("agent_id", UUID.class),
                            rs.getInt("seq"),
                            TurnEventType.valueOf(rs.getString("event_type")),
                            rs.getString("payload_hash").trim(),
                            rs.getString("summary"),
                            rs.getString("prev_hash").trim(),
                            rs.getString("hash").trim(),
                            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public JdbcTurnJournal(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public TurnEvent append(
            UUID agentId, UUID turnId, TurnEventType type, String payloadHash, String summary) {
        return append(agentId, turnId, null, type, payloadHash, summary);
    }

    @Override
    public TurnEvent append(
            UUID agentId,
            UUID turnId,
            String sessionId,
            TurnEventType type,
            String payloadHash,
            String summary) {
        // Only a digest of the session id is stored: the column is an index for "the turns of this session", not a
        // copy of a client-chosen string, and a digest keeps the index key a fixed size whatever the id looks like.
        String sessionHash = sessionId == null ? null : TurnHashChain.sha256(sessionId);
        DuplicateKeyException last = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            List<TurnEvent> head =
                    jdbc.query(
                            "SELECT * FROM turn_journal_event WHERE turn_id = ?"
                                    + " ORDER BY seq DESC LIMIT 1",
                            MAPPER,
                            turnId);
            TurnEvent event =
                    TurnHashChain.next(
                            head.isEmpty() ? null : head.get(0),
                            agentId,
                            turnId,
                            type,
                            payloadHash,
                            summary);
            try {
                jdbc.update(
                        "INSERT INTO turn_journal_event (turn_id, seq, agent_id, event_type,"
                                + " payload_hash, summary, prev_hash, hash, created_at, session_hash)"
                                + " VALUES (?,?,?,?,?,?,?,?,?,?)",
                        event.turnId(),
                        event.seq(),
                        event.agentId(),
                        event.type().name(),
                        event.payloadHash(),
                        event.summary(),
                        event.prevHash(),
                        event.hash(),
                        Timestamp.from(event.createdAt()),
                        sessionHash);
                return event;
            } catch (DuplicateKeyException e) {
                last = e;
            }
        }
        throw last;
    }

    @Override
    public List<TurnEvent> read(UUID agentId, UUID turnId) {
        return jdbc.query(
                "SELECT * FROM turn_journal_event WHERE turn_id = ? AND agent_id = ? ORDER BY seq",
                MAPPER,
                turnId,
                agentId);
    }

    @Override
    public int purgeOlderThan(java.time.Instant cutoff, int maxTurns) {
        if (cutoff == null || maxTurns <= 0) {
            return 0;
        }
        // Whole turns only: a turn whose newest event is past the cutoff goes entirely, so the chain of a turn that is
        // kept is never missing its head. Deleting is allowed (the table is append-only against UPDATE, not DELETE).
        return jdbc.update(
                "DELETE FROM turn_journal_event WHERE turn_id IN (SELECT turn_id FROM turn_journal_event"
                        + " GROUP BY turn_id HAVING MAX(created_at) < ? ORDER BY MIN(created_at) LIMIT ?)",
                Timestamp.from(cutoff),
                maxTurns);
    }

    @Override
    public List<TurnSummary> listBySession(UUID agentId, String sessionId, int limit) {
        if (sessionId == null || limit <= 0) {
            return List.of();
        }
        return jdbc.query(
                "SELECT e.turn_id, MIN(e.created_at) AS started_at, MAX(e.created_at) AS last_at,"
                        + " COUNT(*) AS events,"
                        + " (SELECT c.summary FROM turn_journal_event c WHERE c.turn_id = e.turn_id"
                        + "   AND c.event_type = 'COMMIT' ORDER BY c.seq DESC LIMIT 1) AS commit_summary"
                        + " FROM turn_journal_event e WHERE e.agent_id = ? AND e.session_hash = ?"
                        + " GROUP BY e.turn_id ORDER BY MIN(e.created_at) DESC LIMIT ?",
                (rs, i) ->
                        new TurnSummary(
                                rs.getObject("turn_id", UUID.class),
                                agentId,
                                rs.getTimestamp("started_at").toInstant(),
                                rs.getTimestamp("last_at").toInstant(),
                                rs.getInt("events"),
                                TurnSummary.statusOf(rs.getString("commit_summary"))),
                agentId,
                TurnHashChain.sha256(sessionId),
                limit);
    }
}
