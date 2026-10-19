package com.agenticai.core.common.privacy;

import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.agents.memory.TenantMemoryService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.common.privacy.RetentionPolicyService.Rule;
import com.agenticai.core.storage.FileStorageService;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Applies the current tenant's enabled retention rules. Must run with the tenant context set (the
 * nightly job does that per tenant through {@code TenantSchemaIterator}); every query is
 * unqualified, so it can only see that tenant's schema.
 *
 * <p>One audit event per rule that actually removed (or failed to remove) something, so a
 * quiet night writes nothing. Deletes go in bounded batches; a run stops at {@value #MAX_BATCHES}
 * batches per rule and the next night carries on.
 */
@Component
public class RetentionPurger {

    private static final Logger log = LoggerFactory.getLogger(RetentionPurger.class);
    static final String ACTOR = "system:retention-purge";
    static final int BATCH = 1000;
    static final int MAX_BATCHES = 200;

    public record Result(
            PrivacyDataClass dataClass,
            String channel,
            int retentionDays,
            Instant cutoff,
            long deleted,
            long failed) {}

    private final JdbcTemplate jdbc;
    private final RetentionPolicyService policies;
    private final AuditLogService audit;
    private final FileStorageService storage;
    private final ObjectProvider<AgentMemoryService> agentMemory;
    private final ObjectProvider<TenantMemoryService> tenantMemory;

    public RetentionPurger(
            JdbcTemplate jdbc,
            RetentionPolicyService policies,
            AuditLogService audit,
            FileStorageService storage,
            ObjectProvider<AgentMemoryService> agentMemory,
            ObjectProvider<TenantMemoryService> tenantMemory) {
        this.jdbc = jdbc;
        this.policies = policies;
        this.audit = audit;
        this.storage = storage;
        this.agentMemory = agentMemory;
        this.tenantMemory = tenantMemory;
    }

    public List<Result> purgeCurrentTenant() {
        return purgeCurrentTenant(Instant.now());
    }

    List<Result> purgeCurrentTenant(Instant now) {
        List<Result> results = new ArrayList<>();
        for (String channel :
                jdbc.queryForList("SELECT DISTINCT channel FROM channel_message_log", String.class)) {
            policies.effective(channel, PrivacyDataClass.TRANSCRIPT)
                    .ifPresent(rule -> results.add(purgeTranscripts(channel, rule, now)));
        }
        policies.effective(PrivacyDataClass.VOICE, PrivacyDataClass.RECORDING)
                .ifPresent(rule -> results.add(purgeRecordings(rule, now)));
        policies.effective(PrivacyDataClass.ALL_CHANNELS, PrivacyDataClass.EXTRACTED_FIELDS)
                .ifPresent(rule -> results.add(purgeExtractedFields(rule, now)));
        for (Result r : results) {
            if (r.deleted() > 0 || r.failed() > 0) {
                audit.record(
                        ACTOR,
                        "privacy.retention.purge",
                        "retention_policy",
                        r.dataClass() + ":" + r.channel(),
                        r.failed() > 0 ? AuditOutcome.FAILURE : AuditOutcome.SUCCESS,
                        "Purged "
                                + r.deleted()
                                + " "
                                + r.dataClass()
                                + " item(s) older than "
                                + r.retentionDays()
                                + " days (cutoff "
                                + r.cutoff()
                                + ") for channel "
                                + r.channel()
                                + (r.failed() > 0 ? "; " + r.failed() + " failed and will be retried" : ""));
            }
        }
        return results;
    }

    private Result purgeTranscripts(String channel, Rule rule, Instant now) {
        Instant cutoff = cutoff(now, rule);
        long deleted = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int n =
                    jdbc.update(
                            "DELETE FROM channel_message_log WHERE id IN (SELECT id FROM"
                                    + " channel_message_log WHERE channel = ? AND created_at < ?"
                                    + " ORDER BY created_at LIMIT ?)",
                            channel,
                            Timestamp.from(cutoff),
                            BATCH);
            deleted += n;
            if (n < BATCH) {
                break;
            }
        }
        // The session row keeps a short preview of the last message; it ages out with the transcript.
        jdbc.update(
                "UPDATE channel_session SET last_message_preview = NULL WHERE channel = ?"
                        + " AND last_message_preview IS NOT NULL AND last_message_at < ?",
                channel,
                Timestamp.from(cutoff));
        return new Result(PrivacyDataClass.TRANSCRIPT, channel, rule.retentionDays(), cutoff, deleted, 0);
    }

    private Result purgeRecordings(Rule rule, Instant now) {
        Instant cutoff = cutoff(now, rule);
        long deleted = 0;
        long failed = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            List<Map<String, Object>> rows =
                    jdbc.queryForList(
                            "SELECT id, object_key FROM voice_call_recording WHERE created_at < ?"
                                    + " ORDER BY created_at LIMIT ?",
                            Timestamp.from(cutoff),
                            BATCH);
            long failedThisBatch = 0;
            for (Map<String, Object> row : rows) {
                String key = (String) row.get("object_key");
                try {
                    if (key != null && !key.isBlank()) {
                        storage.delete(key);
                    }
                    jdbc.update("DELETE FROM voice_call_recording WHERE id = ?", (UUID) row.get("id"));
                    deleted++;
                } catch (RuntimeException e) {
                    // Keep the row so the object is not orphaned from its record; retry tomorrow.
                    failedThisBatch++;
                    log.warn("Retention purge could not delete a recording object: {}", e.getMessage());
                }
            }
            failed += failedThisBatch;
            if (rows.size() < BATCH || failedThisBatch > 0) {
                break;
            }
        }
        return new Result(PrivacyDataClass.RECORDING, PrivacyDataClass.VOICE, rule.retentionDays(), cutoff, deleted, failed);
    }

    private Result purgeExtractedFields(Rule rule, Instant now) {
        Instant cutoff = cutoff(now, rule);
        long deleted = 0;
        AgentMemoryService agent = agentMemory.getIfAvailable();
        if (agent != null) {
            deleted += drain(limit -> agent.purgeCreatedBefore(cutoff, limit));
        }
        TenantMemoryService tenant = tenantMemory.getIfAvailable();
        if (tenant != null) {
            deleted += drain(limit -> tenant.purgeCreatedBefore(cutoff, limit));
        }
        return new Result(
                PrivacyDataClass.EXTRACTED_FIELDS, PrivacyDataClass.ALL_CHANNELS, rule.retentionDays(), cutoff, deleted, 0);
    }

    private static long drain(java.util.function.IntUnaryOperator batch) {
        long total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            int n = batch.applyAsInt(BATCH);
            total += n;
            if (n < BATCH) {
                break;
            }
        }
        return total;
    }

    private static Instant cutoff(Instant now, Rule rule) {
        return now.minus(Duration.ofDays(rule.retentionDays()));
    }
}
