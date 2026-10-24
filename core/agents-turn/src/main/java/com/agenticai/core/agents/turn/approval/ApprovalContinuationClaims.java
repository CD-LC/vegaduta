package com.agenticai.core.agents.turn.approval;

import com.agenticai.core.agents.turn.ApprovalContinuationProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * "This decision has already been continued": the one-shot claim that makes a repeat of the same
 * decision (a retry of the executor, a duplicated event, two replicas reacting to one) start the
 * task again at most once.
 *
 * <p>A Redis {@code SET NX} with a TTL ({@code app.approvals.continuation.claim-ttl}) keyed by the
 * approval request id, shared by every replica. It guards only the CONTINUATION; a second human
 * decision on the same request is already refused by the approval gate (the request is no longer
 * pending), and that is where a double click is stopped.
 *
 * <p>Fails closed: with no Redis (or a Redis error) nothing can be claimed, so no continuation
 * starts and the history note remains the only trace of the decision - a missed continuation is a
 * message the person has to send, a duplicated one is a second billed turn that may repeat an
 * action.
 */
@Component
public class ApprovalContinuationClaims {

    private static final Logger log = LoggerFactory.getLogger(ApprovalContinuationClaims.class);

    static final String KEY_PREFIX = "agentic:approval-continuation:";

    private final ObjectProvider<StringRedisTemplate> redis;
    private final ApprovalContinuationProperties properties;

    public ApprovalContinuationClaims(
            ObjectProvider<StringRedisTemplate> redis, ApprovalContinuationProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /** True exactly once per request id inside the claim TTL; false for a repeat or on any error. */
    public boolean tryClaim(UUID requestId) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null || requestId == null) {
            log.warn(
                    "Approval request {} was decided but no continuation can be claimed (no Redis); the task will not"
                            + " be continued by the platform",
                    requestId);
            return false;
        }
        try {
            Boolean claimed =
                    template.opsForValue()
                            .setIfAbsent(KEY_PREFIX + requestId, "1", properties.getClaimTtl());
            return Boolean.TRUE.equals(claimed);
        } catch (RuntimeException e) {
            log.warn(
                    "Could not claim the continuation of approval request {}; the task will not be continued by the"
                            + " platform",
                    requestId,
                    e);
            return false;
        }
    }

    /**
     * Gives the claim back, for a continuation that could not even be started (so a later retry of
     * the decision may try again). Never throws.
     */
    public void release(UUID requestId) {
        StringRedisTemplate template = redis.getIfAvailable();
        if (template == null || requestId == null) {
            return;
        }
        try {
            template.delete(KEY_PREFIX + requestId);
        } catch (RuntimeException e) {
            log.warn("Could not release the continuation claim of approval request {}", requestId, e);
        }
    }
}
