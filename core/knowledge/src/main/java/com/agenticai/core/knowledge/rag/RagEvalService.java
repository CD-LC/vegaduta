package com.agenticai.core.knowledge.rag;

import com.agenticai.core.knowledge.KnowledgeCollectionNotFoundException;
import com.agenticai.core.knowledge.KnowledgeCollectionRepository;
import com.agenticai.core.knowledge.KnowledgeSchemaSupport;
import com.agenticai.core.knowledge.KnowledgeSearchService;
import com.agenticai.core.knowledge.dto.SearchResultResponse;
import com.agenticai.core.knowledge.rag.RagDtos.RagEvalConfig;
import com.agenticai.core.knowledge.rag.RagDtos.RagEvalConfigResult;
import com.agenticai.core.knowledge.rag.RagDtos.RagEvalQuestion;
import com.agenticai.core.knowledge.rag.RagDtos.RagEvalQuestionResult;
import com.agenticai.core.knowledge.rag.RagDtos.RagEvalRequest;
import com.agenticai.core.knowledge.rag.RagDtos.RagEvalResponse;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimitExceededException;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * RAG eval harness: runs a dataset of questions against ONE collection under several retrieval
 * configurations (baseline vs fusion vs parent-child vs query rewriting) and reports hit-rate (with a
 * Wilson interval), recall@k and MRR per configuration, so a retrieval change is measured on the
 * tenant's own documents instead of assumed to help.
 *
 * <p>The dataset is a request body capped at {@code app.knowledge.rag-eval.max-questions} (200); it does
 * not reuse {@code core/quality}'s eval-dataset entities because those score agent turns with an LLM
 * judge and live in a module this one cannot depend on, while retrieval scoring here is deterministic
 * (document ids / substrings) and spends no model call except the optional query-rewrite configs.
 *
 * <p><b>Execution.</b> Question-major (each question is run under every config before the next) so a
 * time-budget stop ({@code max-seconds}) leaves all configs with the same number of questions and the
 * comparison stays fair. Sequential on the request thread, which already carries the tenant context.
 *
 * <p><b>Plan gate</b> mirrors the other eval surfaces: a FREE tenant is refused unless
 * {@code app.evals.free-plan-enabled} (the switch {@code QualityController} reads) is on, and then runs
 * with a smaller question cap. The shared {@code /try} demo tenant is refused outright - an anonymous
 * audience must not be able to drive embedding calls through an eval loop.
 *
 * <p>No benchmark claim is made anywhere: the numbers describe THIS dataset on THIS collection.
 */
@Service
@Profile("knowledge")
public class RagEvalService {

    private static final Logger log = LoggerFactory.getLogger(RagEvalService.class);

    static final int DEFAULT_K = 5;
    static final int MAX_K = 20;
    static final int MAX_CONFIGS = 6;
    static final int MAX_QUESTION_CHARS = 1000;
    static final int MAX_PER_QUESTION_DETAIL = 50;
    /** Per-question caps on the expected targets: they multiply the matching work by questions x configs x k. */
    static final int MAX_EXPECTED_SUBSTRINGS = 10;
    static final int MAX_EXPECTED_SUBSTRING_CHARS = 500;
    static final int MAX_EXPECTED_DOCUMENT_IDS = 20;

    private final KnowledgeSearchService searchService;
    private final KnowledgeCollectionRepository collectionRepository;
    private final RagRetrievalConfig config;
    private final JdbcTemplate jdbcTemplate;
    private final TenantLimits tenantLimits;
    private final boolean freePlanEnabled;
    private final int maxQuestions;
    private final int freeMaxQuestions;
    private final long maxMillis;
    /** Tenants with an eval in flight: one run at a time per tenant (each run fans out to the sidecar and, for rewrite presets, a platform-paid model). */
    private final Set<String> runningTenants = ConcurrentHashMap.newKeySet();

