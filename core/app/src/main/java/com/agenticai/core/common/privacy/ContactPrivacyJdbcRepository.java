package com.agenticai.core.common.privacy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Raw SQL for {@link ContactPrivacyService}: per-contact reads and deletes across the channel,
 * knowledge and CRM tables of the current tenant's schema.
 */
@Repository
public class ContactPrivacyJdbcRepository {

    private final JdbcTemplate jdbc;

    public ContactPrivacyJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<Map<String, Object>> messages(String channel, String externalId) {
        return jdbc.queryForList(
                "SELECT created_at, direction, author, body FROM channel_message_log"
                        + " WHERE channel = ? AND external_id = ? ORDER BY created_at",
                channel,
                externalId);
    }

    List<Map<String, Object>> contactRecord(String channel, String externalId, String digits) {
        return jdbc.queryForList(
                "SELECT identifier, display_name, source, created_at, last_seen_at"
                        + " FROM channel_contact WHERE channel = ? AND (identifier = ?"
                        + " OR (identifier_digits IS NOT NULL AND identifier_digits = ?))",
                channel,
                externalId,
                digits);
    }

    List<Map<String, Object>> derivedKnowledgeChunks(String pattern) {
        return jdbc.queryForList(
                "SELECT id, collection_id, source, content, trust FROM knowledge_chunks"
                        + " WHERE source LIKE ?",
                pattern);
    }

    Optional<Map<String, Object>> session(String channel, String externalId) {
        return jdbc
                .queryForList(
                        "SELECT id, session_id, agent_id, created_at, last_message_at, paired,"
                                + " resolved_phone_number FROM channel_session"
                                + " WHERE channel = ? AND external_id = ?",
                        channel,
                        externalId)
                .stream()
                .findFirst();
    }

    List<Map<String, Object>> leadsByEmail(String email) {
        return jdbc.queryForList(
                "SELECT id, name, email, phone, company, notes, created_at FROM lead"
                        + " WHERE lower(email) = lower(?)",
                email);
    }

    List<Map<String, Object>> leadsByPhoneDigits(String digits) {
        return jdbc.queryForList(
                "SELECT id, name, email, phone, company, notes, created_at FROM lead"
                        + " WHERE regexp_replace(phone, '\\D', '', 'g') = ?",
                digits);
    }

    int deleteMessages(String channel, String externalId) {
        return jdbc.update(
                "DELETE FROM channel_message_log WHERE channel = ? AND external_id = ?",
                channel,
                externalId);
    }

    int deleteDerivedKnowledgeChunks(String pattern) {
        return jdbc.update("DELETE FROM knowledge_chunks WHERE source LIKE ?", pattern);
    }

    int deleteContactRecords(String channel, String externalId, String digits) {
        return jdbc.update(
                "DELETE FROM channel_contact WHERE channel = ? AND (identifier = ?"
                        + " OR (identifier_digits IS NOT NULL AND identifier_digits = ?))",
                channel,
                externalId,
                digits);
    }

    int deleteEmailSendRecords(String recipient) {
        return jdbc.update("DELETE FROM email_message WHERE lower(recipient) = lower(?)", recipient);
    }

    int deleteSession(String channel, String externalId) {
        return jdbc.update(
                "DELETE FROM channel_session WHERE channel = ? AND external_id = ?",
                channel,
                externalId);
    }

    int deleteLeadsByEmail(String email) {
        return jdbc.update("DELETE FROM lead WHERE lower(email) = lower(?)", email);
    }

    int deleteLeadsByPhoneDigits(String digits) {
        return jdbc.update("DELETE FROM lead WHERE regexp_replace(phone, '\\D', '', 'g') = ?", digits);
    }
}
