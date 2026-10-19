package com.agenticai.core.workflow;

import com.agenticai.core.resilience.RateLimitedLog;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Pause Run - the cross-request "please park this run" signal for {@link
 * WorkflowExecutionEngine}'s synchronous traversal. Exact same mechanism and
 * trade-offs as {@link WorkflowRunCancellationService} (same "signal a
 * running process from another request" Redis-flag pattern, same TTL, same
 * fail-open posture), duplicated as its own tiny class rather than folded
 * into the cancellation service for the same reason they are separate verbs
 * in the API: a pause flag must NEVER be readable as a cancel flag - merging
 * them into one "control" key would make a lost write indistinguishable and
 * a mis-parked run uncancellable.
 *
 * <p>Semantics differ from cancellation in the one place that matters:
 * observing the flag PARKS the run (status PAUSED + resume envelope on the
 * row, see {@code WorkflowRun#pause}) instead of terminating it, and
 * {@code POST .../resume} re-enters the traversal at the parked node. A node
 * already mid-flight finishes first - the flag is only ever read at the
 * between-node checkpoint, same guarantee as cancel.
 *
 * <p><b>Legacy engine only.</b> A Temporal-path run never polls this flag -
 * the pause request is recorded and harmlessly expires, and the run keeps
 * going, exactly the degraded outcome {@code cancelRun} documents for a
 * signal that misses its engine. Durable Temporal pause/resume is signal-
 * based future work, same class of work as durable approval-resume.
 */
@Component
public class WorkflowRunPauseService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunPauseService.class);
    /** One WARN per minute per call site while a dependency is down, instead of one (with a stack trace) per request. */
    private static final RateLimitedLog REDIS_DOWN_LOG = new RateLimitedLog(log, Duration.ofMinutes(1));
    private static final String KEY_PREFIX = "agentic:workflow-run-pause:";

    // Same reasoning as WorkflowRunCancellationService.FLAG_TTL: self-cleaning
    // insurance against a flag that never gets read, not a correctness input.
    private static final Duration FLAG_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redisTemplate;

    public WorkflowRunPauseService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * @return true if the request was recorded; false if Redis was
     *     unreachable (logged, not thrown). {@code WorkflowService#pauseRun}
     *     returns the run's current status either way - a lost pause signal
     *     means the run simply keeps executing, the same degraded outcome a
     *     lost cancel produces.
     */
    public boolean requestPause(UUID runId) {
        try {
            redisTemplate.opsForValue().set(key(runId), "1", FLAG_TTL);
            return true;
        } catch (RuntimeException e) {
            REDIS_DOWN_LOG.warnf("Could not record pause request for workflow run {} - Redis unreachable, "
                + "the run will keep executing as if Pause was never clicked.", runId, e);
            return false;
        }
    }

    /** Polled at {@link WorkflowExecutionEngine}'s between-node checkpoint, beside the cancel check. */
    public boolean isPauseRequested(UUID runId) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(runId)));
        } catch (RuntimeException e) {
            // Fail open toward KEEP RUNNING, same as the cancel flag - a
            // Redis blip must never spuriously park every in-flight run.
            REDIS_DOWN_LOG.warnf("Could not check pause flag for workflow run {} - Redis unreachable, "
                + "treating as not paused.", runId, e);
            return false;
        }
    }

    /** Cleared by the engine once it has actually parked the run, and best-effort on cancel. */
    public void clearPauseFlag(UUID runId) {
        try {
            redisTemplate.delete(key(runId));
        } catch (RuntimeException e) {
            log.warn("Could not clear pause flag for workflow run {} - it will expire on its own in {}.",
                runId, FLAG_TTL, e);
        }
    }

    private String key(UUID runId) {
        return KEY_PREFIX + runId;
    }
}
