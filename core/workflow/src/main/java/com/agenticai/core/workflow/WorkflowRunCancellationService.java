package com.agenticai.core.workflow;

import com.agenticai.core.resilience.RateLimitedLog;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Stop Run - the cross-request "please stop" signal for {@link
 * WorkflowExecutionEngine}'s default synchronous execution path. That engine
 * runs a whole graph on the single HTTP request thread that called {@code
 * POST /api/workflows/{id}/run}; a separate {@code POST .../runs/{runId}/cancel}
 * request arrives on a completely different thread with no shared in-process
 * state to signal through, so this reuses this codebase's existing "signal a
 * running process from another request" pattern - plain Redis, same shape as
 * {@link com.agenticai.core.omnichannel.trigger.WorkflowTriggerRateLimiter}
 * (a simple key with a TTL, StringRedisTemplate, no new infrastructure).
 * {@link WorkflowExecutionEngine#execute} polls {@link #isCancellationRequested}
 * once at the top of its node-traversal loop and once per Loop-node
 * iteration - see that class's javadoc for exactly where and why only there.
 *
 * <p><b>Not used by the Temporal execution path</b> - {@code
 * TemporalWorkflowExecutionEngine#cancelRun} uses Temporal's own
 * client-initiated cancellation instead (a real platform feature for exactly
 * this use case), which is the more idiomatic fit for a durable, possibly
 * cross-worker execution than teaching a Temporal Workflow method to poll
 * Redis (which would itself need to be an Activity call, since Workflow code
 * must stay side-effect-free - see {@code WorkflowRunWorkflowImpl}'s class
 * javadoc on why). {@code WorkflowService#cancelRun} still unconditionally
 * writes this flag regardless of which engine actually ran a given request,
 * since a run's execution engine isn't recorded per-row anywhere - a harmless
 * no-op write for a run this class never gets asked about.
 *
 * <p><b>Fail-open trade-off, stated honestly</b>: unlike an abuse throttle
 * (e.g. {@code WorkflowTriggerRateLimiter}, which fails open because
 * degrading a rate limit is the safe direction), there is no "safe direction"
 * distinction to get wrong here either way - Redis being unreachable simply
 * means a cancel request never gets recorded, and the run keeps executing
 * exactly as if Stop had never been clicked. That is an acceptable degraded
 * UX ("the stop didn't take effect, try again or wait for the run to finish
 * on its own"), not a correctness bug: this class must never be the reason a
 * run is wrongly killed by a transient Redis blip, and never claims a
 * cancellation succeeded when the underlying write actually failed.
 */
@Component
public class WorkflowRunCancellationService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunCancellationService.class);
    /** One WARN per minute per call site while a dependency is down, instead of one (with a stack trace) per request. */
    private static final RateLimitedLog REDIS_DOWN_LOG = new RateLimitedLog(log, Duration.ofMinutes(1));
    private static final String KEY_PREFIX = "agentic:workflow-run-cancel:";

    // Generous relative to how long a single synchronous run should ever
    // take (OrchestratorService.MAX_STEPS_PER_RUN bounds the node count, and
    // most nodes complete in seconds) - this is just self-cleaning insurance
    // against a flag that somehow never gets cleared (e.g. the engine
    // crashes between reading the flag and reaching #clearCancellationFlag),
    // not a value anything else depends on.
    private static final Duration FLAG_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;

    public WorkflowRunCancellationService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * @return true if the request was recorded; false if Redis was
     *     unreachable (logged, not thrown - see class javadoc). The caller
     *     ({@code WorkflowService#cancelRun}) still returns the run's current
     *     status either way, since a lost signal isn't an error the caller
     *     did anything wrong to cause.
     */
    public boolean requestCancellation(UUID runId) {
        try {
            redisTemplate.opsForValue().set(key(runId), "1", FLAG_TTL);
            return true;
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("Could not record cancellation request for workflow run {} - Redis unreachable, "
                + "the run will keep executing as if Stop was never clicked.", runId, e);
            return false;
        }
    }

    /** Polled from {@link WorkflowExecutionEngine}'s traversal loop - see that class's javadoc. */
    public boolean isCancellationRequested(UUID runId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(runId)));
        } catch (RuntimeException e) {
            // Fail open in the sense that the RUN keeps going (see class
            // javadoc) - a Redis blip must never spuriously cancel every
            // in-flight run.
            REDIS_DOWN_LOG.warnf("Could not check cancellation flag for workflow run {} - Redis unreachable, "
                + "treating as not cancelled.", runId, e);
            return false;
        }
    }

    /** Best-effort cleanup once a run reaches ANY terminal status - not just CANCELLED, so a flag set
     * just as a run was already finishing on its own never lingers for the full TTL. Swallows Redis
     * errors: a leftover key only costs an extra {@link #isCancellationRequested} call for its TTL. */
    public void clearCancellationFlag(UUID runId) {
        try {
            redisTemplate.delete(key(runId));
        } catch (RuntimeException e) {
            log.warn("Could not clear cancellation flag for workflow run {} - it will expire on its own in {}.",
                runId, FLAG_TTL, e);
        }
    }

    private String key(UUID runId) {
        return KEY_PREFIX + runId;
    }
}
