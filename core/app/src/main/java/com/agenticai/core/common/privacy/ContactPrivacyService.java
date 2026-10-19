package com.agenticai.core.common.privacy;

import com.agenticai.core.agents.memory.AgentMemoryEntry;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.ChatTurn;
import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryEntry;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.graphmemory.GraphMemoryService;
import com.agenticai.core.omnichannel.session.ChannelType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-contact export and erasure (Act s.11 and s.12(3); docs/compliance/dpdp-obligations.md). A
 * "contact" is a channel plus the external id the channel gave us (phone number, chat id, email
 * address). Everything runs in the current tenant's schema, so it cannot reach another tenant.
 *
 * <p>Both operations say, in the response, what they did NOT look at ({@link #NOT_REACHED}). That
 * list is part of the contract: an erasure that reports only what it deleted would read as more
 * complete than it is.
 *
 * <p>Erasure continues past a failing step and reports it ({@code complete=false} plus the failure)
 * instead of stopping or pretending, because a half-done erasure that is retried is better than one
 * that quit on the first error; the audit outcome is FAILURE when any step failed.
 */
@Service
public class ContactPrivacyService {

    /** Stored data this feature does not touch. Returned verbatim with every export and erasure. */
    public static final List<String> NOT_REACHED =
            List.of(
                    "Offline and database backups, snapshots and replicas: they age out under the"
                            + " operator's backup retention schedule and are not edited per contact.",
                    "Copies held by third parties: the messaging channel provider (WhatsApp, Twilio,"
                            + " Telegram, Slack, email servers), the LLM provider the agent calls,"
                            + " and any tool or MCP server the agent sent this contact's data to.",
                    "Operational logs that may quote the contact's messages in a prompt or response:"
                            + " llm_call_log, tool_call_log, sarvam_call_log. They have their own"
                            + " retention and are not matched to a contact.",
                    "Response caches (exact and semantic): keyed by prompt text, not by contact;"
                            + " they expire on their own TTL.",
                    "The append-only audit log: entries are immutable by design and carry only a"
                            + " masked contact identifier.",
                    "Voice call recordings and their transcripts: not linked to a channel identity,"
                            + " so not matched to a contact. Use the recording list and retention"
                            + " policy.",
                    "Device-ingested data (device_sms_message and similar): belongs to the device"
                            + " owner, not to a channel contact.",
                    "Workflow run inputs and outputs and generated documents that happened to"
                            + " include this contact's data: not matched to a contact.",
                    "Knowledge chunks the agent wrote are matched only through the session id the"
                            + " agent stamped into the chunk's source; chunks created some other way"
                            + " (uploads, connectors, workflow nodes) are not matched.",
                    "The email suppression list is kept on purpose so an opt-out keeps being"
                            + " honoured.");

    public record EraseOptions(boolean includeLeads, boolean keepConsentProof) {}

    public record ErasureReport(
            String channel,
            String subjectMasked,
            Instant erasedAt,
            boolean complete,
            Map<String, Long> erased,
            List<String> failures,
            boolean consentProofKept,
            List<String> notReached) {}

    public record ExportReport(
            String channel,
            String subjectMasked,
            Instant generatedAt,
            Map<String, Object> data,
            List<String> notIncluded) {}

    private static final int MIN_DIGITS_FOR_PHONE_MATCH = 7;

    private final JdbcTemplate jdbc;
    private final AuditLogService audit;
    private final ConsentLedger ledger;
    private final ObjectProvider<AgentMemoryService> agentMemory;
    private final ObjectProvider<TenantMemoryService> tenantMemory;
    private final ObjectProvider<GraphMemoryService> graphMemory;
    private final ObjectProvider<SessionMemoryService> sessionMemory;

    public ContactPrivacyService(
            JdbcTemplate jdbc,
            AuditLogService audit,
            ConsentLedger ledger,
            ObjectProvider<AgentMemoryService> agentMemory,
            ObjectProvider<TenantMemoryService> tenantMemory,
            ObjectProvider<GraphMemoryService> graphMemory,
            ObjectProvider<SessionMemoryService> sessionMemory) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.ledger = ledger;
        this.agentMemory = agentMemory;
        this.tenantMemory = tenantMemory;
        this.graphMemory = graphMemory;
        this.sessionMemory = sessionMemory;
    }

    // ------------------------------------------------------------------ export

    public ExportReport export(String channelName, String externalId, boolean includeLeads, String actor) {
        String channel = channel(channelName);
        requireExternalId(externalId);
        Map<String, Object> data = new LinkedHashMap<>();
        List<String> notIncluded = new ArrayList<>(NOT_REACHED);

        Optional<Map<String, Object>> session = session(channel, externalId);
        session.ifPresent(s -> data.put("session", s));
        data.put(
                "messages",
                jdbc.queryForList(
                        "SELECT created_at, direction, author, body FROM channel_message_log"
                                + " WHERE channel = ? AND external_id = ? ORDER BY created_at",
                        channel,
                        externalId));
        data.put(
                "contactRecord",
                jdbc.queryForList(
                        "SELECT identifier, display_name, source, created_at, last_seen_at"
                                + " FROM channel_contact WHERE channel = ? AND (identifier = ?"
                                + " OR (identifier_digits IS NOT NULL AND identifier_digits = ?))",
                        channel,
                        externalId,
                        phoneDigits(externalId)));
        data.put("consentHistory", ledger.history(channel, externalId));

        AgentMemoryService agent = agentMemory.getIfAvailable();
        if (agent != null) {
            List<Map<String, Object>> facts = new ArrayList<>();
            for (AgentMemoryEntry e : agent.listAllForSubject(externalId)) {
                facts.add(Map.of("agentId", e.agentId(), "content", e.content(), "createdAt", e.createdAt()));
            }
            data.put("agentMemory", facts);
        } else {
            notIncluded.add("Agent memory was not exported: the memory service is not active here.");
        }
        TenantMemoryService tenant = tenantMemory.getIfAvailable();
        if (tenant != null) {
            List<Map<String, Object>> facts = new ArrayList<>();
            for (TenantMemoryEntry e : tenant.listFactsForSubject(externalId)) {
                facts.add(Map.of("content", e.content(), "createdAt", e.createdAt()));
            }
            data.put("sharedMemory", facts);
        }
        if (graphMemory.getIfAvailable() != null) {
            notIncluded.add(
                    "Graph memory facts are erased by the erase action but have no per-subject read"
                            + " path, so they are not listed in this export.");
        }
        SessionMemoryService history = sessionMemory.getIfAvailable();
        if (history != null && session.isPresent()) {
            List<Map<String, Object>> turns = new ArrayList<>();
            for (ChatTurn t : history.getFullHistory(String.valueOf(session.get().get("session_id")))) {
                turns.add(Map.of("role", String.valueOf(t.role()), "content", t.content()));
            }
            data.put("liveConversationWindow", turns);
        }
        if (session.isPresent()) {
            data.put(
                    "derivedKnowledgeChunks",
                    jdbc.queryForList(
                            "SELECT id, collection_id, source, content FROM knowledge_chunks"
                                    + " WHERE source LIKE ?",
                            derivedChunkPattern(String.valueOf(session.get().get("session_id")))));
        }
        if (includeLeads) {
            data.put("crmLeads", leadsFor(channel, externalId));
        } else {
            notIncluded.add("CRM leads were not included (set includeLeads to include them).");
        }
        audit.record(
                actor,
                "privacy.contact.export",
                "channel_contact",
                mask(externalId),
                AuditOutcome.SUCCESS,
                "Exported data for " + channel + " contact " + mask(externalId));
        return new ExportReport(channel, mask(externalId), Instant.now(), data, notIncluded);
    }

    // ------------------------------------------------------------------ erase

    public ErasureReport erase(String channelName, String externalId, EraseOptions options, String actor) {
        String channel = channel(channelName);
        requireExternalId(externalId);
        Map<String, Long> erased = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();

        Optional<Map<String, Object>> session = session(channel, externalId);
        String sessionKey = session.map(s -> String.valueOf(s.get("session_id"))).orElse(null);

        step(
                "messages",
                erased,
                failures,
                () ->
                        (long)
                                jdbc.update(
                                        "DELETE FROM channel_message_log WHERE channel = ? AND external_id = ?",
                                        channel,
                                        externalId));
        if (sessionKey != null) {
            step(
                    "derivedKnowledgeChunks",
                    erased,
                    failures,
                    () ->
                            (long)
                                    jdbc.update(
                                            "DELETE FROM knowledge_chunks WHERE source LIKE ?",
                                            derivedChunkPattern(sessionKey)));
            SessionMemoryService history = sessionMemory.getIfAvailable();
            if (history != null) {
                step(
                        "liveConversationWindow",
                        erased,
                        failures,
                        () -> (long) history.rewind(sessionKey, 0));
            }
        }
        AgentMemoryService agent = agentMemory.getIfAvailable();
        if (agent != null) {
            step("agentMemory", erased, failures, () -> (long) agent.forgetAllForSubject(externalId));
        }
        TenantMemoryService tenant = tenantMemory.getIfAvailable();
        if (tenant != null) {
            step("sharedMemory", erased, failures, () -> (long) tenant.forgetAllForSubject(externalId));
        }
        GraphMemoryService graph = graphMemory.getIfAvailable();
        if (graph != null) {
            // eraseSubject deliberately does not fail open; a failure lands in `failures`.
            step("graphMemoryEntities", erased, failures, () -> (long) graph.eraseSubject(externalId));
        }
        step(
                "contactRecords",
                erased,
                failures,
                () ->
                        (long)
                                jdbc.update(
                                        "DELETE FROM channel_contact WHERE channel = ? AND (identifier = ?"
                                                + " OR (identifier_digits IS NOT NULL AND identifier_digits = ?))",
                                        channel,
                                        externalId,
                                        phoneDigits(externalId)));
        if (ChannelType.EMAIL.name().equals(channel)) {
            step(
                    "emailSendRecords",
                    erased,
                    failures,
                    () ->
                            (long)
                                    jdbc.update(
                                            "DELETE FROM email_message WHERE lower(recipient) = lower(?)",
                                            externalId));
        }
        if (options.includeLeads()) {
            step("crmLeads", erased, failures, () -> deleteLeads(channel, externalId));
        }
        if (!options.keepConsentProof()) {
            step("consentRecords", erased, failures, () -> (long) ledger.deleteFor(channel, externalId));
        }
        // The session row last: contact rows point at it, and a retried erasure can still find
        // the session key if an earlier step failed.
        if (failures.isEmpty()) {
            step(
                    "sessions",
                    erased,
                    failures,
                    () ->
                            (long)
                                    jdbc.update(
                                            "DELETE FROM channel_session WHERE channel = ? AND external_id = ?",
                                            channel,
                                            externalId));
        }

        boolean complete = failures.isEmpty();
        audit.record(
                actor,
                "privacy.contact.erase",
                "channel_contact",
                mask(externalId),
                complete ? AuditOutcome.SUCCESS : AuditOutcome.FAILURE,
                "Erased "
                        + channel
                        + " contact "
                        + mask(externalId)
                        + ": "
                        + erased
                        + (complete ? "" : "; INCOMPLETE, failed steps: " + failures)
                        + (options.keepConsentProof() ? "; consent proof kept" : ""));
        return new ErasureReport(
                channel,
                mask(externalId),
                Instant.now(),
                complete,
                erased,
                failures,
                options.keepConsentProof(),
                NOT_REACHED);
    }

    // ------------------------------------------------------------------ helpers

    private interface Step {
        long run();
    }

    private static void step(String name, Map<String, Long> erased, List<String> failures, Step step) {
        try {
            erased.put(name, step.run());
        } catch (RuntimeException e) {
            failures.add(name + ": " + e.getClass().getSimpleName());
        }
    }

    private Optional<Map<String, Object>> session(String channel, String externalId) {
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

    private List<Map<String, Object>> leadsFor(String channel, String externalId) {
        if (externalId.contains("@")) {
            return jdbc.queryForList(
                    "SELECT id, name, email, phone, company, notes, created_at FROM lead"
                            + " WHERE lower(email) = lower(?)",
                    externalId);
        }
        String digits = phoneDigits(externalId);
        if (digits == null) {
            return List.of();
        }
        return jdbc.queryForList(
                "SELECT id, name, email, phone, company, notes, created_at FROM lead"
                        + " WHERE regexp_replace(phone, '\\D', '', 'g') = ?",
                digits);
    }

    private long deleteLeads(String channel, String externalId) {
        if (externalId.contains("@")) {
            return jdbc.update("DELETE FROM lead WHERE lower(email) = lower(?)", externalId);
        }
        String digits = phoneDigits(externalId);
        if (digits == null) {
            return 0;
        }
        return jdbc.update("DELETE FROM lead WHERE regexp_replace(phone, '\\D', '', 'g') = ?", digits);
    }

    /** {@code AddToKnowledgeBaseToolCallback#buildSource} stamps ", session <id>, at " in the source. */
    static String derivedChunkPattern(String sessionKey) {
        String escaped = sessionKey.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "Agent memory: %(written by agent %, session " + escaped + ", at %";
    }

    /** Digits of a phone-like identifier, or null when too short to match on safely. */
    static String phoneDigits(String externalId) {
        String digits = externalId == null ? "" : externalId.replaceAll("\\D", "");
        return digits.length() >= MIN_DIGITS_FOR_PHONE_MATCH ? digits : null;
    }

    static String mask(String externalId) {
        return externalId.length() >= 4 ? "***" + externalId.substring(externalId.length() - 4) : "***";
    }

    private static String channel(String name) {
        try {
            return ChannelType.valueOf(name.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown channel");
        }
    }

    private static void requireExternalId(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "externalId is required");
        }
    }

    /** Used by the consent gate on withdrawal. */
    ErasureReport eraseOnWithdrawal(String channel, String externalId) {
        return erase(channel, externalId, new EraseOptions(false, true), "system:consent-withdrawal");
    }
}