    @Autowired
    public RagEvalService(
        KnowledgeSearchService searchService,
        KnowledgeCollectionRepository collectionRepository,
        RagRetrievalConfig config,
        JdbcTemplate jdbcTemplate,
        TenantLimits tenantLimits,
        @Value("${app.evals.free-plan-enabled:true}") boolean freePlanEnabled,
        @Value("${app.knowledge.rag-eval.max-questions:200}") int maxQuestions,
        @Value("${app.knowledge.rag-eval.free-max-questions:25}") int freeMaxQuestions,
        // 80s: the Cloudflare edge answers 524 at about 100s, so a synchronous run must stop well before that
        // (a question already in flight can still finish after the deadline).
        @Value("${app.knowledge.rag-eval.max-seconds:80}") int maxSeconds
    ) {
        this.searchService = searchService;
        this.collectionRepository = collectionRepository;
        this.config = config;
        this.jdbcTemplate = jdbcTemplate;
        this.tenantLimits = tenantLimits;
        this.freePlanEnabled = freePlanEnabled;
        this.maxQuestions = Math.max(1, Math.min(maxQuestions, 200));
        this.freeMaxQuestions = Math.max(1, Math.min(freeMaxQuestions, this.maxQuestions));
        this.maxMillis = Math.max(1, maxSeconds) * 1000L;
    }

