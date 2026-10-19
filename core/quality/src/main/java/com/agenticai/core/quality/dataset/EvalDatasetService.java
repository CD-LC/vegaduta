package com.agenticai.core.quality.dataset;

import com.agenticai.core.chat.LlmCallLogService;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD + validation for {@link EvalDataset} / {@link EvalDatasetCase}.
 *
 * <p>Access posture: PREMIUM-plan gating happens in {@code EvalDatasetController} (mirroring {@code
 * QualityController#assertPremiumPlan}); per-resource ownership checks via {@code
 * ResourceAccessService} are NOT applied, because {@code core/sharing} is not on this module's
 * dependency list and adding the edge is outside this workstream's file ownership. That matches the
 * existing posture of this module exactly - {@code QualityController} reads any agent's quality
 * records with no ownership check either - so datasets are no more open than the quality surface
 * they extend. Tightening BOTH to {@code requireAccess(VIEWER/EDITOR)} together is a recorded
 * follow-up.
 *
 * <p>Every read validates the dataset's {@code agentId} against the path's agent id, so a dataset
 * can never be read or mutated through another agent's URL - cheap, and it keeps deep links honest.
 */
@Service
public class EvalDatasetService {

    /**
     * Hard bound on cases per dataset. A run costs N x (1 replay + up to 6 judge LLM calls),
     * serially - 100 cases is already a ~700-call, many-minutes run on the single bounded executor.
     * A bigger suite is a sign the dataset should be split per concern, not that the cap should
     * grow.
     */
    static final int MAX_CASES_PER_DATASET = 100;

    /** Sanity bound per expected-tools assertion list. */
    static final int MAX_EXPECTED_TOOLS = 20;

    /**
     * Shared, thread-safe mapper for the {@code expected_tools} JSON-array column - same
     * static-mapper pattern as {@code AgentChatService.STRUCTURED_PARAMS_MAPPER} (reads only, no
     * config).
     */
    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private static final Logger log = LoggerFactory.getLogger(EvalDatasetService.class);

    /**
     * Where the seed endpoint finds the golden cases. The former {@code core/eval} module is now
     * merged into this module as {@code com.agenticai.core.quality.eval}, so its {@code
     * golden-dataset.json} ships at the classpath root of this very jar - the single source of
     * truth (the hand-maintained duplicate under {@code src/main/resources/eval/} was removed with
     * the merge). {@code GoldenSeedTest} pins the file's shape so a stale/malformed edit fails the
     * free CI job.
     */
    static final String[] GOLDEN_DATASET_RESOURCES = {
        "/golden-dataset.json"
    };

    /**
     * Name the seed endpoint gives the created dataset; also the per-agent idempotency key (409 on
     * repeat). The {@code (core/eval)} suffix is kept verbatim on purpose: agents already seeded
     * before the eval module merged into this one carry datasets under this exact name, and
     * renaming it would silently defeat the 409 dedup for them.
     */
    static final String GOLDEN_DATASET_NAME = "VegaDuta golden set (core/eval)";

    /**
     * Whether a case's {@code grader_spec} may be a trajectory spec ({@code
     * app.quality.trajectory-eval.enabled}, default false). Field-injected so the constructor stays
     * as tests build it.
     */
    @Value("${app.quality.trajectory-eval.enabled:false}")
    boolean trajectoryEvalEnabled = false;

    private final EvalDatasetRepository datasetRepository;
    private final EvalDatasetCaseRepository caseRepository;
    private final EvalRunRepository runRepository;
    private final PersistedTurnLookup persistedTurns;
    private final EvalTraceLinks traceLinks;

    /**
     * @param llmCallLogService core/chat's durable per-call log (already a declared dependency of
     *     this module) - the read-side source of the replayed turn's trace id, via {@code
     *     LlmCallLogService#mostRecentTraceIdForSession}. {@link ObjectProvider} so a module-scoped
     *     test slice without core/chat's beans still constructs this service.
     * @param applicationContext used ONLY for the best-effort reflective lookup of a persisted
     *     Playground turn in core/rl (see {@link #importTurnCase}) - same
     *     runtime-lookup-against-the-merged-context trick {@link ReflectiveAgentChatTurnReplayer}
     *     already uses, for the same pom-boundary reason.
     * @param langfuseHost with {@code otlpTracesEndpoint}, {@code langfuseAuthHeader} and {@code
     *     langfuseProjectId}: the Langfuse trace-link settings, see {@link EvalTraceLinks}.
     */
    public EvalDatasetService(
            EvalDatasetRepository datasetRepository,
            EvalDatasetCaseRepository caseRepository,
            EvalRunRepository runRepository,
            ObjectProvider<LlmCallLogService> llmCallLogService,
            ApplicationContext applicationContext,
            @Value("${LANGFUSE_HOST:}") String langfuseHost,
            @Value(
                            "${management.otlp.tracing.endpoint:http://langfuse-web:3000/api/public/otel/v1/traces}")
                    String otlpTracesEndpoint,
            @Value("${LANGFUSE_OTEL_AUTH_HEADER:}") String langfuseAuthHeader,
            @Value("${LANGFUSE_INIT_PROJECT_ID:}") String langfuseProjectId) {
        this.datasetRepository = datasetRepository;
        this.caseRepository = caseRepository;
        this.runRepository = runRepository;
        this.persistedTurns = new PersistedTurnLookup(applicationContext);
        this.traceLinks =
                new EvalTraceLinks(
                        llmCallLogService,
                        langfuseHost,
                        otlpTracesEndpoint,
                        langfuseAuthHeader,
                        langfuseProjectId);
    }

    // ------------------------------------------------------------------
    // Datasets
    // ------------------------------------------------------------------

    public List<EvalDataset> listDatasets(UUID agentId) {
        return datasetRepository.findByAgentIdOrderByCreatedAtDesc(agentId);
    }

    public long countCases(UUID datasetId) {
        return caseRepository.countByDatasetId(datasetId);
    }

    @Transactional
    public EvalDataset createDataset(
            UUID agentId,
            String name,
            String description,
            Double passThreshold,
            Boolean toolExecutionEnabled) {
        String validName = EvalCaseInputs.requireName(name);
        double threshold = EvalCaseInputs.normalizeThreshold(passThreshold);
        String createdBy = TenantContext.current().map(TenantContext::getSubject).orElse(null);
        EvalDataset dataset =
                new EvalDataset(
                        agentId,
                        validName,
                        description,
                        threshold,
                        Boolean.TRUE.equals(toolExecutionEnabled),
                        createdBy);
        // Content hash over the (empty) case set - see DatasetContentHash.
        // Computing it eagerly keeps "created but never run" datasets
        // indistinguishable from "edited" ones for the gate's staleness read.
        dataset.markContentHash(DatasetContentHash.of(dataset, List.of()));
        return datasetRepository.save(dataset);
    }

    /**
     * The dataset by id with no agent check, or empty - for a caller that holds only the dataset id
     * (the brief envoy finding the agent a dataset it created belongs to). Other areas use this
     * instead of reaching into {@link EvalDatasetRepository}.
     */
    public Optional<EvalDataset> findDataset(UUID datasetId) {
        return datasetRepository.findById(datasetId);
    }

    /** Loads a dataset, 404ing on absence OR on an agent-id mismatch (see class javadoc). */
    public EvalDataset requireDataset(UUID agentId, UUID datasetId) {
        EvalDataset dataset =
                datasetRepository
                        .findById(datasetId)
                        .orElseThrow(() -> new EvalDatasetNotFoundException(datasetId));
        if (!dataset.getAgentId().equals(agentId)) {
            // Mismatch is reported as NOT FOUND, not FORBIDDEN - revealing
            // "exists, but under a different agent" would leak existence
            // across deep links for no benefit.
            throw new EvalDatasetNotFoundException(datasetId);
        }
        return dataset;
    }

    @Transactional
    public EvalDataset updateDataset(
            UUID agentId,
            UUID datasetId,
            String name,
            String description,
            Double passThreshold,
            Boolean toolExecutionEnabled) {
        EvalDataset dataset = requireDataset(agentId, datasetId);
        dataset.updateDefinition(
                EvalCaseInputs.requireName(name),
                description,
                passThreshold != null
                        ? EvalCaseInputs.normalizeThreshold(passThreshold)
                        : dataset.getPassThreshold(),
                toolExecutionEnabled != null
                        ? toolExecutionEnabled
                        : dataset.isToolExecutionEnabled());
        // Threshold/tool-flag changes alter what a run measures - the hash
        // covers them, so a definition edit invalidates prior runs for the
        // gate exactly like a case edit does.
        refreshContentHash(dataset);
        return datasetRepository.save(dataset);
    }

    /**
     * Deletes a dataset and (via the V213 ON DELETE CASCADEs) its cases, runs and results. Refused
     * while a run is live: the worker holds row references it would otherwise lose mid-flight to
     * the cascade, and a 409 telling the user to cancel first is a better outcome than a
     * half-written run erroring in the background.
     */
    @Transactional
    public void deleteDataset(UUID agentId, UUID datasetId) {
        EvalDataset dataset = requireDataset(agentId, datasetId);
        if (runRepository.existsByDatasetIdAndStatusIn(
                datasetId, List.of(EvalRunStatus.QUEUED, EvalRunStatus.RUNNING))) {
            throw new EvalRunConflictException(
                    "This dataset has a run in progress - cancel it (or let it finish) before deleting.");
        }
        datasetRepository.delete(dataset);
    }

    // ------------------------------------------------------------------
    // Cases
    // ------------------------------------------------------------------

    public List<EvalDatasetCase> listCases(UUID datasetId) {
        return caseRepository.findByDatasetIdOrderByPositionAscCreatedAtAsc(datasetId);
    }

    @Transactional
    public EvalDatasetCase addCase(
            UUID agentId,
            UUID datasetId,
            String userMessage,
            String expectedOutput,
            List<String> expectedTools,
            Integer position) {
        EvalDataset dataset = requireDataset(agentId, datasetId);
        long existing = caseRepository.countByDatasetId(datasetId);
        if (existing >= MAX_CASES_PER_DATASET) {
            throw new IllegalArgumentException(
                    "This dataset already has "
                            + existing
                            + " cases (max "
                            + MAX_CASES_PER_DATASET
                            + "). Split it into focused datasets instead of growing one monolith.");
        }
        int resolvedPosition = position != null ? position : (int) existing;
        EvalDatasetCase saved =
                caseRepository.save(
                        new EvalDatasetCase(
                                dataset.getId(),
                                EvalCaseInputs.requireUserMessage(userMessage),
                                EvalCaseInputs.blankToNull(expectedOutput),
                                EvalCaseInputs.serializeExpectedTools(expectedTools),
                                resolvedPosition));
        refreshContentHash(dataset);
        return saved;
    }

    @Transactional
    public EvalDatasetCase updateCase(
            UUID agentId,
            UUID datasetId,
            UUID caseId,
            String userMessage,
            String expectedOutput,
            List<String> expectedTools,
            Integer position) {
        EvalDataset dataset = requireDataset(agentId, datasetId);
        EvalDatasetCase datasetCase = requireCase(datasetId, caseId);
        datasetCase.updateCase(
                EvalCaseInputs.requireUserMessage(userMessage),
                EvalCaseInputs.blankToNull(expectedOutput),
                EvalCaseInputs.serializeExpectedTools(expectedTools),
                position != null ? position : datasetCase.getPosition());
        EvalDatasetCase saved = caseRepository.save(datasetCase);
        refreshContentHash(dataset);
        return saved;
    }

    /**
     * Sets (or, with a blank spec, clears) the case's deterministic code grader (V375). The spec is
     * validated by {@code CodeGrader#parse} - a bad regex or unsupported schema keyword is a 400
     * here rather than a silent pass at run time. Changes what a run measures, so the dataset
     * content hash is refreshed (prior runs become stale for the gate, as with any other case
     * edit).
     */
    @Transactional
    public EvalDatasetCase setCaseGrader(
            UUID agentId, UUID datasetId, UUID caseId, String graderSpec) {
        EvalDataset dataset = requireDataset(agentId, datasetId);
        EvalDatasetCase datasetCase = requireCase(datasetId, caseId);
        String spec = EvalCaseInputs.blankToNull(graderSpec);
        if (spec != null
                && com.agenticai.core.quality.trajectory.TrajectorySpec.isTrajectory(spec)) {
            // A trajectory spec shares this column (no migration). Feature off = the same "unknown
            // grader type"
            // answer as before it existed; on = fully validated here, not at run time.
            com.agenticai.core.quality.trajectory.TrajectorySpec.validateForWrite(
                    spec, trajectoryEvalEnabled);
        } else if (spec != null) {
            com.agenticai.core.quality.grader.CodeGrader.parse(
                    spec); // throws IllegalArgumentException
        }
        datasetCase.applyGraderSpec(spec);
        EvalDatasetCase saved = caseRepository.save(datasetCase);
        refreshContentHash(dataset);
        return saved;
    }

    @Transactional
    public void deleteCase(UUID agentId, UUID datasetId, UUID caseId) {
        EvalDataset dataset = requireDataset(agentId, datasetId);
        caseRepository.delete(requireCase(datasetId, caseId));
        // Flush so the recompute below does not count the row being deleted
        // (AUTO flush order is not guaranteed to precede a JPQL read inside
        // the same transaction on every provider version).
        caseRepository.flush();
        refreshContentHash(dataset);
    }

    private EvalDatasetCase requireCase(UUID datasetId, UUID caseId) {
        EvalDatasetCase datasetCase =
                caseRepository
                        .findById(caseId)
                        .orElseThrow(() -> new EvalDatasetNotFoundException(caseId));
        if (!datasetCase.getDatasetId().equals(datasetId)) {
            throw new EvalDatasetNotFoundException(caseId);
        }
        return datasetCase;
    }

    // ------------------------------------------------------------------
    // Dataset content hash (V343 - the gate's staleness binding)
    // ------------------------------------------------------------------

    /**
     * The dataset's CURRENT content hash - a pure read (no write): loads the dataset and its cases
     * and computes {@link DatasetContentHash}. Used by the run trigger (snapshot onto the run row)
     * and by {@code EvalGateService} (compare against each candidate run's snapshot). Callers in
     * read-only transactions can use it safely precisely because it never persists - the stored
     * {@code content_hash} column is the mutation paths' cache of this same function.
     */
    @Transactional(readOnly = true)
    public String computeContentHash(UUID datasetId) {
        EvalDataset dataset =
                datasetRepository
                        .findById(datasetId)
                        .orElseThrow(() -> new EvalDatasetNotFoundException(datasetId));
        return DatasetContentHash.of(
                dataset, caseRepository.findByDatasetIdOrderByPositionAscCreatedAtAsc(datasetId));
    }

    /**
     * Recomputes and stores the content hash after a mutation. Every write path (create/update/case
     * add|update|delete/seed/import) calls this so {@code eval_dataset.content_hash} always equals
     * {@link #computeContentHash} - any drift between the two is a bug, not a staleness signal.
     */
    private void refreshContentHash(EvalDataset dataset) {
        dataset.markContentHash(
                DatasetContentHash.of(
                        dataset,
                        caseRepository.findByDatasetIdOrderByPositionAscCreatedAtAsc(
                                dataset.getId())));
        datasetRepository.save(dataset);
    }

    // ------------------------------------------------------------------
    // "Add this turn to a dataset" (2026-09, console evals gap)
    // ------------------------------------------------------------------

    /**
     * Creates a case from a real conversation turn. The user message and the assistant's reply are
     * resolved in this order:
     *
     * <ol>
     *   <li>The persisted turn, when {@code sessionId} + {@code turnRef} name one: core/rl records
     *       every Playground turn that carried a {@code turnRef} as an {@code Episode} whose
     *       trajectory steps hold the {@code "user"} message and the {@code "assistant"} answer
     *       (see {@code AgentChatService#beginRlEpisode}/{@code completeRlEpisode}). Read
     *       reflectively through the merged context ({@link PersistedTurnLookup}) because {@code
     *       core/rl} is not - and per this module's pom boundary cannot be - a compile-time
     *       dependency.
     *   <li>The caller-supplied {@code userMessage}/{@code agentResponse} otherwise. This is the
     *       COMMON path, not an edge case: an episode exists only when the RL module is on the
     *       classpath AND the turn carried a turnRef AND the recorder didn't fail open - none of
     *       which the console can know. The console has both texts in hand anyway (it just rendered
     *       them), so the body is the contract and the persisted lookup is the "prefer the server's
     *       copy" upgrade.
     * </ol>
     *
     * The reply becomes the case's {@code expectedOutput} unless the caller supplied one - a turn a
     * human chose to save is, by that choice, the reference answer for the ACCURACY judge (and
     * editable afterwards like any case).
     *
     * @throws IllegalArgumentException (400) when neither source yields a user message - the one
     *     field a case cannot exist without
     */
    @Transactional
    public EvalDatasetCase importTurnCase(
            UUID agentId,
            UUID datasetId,
            String sessionId,
            String turnRef,
            String userMessage,
            String agentResponse,
            String expectedOutput,
            List<String> expectedTools) {
        String resolvedUser = EvalCaseInputs.blankToNull(userMessage);
        String resolvedReply = EvalCaseInputs.blankToNull(agentResponse);
        if (sessionId != null && !sessionId.isBlank() && turnRef != null && !turnRef.isBlank()) {
            Optional<PersistedTurnLookup.PersistedTurn> persisted =
                    persistedTurns.find(agentId, sessionId.trim(), turnRef.trim());
            if (persisted.isPresent()) {
                // The server's durable copy wins over whatever the client
                // still had on screen - same text in the normal case, and
                // the one that actually went to the model when not.
                resolvedUser =
                        persisted.get().userMessage() != null
                                ? persisted.get().userMessage()
                                : resolvedUser;
                resolvedReply =
                        persisted.get().assistantReply() != null
                                ? persisted.get().assistantReply()
                                : resolvedReply;
            }
        }
        if (resolvedUser == null) {
            throw new IllegalArgumentException(
                    "Could not resolve the turn's user message: no persisted turn was found for this "
                            + "sessionId/turnRef (only Playground turns that carried a turnRef are recorded), and "
                            + "'userMessage' was not supplied in the request body.");
        }
        String resolvedExpected =
                EvalCaseInputs.blankToNull(expectedOutput) != null ? expectedOutput : resolvedReply;
        return addCase(agentId, datasetId, resolvedUser, resolvedExpected, expectedTools, null);
    }

    // ------------------------------------------------------------------
    // Golden-set seed (2026-09, console evals gap: surface the eval harness's set)
    // ------------------------------------------------------------------

    /**
     * One entry of this module's {@code golden-dataset.json} - the same six fields as {@code
     * com.agenticai.core.quality.eval.EvalCase}, re-declared here as a deliberate projection: the
     * seed only needs these fields, and unknown properties (e.g. {@code feasibility}, which the
     * CLI runner consumes) are ignored so a field added to the JSON never breaks the seed.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GoldenCase(
            String id,
            String persona,
            String category,
            String systemPrompt,
            String userMessage,
            String rubric) {}

    /**
     * Loads the golden cases from the first resource in {@link #GOLDEN_DATASET_RESOURCES} that
     * exists. Package-private so {@code GoldenSeedTest} validates the in-module copy through the
     * exact code path the endpoint uses.
     */
    static List<GoldenCase> loadGoldenCases() throws IOException {
        for (String resource : GOLDEN_DATASET_RESOURCES) {
            try (InputStream in = EvalDatasetService.class.getResourceAsStream(resource)) {
                if (in == null) {
                    continue;
                }
                return MAPPER.readValue(in, new TypeReference<List<GoldenCase>>() {});
            }
        }
        throw new IOException(
                "golden-dataset.json not found on the classpath at any of "
                        + String.join(", ", GOLDEN_DATASET_RESOURCES));
    }

    /**
     * Creates a dataset for {@code agentId} pre-filled with every golden case's user turn, the
     * rubric as the reference for the ACCURACY judge, and no tool assertions (the golden set is
     * text-only by design - see eval-harness.md, "honest to the runner's mechanics").
     *
     * <p><b>What is deliberately dropped:</b> each golden case's own {@code systemPrompt}. A tenant
     * dataset replays against THIS agent's real system prompt (that is the point of a replay), so
     * the persona the case was written for is recorded in the reference text rather than
     * substituted - which also means a case whose persona differs from this agent's (a
     * homework-tutor case replayed against a sales agent) is expected to score low. The dataset
     * description says so; the dataset is a starting point to prune, not a verdict.
     *
     * <p>Idempotent per agent by name: a second call is a 409 rather than a silent duplicate,
     * mirroring {@code EvalRunConflictException}'s use for "you already have one of these in
     * flight".
     */
    @Transactional
    public EvalDataset seedGoldenDataset(UUID agentId) {
        boolean alreadySeeded =
                datasetRepository.findByAgentIdOrderByCreatedAtDesc(agentId).stream()
                        .anyMatch(d -> GOLDEN_DATASET_NAME.equals(d.getName()));
        if (alreadySeeded) {
            throw new EvalRunConflictException(
                    "This agent already has a '"
                            + GOLDEN_DATASET_NAME
                            + "' dataset - edit or delete it instead of seeding again.");
        }
        List<GoldenCase> golden;
        try {
            golden = loadGoldenCases();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "The golden dataset resource could not be read: " + e.getMessage(), e);
        }
        if (golden.size() > MAX_CASES_PER_DATASET) {
            // Not reachable at today's ~35 cases; guarded so a future
            // expansion fails loudly at seed time instead of half-inserting.
            throw new IllegalStateException(
                    "The golden dataset has "
                            + golden.size()
                            + " cases, above the per-dataset cap of "
                            + MAX_CASES_PER_DATASET
                            + ".");
        }

        EvalDataset dataset =
                createDataset(
                        agentId,
                        GOLDEN_DATASET_NAME,
                        "Seeded from this module's golden-dataset.json ("
                                + golden.size()
                                + " cases). Each case's rubric is "
                                + "the reference the ACCURACY judge grades against; the persona each case was written for is "
                                + "named in that reference. The original per-case system prompts are NOT applied - runs replay "
                                + "against this agent's own prompt - so cases written for a different persona are expected to "
                                + "fail. Prune to the cases that fit this agent, then run.",
                        null,
                        false);

        List<EvalDatasetCase> cases = new ArrayList<>(golden.size());
        int position = 0;
        for (GoldenCase g : golden) {
            cases.add(
                    new EvalDatasetCase(
                            dataset.getId(),
                            g.userMessage(),
                            goldenReferenceText(g),
                            null,
                            position++));
        }
        caseRepository.saveAll(cases);
        refreshContentHash(dataset);
        return dataset;
    }

    /**
     * The rubric, framed with its provenance so the judge and a human reading the case both know
     * what it assumes.
     */
    static String goldenReferenceText(GoldenCase g) {
        String category =
                g.category() == null || g.category().isBlank() ? "uncategorized" : g.category();
        return "Rubric from golden-set case '"
                + g.id()
                + "' (persona: "
                + g.persona()
                + ", category: "
                + category
                + "): "
                + g.rubric();
    }

    // ------------------------------------------------------------------
    // Langfuse trace links on run results (2026-09)
    // ------------------------------------------------------------------

    /** See {@link EvalTraceLinks#traceIdForRunCase}: null when there is no trace to link. */
    public String traceIdForRunCase(UUID runId, int index) {
        return traceLinks.traceIdForRunCase(runId, index);
    }

    /** See {@link EvalTraceLinks#langfuseTraceUrl(String)}: null when Langfuse is not configured. */
    public String langfuseTraceUrl(String traceId) {
        return traceLinks.langfuseTraceUrl(traceId);
    }

    // ------------------------------------------------------------------
    // Decoding helper (package-private: the run service, export and controller reuse it)
    // ------------------------------------------------------------------

    /**
     * Stored JSON text -> list form, for DTOs and the run-time assertion. A row whose stored text
     * does not parse (hand-run SQL fix gone wrong) yields an EMPTY list - the assertion then
     * evaluates as "nothing expected" rather than the whole read path 500ing, the same blast-radius
     * reasoning as V207's status CHECK constraint but on the read side.
     */
    static List<String> parseExpectedTools(String storedJson) {
        if (storedJson == null || storedJson.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(storedJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
