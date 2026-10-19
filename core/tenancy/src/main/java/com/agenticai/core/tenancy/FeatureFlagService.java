package com.agenticai.core.tenancy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Master Roadmap 2026 item #13 (A/B testing & feature flags, §B) - the
 * governance/admin half lives here (CRUD, exposed by {@code
 * PlatformController}); the live-traffic half is {@link #evaluate}, called
 * from {@code AgentChatService}'s request path (the one place the tech-stack
 * audit's own scoping note named as the right hook - see that class's own
 * javadoc on the {@code experimentVariants} advisor param it threads
 * through).
 *
 * <p><b>Bucketing is deterministic, not assignment-table-driven.</b> {@link
 * #evaluate} hashes {@code flagKey + ":" + tenantId} with SHA-256, takes the
 * low bits as an integer in [0, 100), and compares against {@link
 * FeatureFlag#getRolloutPercentage()} - the same tenant always lands in the
 * same bucket for the same flag (sticky), the distribution of tenant ids
 * across buckets is uniform enough for a rollout percentage to mean what it
 * says, and there is nothing to write on the hot path to get that guarantee.
 * {@link ExperimentAssignmentRepository} is written to best-effort, after
 * the decision is already made, purely so a platform admin can see the
 * assignment list - see {@link ExperimentAssignment}'s javadoc.
 *
 * <p><b>Fail-open to "control"</b> - same posture {@code
 * EntityExtractionService}/{@code MemoryWriteArbitrationService}/{@code
 * LlmQualityJudgeService}/the Supervisor workflow node (Master Roadmap #12)
 * all already established for their own decision layers: a missing flag, a
 * disabled flag, a null/blank tenant id, or any unexpected exception all
 * resolve to {@link #CONTROL} rather than throwing - a broken or
 * misconfigured experiment must never be able to break the real request it
 * rides along on.
 */
@Service
public class FeatureFlagService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagService.class);

    /** The two variants this simple A/B mechanism supports - see class javadoc for why N-ary isn't needed yet. */
    public static final String CONTROL = "control";
    public static final String TREATMENT = "treatment";

    private final FeatureFlagRepository flagRepository;
    private final ExperimentAssignmentRepository assignmentRepository;

    public FeatureFlagService(FeatureFlagRepository flagRepository, ExperimentAssignmentRepository assignmentRepository) {
        this.flagRepository = flagRepository;
        this.assignmentRepository = assignmentRepository;
    }

    @Transactional(readOnly = true)
    public List<FeatureFlag> listFlags() {
        return flagRepository.findAllByOrderByKeyAsc();
    }

    @Transactional
    public FeatureFlag createOrUpdate(String key, String description, boolean enabled, int rolloutPercentage, String updatedBy) {
        return flagRepository.findByKey(key)
            .map(existing -> {
                existing.update(description, enabled, rolloutPercentage, updatedBy);
                return existing;
            })
            .orElseGet(() -> flagRepository.save(new FeatureFlag(key, description, enabled, rolloutPercentage, updatedBy)));
    }

    @Transactional
    public void delete(String key) {
        flagRepository.deleteByKey(key);
    }

    @Transactional(readOnly = true)
    public List<ExperimentAssignment> assignmentsForFlag(String key) {
        return assignmentRepository.findByFlagKeyOrderByAssignedAtDesc(key);
    }

    /**
     * Evaluates every currently-enabled flag for one tenant, in one call -
     * this is what {@code AgentChatService} calls once per chat turn rather
     * than evaluating flags one at a time, so a request that's subject to
     * several simultaneous experiments only pays for one flag-table read.
     * Never throws: any failure logs a warning and returns an empty map, the
     * same "a missing metric/decision never breaks the real feature" rule
     * this codebase applies everywhere else this class's javadoc names.
     */
    @Transactional
    public Map<String, String> evaluateActiveFlags(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return Map.of();
        }
        try {
            List<FeatureFlag> active = flagRepository.findByEnabledTrue();
            if (active.isEmpty()) {
                return Map.of();
            }
            Map<String, String> variants = new LinkedHashMap<>();
            for (FeatureFlag flag : active) {
                variants.put(flag.getKey(), evaluate(flag, tenantId));
            }
            return variants;
        } catch (Exception e) {
            log.warn("Feature flag evaluation failed for tenant {} - defaulting to no active experiments ({}).", tenantId, e.getMessage());
            return Map.of();
        }
    }

    /** Single-flag evaluation by key - looks the flag up first, so this is the convenience overload {@link #evaluate(FeatureFlag, String)} feeds. */
    @Transactional
    public String evaluate(String flagKey, String tenantId) {
        if (flagKey == null || flagKey.isBlank() || tenantId == null || tenantId.isBlank()) {
            return CONTROL;
        }
        try {
            FeatureFlag flag = flagRepository.findByKey(flagKey).orElse(null);
            if (flag == null || !flag.isEnabled()) {
                return CONTROL;
            }
            return evaluate(flag, tenantId);
        } catch (Exception e) {
            log.warn("Feature flag '{}' evaluation failed for tenant {} - defaulting to control ({}).", flagKey, tenantId, e.getMessage());
            return CONTROL;
        }
    }

    private String evaluate(FeatureFlag flag, String tenantId) {
        String variant = bucketOf(flag.getKey(), tenantId) < flag.getRolloutPercentage() ? TREATMENT : CONTROL;
        recordAssignmentBestEffort(flag.getKey(), tenantId, variant);
        return variant;
    }

    /** Deterministic bucket in [0, 100) from a stable hash of {@code flagKey + tenantId} - see class javadoc. */
    private static int bucketOf(String flagKey, String tenantId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((flagKey + ":" + tenantId).getBytes(StandardCharsets.UTF_8));
            // First 4 bytes as an unsigned int, mod 100 - plenty of entropy for a
            // 0-99 bucket, no need for the full 32 bytes.
            int unsigned = ((hash[0] & 0xFF) << 24) | ((hash[1] & 0xFF) << 16) | ((hash[2] & 0xFF) << 8) | (hash[3] & 0xFF);
            return Math.floorMod(unsigned, 100);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is always available on every JVM - unreachable in practice,
            // but if it ever weren't, fail open to CONTROL by returning a bucket
            // that's never < a positive rolloutPercentage's floor... actually
            // returning 100 guarantees CONTROL regardless of rolloutPercentage.
            return 100;
        }
    }

    /** Best-effort - see {@link ExperimentAssignment}'s javadoc for why this is observability, not the source of truth. */
    private void recordAssignmentBestEffort(String flagKey, String tenantId, String variant) {
        try {
            if (assignmentRepository.findByFlagKeyAndTenantId(flagKey, tenantId).isEmpty()) {
                assignmentRepository.save(new ExperimentAssignment(flagKey, tenantId, variant));
            }
        } catch (Exception e) {
            log.debug("Could not record experiment assignment for flag '{}' tenant {} (non-fatal) - {}", flagKey, tenantId, e.getMessage());
        }
    }
}
