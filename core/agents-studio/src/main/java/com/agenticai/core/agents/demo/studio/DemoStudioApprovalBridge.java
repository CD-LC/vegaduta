package com.agenticai.core.agents.demo.studio;

import com.agenticai.core.tenancy.replica.BoundedRedisCall;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Carries a /try Agent Studio approval decision between core replicas
 * ({@code app.demo.studio.approval-store=redis}).
 *
 * <p>A studio workflow run parked on an approval step waits on an in-memory future in the replica that is streaming
 * the run to the browser ({@link DemoStudioWorkflowRunner}). The visitor's "approve / reject" is a separate POST that
 * a load balancer can route to the OTHER replica, which has no future to complete, so the decision was lost and the
 * run timed out after ten minutes. With this bridge:
 * <ul>
 *   <li>the owner marks the run pending ({@code demo:studio:approval:pending:<runId>}, TTL a little over the approval
 *       timeout) when it parks;</li>
 *   <li>a replica that receives a decision for a run it does not own checks that marker and, if present, writes
 *       {@code demo:studio:approval:decision:<runId>} ({@code SET NX}, so a second click cannot overwrite the first and
 *       reports "not accepted", the same answer an already-decided run gives today);</li>
 *   <li>the owner polls that key about once a second while it waits and completes the run when it appears.</li>
 * </ul>
 * Only parked runs of the demo tenant touch Redis; the chat path never does. Any Redis failure answers "no pending
 * run / no decision", i.e. today's per-replica behaviour. Every call is bounded to
 * {@link BoundedRedisCall#DEFAULT_TIMEOUT_MILLIS} ms (Lettuce's own command timeout is 60 s), because {@link #offer}
 * runs on the controller thread.
 */
final class DemoStudioApprovalBridge {

    private static final Logger log = LoggerFactory.getLogger(DemoStudioApprovalBridge.class);

    static final String PENDING_PREFIX = "demo:studio:approval:pending:";
    static final String DECISION_PREFIX = "demo:studio:approval:decision:";
    static final Duration PENDING_TTL = DemoStudioWorkflowRunner.APPROVAL_TIMEOUT.plusMinutes(1);
    static final Duration DECISION_TTL = Duration.ofMinutes(2);
    private static final String APPROVED = "approved";
    private static final String REJECTED = "rejected";

    private final StringRedisTemplate redis;
    private final AtomicBoolean warned = new AtomicBoolean();
    private final BoundedRedisCall bounded = new BoundedRedisCall("studio-approval-redis");

    DemoStudioApprovalBridge(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** The owner replica parked the run on an approval step. */
    void markPending(UUID runId) {
        try {
            bounded.run(BoundedRedisCall.DEFAULT_TIMEOUT_MILLIS,
                () -> redis.opsForValue().set(PENDING_PREFIX + runId, "1", PENDING_TTL));
        } catch (RuntimeException e) {
            warnOnce("could not mark a parked run in Redis", e);
        }
    }

    /**
     * A replica without the waiting future records the decision for the owner. True only when a parked run exists and
     * this call was the first decision for it.
     */
    boolean offer(UUID runId, boolean approved) {
        try {
            // Both commands in ONE bounded call: the caller (a controller thread) waits at most the bound in total.
            return bounded.call(BoundedRedisCall.DEFAULT_TIMEOUT_MILLIS, () -> {
                if (!Boolean.TRUE.equals(redis.hasKey(PENDING_PREFIX + runId))) {
                    return false;
                }
                return Boolean.TRUE.equals(redis.opsForValue()
                    .setIfAbsent(DECISION_PREFIX + runId, approved ? APPROVED : REJECTED, DECISION_TTL));
            });
        } catch (RuntimeException e) {
            warnOnce("could not record an approval decision in Redis", e);
            return false;
        }
    }

    /** The owner's poll: the decision another replica recorded, consumed, or null when there is none (or on error). */
    Boolean takeDecision(UUID runId) {
        try {
            return bounded.call(BoundedRedisCall.DEFAULT_TIMEOUT_MILLIS, () -> {
                String value = redis.opsForValue().get(DECISION_PREFIX + runId);
                if (value == null) {
                    return null;
                }
                redis.delete(DECISION_PREFIX + runId);
                return APPROVED.equals(value.strip());
            });
        } catch (RuntimeException e) {
            warnOnce("could not read an approval decision from Redis", e);
            return null;
        }
    }

    /** The run finished (decided, timed out or failed): drop both keys. */
    void clear(UUID runId) {
        try {
            bounded.run(BoundedRedisCall.DEFAULT_TIMEOUT_MILLIS, () -> {
                redis.delete(PENDING_PREFIX + runId);
                redis.delete(DECISION_PREFIX + runId);
            });
        } catch (RuntimeException e) {
            warnOnce("could not clear a finished run's approval keys in Redis", e);
        }
    }

    private void warnOnce(String what, RuntimeException cause) {
        if (warned.compareAndSet(false, true)) {
            log.warn("Studio approval bridge: {} - approvals work only on the replica that streams the run", what, cause);
        }
    }
}
