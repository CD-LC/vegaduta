package com.agenticai.core.workflow.output;

import com.agenticai.core.chat.gateway.GovernanceDegradationSignal;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Idempotency guard for the Output node's external sends ({@link
 * EmailOutputService}, {@link TelegramOutputService}, {@link
 * WhatsAppOutputService}, {@link GoogleDriveOutputService}). Workflow
 * execution is at-least-once - a Temporal Activity retry, a node-level retry,
 * or a recovered run can re-execute an Output node whose send already
 * succeeded - and none of these destinations is naturally idempotent: a
 * re-executed node means a second email/message/file. This guard closes that
 * gap at the last hop before the wire, the one place the retry paths all
 * funnel through.
 *
 * <p>Same Redis idiom as {@code InboundMessageDedupService} (core/omnichannel)
 * - plain {@link StringRedisTemplate}, {@code SETNX}-with-TTL via {@link
 * org.springframework.data.redis.core.ValueOperations#setIfAbsent}, tenant-
 * scoped keys, fail-open when Redis can't answer (a workflow output silently
 * never delivered is worse than a rare duplicate delivery), with every
 * fail-open branch reported to {@link GovernanceDegradationSignal} so a Redis
 * outage disabling output dedup shows up on the {@code
 * governance_fail_open_total} dashboard rather than only as log noise.
 * Implemented locally rather than reusing that class because its key contract
 * is provider-message-id-shaped ({@code (channel, providerMessageId)}) and
 * carries channel-webhook semantics (null id => always process) that don't
 * fit outbound sends.
 *
 * <p><b>Why the key is {@code (runId, nodeId)}, not a content hash.</b> An
 * earlier version of this guard keyed on {@code
 * SHA-256(recipient + normalized payload + coarse time bucket)} because the
 * output services only received {@code (recipient, payload)} - runId and
 * node id were in hand at the {@code WorkflowNodeExecutor.executeOutputNode}
 * call site but not threaded any further down. That content hash had two
 * real gaps: a retry whose payload legitimately drifts (a timestamp or
 * generated id interpolated into the agent's output between attempts)
 * derives a different key and slips past dedup entirely, and a retry that
 * happens to straddle the coarse time-bucket boundary does the same. Both
 * are closed by keying on identity instead: {@code runId} and {@code nodeId}
 * are stable and known <em>before</em> the node executes, a run visits a
 * given node at most once (no loop/repeat node type exists today - see
 * {@code WorkflowNodeExecutor}), and a run id is never reused across a
 * legitimate re-run of the same workflow. So two sends sharing a
 * {@code (runId, nodeId)} pair are unambiguously the same node execution
 * being retried, regardless of what its output happens to contain on either
 * attempt - and two sends that are genuinely different executions (a human
 * re-running the workflow, or two different nodes) can never collide.
 *
 * <p><b>Honest limitations still present:</b>
 * <ul>
 *   <li>{@link #tryClaim} runs <em>before</em> the send (that's what makes it
 *       atomic against concurrent duplicates), so a send that fails is
 *       compensated by {@link #release} at each call site - but a process
 *       crash between claim and send leaves the key claimed, suppressing a
 *       retry within the TTL. That trades a bounded (TTL-long) at-most-once
 *       window on crashes for duplicate protection on the vastly more common
 *       retry-after-success path; the short TTL ({@value
 *       #DEFAULT_TTL_MINUTES} minutes by default, {@code
 *       app.workflow.output-dedup.ttl-minutes}) bounds the loss window.
 *   <li>Only the Output node's four external-send destinations (this
 *       class's current callers) get this protection - Agent and Knowledge
 *       output have no idempotency layer of their own. Unchanged by this
 *       fix; a separate item.
 * </ul>
 */
@Component
public class WorkflowOutputDedupGuard {

    private static final Logger log = LoggerFactory.getLogger(WorkflowOutputDedupGuard.class);

    private static final String KEY_PREFIX = "agentic:workflow-output-dedup:";
    // Single component tag on governance_fail_open_total for every fail-open
    // branch here, mirroring InboundMessageDedupService's one-series-per-guard
    // convention.
    private static final String DEGRADATION_COMPONENT = "workflow-output-dedup";
    static final long DEFAULT_TTL_MINUTES = 10;

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final GovernanceDegradationSignal degradationSignal;

    @Autowired
    public WorkflowOutputDedupGuard(
        StringRedisTemplate redisTemplate,
        @Value("${app.workflow.output-dedup.ttl-minutes:" + DEFAULT_TTL_MINUTES + "}") long ttlMinutes,
        GovernanceDegradationSignal degradationSignal
    ) {
        this.redisTemplate = redisTemplate;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.degradationSignal = degradationSignal;
    }

    /**
     * Derives the dedup key for one Output node execution: {@code
     * (runId, nodeId)}, prefixed with the current tenant and the destination
     * kind so the same node re-executing under two different tenants (or, if
     * a node's destination were ever reconfigured mid-run, two different
     * destinations) never collides. Neither runId nor nodeId is sensitive, so
     * this is a plain composite key rather than a hash - unlike the recipient
     * in the content-hash key this replaced, there is no masking discipline
     * to preserve here. See the class javadoc for why identity replaced
     * content as the primary key.
     *
     * @param destination short destination kind ("email", "telegram",
     *     "whatsapp", "google_drive") - key namespace only, never sent anywhere.
     * @param runId the workflow run this Output node execution belongs to.
     * @param nodeId the Output node's id within the workflow graph.
     */
    public String identityKey(String destination, String runId, String nodeId) {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse("__no-tenant__");
        return KEY_PREFIX + tenant + ":" + destination + ":" + runId + ":" + nodeId;
    }

    /**
     * {@link #identityKey(String, String, String)} plus a recipient tag, for destinations whose
     * recipient can differ between executions of the SAME node in the SAME run: an Output node used
     * as the per-item step of a loop / fan-out takes its destination from the item
     * ({@code {{ $json.email }}}), and without the tag every recipient after the first was
     * suppressed as a "duplicate" while the node still reported OK. A retry of the same node for
     * the same recipient derives the same key and is still suppressed. The tag is a short SHA-256
     * prefix, so the address itself never reaches the key.
     */
    public String identityKey(String destination, String runId, String nodeId, String recipient) {
        return identityKey(destination, runId, nodeId + ":" + recipientTag(recipient));
    }

    /** First 6 bytes (hex) of SHA-256 over the trimmed, lower-cased recipient; stable for null / blank. */
    public static String recipientTag(String recipient) {
        try {
            String normalised = recipient == null ? "" : recipient.strip().toLowerCase(java.util.Locale.ROOT);
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(normalised.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash, 0, 6);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * @return true if this key is unclaimed within the TTL window (the caller
     *     should perform the send); false if it was already claimed (the
     *     caller should skip the send and return its normal success shape -
     *     from the workflow's point of view the output WAS delivered, on the
     *     first execution). Fails open (returns true) whenever Redis cannot
     *     answer - see the class javadoc for why a duplicate beats a lost
     *     message - reporting the degradation so it is observable.
     */
    public boolean tryClaim(String key) {
        try {
            Boolean claimed = redisTemplate.opsForValue().setIfAbsent(key, "1", ttl);
            if (claimed == null) {
                // Not a documented return for a reachable Redis; claim
                // defensively rather than suppressing a send, and report it,
                // same as InboundMessageDedupService's null-result branch.
                degradationSignal.failOpen(DEGRADATION_COMPONENT, null);
                return true;
            }
            return claimed;
        } catch (RuntimeException e) {
            degradationSignal.failOpen(DEGRADATION_COMPONENT, e);
            return true;
        }
    }

    /**
     * Best-effort compensation for a claim whose send then failed: deletes the
     * key so the retry that at-least-once execution will produce is not
     * suppressed as a "duplicate" of a send that never happened. Never throws
     * - the send failure propagating to the caller is the primary signal, and
     * a release failure must not mask it. If this delete itself fails (Redis
     * blipped between claim and release), a retry within the TTL will be
     * wrongly suppressed - logged at WARN because that is the one branch where
     * this guard can cost a message.
     */
    public void release(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException e) {
            log.warn(
                "Could not release workflow-output dedup key after a failed send - a retry of this send within {}"
                    + " may be wrongly suppressed as a duplicate.", ttl, e
            );
        }
    }
}
