package com.agenticai.core.sarvam;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists one {@link SarvamCallLog} row per real call made by any of
 * Stage 3's five capability clients. Called directly by those clients
 * (not via an advisor chain the way {@code LlmMetricsAdvisor} wraps chat
 * calls - these clients are plain injectable services, not {@code
 * ChatClient}s, so there's no advisor seam to hook into; each client calls
 * {@link #record} itself in a {@code finally} block around its HTTP call).
 *
 * <p>A logging failure here must never break the real translate/transcribe/
 * synthesize call that triggered it - same "a missing metric never breaks
 * the real feature" rule {@link SarvamCallLog}'s chat-log counterpart,
 * {@code LlmCallLogService#record}, already follows.
 */
@Service
public class SarvamCallLogService {

    private static final Logger log = LoggerFactory.getLogger(SarvamCallLogService.class);

    private final SarvamCallLogRepository repository;

    public SarvamCallLogService(SarvamCallLogRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(
        SarvamCallCapability capability,
        String model,
        boolean success,
        String errorMessage,
        long latencyMs,
        Instant startedAt
    ) {
        try {
            repository.save(new SarvamCallLog(capability, model, success, errorMessage, latencyMs, startedAt));
        } catch (Exception e) {
            log.warn("Failed to persist SarvamCallLog for capability {} (call itself already completed) - {}", capability, e.getMessage());
        }
    }

    /** Calls of one capability that started after {@code since} (current tenant schema). */
    @Transactional(readOnly = true)
    public long countSince(SarvamCallCapability capability, Instant since) {
        return repository.countByCapabilityAndStartedAtAfter(capability, since);
    }

    @Transactional(readOnly = true)
    public List<SarvamCallLog> recent(int limit) {
        List<SarvamCallLog> rows = repository.findTop200ByOrderByStartedAtDesc();
        return rows.size() > limit ? rows.subList(0, limit) : rows;
    }
}
