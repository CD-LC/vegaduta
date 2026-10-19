package com.agenticai.core.brief;

import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.brief.BriefApplyLedger.Binding;
import com.agenticai.core.brief.ProofSource.ProofSnapshot;
import com.agenticai.core.brief.ProofSource.ScorePoint;
import com.agenticai.core.brief.dto.TrustDtos.EvidenceLine;
import com.agenticai.core.brief.dto.TrustDtos.TrustChange;
import com.agenticai.core.brief.dto.TrustDtos.TrustProposal;
import com.agenticai.core.brief.model.Brief;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.workflow.dto.WorkflowGraphDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Earned autonomy (contract section 1): proposes, applies and reverses trust rungs.
 *
 * <p><b>What a rung is, mechanically.</b> Moving between rungs does four things to the running envoy and
 * ONE thing to the brief: every agent's autonomy tier is set to the rung's tier ({@link TrustRung#tier()},
 * read off the real enum); each workflow's graph is re-materialised so it carries the rung's APPROVAL
 * (and HUMAN_INPUT) nodes ({@link TrustGraphMaterializer}); the tenant approval policy is tightened for
 * {@code workflow.node.approval} when approval nodes exist (without that policy an approval node
 * auto-approves); and the quality gate is armed (TRUSTED, AUTONOMOUS) or removed (UNDER_WATCH). The brief
 * gets exactly one new {@code brief_version} (the same manifest with the new {@code trust.rung}) and one
 * {@code brief_trust_event} carrying the evidence. If any entity change fails, the ones already made are
 * reversed and nothing is recorded.
 *
 * <p><b>Deviation from the contract table, deliberately.</b> The approval policy is tenant-wide, so it is
 * only ever tightened here, never loosened: AUTONOMOUS does not switch the workspace's approval policy to
 * "audit only", because that would also relax hand-built workflows and other envoys. The per-agent tier
 * (FULLY_AUTONOMOUS never gates that agent's tool calls) and the removal of the envoy's own approval nodes
 * are what make AUTONOMOUS autonomous.
 *
 * <p><b>Evidence is never invented.</b> A promotion needs every line of {@link #evaluate} to hold, read from
 * a {@link ProofSource}; with no source connected there is no proposal. Promotion moves one rung at a time.
 * A person may lower the rung at any time; the system lowers it via {@link #demote}.
 */
@Service
public class TrustLadderService {

    private static final Logger log = LoggerFactory.getLogger(TrustLadderService.class);

    static final int WINDOW_DAYS = 7;
    static final int REAL_RUNS_THRESHOLD = 20;
    static final int MIN_RATED_TURNS = 10;
    static final double MIN_THUMBS_RATIO = 0.7;
    static final double DEFAULT_GATE_MIN_SCORE = 0.7;
    static final int GATE_MAX_RUN_AGE_HOURS = 24 * 7;

    private final BriefVersionStore store;
    private final BriefApplyLedger ledger;
    private final EnvoyPorts ports;
    private final ObjectProvider<ProofSource> proofSource;
    private final BriefTrustEventRepository eventRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public TrustLadderService(
        BriefVersionStore store,
        BriefApplyLedger ledger,
        EnvoyPorts ports,
        ObjectProvider<ProofSource> proofSource,
        BriefTrustEventRepository eventRepository,
        AuditLogService auditLogService,
        ObjectMapper objectMapper
    ) {
        this(store, ledger, ports, proofSource, eventRepository, auditLogService, objectMapper, Clock.systemUTC());
    }

    TrustLadderService(
        BriefVersionStore store,
        BriefApplyLedger ledger,
        EnvoyPorts ports,
        ObjectProvider<ProofSource> proofSource,
        BriefTrustEventRepository eventRepository,
        AuditLogService auditLogService,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this.store = store;
        this.ledger = ledger;
        this.ports = ports;
        this.proofSource = proofSource;
        this.eventRepository = eventRepository;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ================================================================== proposal

    /** {@code POST /trust/proposal}: what the next rung needs, line by line, and what it would change. */
    public TrustProposal proposal(UUID briefId) {
        BriefEntity entity = store.require(briefId);
        Brief brief = store.loadCurrent(entity);
        TrustRung current = TrustRung.parseOrLowest(entity.getRung());
        TrustRung target = current.up();
        ArrayNode noDiff = objectMapper.createArrayNode();

        if (!BriefEntity.APPLIED.equals(entity.getStatus())) {
            return new TrustProposal(false, current.name(), null,
                List.of(new EvidenceLine("applied", "The solution is running", false,
                    "Apply the solution first - trust is earned by what it does once it is running.")),
                noDiff, List.of());
        }
        if (target == null) {
            return new TrustProposal(false, current.name(), null, List.of(), noDiff, List.of());
        }
        Evidence evidence = gather(briefId, brief);
        List<EvidenceLine> lines = evidence.lines;
        // No plan support for eval runs: no proposal at all (the plan line explains why).
        boolean plan = evidence.snapshot != null && evidence.snapshot.planAllowsEval();
        boolean eligible = evidence.sourceConnected && plan && lines.stream().allMatch(EvidenceLine::met);
        String targetName = evidence.sourceConnected && plan ? target.name() : null;

        ArrayNode diff = BriefJsonPatch.diffAsArray(objectMapper.valueToTree(brief),
            objectMapper.valueToTree(BriefVersionStore.withRung(brief, target)));
        List<String> effects = describeEffects(brief, current, target, evidence.snapshot);
        if (eligible) {
            recordProposed(briefId, current, target, lines);
        }
        return new TrustProposal(eligible, current.name(), targetName, lines, diff, effects);
    }

    // ================================================================== change

    /**
     * {@code POST /trust/{rung}}. Up: one rung, only with full evidence (409 otherwise). Down: any lower rung, always allowed.
     */
    public TrustChange setRung(UUID briefId, TrustRung target) {
        BriefEntity entity = store.require(briefId);
        requireIdle(entity);
        Brief brief = store.loadCurrent(entity);
        TrustRung current = TrustRung.parseOrLowest(entity.getRung());
        String actor = BriefVersionStore.actor();
        if (target == current) {
            throw new BriefConflictException("This solution is already at " + current.name() + ".");
        }
        if (target.isAbove(current)) {
            if (target != current.up()) {
                throw new BriefConflictException("Trust is earned one rung at a time: next is " + current.up().name() + ".");
            }
            Evidence evidence = gather(briefId, brief);
            boolean plan = evidence.snapshot != null && evidence.snapshot.planAllowsEval();
            if (!evidence.sourceConnected || !plan || !evidence.lines.stream().allMatch(EvidenceLine::met)) {
                String unmet = evidence.lines.stream().filter(l -> !l.met()).map(EvidenceLine::detail).findFirst()
                    .orElse("The evidence is not there yet.");
                throw new BriefConflictException("Not enough evidence to move up yet: " + unmet);
            }
            return change(entity, brief, current, target, "PROMOTED", evidence.lines, evidence.snapshot, actor,
                "Promoted to " + target.name(), false);
        }
        List<EvidenceLine> lines = List.of(new EvidenceLine("manual", "Lowered by a person", true,
            "Lowered to " + target.name() + " by " + actor + "."));
        return change(entity, brief, current, target, "DEMOTED", lines, snapshotQuietly(briefId), actor,
            "Lowered to " + target.name(), true);
    }

    /**
     * Automatic demotion entry point (guardrail strike, gate breach): one rung down, approvals re-inserted, event
     * written with the evidence. Empty when there is nothing to lower (not applied, or already at the bottom).
     * The caller must have the tenant context set.
     */
    public Optional<TrustChange> demote(UUID briefId, String reason, Map<String, Object> evidence) {
        BriefEntity entity = store.require(briefId);
        TrustRung current = TrustRung.parseOrLowest(entity.getRung());
        TrustRung down = current.down();
        if (!BriefEntity.APPLIED.equals(entity.getStatus()) || down == null) {
            return Optional.empty();
        }
        requireIdle(entity);
        Brief brief = store.loadCurrent(entity);
        List<EvidenceLine> lines = new ArrayList<>();
        lines.add(new EvidenceLine("trigger", "Automatic demotion", false, reason));
        if (evidence != null) {
            evidence.forEach((k, v) -> lines.add(new EvidenceLine(k, k, false, String.valueOf(v))));
        }
        // The same snapshot a manual lowering uses, so the gate is re-armed as strictly whoever triggered it.
        return Optional.of(change(entity, brief, current, down, "DEMOTED", lines, snapshotQuietly(briefId), "system",
            "Lowered to " + down.name() + ": " + reason, true));
    }

    private TrustChange change(
        BriefEntity entity, Brief brief, TrustRung from, TrustRung to, String kind, List<EvidenceLine> lines,
        ProofSnapshot snapshot, String actor, String note, boolean lowering
    ) {
        UUID briefId = entity.getId();
        Map<String, Binding> bindings = ledger.bindings(briefId);
        Brief next = BriefVersionStore.withRung(brief, to);
        List<String> effects = new ArrayList<>();
        List<Runnable> undo = new ArrayList<>();
        try {
            applyRung(next, to, bindings, snapshot, effects, undo, lowering);
        } catch (RuntimeException e) {
            rollBack(undo);
            throw new BriefConflictException("The rung was not changed - " + BriefApplyService.messageOf(e)
                + " Everything already changed was put back.");
        }
        int version;
        try {
            version = store.appendAndMove(briefId, next, note, actor, from, to);
        } catch (RuntimeException e) {
            // The envoy was changed but the brief was not: put the envoy back so it never runs at a rung the brief
            // does not say it has.
            rollBack(undo);
            throw e;
        }
        // From here the version and rung are committed. A failed event write must not put the envoy back (that would
        // leave the brief claiming a rung the envoy is not at); the version note above carries the reason.
        try {
            eventRepository.save(new BriefTrustEventEntity(briefId, from.name(), to.name(), kind,
                evidenceJson(lines, snapshot, note), version, actor));
        } catch (RuntimeException e) {
            log.error("Brief {} moved {} -> {} (version {}) but its trust event could not be written", briefId, from, to,
                version, e);
        }
        try {
            auditLogService.record(actor, "BRIEF_" + kind, "brief", briefId.toString(), AuditOutcome.SUCCESS,
                from + " -> " + to);
        } catch (RuntimeException e) {
            log.warn("Could not audit the rung change of brief {}", briefId, e);
        }
        return new TrustChange(kind, from.name(), to.name(), version, effects, lines);
    }

    /**
     * Sets tiers, re-materialises workflow graphs, tightens the approval policy and arms/removes the gate for
     * {@code rung}. Every entity change registers its own reversal in {@code undo}.
     */
    void applyRung(
        Brief brief, TrustRung rung, Map<String, Binding> bindings, ProofSnapshot snapshot,
        List<String> effects, List<Runnable> undo
    ) {
        applyRung(brief, rung, bindings, snapshot, effects, undo, false);
    }

    /**
     * @param lenient true when lowering the rung: a protective change must not be blocked by one sibling entity that
     *     was deleted or edited into an invalid state, so an entity that cannot be changed is skipped and named in
     *     {@code effects} while everything else still comes down.
     */
    void applyRung(
        Brief brief, TrustRung rung, Map<String, Binding> bindings, ProofSnapshot snapshot,
        List<String> effects, List<Runnable> undo, boolean lenient
    ) {
        int agents = 0;
        for (Brief.AgentSpec a : brief.agents()) {
            Binding b = bindings.get(a.ref());
            if (b == null) {
                continue;
            }
            try {
                var before = ports.agentTier(b.entityId());
                ports.setAgentTier(b.entityId(), rung.tier());
                undo.add(() -> ports.setAgentTier(b.entityId(), before));
                agents++;
            } catch (RuntimeException e) {
                if (!lenient) {
                    throw e;
                }
                effects.add("Agent " + a.ref() + " could not be changed (" + BriefApplyService.messageOf(e) + ") and was left as it is.");
            }
        }
        effects.add(agents + " agent(s) set to " + rung.tier() + ".");

        int approvals = 0;
        int humanSteps = 0;
        for (Brief.WorkflowSpec w : brief.workflows()) {
            Binding b = bindings.get(w.ref());
            if (b == null) {
                continue;
            }
            try {
                WorkflowGraphDto live = ports.workflowGraph(b.entityId());
                WorkflowGraphDto next = TrustGraphMaterializer.materialise(live, w.ref(), rung, brief.trust());
                if (!next.equals(live)) {
                    ports.updateWorkflowGraph(b.entityId(), next);
                    undo.add(() -> ports.updateWorkflowGraph(b.entityId(), live));
                }
                approvals += TrustGraphMaterializer.approvalCount(next);
                humanSteps += TrustGraphMaterializer.humanInputCount(next);
            } catch (RuntimeException e) {
                if (!lenient) {
                    throw e;
                }
                effects.add("Workflow " + w.ref() + " could not be changed (" + BriefApplyService.messageOf(e) + ") and was left as it is.");
            }
        }
        effects.add(approvals + " approval step(s) and " + humanSteps + " always-a-person step(s) in the workflows.");

        String action = TrustGraphMaterializer.APPROVAL_ACTION_TYPE;
        if (approvals > 0 && !ports.approvalRequired(action)) {
            ports.setApprovalRequired(action, true);
            undo.add(() -> ports.setApprovalRequired(action, false));
            effects.add("Workflow approval steps now pause runs (workspace-wide policy, tightened only).");
        }

        Brief.EvalSet eval = brief.evalSet();
        Binding dataset = bindings.get(BriefApplyService.EVAL_REF);
        Binding agent = eval == null || eval.targetAgent() == null ? null : bindings.get(eval.targetAgent());
        if (dataset != null && agent != null) {
            try {
                // Capture the gate as it is, so a failure later puts it back exactly (an admin's own gate included).
                var prior = ports.gateState(agent.entityId());
                if (rung == TrustRung.UNDER_WATCH) {
                    ports.disarmGate(agent.entityId());
                    undo.add(() -> ports.restoreGate(agent.entityId(), prior));
                    effects.add("Quality gate removed; a baseline is collected again.");
                } else {
                    double min = gateMinScore(brief, snapshot);
                    ports.armGate(agent.entityId(), dataset.entityId(), min, GATE_MAX_RUN_AGE_HOURS);
                    undo.add(() -> ports.restoreGate(agent.entityId(), prior));
                    effects.add("Quality gate armed at " + String.format(Locale.ROOT, "%.2f", min)
                        + (rung == TrustRung.AUTONOMOUS ? " with automatic demotion on a breach." : "."));
                }
            } catch (RuntimeException e) {
                if (!lenient) {
                    throw e;
                }
                effects.add("The quality gate could not be changed (" + BriefApplyService.messageOf(e) + ").");
            }
        }
    }

    private void rollBack(List<Runnable> undo) {
        for (int i = undo.size() - 1; i >= 0; i--) {
            try {
                undo.get(i).run();
            } catch (RuntimeException e) {
                log.error("Could not reverse part of a failed rung change: {}", e.getMessage());
            }
        }
    }

    private void requireIdle(BriefEntity entity) {
        if (!BriefEntity.APPLIED.equals(entity.getStatus())) {
            throw new BriefConflictException("Only a running (applied) solution has a trust rung to change.");
        }
        if (ledger.runningJob(entity.getId(), clock.instant().minus(Duration.ofMinutes(30))).isPresent()) {
            throw new BriefConflictException("This solution is being applied right now. Try again when it finishes.");
        }
    }

    // ================================================================== evidence

    private record Evidence(boolean sourceConnected, ProofSnapshot snapshot, List<EvidenceLine> lines) {
    }

    private Evidence gather(UUID briefId, Brief brief) {
        ProofSource source = proofSource.getIfAvailable();
        if (source == null) {
            return new Evidence(false, null, List.of(new EvidenceLine("source", "Proof ledger", false,
                "The proof ledger is not connected yet, so there is nothing to base a promotion on.")));
        }
        ProofSnapshot snapshot;
        try {
            snapshot = source.snapshot(briefId, clock.instant());
        } catch (RuntimeException e) {
            log.warn("Brief {}: proof could not be read", briefId, e);
            return new Evidence(false, null, List.of(new EvidenceLine("source", "Proof ledger", false,
                "The proof ledger could not be read just now. Try again shortly.")));
        }
        return new Evidence(true, snapshot, evaluate(brief, snapshot, clock.instant()));
    }

    private ProofSnapshot snapshotQuietly(UUID briefId) {
        ProofSource source = proofSource.getIfAvailable();
        if (source == null) {
            return null;
        }
        try {
            return source.snapshot(briefId, clock.instant());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The promotion rules (contract section 1), each as one line the user sees. All must hold.
     * Order: plan, eval, guardrails, feedback.
     */
    List<EvidenceLine> evaluate(Brief brief, ProofSnapshot s, Instant now) {
        List<EvidenceLine> lines = new ArrayList<>();
        lines.add(new EvidenceLine("plan", "Your plan runs evals", s.planAllowsEval(),
            s.planAllowsEval() ? "Eval runs are available on your plan."
                : orElse(s.planNote(), "Eval runs are not available on your plan, so trust cannot be measured.")));

        Brief.EvalSet eval = brief.evalSet();
        // The bar judged here is the bar armed on promotion (baseline - noise floor included), or the ladder would promote
        // on one threshold and demote on another.
        double gate = gateMinScore(brief, s);
        lines.add(evalLine(eval != null, gate, s, now));

        lines.add(new EvidenceLine("guardrails", "No guardrail strikes", s.guardrailStrikes() == 0,
            s.guardrailStrikes() == 0 ? "No guardrail strike on any agent in this period."
                : s.guardrailStrikes() + " guardrail strike(s) in this period."));

        boolean ratedEnough = s.ratedTurns() >= MIN_RATED_TURNS;
        double ratio = ratedEnough ? (double) s.thumbsUp() / s.ratedTurns() : 0;
        boolean thumbsOk = ratedEnough && ratio >= MIN_THUMBS_RATIO;
        ProofSource.ExperimentResult ex = s.experiment();
        boolean experimentOk = ex != null && ex.significant() && ex.delta() > 0;
        String detail;
        if (thumbsOk) {
            detail = s.thumbsUp() + " of " + s.ratedTurns() + " rated turns were thumbs-up (" + Math.round(ratio * 100) + "%).";
        } else if (experimentOk) {
            detail = "Beat the previous version on thumbs-up by " + String.format(Locale.ROOT, "%+.2f", ex.delta())
                + (ex.noiseFloor() != null ? ", outside the noise floor of " + String.format(Locale.ROOT, "%.2f", ex.noiseFloor()) : "")
                + " (a finished experiment, statistically distinguishable from chance).";
        } else {
            detail = (ratedEnough
                ? s.thumbsUp() + " of " + s.ratedTurns() + " rated turns were thumbs-up (" + Math.round(ratio * 100) + "%, needs 70%)"
                : "Only " + s.ratedTurns() + " rated turn(s); needs " + MIN_RATED_TURNS + " with at least 70% thumbs-up")
                + ", and no finished experiment has clearly beaten the previous version.";
        }
        lines.add(new EvidenceLine("feedback", "People and experiments agree", thumbsOk || experimentOk, detail));
        return lines;
    }

    private static EvidenceLine evalLine(boolean hasEvalSet, double gate, ProofSnapshot s, Instant now) {
        String label = "Eval score holds above the gate";
        if (!hasEvalSet) {
            return new EvidenceLine("eval", label, false, "This solution has no eval set, so its quality cannot be measured.");
        }
        List<ScorePoint> history = s.scoreHistory();
        if (history.isEmpty()) {
            return new EvidenceLine("eval", label, false, "No eval run has been recorded yet.");
        }
        Instant windowStart = now.minus(Duration.ofDays(WINDOW_DAYS));
        boolean fullWindow = s.observedSince() != null && !s.observedSince().isAfter(windowStart);
        boolean enoughRuns = s.realRuns() >= REAL_RUNS_THRESHOLD;
        List<ScorePoint> judged = fullWindow ? history.stream().filter(p -> !p.at().isBefore(windowStart)).toList() : history;
        double latest = history.get(history.size() - 1).score();
        double worst = judged.stream().mapToDouble(ScorePoint::score).min().orElse(latest);
        String gateText = String.format(Locale.ROOT, "%.2f", gate);
        if (!fullWindow && !enoughRuns) {
            return new EvidenceLine("eval", label, false, "Not enough observed yet: needs " + WINDOW_DAYS + " days or "
                + REAL_RUNS_THRESHOLD + " real runs (" + s.realRuns() + " so far).");
        }
        if (latest < gate) {
            return new EvidenceLine("eval", label, false, "Latest eval score " + String.format(Locale.ROOT, "%.2f", latest)
                + " is below the gate of " + gateText + ".");
        }
        if (worst < gate) {
            return new EvidenceLine("eval", label, false, "An eval score of " + String.format(Locale.ROOT, "%.2f", worst)
                + " in this period was below the gate of " + gateText + ".");
        }
        return new EvidenceLine("eval", label, true, "Latest eval score " + String.format(Locale.ROOT, "%.2f", latest)
            + " (gate " + gateText + "); " + judged.size() + " eval run(s) in this period, none below the gate.");
    }

    /**
     * {@code max(evalSet.gate.minScore, baseline - noiseFloor)}. The baseline term applies only when a noise floor was
     * actually measured; without one the manifest's own minimum stands.
     */
    static double gateMinScore(Brief brief, ProofSnapshot snapshot) {
        double base = brief.evalSet() != null && brief.evalSet().gate() != null && brief.evalSet().gate().minScore() != null
            ? brief.evalSet().gate().minScore() : DEFAULT_GATE_MIN_SCORE;
        if (snapshot != null && snapshot.baseline() != null && snapshot.noiseFloor() != null) {
            base = Math.max(base, snapshot.baseline() - snapshot.noiseFloor());
        }
        return Math.min(1.0, Math.max(0.0, base));
    }

    // ================================================================== effects, events

    private List<String> describeEffects(Brief brief, TrustRung from, TrustRung to, ProofSnapshot snapshot) {
        List<String> effects = new ArrayList<>();
        effects.add("Agents move from " + from.tier() + " to " + to.tier() + ".");
        int before = 0;
        int after = 0;
        for (Brief.WorkflowSpec w : brief.workflows()) {
            before += TrustGraphMaterializer.approvalCount(
                TrustGraphMaterializer.materialise(w.graph(), w.ref(), from, brief.trust()));
            after += TrustGraphMaterializer.approvalCount(
                TrustGraphMaterializer.materialise(w.graph(), w.ref(), to, brief.trust()));
        }
        effects.add("Approval steps go from " + before + " to " + after + ".");
        if (brief.evalSet() != null) {
            effects.add("The quality gate is armed at " + String.format(Locale.ROOT, "%.2f", gateMinScore(brief, snapshot))
                + (to == TrustRung.AUTONOMOUS ? ", and a breach lowers the rung automatically." : "."));
        }
        return effects;
    }

    private void recordProposed(UUID briefId, TrustRung from, TrustRung to, List<EvidenceLine> lines) {
        try {
            List<BriefTrustEventEntity> events = eventRepository.findByBriefIdOrderByCreatedAtDesc(briefId);
            boolean repeat = !events.isEmpty() && "PROPOSED".equals(events.get(0).getKind())
                && to.name().equals(events.get(0).getToRung());
            if (!repeat) {
                eventRepository.save(new BriefTrustEventEntity(briefId, from.name(), to.name(), "PROPOSED",
                    evidenceJson(lines, null, null), null, BriefVersionStore.actor()));
            }
        } catch (RuntimeException e) {
            log.warn("Could not record the trust proposal for brief {}", briefId, e);
        }
    }

    private String evidenceJson(List<EvidenceLine> lines, ProofSnapshot snapshot, String note) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", lines);
        if (note != null) {
            body.put("note", note);
        }
        if (snapshot != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("baseline", snapshot.baseline());
            s.put("noiseFloor", snapshot.noiseFloor());
            s.put("realRuns", snapshot.realRuns());
            s.put("guardrailStrikes", snapshot.guardrailStrikes());
            s.put("ratedTurns", snapshot.ratedTurns());
            s.put("thumbsUp", snapshot.thumbsUp());
            s.put("observedSince", snapshot.observedSince() == null ? null : snapshot.observedSince().toString());
            s.put("evalRuns", snapshot.scoreHistory().size());
            body.put("snapshot", s);
        }
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /** Current tenant subject, for callers that need an actor label. */
    static String currentActor() {
        return TenantContext.current().map(TenantContext::getSubject).orElse("system");
    }

    private static String orElse(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
