package com.agenticai.core.brief;

import com.agenticai.core.brief.BriefApplyLedger.Binding;
import com.agenticai.core.brief.LedgerMath.VersionStamp;
import com.agenticai.core.brief.ProofData.EvalRunPoint;
import com.agenticai.core.brief.ProofData.ExperimentRow;
import com.agenticai.core.brief.ProofData.Thumbs;
import com.agenticai.core.brief.dto.LedgerDtos;
import com.agenticai.core.brief.dto.LedgerDtos.Baseline;
import com.agenticai.core.brief.dto.LedgerDtos.Comparison;
import com.agenticai.core.brief.dto.LedgerDtos.ExperimentView;
import com.agenticai.core.brief.dto.LedgerDtos.Ledger;
import com.agenticai.core.brief.dto.LedgerDtos.LedgerSummary;
import com.agenticai.core.brief.dto.LedgerDtos.RunView;
import com.agenticai.core.brief.dto.LedgerDtos.ThumbsView;
import com.agenticai.core.brief.dto.LedgerDtos.TrustEventView;
import com.agenticai.core.brief.dto.LedgerDtos.VariantView;
import com.agenticai.core.brief.model.Brief;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The proof ledger (contract section 2, {@code GET /ledger}) and the {@link ProofSource} the trust
 * ladder decides from. One class, because they are the same evidence read two ways: the ledger
 * shows it to the person, the snapshot hands it to the ladder.
 *
 * <p><b>Measured or absent.</b> Every number here comes from a finished eval run, a thumbs row, an
 * experiment's metrics or a recorded guardrail strike. When the plan has no eval runs the ledger is
 * {@code LOCKED} with the reason and no numbers at all; when nothing has been measured yet it is
 * {@code COLLECTING}, not zero.
 *
 * <p><b>A baseline run on apply.</b> The eval dataset is created by the apply engine from {@code
 * brief.evalSet}; as a {@link BriefAppliedHook} this service starts one eval run of it after each
 * successful apply (on a plan that has eval runs, when none is already running), so every version
 * gets a score and the first one is the baseline.
 *
 * <p><b>Which version a score belongs to.</b> An eval run records the agent's own version, not the
 * brief's, so a run is placed on the newest brief version saved before it started. A rollback
 * (which repoints the brief at an older manifest without saving a new one) is therefore not visible
 * in that column.
 */
@Service
public class ProofLedgerService implements ProofSource, BriefAppliedHook {

    private static final Logger log = LoggerFactory.getLogger(ProofLedgerService.class);

    /**
     * Experiments started by {@code POST /experiments} are named with this prefix + the brief id,
     * so they can be told apart.
     */
    static final String EXPERIMENT_PREFIX = "brief:";

    static final String PLAN_LOCKED_REASON =
            "Eval runs are not part of your plan, so no scores are measured. Upgrade to see this solution's proof ledger.";

    private final BriefVersionStore store;
    private final BriefApplyLedger applyLedger;
    private final EnvoyPorts ports;
    private final ProofData data;
    private final BriefTrustEventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ProofLedgerService(
            BriefVersionStore store,
            BriefApplyLedger applyLedger,
            EnvoyPorts ports,
            ProofData data,
            BriefTrustEventRepository eventRepository,
            ObjectMapper objectMapper) {
        this(store, applyLedger, ports, data, eventRepository, objectMapper, Clock.systemUTC());
    }

