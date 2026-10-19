package com.agenticai.core.agents.job;

import java.time.Duration;

/**
 * {@code app.agent-jobs.trigger.*} - read by the EVENT-TRIGGERS PR. Bound through
 * {@link AgentJobProperties#getTrigger()}; the property names are the field names here.
 */
public class AgentJobTriggerProperties {

    /** Master switch. Off means events are still published; nothing fires a job. */
    private boolean enabled = true;

    private int maxTriggersPerAgent = 10;

    /**
     * How many triggers ONE event may fan out to. Bounds the work done on
     * the publishing thread - a webhook request thread - which is why this
     * is a hard cap and not advisory.
     */
    private int maxTriggersPerEvent = 10;

    /**
     * The loop guard's BURST half. More than this many firings of ONE
     * trigger inside {@link #window} disables that trigger with {@code
     * disabled_reason = 'LOOP_GUARD_TRIPPED'} rather than letting it keep
     * spending. Both counts are SLIDING and are derived from the firing
     * ledger, never from a counter column (which loses increments under
     * concurrent firings).
     *
     * <p>This half alone is provably insufficient, which is why {@link
     * #maxFiresPerSustainedWindow} exists: 20 fires per 5 minutes is one per
     * 15 seconds, and the motivating cycle (an agent's generate-document
     * call whose completion event re-triggers that same agent) takes one LLM
     * turn plus a document render per iteration - comfortably longer than
     * 15s. The burst cap would therefore never trip on the exact loop it was
     * written for.
     */
    private int maxFiresPerWindow = 20;

    private Duration window = Duration.ofMinutes(5);

    /**
     * The loop guard's SUSTAINED half - the one that actually catches a slow
     * self-triggering cycle. More than this many firings of one trigger
     * inside {@link #sustainedWindow} disables it with the same {@code
     * LOOP_GUARD_TRIPPED} reason and the same diagnostic.
     *
     * <p>This is a SPEND BOUND, not a loop detector: no rate-based guard can
     * tell a genuinely busy trigger from a loop, so the honest contract is
     * "a self-triggering configuration costs at most this many jobs before
     * it is switched off and an operator is told". Raise it for a
     * legitimately high-volume trigger, having read that sentence.
     */
    private int maxFiresPerSustainedWindow = 200;

    private Duration sustainedWindow = Duration.ofHours(24);

    /**
     * TENANT-WIDE ceiling on trigger firings inside {@link #window}, across
     * ALL of the tenant's triggers. The per-trigger caps bound one trigger;
     * they do not bound a fan-out (one event matching up to {@link
     * #maxTriggersPerEvent} triggers, each with its own budget) or two
     * triggers looping through each other, each under its own cap. This
     * does: no configuration can spend faster than this many jobs per
     * window, full stop. The trigger that crosses the line is disabled with
     * the same {@code LOOP_GUARD_TRIPPED} reason and the same diagnostics
     * as the per-trigger caps - and if others keep firing, each is disabled
     * as it crosses too, which is exactly the conservative direction for a
     * spend guard.
     */
    private int maxTenantFiresPerWindow = 100;

    /**
     * TENANT-WIDE ceiling inside {@link #sustainedWindow}, across all
     * triggers - the tenant-level spend bound the sustained per-trigger cap
     * cannot provide against fan-out. Worst case per tenant per day is this
     * number, not {@code maxTriggersPerEvent * maxFiresPerSustainedWindow}.
     */
    private int maxTenantFiresPerSustainedWindow = 1000;

    /**
     * How long the ledger suppresses a repeat firing on an IDENTICAL event
     * payload for one trigger.
     *
     * <p>This is the duplicate-delivery guard for the eight channel event
     * types, whose {@code runId} is a fresh random UUID per publish
     * ({@code WorkflowEventPublisher#publishNewRun}) and therefore cannot be
     * deduplicated by the exact {@code (trigger_id, event_key)} unique
     * index. Upstream, {@code InboundMessageDedupService} is Redis SETNX and
     * documented fail-open, and two inbound paths (X DM polling, IMAP email
     * polling) do not call it at all - so without this a provider retry buys
     * a second billed job.
     *
     * <p>Suppressing on payload equality is acceptable precisely here
     * because a channel payload is {@code {channel, fromMasked, bodyLength}}
     * and that is the whole information content a trigger can act on: two
     * events with an identical payload produce two jobs that are identical
     * in every observable respect. Zero disables the check.
     */
    private Duration duplicateWindow = Duration.ofMinutes(5);

    /** Ceiling on a rendered goal template, so one event cannot mint a huge prompt. */
    private int maxGoalChars = 4000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxTriggersPerAgent() {
        return maxTriggersPerAgent;
    }

    public void setMaxTriggersPerAgent(int maxTriggersPerAgent) {
        this.maxTriggersPerAgent = maxTriggersPerAgent;
    }

    public int getMaxTriggersPerEvent() {
        return maxTriggersPerEvent;
    }

    public void setMaxTriggersPerEvent(int maxTriggersPerEvent) {
        this.maxTriggersPerEvent = maxTriggersPerEvent;
    }

    public int getMaxFiresPerWindow() {
        return maxFiresPerWindow;
    }

    public void setMaxFiresPerWindow(int maxFiresPerWindow) {
        this.maxFiresPerWindow = maxFiresPerWindow;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public int getMaxFiresPerSustainedWindow() {
        return maxFiresPerSustainedWindow;
    }

    public void setMaxFiresPerSustainedWindow(int maxFiresPerSustainedWindow) {
        this.maxFiresPerSustainedWindow = maxFiresPerSustainedWindow;
    }

    public Duration getSustainedWindow() {
        return sustainedWindow;
    }

    public void setSustainedWindow(Duration sustainedWindow) {
        this.sustainedWindow = sustainedWindow;
    }

    public int getMaxTenantFiresPerWindow() {
        return maxTenantFiresPerWindow;
    }

    public void setMaxTenantFiresPerWindow(int maxTenantFiresPerWindow) {
        this.maxTenantFiresPerWindow = maxTenantFiresPerWindow;
    }

    public int getMaxTenantFiresPerSustainedWindow() {
        return maxTenantFiresPerSustainedWindow;
    }

    public void setMaxTenantFiresPerSustainedWindow(int maxTenantFiresPerSustainedWindow) {
        this.maxTenantFiresPerSustainedWindow = maxTenantFiresPerSustainedWindow;
    }

    public Duration getDuplicateWindow() {
        return duplicateWindow;
    }

    public void setDuplicateWindow(Duration duplicateWindow) {
        this.duplicateWindow = duplicateWindow;
    }

    public int getMaxGoalChars() {
        return maxGoalChars;
    }

    public void setMaxGoalChars(int maxGoalChars) {
        this.maxGoalChars = maxGoalChars;
    }
}
