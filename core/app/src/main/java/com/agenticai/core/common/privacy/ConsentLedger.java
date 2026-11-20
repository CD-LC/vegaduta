package com.agenticai.core.common.privacy;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Append-only record of consent decisions per contact (Act s.6(10): the fiduciary must be able to
 * prove a notice was given and consent recorded). The current state of a contact is their latest
 * row for the purpose.
 */
@Component
public class ConsentLedger {

    public enum Decision {
        PROMPTED,
        GRANTED,
        DECLINED,
        WITHDRAWN
    }

    public record Entry(
            UUID id,
            String channel,
            String externalId,
            UUID agentId,
            String purpose,
            int noticeVersion,
            String noticeLanguage,
            String noticeSha256,
            Decision decision,
            Instant decidedAt) {}

    private final ConsentLedgerJdbcRepository repository;

    public ConsentLedger(ConsentLedgerJdbcRepository repository) {
        this.repository = repository;
    }

    public Entry record(
            String channel,
            String externalId,
            UUID agentId,
            String purpose,
            int noticeVersion,
            String language,
            String noticeSha256,
            Decision decision) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        repository.insert(
                id,
                channel,
                externalId,
                agentId,
                purpose,
                noticeVersion,
                language,
                noticeSha256,
                decision,
                now);
        return new Entry(
                id,
                channel,
                externalId,
                agentId,
                purpose,
                noticeVersion,
                language,
                noticeSha256,
                decision,
                now);
    }

    public Optional<Entry> latest(String channel, String externalId, String purpose) {
        return repository.latest(channel, externalId, purpose);
    }

    /** Every decision for the contact, oldest first. */
    public List<Entry> history(String channel, String externalId) {
        return repository.history(channel, externalId);
    }

    public int deleteFor(String channel, String externalId) {
        return repository.deleteFor(channel, externalId);
    }
}
