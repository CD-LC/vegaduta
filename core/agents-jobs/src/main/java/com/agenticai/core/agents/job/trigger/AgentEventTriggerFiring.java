package com.agenticai.core.agents.job.trigger;

import com.agenticai.core.agents.job.AgentJobLaunch;
import com.agenticai.core.agents.job.AgentJobLauncher;
import com.agenticai.core.agents.job.AgentJobOrigin;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobTriggerProperties;
import com.agenticai.core.eventbus.WorkflowEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One trigger's reaction to one event, in its OWN transaction: claim the event,
 * account for it against the loop guard, and enqueue a job.
 *
 * <p><b>Why this is a separate bean from {@link AgentEventTriggerDispatcher}.</b>
 * Two reasons, both mechanical:
 *
 * <ol>
 *   <li><b>{@code REQUIRES_NEW} only applies through the proxy.</b> A private
 *       method on the dispatcher would be a self-invocation and would silently
 *       run in the caller's transaction - which is the whole thing this class
 *       exists to avoid.</li>
 *   <li><b>The duplicate-delivery {@code DataIntegrityViolationException} must
 *       not poison the caller's transaction.</b> The caller here is a WEBHOOK
 *       REQUEST THREAD, in the middle of handling an inbound message. A
 *       constraint violation raised inside its transaction marks it rollback-only
 *       and breaks the message handling that emitted the event in the first
 *       place - i.e. a retried WhatsApp delivery would break the WhatsApp
 *       integration. With {@code REQUIRES_NEW} the outer transaction is
 *       suspended, the inner one rolls back alone, and the outer resumes intact.</li>
 * </ol>
 *
 * <p><b>The order of operations inside {@link #fire} is the design.</b>
 * <ol>
 *   <li><b>Duplicate-payload check first</b>, because it is the only guard that
 *       has to run BEFORE a row is inserted - it is a windowed check over
 *       previous rows, so inserting first would make every event its own
 *       duplicate.</li>
 *   <li><b>Claim second.</b> {@code saveAndFlush} of the ledger row. Flushing
 *       eagerly is what makes the exception ATTRIBUTABLE: a deferred flush would
 *       raise the identical exception type at commit, after the job had already
 *       been enqueued, and there would be no way to tell a duplicate delivery
 *       from some other constraint. Nothing else in this transaction can raise
 *       it - {@code agent_job} carries no trigger-scoped unique index (V207/V208's
 *       two are both {@code WHERE schedule_id IS NOT NULL}).</li>
 *   <li><b>Loop guard third, after the claim.</b> Deliberately: the guard counts
 *       CLAIMED events, and the claim is what makes them distinct. Counting
 *       before the dedupe would let a provider retrying one delivery twenty
 *       times disable a perfectly healthy trigger.</li>
 *   <li><b>Enqueue last</b>, so a job only ever exists for a delivery that was
 *       claimed and was inside the firing budget.</li>
 * </ol>
 *
 * <h2>The loop guard: two sliding counts, both derived from the ledger</h2>
 * An agent whose own actions publish an event it subscribes to runs forever and
 * spends real money. The live cycle needs no external party at all:
 * {@code GenerateDocumentToolCallback} -&gt; {@code
 * DocumentGenerationService#requestGeneration} -&gt; {@code
 * DocumentGenerationEventListener} -&gt; {@code document.generation.completed}
 * -&gt; a trigger whose agent holds the generate-document tool -&gt; forever,
 * burning LLM budget every iteration. Channel echo loops are the other family
 * (core has no author-is-bot check for Discord, and {@code
 * EmailInboundPollingJob} has no {@code Auto-Submitted}/{@code Precedence}/
 * {@code List-Id} guard, so an out-of-office responder is a classic mail loop).
 *
 * <p>Two things about the first cut of this guard were wrong and are fixed here,
 * both of which mattered:
 *
 * <ul>
 *   <li><b>A single 5-minute/20-fire window cannot see the loop it was written
 *       for.</b> 20 fires per 5 minutes is one per 15 seconds; the
 *       document-generation cycle above costs one LLM turn plus a render per
 *       iteration, which is longer. The count therefore never reached the cap,
 *       the trigger was never disabled, and NONE of the three diagnostics (WARN,
 *       {@code agent.job.trigger.loop_guard_tripped}, the tenant-admin
 *       notification) ever fired - they all live in the tripped arm. A second,
 *       SUSTAINED count ({@code max-fires-per-sustained-window} over {@code
 *       sustained-window}, 200/24h by default) bounds exactly that shape.</li>
 *   <li><b>A counter column on the trigger loses increments.</b> It was an
 *       unguarded read-modify-write on an entity with no {@code @Version}, and
 *       firings genuinely run concurrently ({@code @Async} document generation,
 *       concurrent webhook threads), so it undercounted precisely when the rate
 *       was highest. Both counts now come from {@code
 *       agent_event_trigger_fire}, where each fire is its own row and nothing
 *       can be lost.</li>
 * </ul>
 *
 * <p><b>What the guard still does NOT do, stated plainly.</b> It is a SPEND
 * BOUND, not a loop detector - no rate-based mechanism can distinguish a busy
 * trigger from a looping one, so the honest contract is "a self-triggering
 * configuration costs at most {@code max-fires-per-sustained-window} jobs before
 * it is switched off and a human is told". FAN-OUT (one event matching up to
 * {@code max-triggers-per-event} triggers, each with its own per-trigger
 * budget) and two triggers looping THROUGH each other, each under its own cap,
 * are bounded not per trigger but by the TENANT-WIDE pair ({@code
 * max-tenant-fires-per-window} / {@code max-tenant-fires-per-sustained-window}
 * - counted over the whole firing ledger, which in a schema-per-tenant database
 * is exactly the tenant's total). What remains genuinely undetected is WHICH
 * trigger is the culprit in a mutual loop: there is no reliable lineage signal
 * - the causing job's identity does not survive to the thread that publishes
 * the follow-on event - which is why {@code agent_job.trigger_depth} is
 * recorded as {@code 0} and documented as reserved rather than pretended to
 * work, and why a tenant-cap trip disables the trigger that crossed the line
 * rather than pretending to know the guilty one.
 *
 * <p><b>Budget note that belongs next to all of this:</b> {@code
 * PerUserBudgetService}'s per-user daily ceiling is reachable only through
 * {@code FreeTierChatClientService}, i.e. only when the tenant uses the platform
 * LLM pool. A paid tenant on its own provider key has no such ceiling, so for
 * that tenant this guard is the ONLY thing bounding a self-triggering
 * configuration.
 */
@Component
public class AgentEventTriggerFiring {

    private static final Logger log = LoggerFactory.getLogger(AgentEventTriggerFiring.class);

    private final AgentEventTriggerRepository repository;
    private final AgentEventTriggerFireRepository fireRepository;
    private final AgentJobLauncher launcher;
    private final AgentJobProperties properties;

    public AgentEventTriggerFiring(
        AgentEventTriggerRepository repository,
        AgentEventTriggerFireRepository fireRepository,
        AgentJobLauncher launcher,
        AgentJobProperties properties
    ) {
        this.repository = repository;
        this.fireRepository = fireRepository;
        this.launcher = launcher;
        this.properties = properties;
    }

    /**
     * @param eventKey {@code eventType|runId} - see {@link
     *     AgentEventTriggerDispatcher#eventKeyOf}. EXACT, and therefore only a
     *     real guarantee for event types whose {@code runId} survives
     *     re-publication.
     * @param payloadFingerprint the windowed dedupe key that covers the eight
     *     channel types, whose {@code runId} is freshly minted per delivery -
     *     see {@link AgentEventTriggerDispatcher#fingerprintOf}. May be null,
     *     in which case only the exact key applies.
     * @return what happened, for the caller to log, meter and (for {@link
     *     Outcome#LOOP_GUARD_TRIPPED}) notify on. Those side effects are
     *     deliberately NOT done here: a notification write failing inside this
     *     transaction would roll back the very disable that stops the loop.
     * @throws org.springframework.dao.DataIntegrityViolationException when this
     *     event was already claimed by this trigger - i.e. an at-least-once
     *     redelivery of an event whose id is stable. The caller catches it and
     *     treats it as "already handled". It is allowed to propagate rather than
     *     being caught here because catching a constraint violation inside its
     *     own transaction leaves that transaction rollback-only, and the commit
     *     would then fail anyway with a less informative exception.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FireResult fire(UUID triggerId, WorkflowEvent event, String eventKey, String payloadFingerprint) {
        Instant now = Instant.now();
        AgentEventTrigger trigger = repository.findById(triggerId).orElse(null);
        if (trigger == null || !trigger.isEnabled()) {
            // Re-read INSIDE this transaction rather than trusting the copy the
            // dispatcher matched against: with several events in flight, another
            // thread may have tripped the guard microseconds ago, and the whole
            // point of disabling a looping trigger is that the next event does
            // not fire it.
            return FireResult.skipped(Outcome.SKIPPED_DISABLED);
        }
        if (trigger.getCreatedBy() == null || trigger.getCreatedBy().isBlank()) {
            log.warn(
                "Agent event trigger {} (agent {}) has no created_by, so there is no human identity to run or bill "
                    + "the job against; re-save the trigger to attach one",
                trigger.getId(), trigger.getAgentId());
            return FireResult.skipped(Outcome.SKIPPED_NO_IDENTITY);
        }

        // (1) The windowed duplicate-payload guard, before any row exists for
        // THIS delivery - otherwise the check would always find itself.
        if (isDuplicatePayload(trigger, payloadFingerprint, now)) {
            return FireResult.skipped(Outcome.SKIPPED_DUPLICATE_PAYLOAD);
        }

        // (2) THE CLAIM. Flushed eagerly - see the class javadoc for why the
        // eagerness is load-bearing rather than defensive.
        AgentEventTriggerFire claim = fireRepository.saveAndFlush(
            AgentEventTriggerFire.claim(UUID.randomUUID(), trigger.getId(), eventKey, payloadFingerprint, now));

        // (3) The loop guard, counted from the ledger (this claim included).
        GuardCounts counts = countAgainstGuard(trigger, now);
        LoopGuardScope tripped = counts.tripped(properties.getTrigger());
        if (tripped != null) {
            repository.disableIfEnabled(trigger.getId(), AgentEventTriggerDisableReason.LOOP_GUARD_TRIPPED, now);
            // The claim row stays, with a null job_id. That is the record of
            // "this event arrived and deliberately produced nothing", which is
            // what an operator asking "where is my job?" needs to see.
            return new FireResult(Outcome.LOOP_GUARD_TRIPPED, null, counts, tripped);
        }

        String goal = AgentEventTriggerGoal.render(
            trigger.getGoalTemplate(), event, properties.getTrigger().getMaxGoalChars());

        UUID jobId = launcher.enqueue(new AgentJobLaunch(
            trigger.getAgentId(),
            goal,
            AgentJobOrigin.EVENT,
            // origin_ref carries the event key, so "who asked for this job?" is
            // answerable from the job row alone even after the trigger is
            // deleted or re-configured - the same role the cron plays for a
            // scheduled job.
            eventKey,
            null,
            null,
            trigger.getCreatedBy(),
            // No roles snapshot exists on the trigger row, so a triggered job
            // runs with NONE - the same safe direction AgentScheduleService
            // takes, and for the same reason: fewer roles can only ever make the
            // job less capable than the human who configured it, never more. A
            // stored role snapshot would instead let a trigger keep exercising
            // an authority its owner has since lost.
            Set.of(),
            trigger.getCreatedBy(),
            null,
            trigger.getId(),
            // trigger_depth: reserved, always 0. See the class javadoc - there
            // is no lineage signal that survives to the publishing thread, and a
            // number that looks like depth tracking but is always zero would be
            // worse than an honest constant.
            0
        ));

        claim.recordJob(jobId);
        fireRepository.save(claim);
        // Atomic, not a mutate-then-save: see AgentEventTriggerRepository#recordFired.
        repository.recordFired(trigger.getId(), now);
        return new FireResult(Outcome.FIRED, jobId, counts, null);
    }

    /**
     * @return true when this exact payload already produced a firing for this
     *     trigger inside {@code app.agent-jobs.trigger.duplicate-window}.
     */
    private boolean isDuplicatePayload(AgentEventTrigger trigger, String payloadFingerprint, Instant now) {
        Duration window = properties.getTrigger().getDuplicateWindow();
        if (payloadFingerprint == null || window == null || window.isZero() || window.isNegative()) {
            return false;
        }
        Instant from = floorAtGuardReset(trigger, now.minus(window));
        if (!fireRepository.existsByTriggerIdAndPayloadFingerprintAndFiredAtGreaterThanEqual(
            trigger.getId(), payloadFingerprint, from)) {
            return false;
        }
        log.info(
            "Agent event trigger {} (agent {}) already fired on an identical '{}' payload since {} - treating this "
                + "delivery as a duplicate rather than buying a second job. The channel event types carry a fresh "
                + "runId per delivery, so this window is what makes a provider retry idempotent here.",
            trigger.getId(), trigger.getAgentId(), trigger.getEventType(), from);
        return true;
    }

    /**
     * Four sliding counts per firing: burst and sustained for THIS trigger, and
     * the same two windows TENANT-WIDE (the whole {@code
     * agent_event_trigger_fire} table - one tenant is one schema). The tenant
     * pair is what bounds a fan-out: {@code max-triggers-per-event} triggers
     * each inside its own per-trigger budget, or two triggers looping through
     * each other, can together spend at most the tenant caps.
     *
     * <p>All four are floored at this trigger's {@code guard_reset_at}, the
     * tenant pair included - otherwise re-saving the one trigger the guard
     * disabled would count straight back into the tenant-wide history that
     * disabled it and be re-disabled by its next single event. The cost of the
     * floor is that re-saving triggers resets their VIEW of the tenant window
     * too; re-saving is an authenticated operator action and already the
     * documented way back for the per-trigger caps, so it widens nothing.
     */
    private GuardCounts countAgainstGuard(AgentEventTrigger trigger, Instant now) {
        AgentJobTriggerProperties config = properties.getTrigger();
        Instant burstFrom = floorAtGuardReset(trigger, now.minus(config.getWindow()));
        Instant sustainedFrom = floorAtGuardReset(trigger, now.minus(config.getSustainedWindow()));
        long burst = fireRepository.countByTriggerIdAndFiredAtGreaterThanEqual(trigger.getId(), burstFrom);
        long sustained = fireRepository.countByTriggerIdAndFiredAtGreaterThanEqual(trigger.getId(), sustainedFrom);
        long tenantBurst = fireRepository.countByFiredAtGreaterThanEqual(burstFrom);
        long tenantSustained = fireRepository.countByFiredAtGreaterThanEqual(sustainedFrom);
        return new GuardCounts(burst, sustained, tenantBurst, tenantSustained);
    }

    /**
     * Every count starts no earlier than {@code guard_reset_at}. Without this,
     * re-saving a trigger the guard disabled would walk straight back into the
     * rows that disabled it and be re-disabled by the next single event - the
     * feature would look broken to the operator who just fixed the cycle.
     */
    private static Instant floorAtGuardReset(AgentEventTrigger trigger, Instant windowStart) {
        Instant reset = trigger.getGuardResetAt();
        return reset != null && reset.isAfter(windowStart) ? reset : windowStart;
    }

    /** Which part of the loop guard tripped - they carry different operator advice. */
    public enum LoopGuardScope {

        /** Many firings of ONE trigger in minutes: a fast echo loop, or a genuine traffic spike. */
        BURST,

        /** A steady drip from one trigger over hours that a burst window can never see. */
        SUSTAINED,

        /**
         * The TENANT's triggers collectively exceeded the per-window ceiling -
         * the fan-out shape no per-trigger cap can see (one event matching many
         * triggers, or triggers looping through each other, each under its own
         * cap).
         */
        TENANT_BURST,

        /** The tenant-wide ceiling over the sustained window - the hard per-tenant daily spend bound. */
        TENANT_SUSTAINED
    }

    /**
     * @param burst THIS trigger's firings inside {@code window}, INCLUDING this one.
     * @param sustained this trigger's firings inside {@code sustained-window}, including this one.
     * @param tenantBurst ALL triggers' firings inside {@code window}, including this one.
     * @param tenantSustained all triggers' firings inside {@code sustained-window}, including this one.
     */
    public record GuardCounts(long burst, long sustained, long tenantBurst, long tenantSustained) {

        LoopGuardScope tripped(AgentJobTriggerProperties config) {
            if (burst > config.getMaxFiresPerWindow()) {
                return LoopGuardScope.BURST;
            }
            if (sustained > config.getMaxFiresPerSustainedWindow()) {
                return LoopGuardScope.SUSTAINED;
            }
            if (tenantBurst > config.getMaxTenantFiresPerWindow()) {
                return LoopGuardScope.TENANT_BURST;
            }
            if (tenantSustained > config.getMaxTenantFiresPerSustainedWindow()) {
                return LoopGuardScope.TENANT_SUSTAINED;
            }
            return null;
        }

        static GuardCounts none() {
            return new GuardCounts(0, 0, 0, 0);
        }
    }

    /** What one (trigger, event) pair produced. */
    public enum Outcome {

        /** A job exists. */
        FIRED,

        /** The trigger was disabled between the dispatcher's read and this transaction. */
        SKIPPED_DISABLED,

        /** No {@code created_by}, so there is no authority to run the job with. */
        SKIPPED_NO_IDENTITY,

        /**
         * An identical payload already fired this trigger inside the duplicate
         * window - almost always a provider redelivery of one message.
         */
        SKIPPED_DUPLICATE_PAYLOAD,

        /** A firing cap was exceeded; the trigger is now disabled. */
        LOOP_GUARD_TRIPPED
    }

    /**
     * @param jobId the created job, present only for {@link Outcome#FIRED}.
     * @param counts both guard counts INCLUDING this firing, so the caller's
     *     WARN can say what the guard actually saw rather than just repeating
     *     the cap.
     * @param trippedScope which cap was exceeded, null unless {@link
     *     Outcome#LOOP_GUARD_TRIPPED}.
     */
    public record FireResult(Outcome outcome, UUID jobId, GuardCounts counts, LoopGuardScope trippedScope) {

        static FireResult skipped(Outcome outcome) {
            return new FireResult(outcome, null, GuardCounts.none(), null);
        }
    }
}
