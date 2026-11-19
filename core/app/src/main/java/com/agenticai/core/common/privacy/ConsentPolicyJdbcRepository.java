package com.agenticai.core.common.privacy;

import com.agenticai.core.common.privacy.ConsentPolicyService.Policy;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Raw SQL for {@link ConsentPolicyService} ({@code privacy_consent_policy}, {@code agent}). */
@Repository
public class ConsentPolicyJdbcRepository {

    private static final ObjectMapper MAPPER = JsonMappers.plain();
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
    private static final TypeReference<Map<String, List<String>>> LIST_MAP = new TypeReference<>() {};

    private final JdbcTemplate jdbc;

    public ConsentPolicyJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Policy> find(UUID agentId) {
        List<Policy> rows =
                jdbc.query(
                        "SELECT agent_id, enabled, purpose, notice_version, default_language,"
                                + " notices::text, instructions::text, keywords::text,"
                                + " erase_on_withdrawal, updated_by, updated_at"
                                + " FROM privacy_consent_policy WHERE agent_id = ?",
                        (rs, n) ->
                                new Policy(
                                        rs.getObject(1, UUID.class),
                                        rs.getBoolean(2),
                                        rs.getString(3),
                                        rs.getInt(4),
                                        rs.getString(5),
                                        readStrings(rs.getString(6)),
                                        readStrings(rs.getString(7)),
                                        readLists(rs.getString(8)),
                                        rs.getBoolean(9),
                                        rs.getString(10),
                                        rs.getTimestamp(11).toInstant()),
                        agentId);
        return rows.stream().findFirst();
    }

    boolean agentExists(UUID agentId) {
        Integer agentExists =
                jdbc.queryForObject("SELECT count(*) FROM agent WHERE id = ?", Integer.class, agentId);
        return agentExists != null && agentExists != 0;
    }

    void upsert(
            UUID agentId,
            boolean enabled,
            String purpose,
            int version,
            String defaultLanguage,
            Map<String, String> notices,
            Map<String, String> instructions,
            Map<String, List<String>> keywords,
            boolean erase,
            String actor) {
        jdbc.update(
                "INSERT INTO privacy_consent_policy (agent_id, enabled, purpose, notice_version,"
                        + " default_language, notices, instructions, keywords, erase_on_withdrawal,"
                        + " updated_by, updated_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?)"
                        + " ON CONFLICT (agent_id) DO UPDATE SET enabled = EXCLUDED.enabled,"
                        + " purpose = EXCLUDED.purpose, notice_version = EXCLUDED.notice_version,"
                        + " default_language = EXCLUDED.default_language,"
                        + " notices = EXCLUDED.notices, instructions = EXCLUDED.instructions,"
                        + " keywords = EXCLUDED.keywords,"
                        + " erase_on_withdrawal = EXCLUDED.erase_on_withdrawal,"
                        + " updated_by = EXCLUDED.updated_by, updated_at = EXCLUDED.updated_at",
                agentId,
                enabled,
                purpose,
                version,
                defaultLanguage,
                json(notices),
                json(instructions),
                json(keywords),
                erase,
                actor,
                Timestamp.from(Instant.now()));
    }

    private static String json(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> readStrings(String json) {
        try {
            return MAPPER.readValue(json, STRING_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable consent policy JSON", e);
        }
    }

    private static Map<String, List<String>> readLists(String json) {
        try {
            return MAPPER.readValue(json, LIST_MAP);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable consent policy JSON", e);
        }
    }
}