    ProofLedgerService(
            BriefVersionStore store,
            BriefApplyLedger applyLedger,
            EnvoyPorts ports,
            ProofData data,
            BriefTrustEventRepository eventRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.store = store;
        this.applyLedger = applyLedger;
        this.ports = ports;
        this.data = data;
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ================================================================== the ledger

    public Ledger ledger(UUID briefId) {
        BriefEntity entity = store.require(briefId);
        List<TrustEventView> trustEvents = trustEvents(briefId);
        String id = briefId.toString();
        String rung = entity.getRung();
        if (!BriefEntity.APPLIED.equals(entity.getStatus())) {
            return empty(
                    id,
                    LedgerDtos.NOT_APPLIED,
                    "Nothing has been measured: the solution is not running. Apply it first.",
                    rung,
                    trustEvents);
        }
        if (!ports.planAllowsEval()) {
            return empty(id, LedgerDtos.LOCKED, PLAN_LOCKED_REASON, rung, trustEvents);
        }
        Brief brief = store.loadCurrent(entity);
        Map<String, Binding> bindings = applyLedger.bindings(briefId);
        Binding dataset = bindings.get(BriefApplyService.EVAL_REF);
        Brief.EvalSet eval = brief.evalSet();
        Binding target =
                eval == null || eval.targetAgent() == null
                        ? null
                        : bindings.get(eval.targetAgent());
        if (dataset == null || target == null) {
            return empty(
                    id,
                    LedgerDtos.NO_EVAL_SET,
                    "This solution has no eval set that could be created, so its quality is not measured.",
                    rung,
                    trustEvents);
        }

        List<EvalRunPoint> points = data.evalRuns(dataset.entityId());
        Double floor = noiseFloorQuietly(target.entityId(), dataset.entityId());
        List<VersionStamp> versions = versionStamps(briefId);
        List<RunView> runs = LedgerMath.runs(points, versions, floor);
        Baseline baseline =
                runs.isEmpty()
                        ? null
                        : new Baseline(
                                runs.get(0).runId(),
                                runs.get(0).score(),
                                runs.get(0).at(),
                                runs.get(0).version());

        List<String> agentIds = agentIds(bindings);
        Thumbs thumbs = data.thumbs(agentIds, entity.getCreatedAt());
        ThumbsView thumbsView =
                new ThumbsView(
                        thumbs.up(),
                        thumbs.down(),
                        thumbs.rated(),
                        LedgerMath.ratio(thumbs.up(), thumbs.rated()));

        String state = runs.isEmpty() ? LedgerDtos.COLLECTING : LedgerDtos.READY;
        String reason =
                runs.isEmpty()
                        ? "The baseline eval run has not finished yet - nothing is scored until it does."
                        : null;
        return new Ledger(
                id,
                state,
                reason,
                rung,
                baseline,
                floor,
                runs,
                thumbsView,
                experimentViews(briefId, bindings),
                trustEvents);
    }

    /**
     * The summary for a brief's detail ({@code full}: noise floor measured, so the last delta can
     * be called significant) or its list row (light: one query, no floor, so a delta is never
     * called significant).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public LedgerSummary summary(UUID briefId, boolean full) {
        if (full) {
            Ledger l = ledger(briefId);
            RunView last = l.runs().isEmpty() ? null : l.runs().get(l.runs().size() - 1);
            return new LedgerSummary(
                    l.state(),
                    l.reason(),
                    l.baseline() == null ? null : l.baseline().score(),
                    last == null ? null : last.score(),
                    last == null ? null : last.deltaVsPrev(),
                    last != null && last.significant(),
                    l.runs().size(),
                    l.noiseFloor());
        }
        BriefEntity entity = store.require(briefId);
        if (!BriefEntity.APPLIED.equals(entity.getStatus())) {
            return new LedgerSummary(
                    LedgerDtos.NOT_APPLIED,
                    "Nothing has been measured: the solution is not running.",
                    null,
                    null,
                    null,
                    false,
                    0,
                    null);
        }
        if (!ports.planAllowsEval()) {
            return new LedgerSummary(
                    LedgerDtos.LOCKED, PLAN_LOCKED_REASON, null, null, null, false, 0, null);
        }
        Brief brief = store.loadCurrent(entity);
        Map<String, Binding> bindings = applyLedger.bindings(briefId);
        Binding dataset = bindings.get(BriefApplyService.EVAL_REF);
        Brief.EvalSet eval = brief.evalSet();
        if (dataset == null
                || eval == null
                || eval.targetAgent() == null
                || bindings.get(eval.targetAgent()) == null) {
            return new LedgerSummary(
                    LedgerDtos.NO_EVAL_SET,
                    "This solution has no eval set, so its quality is not measured.",
                    null,
                    null,
                    null,
                    false,
                    0,
                    null);
        }
        List<RunView> runs =
                LedgerMath.runs(data.evalRuns(dataset.entityId()), versionStamps(briefId), null);
        if (runs.isEmpty()) {
            return new LedgerSummary(
                    LedgerDtos.COLLECTING,
                    "The baseline eval run has not finished yet.",
                    null,
                    null,
                    null,
                    false,
                    0,
                    null);
        }
        RunView last = runs.get(runs.size() - 1);
        return new LedgerSummary(
                LedgerDtos.READY,
                null,
                runs.get(0).score(),
                last.score(),
                last.deltaVsPrev(),
                false,
                runs.size(),
                null);
    }

    private static Ledger empty(
            String id, String state, String reason, String rung, List<TrustEventView> events) {
        return new Ledger(id, state, reason, rung, null, null, List.of(), null, List.of(), events);
    }

    private List<ExperimentView> experimentViews(UUID briefId, Map<String, Binding> bindings) {
        List<ExperimentView> out = new ArrayList<>();
        for (Map.Entry<String, Binding> b : bindings.entrySet()) {
            if (!"AGENT".equals(b.getValue().kind())) {
                continue;
            }
            for (ExperimentRow row : data.experiments(b.getValue().entityId())) {
                Comparison comparison = ownedByBrief(row, briefId) ? LedgerMath.compare(row) : null;
                out.add(
                        new ExperimentView(
                                row.id(),
                                b.getKey(),
                                row.agentId(),
                                row.name(),
                                row.status(),
                                row.createdAt(),
                                variant(row.control()),
                                variant(row.treatment()),
                                comparison));
            }
        }
        out.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
        return out;
    }

    private static VariantView variant(ProofData.Variant v) {
        return v == null
                ? null
                : new VariantView(
                        v.agentId(),
                        v.callCount(),
                        v.feedbackCount(),
                        v.thumbsUpRatePct(),
                        v.errorRatePct());
    }

    private List<TrustEventView> trustEvents(UUID briefId) {
        List<BriefTrustEventEntity> rows =
                new ArrayList<>(eventRepository.findByBriefIdOrderByCreatedAtDesc(briefId));
        Collections.reverse(rows);
        List<TrustEventView> out = new ArrayList<>();
        for (BriefTrustEventEntity e : rows) {
            out.add(
                    new TrustEventView(
                            e.getKind(),
                            e.getFromRung(),
                            e.getToRung(),
                            e.getVersion(),
                            e.getActor(),
                            e.getCreatedAt(),
                            evidence(e.getEvidenceJson())));
        }
        return out;
    }

    private JsonNode evidence(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    // ================================================================== the trust ladder's
    // evidence

    @Override
    public ProofSnapshot snapshot(UUID briefId, Instant now) {
        BriefEntity entity = store.require(briefId);
        Brief brief = store.loadCurrent(entity);
        Map<String, Binding> bindings = applyLedger.bindings(briefId);
        Instant since = observedSince(entity);
        List<String> agentIds = agentIds(bindings);

        boolean plan = ports.planAllowsEval();
        List<ScorePoint> history = List.of();
        Double baseline = null;
        Double floor = null;
        if (plan) {
            Binding dataset = bindings.get(BriefApplyService.EVAL_REF);
            Brief.EvalSet eval = brief.evalSet();
            Binding target =
                    eval == null || eval.targetAgent() == null
                            ? null
                            : bindings.get(eval.targetAgent());
            if (dataset != null && target != null) {
                List<EvalRunPoint> all = data.evalRuns(dataset.entityId());
                if (!all.isEmpty()) {
                    baseline = all.get(0).score();
                }
                history =
                        all.stream()
                                .filter(r -> !r.at().isBefore(since))
                                .map(r -> new ScorePoint(r.at(), r.score()))
                                .toList();
                floor = noiseFloorQuietly(target.entityId(), dataset.entityId());
            }
        }
        Thumbs thumbs = data.thumbs(agentIds, since);
        return new ProofSnapshot(
                plan,
                plan
                        ? null
                        : "Eval runs are not available on your plan, so trust cannot be measured.",
                since,
                history,
                data.realRuns(agentIds, since),
                data.strikes(briefId, since),
                (int) thumbs.rated(),
                (int) thumbs.up(),
                latestExperiment(briefId, bindings, since),
                baseline,
                floor);
    }

    private static boolean isOpen(String status) {
        return status == null
                || "RUNNING".equalsIgnoreCase(status)
                || "DRAFT".equalsIgnoreCase(status);
    }

    /**
     * The newest measurable experiment this brief started (running version against an earlier one),
     * or null.
     */
    private ExperimentResult latestExperiment(
            UUID briefId, Map<String, Binding> bindings, Instant since) {
        ExperimentRow newest = null;
        Comparison newestComparison = null;
        for (Binding b : bindings.values()) {
            if (!"AGENT".equals(b.kind())) {
                continue;
            }
            for (ExperimentRow row : data.experiments(b.entityId())) {
                // Evidence for the rung it is at now: begun since the rung did, and finished (a
                // running experiment
                // re-tested on every look would inflate false positives).
                if (!ownedByBrief(row, briefId)
                        || row.createdAt().isBefore(since)
                        || isOpen(row.status())) {
                    continue;
                }
                Comparison c = LedgerMath.compare(row);
                if (c != null && (newest == null || row.createdAt().isAfter(newest.createdAt()))) {
                    newest = row;
                    newestComparison = c;
                }
            }
        }
        return newestComparison == null
                ? null
                : new ExperimentResult(
                        newestComparison.significant(),
                        newestComparison.delta(),
                        null,
                        newestComparison.summary());
    }

    /**
     * When the envoy began collecting evidence at its CURRENT rung: the later of its last
     * successful apply and its last rung change (promotion or demotion); the brief's creation if
     * neither happened.
     */
    Instant observedSince(BriefEntity entity) {
        Instant since = entity.getCreatedAt();
        Optional<BriefApplyLedger.JobView> job = applyLedger.latestSuccessfulJob(entity.getId());
        if (job.isPresent()
                && job.get().startedAt() != null
                && job.get().startedAt().isAfter(since)) {
            since = job.get().startedAt();
        }
        for (BriefTrustEventEntity e :
                eventRepository.findByBriefIdOrderByCreatedAtDesc(entity.getId())) {
            if (("PROMOTED".equals(e.getKind()) || "DEMOTED".equals(e.getKind()))
                    && e.getCreatedAt().isAfter(since)) {
                since = e.getCreatedAt();
                break;
            }
        }
        return since;
    }

    // ================================================================== baseline run

    /** {@link BriefAppliedHook}: one eval run of the freshly applied version, so it has a score. */
    @Override
    public void applied(UUID briefId) {
        if (!ports.planAllowsEval()) {
            return;
        }
        BriefEntity entity = store.require(briefId);
        Brief brief = store.loadCurrent(entity);
        Map<String, Binding> bindings = applyLedger.bindings(briefId);
        Binding dataset = bindings.get(BriefApplyService.EVAL_REF);
        Brief.EvalSet eval = brief.evalSet();
        Binding target =
                eval == null || eval.targetAgent() == null
                        ? null
                        : bindings.get(eval.targetAgent());
        if (dataset == null || target == null) {
            return;
        }
        String run = data.startEvalRun(target.entityId(), dataset.entityId());
        if (run == null) {
            log.info(
                    "Brief {}: an eval run is already in progress, not starting another after this apply",
                    briefId);
        } else {
            log.info("Brief {}: started eval run {} after apply", briefId, run);
        }
    }

    // ================================================================== strikes

    /**
     * Records a guardrail strike on one of the brief's agents (read back by {@link #snapshot} as
     * evidence).
     */
    void recordStrike(UUID briefId, String agentId, String detail) {
        data.recordStrike(briefId, agentId, detail);
    }

    /** Timestamp source shared with the watcher so a test can pin "now". */
    Instant now() {
        return clock.instant();
    }

    // ================================================================== helpers

    static boolean ownedByBrief(ExperimentRow row, UUID briefId) {
        return row.name() != null && row.name().startsWith(EXPERIMENT_PREFIX + briefId);
    }

    private Double noiseFloorQuietly(String agentId, String datasetId) {
        try {
            return data.noiseFloor(agentId, datasetId);
        } catch (RuntimeException e) {
            log.warn("Noise floor unavailable for dataset {}: {}", datasetId, e.getMessage());
            return null;
        }
    }

    private List<VersionStamp> versionStamps(UUID briefId) {
        return store.versions(briefId).stream()
                .map(v -> new VersionStamp(v.getVersion(), v.getCreatedAt()))
                .toList();
    }

    static List<String> agentIds(Map<String, Binding> bindings) {
        return bindings.values().stream()
                .filter(b -> "AGENT".equals(b.kind()))
                .map(Binding::entityId)
                .toList();
    }
}