    public RagEvalResponse run(UUID collectionId, RagEvalRequest request) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId != null && !runningTenants.add(tenantId)) {
            throw new ResponseStatusException(
                HttpStatus.TOO_MANY_REQUESTS, "A retrieval eval is already running for your workspace - wait for it to finish."
            );
        }
        try {
            return runEval(collectionId, request);
        } finally {
            if (tenantId != null) {
                runningTenants.remove(tenantId);
            }
        }
    }

    private RagEvalResponse runEval(UUID collectionId, RagEvalRequest request) {
        PlanGate gate = planGate();
        int cap = gate.questionCap();
        if (collectionRepository.findById(collectionId).isEmpty()) {
            throw new KnowledgeCollectionNotFoundException(collectionId);
        }
        if (request == null || request.questions() == null || request.questions().isEmpty()) {
            throw new IllegalArgumentException("questions must contain at least one question");
        }
        List<RagEvalQuestion> questions = request.questions();
        if (questions.size() > cap) {
            throw new IllegalArgumentException("At most " + cap + " questions per eval run on your plan (got " + questions.size() + ")");
        }
        for (int i = 0; i < questions.size(); i++) {
            RagEvalQuestion q = questions.get(i);
            if (q == null || q.question() == null || q.question().isBlank()) {
                throw new IllegalArgumentException("question " + (i + 1) + " is empty");
            }
            if (q.question().length() > MAX_QUESTION_CHARS) {
                throw new IllegalArgumentException("question " + (i + 1) + " is longer than " + MAX_QUESTION_CHARS + " characters");
            }
            if (!RagEvalMetrics.hasTargets(q)) {
                throw new IllegalArgumentException(
                    "question " + (i + 1) + " needs expectedDocumentIds or expectedSubstrings - without a target there is nothing to measure"
                );
            }
            validateTargets(q, i + 1);
        }
        int k = request.k() == null ? DEFAULT_K : Math.max(1, Math.min(request.k(), MAX_K));
        List<RagEvalConfig> configs = request.configs() == null || request.configs().isEmpty()
            ? List.of(new RagEvalConfig("baseline", "baseline", null, null, null, null),
                new RagEvalConfig("fusion", "fusion", null, null, null, null))
            : request.configs();
        if (configs.size() > MAX_CONFIGS) {
            throw new IllegalArgumentException("At most " + MAX_CONFIGS + " configs per eval run");
        }
        for (RagEvalConfig c : configs) {
            if (c == null) {
                throw new IllegalArgumentException("configs must not contain null entries");
            }
            if (gate.free() && presetOf(c).startsWith("rewrite")) {
                // Rewrite presets spend the platform-wide rag-query-rewrite budget on every question; a FREE
                // tenant must not be able to exhaust it for everyone.
                throw new TenantLimitExceededException(
                    "Query-rewrite comparisons call a hosted model and are a Premium feature - upgrade your plan to run them."
                );
            }
        }
        List<RetrievalOptions> options = new ArrayList<>(configs.size());
        List<String> names = new ArrayList<>(configs.size());
        for (RagEvalConfig c : configs) {
            options.add(resolveOptions(c, config));
            names.add(c.name() == null || c.name().isBlank() ? presetOf(c) : c.name().strip());
        }

        List<List<RagEvalMetrics.QuestionOutcome>> outcomes = new ArrayList<>();
        List<List<RagEvalQuestionResult>> details = new ArrayList<>();
        long[] latencyNanos = new long[configs.size()];
        for (int c = 0; c < configs.size(); c++) {
            outcomes.add(new ArrayList<>());
            details.add(new ArrayList<>());
        }

        Set<UUID> scope = Set.of(collectionId);
        long deadline = System.nanoTime() + maxMillis * 1_000_000L;
        boolean truncated = false;
        int evaluated = 0;
        for (RagEvalQuestion q : questions) {
            if (evaluated > 0 && System.nanoTime() > deadline) {
                truncated = true;
                break;
            }
            for (int c = 0; c < configs.size(); c++) {
                long t0 = System.nanoTime();
                RagEvalMetrics.QuestionOutcome outcome;
                try {
                    List<SearchResultResponse> hits = searchService.search(q.question(), k, scope, false, null, options.get(c));
                    outcome = RagEvalMetrics.score(q, hits, k);
                } catch (RuntimeException e) {
                    log.warn("RAG eval search failed for config '{}': {}", names.get(c), e.toString());
                    outcome = RagEvalMetrics.QuestionOutcome.failure();
                }
                latencyNanos[c] += System.nanoTime() - t0;
                outcomes.get(c).add(outcome);
                if (details.get(c).size() < MAX_PER_QUESTION_DETAIL) {
                    details.get(c).add(new RagEvalQuestionResult(
                        q.question(), outcome.hit(), outcome.firstRelevantRank(), outcome.recall(), outcome.returned(), outcome.returnedChars()
                    ));
                }
            }
            evaluated++;
        }

        List<RagEvalConfigResult> results = new ArrayList<>(configs.size());
        for (int c = 0; c < configs.size(); c++) {
            RagEvalMetrics.Aggregate agg = RagEvalMetrics.aggregate(outcomes.get(c));
            results.add(new RagEvalConfigResult(
                names.get(c), presetOf(configs.get(c)), evaluated, k,
                agg.hitRate(), agg.hitRateCi(), agg.recallAtK(), agg.mrr(), agg.avgReturnedChars(),
                evaluated == 0 ? 0.0 : latencyNanos[c] / 1_000_000.0 / evaluated,
                agg.failed(), details.get(c)
            ));
        }

        long children = countChildren(collectionId);
        long parents = countParents(collectionId);
        return new RagEvalResponse(
            collectionId, k, evaluated, children, parents, truncated, notes(configs, children, parents, truncated, evaluated, questions.size()), results
        );
    }

    /** The question cap of the caller's plan and whether it is the FREE plan (which may not run rewrite presets). */
    private record PlanGate(int questionCap, boolean free) {}

    private PlanGate planGate() {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null) {
            return new PlanGate(maxQuestions, false);
        }
        if (tenantLimits.isSharedDemoTenant(tenantId)) {
            throw new TenantLimitExceededException("Retrieval evals are not available in the shared demo workspace.");
        }
        TenantPlan plan = tenantLimits.plan(tenantId);
        if (plan == TenantPlan.FREE) {
            if (!freePlanEnabled) {
                throw new TenantLimitExceededException(
                    "Retrieval quality evals are a Premium feature - upgrade your plan to run them."
                );
            }
            return new PlanGate(freeMaxQuestions, true);
        }
        return new PlanGate(maxQuestions, false);
    }

    /** Bounds the expected targets of one question (400 on a violation) so a hostile body cannot multiply the matching work. */
    static void validateTargets(RagEvalQuestion q, int number) {
        if (q.expectedDocumentIds() != null) {
            if (q.expectedDocumentIds().size() > MAX_EXPECTED_DOCUMENT_IDS) {
                throw new IllegalArgumentException(
                    "question " + number + " has more than " + MAX_EXPECTED_DOCUMENT_IDS + " expectedDocumentIds"
                );
            }
            for (UUID id : q.expectedDocumentIds()) {
                if (id == null) {
                    throw new IllegalArgumentException("question " + number + " has a null expectedDocumentIds entry");
                }
            }
        }
        if (q.expectedSubstrings() != null) {
            if (q.expectedSubstrings().size() > MAX_EXPECTED_SUBSTRINGS) {
                throw new IllegalArgumentException(
                    "question " + number + " has more than " + MAX_EXPECTED_SUBSTRINGS + " expectedSubstrings"
                );
            }
            for (String s : q.expectedSubstrings()) {
                if (s != null && s.length() > MAX_EXPECTED_SUBSTRING_CHARS) {
                    throw new IllegalArgumentException(
                        "question " + number + " has an expected substring longer than " + MAX_EXPECTED_SUBSTRING_CHARS + " characters"
                    );
                }
            }
        }
    }

    /** Maps a request config to search options; null = the exact legacy pipeline. Throws IllegalArgumentException (400) on a bad preset or override. */
    static RetrievalOptions resolveOptions(RagEvalConfig c, RagRetrievalConfig cfg) {
        String preset = presetOf(c);
        boolean overrides = c != null && (c.rrfK() != null || c.vectorWeight() != null || c.keywordWeight() != null || c.mmrLambda() != null);
        RetrievalOptions fusion = cfg.fusionPreset();
        int variants = cfg.deploymentOptions().maxVariants();
        boolean wantsFusion = false;
        boolean parents = false;
        RetrievalOptions.RewriteMode rewrite = RetrievalOptions.RewriteMode.OFF;
        switch (preset) {
            case "baseline" -> {
                if (overrides) {
                    throw new IllegalArgumentException("the baseline preset takes no fusion overrides");
                }
                return null;
            }
            case "parent-child" -> {
                if (overrides) {
                    throw new IllegalArgumentException("the parent-child preset takes no fusion overrides - use fusion+parent-child");
                }
                wantsFusion = false;
                parents = true;
                rewrite = RetrievalOptions.RewriteMode.OFF;
            }
            case "fusion" -> {
                wantsFusion = true;
                parents = false;
                rewrite = RetrievalOptions.RewriteMode.OFF;
            }
            case "fusion+parent-child" -> {
                wantsFusion = true;
                parents = true;
                rewrite = RetrievalOptions.RewriteMode.OFF;
            }
            case "rewrite-multi" -> {
                wantsFusion = overrides;
                parents = false;
                rewrite = RetrievalOptions.RewriteMode.MULTI;
            }
            case "rewrite-hyde" -> {
                wantsFusion = overrides;
                parents = false;
                rewrite = RetrievalOptions.RewriteMode.HYDE;
            }
            default -> throw new IllegalArgumentException(
                "Unknown preset \"" + preset + "\" - use baseline, fusion, parent-child, fusion+parent-child, rewrite-multi or rewrite-hyde"
            );
        }
        return new RetrievalOptions(
            wantsFusion,
            c != null && c.rrfK() != null ? c.rrfK() : fusion.rrfK(),
            c != null && c.vectorWeight() != null ? c.vectorWeight() : fusion.vectorWeight(),
            c != null && c.keywordWeight() != null ? c.keywordWeight() : fusion.keywordWeight(),
            c != null && c.mmrLambda() != null ? c.mmrLambda() : fusion.mmrLambda(),
            rewrite,
            variants,
            parents
        );
    }

    static String presetOf(RagEvalConfig c) {
        return c == null || c.preset() == null || c.preset().isBlank() ? "baseline" : c.preset().strip().toLowerCase(Locale.ROOT);
    }

    static List<String> notes(List<RagEvalConfig> configs, long children, long parents, boolean truncated, int evaluated, int total) {
        List<String> notes = new ArrayList<>();
        notes.add("Results describe this dataset on this collection only; they are not a benchmark and small datasets give wide intervals.");
        boolean usesParents = configs.stream().anyMatch(c -> presetOf(c).contains("parent-child"));
        if (usesParents && parents == 0) {
            notes.add("This collection has no parent-child documents, so the parent-child configs return the same chunks as their non-parent counterparts. "
                + "Parent-child applies to documents ingested after the collection opted in.");
        }
        if (usesParents && parents > 0) {
            notes.add("Parent-child configs return larger passages: compare avgReturnedChars, and prefer expectedDocumentIds over expectedSubstrings, "
                + "because a larger passage can contain an expected substring without ranking the right document higher.");
        }
        if (configs.stream().anyMatch(c -> presetOf(c).startsWith("rewrite"))) {
            notes.add("Rewrite configs fall back to the original query when no cheap model is available, the call fails, or your workspace uses its own provider key (queries are not sent to the platform model), so they may equal the baseline.");
        }
        if (truncated) {
            notes.add("Stopped after " + evaluated + " of " + total + " questions because the run's time budget was reached; every config covers the same questions.");
        }
        return notes;
    }

    private long countChildren(UUID collectionId) {
        String table = KnowledgeSchemaSupport.qualifiedTableName("knowledge_chunks");
        try {
            Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE collection_id = ? AND metadata->>'parent_id' IS NOT NULL", Long.class, collectionId
            );
            return n == null ? 0L : n;
        } catch (DataAccessException e) {
            return 0L;
        }
    }

    private long countParents(UUID collectionId) {
        String table = KnowledgeSchemaSupport.qualifiedTableName("knowledge_parent_chunk");
        try {
            Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE collection_id = ?", Long.class, collectionId);
            return n == null ? 0L : n;
        } catch (DataAccessException e) {
            return 0L;
        }
    }

}
