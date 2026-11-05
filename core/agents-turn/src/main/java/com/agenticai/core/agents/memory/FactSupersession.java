package com.agenticai.core.agents.memory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The SQL behind "a corrected fact supersedes the old one" for the two legacy fact stores ({@code agent_memory},
 * {@code tenant_memory}; V685). Stateless apart from its settings; {@link AgentMemoryService} and
 * {@link TenantMemoryService} pass the table and the scope (one agent and person, or one person) in.
 *
 * <p>Rules, in one place:
 *
 * <ul>
 *   <li>Only a TRUSTED, live (not yet superseded) row can be replaced, and only by a TRUSTED write. A QUARANTINED write
 *       is stored with the rows it WOULD replace in {@code supersedes_ids} and changes nothing; {@link #applyPending}
 *       applies the list when a person approves it.
 *   <li>A row is never deleted: {@code superseded_by}/{@code superseded_at} mark it. Recall leaves marked rows out; list,
 *       export and erasure still see them.
 *   <li>The same text told again (case and whitespace aside) is a re-confirmation: {@link #reconfirm} moves the live row's
 *       {@code updated_at} and no second row is written.
 *   <li>An agent-initiated write never replaces a row a person typed or pinned; a person-typed write may replace any.
 *   <li>Candidates: the row named by {@code replaces} (exact text first, else the closest fact at or above the replace
 *       similarity), plus every live row whose similarity to the new text is at or above the automatic similarity.
 * </ul>
 */
final class FactSupersession {

    private static final Logger log = LoggerFactory.getLogger(FactSupersession.class);

    /** What to look in: the table and the WHERE fragment (with its arguments) that scopes it to one person. */
    record Scope(String table, String where, Object... args) {}

    private final JdbcTemplate jdbc;
    private final MemorySupersessionProperties settings;

    FactSupersession(JdbcTemplate jdbc, MemorySupersessionProperties settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    /** Case-folded, whitespace-collapsed text: the key of "the same fact told again" and of an exact {@code replaces}. */
    static String normalize(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static final String NORMALIZED_CONTENT = "lower(regexp_replace(btrim(content), '\\s+', ' ', 'g'))";

    /**
     * The same text told again: moves the live TRUSTED row's {@code updated_at} to {@code now} and returns its id; empty
     * when there is no such row (the caller then writes a new one).
     */
    Optional<UUID> reconfirm(Scope scope, String content, Instant now) {
        List<UUID> same =
                jdbc.queryForList(
                        "SELECT id FROM " + scope.table() + " WHERE " + scope.where()
                                + " AND trust = 'TRUSTED' AND superseded_by IS NULL AND " + NORMALIZED_CONTENT
                                + " = ? ORDER BY created_at DESC LIMIT 1",
                        UUID.class,
                        concat(scope.args(), normalize(content)));
        if (same == null || same.isEmpty()) {
            return Optional.empty();
        }
        jdbc.update("UPDATE " + scope.table() + " SET updated_at = ? WHERE id = ?", Timestamp.from(now), same.get(0));
        return Optional.of(same.get(0));
    }

    /**
     * The live TRUSTED rows the just-written row {@code newId} replaces, never {@code newId} itself.
     *
     * @param newLiteral the new fact's embedding as a pgvector literal
     * @param replaces the text the writer says it replaces, or null
     * @param replacesLiteral embeds {@code replaces} as a pgvector literal; asked only when the exact text did not match
     * @param byPerson the write is a person's own typed fact (it may replace a typed or pinned row)
     */
    List<UUID> candidates(
            Scope scope,
            UUID newId,
            String newLiteral,
            String replaces,
            Function<String, String> replacesLiteral,
            boolean byPerson) {
        String live =
                " WHERE " + scope.where() + " AND id <> ? AND trust = 'TRUSTED' AND superseded_by IS NULL"
                        + (byPerson ? "" : " AND NOT person_authored AND NOT pinned");
        Object[] base = concat(scope.args(), newId);
        Set<UUID> out = new LinkedHashSet<>();
        if (replaces != null && !replaces.isBlank()) {
            List<UUID> named =
                    jdbc.queryForList(
                            "SELECT id FROM " + scope.table() + live + " AND " + NORMALIZED_CONTENT + " = ?",
                            UUID.class,
                            concat(base, normalize(replaces)));
            if (named != null && !named.isEmpty()) {
                out.addAll(named);
            } else {
                String literal = replacesLiteral.apply(replaces);
                List<UUID> close =
                        jdbc.queryForList(
                                "SELECT id FROM " + scope.table() + live
                                        + " AND (embedding OPERATOR(public.<=>) ?::public.vector) <= ?"
                                        + " ORDER BY embedding OPERATOR(public.<=>) ?::public.vector LIMIT 1",
                                UUID.class,
                                concat(base, literal, 1.0 - settings.getReplaceMinSimilarity(), literal));
                if (close != null) {
                    out.addAll(close);
                }
            }
        }
        List<UUID> similar =
                jdbc.queryForList(
                        "SELECT id FROM " + scope.table() + live
                                + " AND (embedding OPERATOR(public.<=>) ?::public.vector) <= ?",
                        UUID.class,
                        concat(base, newLiteral, 1.0 - settings.getMinSimilarity()));
        if (similar != null) {
            out.addAll(similar);
        }
        return new ArrayList<>(out);
    }

    /**
     * After the row {@code id} was inserted: a TRUSTED write supersedes the rows it replaces now, a QUARANTINED one only
     * parks them until a person approves it.
     */
    void afterInsert(
            Scope scope,
            UUID id,
            String literal,
            String replaces,
            Function<String, String> replacesLiteral,
            boolean byPerson,
            boolean trusted,
            Instant now) {
        List<UUID> replaced = candidates(scope, id, literal, replaces, replacesLiteral, byPerson);
        if (trusted) {
            supersede(scope.table(), id, replaced, now);
        } else {
            park(scope.table(), id, replaced);
        }
    }

    /** Marks {@code replaced} as superseded by {@code newId} (a TRUSTED write). Rows that stopped being live are skipped. */
    int supersede(String table, UUID newId, List<UUID> replaced, Instant now) {
        if (replaced == null || replaced.isEmpty()) {
            return 0;
        }
        Object[] args = new Object[replaced.size() + 2];
        args[0] = newId;
        args[1] = Timestamp.from(now);
        for (int i = 0; i < replaced.size(); i++) {
            args[i + 2] = replaced.get(i);
        }
        int n =
                jdbc.update(
                        "UPDATE " + table + " SET superseded_by = ?, superseded_at = ? WHERE superseded_by IS NULL"
                                + " AND trust = 'TRUSTED' AND id IN ("
                                + String.join(",", Collections.nCopies(replaced.size(), "?"))
                                + ")",
                        args);
        log.debug("Fact {} superseded {} older fact(s)", newId, n);
        return n;
    }

    /** A QUARANTINED write: remembers the rows it would replace, replaces nothing now. */
    void park(String table, UUID newId, List<UUID> wouldReplace) {
        if (wouldReplace == null || wouldReplace.isEmpty()) {
            return;
        }
        jdbc.update(
                "UPDATE " + table + " SET supersedes_ids = ?::uuid[] WHERE id = ?",
                "{" + String.join(",", wouldReplace.stream().map(UUID::toString).toList()) + "}",
                newId);
    }

    /**
     * After a person approved {@code approvedId}: applies the parked list (rows that are still live and TRUSTED) and
     * clears it. Cheap when nothing was parked.
     */
    void applyPending(String table, UUID approvedId, Instant now) {
        jdbc.update(
                "UPDATE " + table + " SET superseded_by = ?, superseded_at = ? WHERE superseded_by IS NULL"
                        + " AND trust = 'TRUSTED' AND id IN (SELECT unnest(supersedes_ids) FROM " + table
                        + " WHERE id = ?)",
                approvedId,
                Timestamp.from(now),
                approvedId);
        jdbc.update("UPDATE " + table + " SET supersedes_ids = NULL WHERE id = ? AND supersedes_ids IS NOT NULL", approvedId);
    }

    private static Object[] concat(Object[] head, Object... tail) {
        Object[] out = new Object[head.length + tail.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(tail, 0, out, head.length, tail.length);
        return out;
    }
}
