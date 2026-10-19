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
                                + " payload_hash, summary, prev_hash, hash, created_at)"
                                + " VALUES (?,?,?,?,?,?,?,?,?)",
                        event.turnId(),
                        event.seq(),
                        event.agentId(),
                        event.type().name(),
                        event.payloadHash(),
                        event.summary(),
                        event.prevHash(),
                        event.hash(),
                        Timestamp.from(event.createdAt()));
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
}
