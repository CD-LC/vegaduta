package com.agenticai.core.quality.eval;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns every {@link EvalRunner} run into a Langfuse <em>Experiment</em>
 * (dataset run) so two runs - before/after a prompt or model change - can be
 * compared item-by-item in the Langfuse UI instead of by eyeballing two CI
 * logs. Three Langfuse public-API calls, nothing more:
 *
 * <ol>
 *   <li><b>Dataset sync</b> ({@link #syncDataset}, once per run, idempotent):
 *       {@code POST /api/public/v2/datasets} for {@value #DATASET_NAME}
 *       (Langfuse upserts by name; a 409 from an older server is treated as
 *       "already exists") and {@code POST /api/public/dataset-items} once per
 *       {@link EvalCase}, with {@code id = case.id()} so a re-run upserts the
 *       same item rather than duplicating it.</li>
 *   <li><b>Run item</b> ({@link #recordCaseResult}, once per case): {@code
 *       POST /api/public/dataset-run-items} linking the dataset item to the
 *       OTel trace id {@code EvalCaseRun#runCase} exported for that case -
 *       this is what makes the case show up under the run in the UI with its
 *       generation/judge trace attached.</li>
 *   <li><b>Scores</b> (same call): {@code POST /api/public/scores} on that
 *       trace - {@code rubric_pass} (BOOLEAN, judge reason as the comment),
 *       {@code outcome} (CATEGORICAL, one of {@link EvalOutcome}'s constant
 *       names), {@code confidence_targeted_score} (NUMERIC, {@code [-1, +1]},
 *       see {@link EvalResult#confidenceTargetedScore()}), {@code
 *       total_tokens} (NUMERIC) and {@code token_budget_ok} (BOOLEAN). Score
 *       ids are deterministic ({@code runName:caseId:name}) so a retried run
 *       upserts instead of double-counting.</li>
 * </ol>
 *
 * <p><b>No SDK, no Spring.</b> Langfuse has no official Java SDK (see {@code
 * com.agenticai.core.chat.langfuse.LangfuseScoreClient}'s javadoc for the
 * doc trail), and this module deliberately has no Spring context (pom.xml
 * description), so this is {@link java.net.http.HttpClient} + Jackson only.
 *
 * <p><b>Config reuse, not invention</b> - exactly the derivation {@code
 * LangfuseScoreClient} (core/chat) uses: the base URL is {@code
 * OTEL_EXPORTER_OTLP_TRACES_ENDPOINT} with Langfuse's documented OTLP path
 * {@value #OTLP_TRACES_SUFFIX} stripped, and the credential is the very same
 * pre-built {@code LANGFUSE_OTEL_AUTH_HEADER} ("Basic base64(pk:sk)") the
 * span exporter already sends - Langfuse accepts that identical pair on
 * every public endpoint. A blank auth header means "Langfuse is not
 * configured here" and {@link #fromEnv} returns {@code null}: the runner
 * then behaves byte-for-byte as it did before this class existed. Gating on
 * the SAME variable as the exporter also guarantees a run item is never
 * posted against a trace id that was never exported.
 *
 * <p><b>Fails open, always.</b> An eval run's pass/fail exit code is the
 * product; the Langfuse mirror is observability on top. Every failure -
 * unreachable host, 4xx/5xx, malformed response - is logged at WARN and
 * swallowed; nothing here can throw into {@code EvalRunner#main}. Same
 * posture as {@code LangfuseScoreClient} and the OTLP exporter itself.
 *
 * <p><b>Trace-id assumption (still unverified against a live instance).</b>
 * This relies on the OTel trace id of an OTLP-ingested span being directly
 * usable as Langfuse's {@code traceId} - the inference {@code
 * LangfuseScoreClient}'s javadoc spells out. First real run: open the run in
 * the UI and confirm each item shows its trace. If it doesn't, that class's
 * caveat is where to start.
 */
public final class LangfuseEvalClient {

    private static final Logger log = LoggerFactory.getLogger(LangfuseEvalClient.class);

    /**
     * The Langfuse dataset every run of this harness is recorded against.
     * Product-branded (VegaDuta) rather than the file name because the
     * Langfuse project is shared with the live platform's traces, where a
     * bare "golden-dataset" would say nothing about whose golden set it is.
     */
    static final String DATASET_NAME = "vegaduta-golden";

    /**
     * Overrides the auto-generated run name - what the workflows should set
     * ({@code nightly-<sha>} / {@code pr-<n>-<sha>}) so a run is findable by
     * the commit that produced it. Same env-var convention as every other
     * knob in this module ({@code EVAL_MODEL}, {@code EVAL_CASE_IDS}).
     */
    static final String RUN_NAME_ENV_VAR = "EVAL_RUN_NAME";

    /** Langfuse's documented native OTLP ingestion path - the suffix stripped to get the API base. */
    static final String OTLP_TRACES_SUFFIX = "/api/public/otel/v1/traces";

    /** Same default as {@code EvalRunner#DEFAULT_OTLP_ENDPOINT} and core/app's application.yml. */
    static final String DEFAULT_OTLP_ENDPOINT = "http://langfuse-web:3000" + OTLP_TRACES_SUFFIX;

    static final String DATASETS_PATH = "/api/public/v2/datasets";
    static final String DATASET_ITEMS_PATH = "/api/public/dataset-items";
    static final String DATASET_RUN_ITEMS_PATH = "/api/public/dataset-run-items";
    static final String SCORES_PATH = "/api/public/scores";

    /** Small observability POSTs, never completions - a hung Langfuse must not stall the billed run. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC);

    private final HttpClient http;
    private final String baseUrl;
    private final String authHeader;
    private final String runName;
    private final String runDescription;
    private final Map<String, Object> runMetadata;
    private final ObjectMapper mapper = JsonMappers.plain();

    LangfuseEvalClient(
        HttpClient http, String baseUrl, String authHeader,
        String runName, String runDescription, Map<String, Object> runMetadata
    ) {
        this.http = http;
        this.baseUrl = baseUrl;
        this.authHeader = authHeader;
        this.runName = runName;
        this.runDescription = runDescription;
        this.runMetadata = runMetadata;
    }

    /**
     * Builds a client from the process environment, or returns {@code null}
     * when {@code LANGFUSE_OTEL_AUTH_HEADER} is blank/unset - the "Langfuse
     * not configured" case (a plain CI runner, a fork) where the run must
     * proceed exactly as before with zero network side effects.
     */
    static LangfuseEvalClient fromEnv(String model, String evalRunId, double tokenThresholdPct) {
        return fromConfig(System.getenv(), model, evalRunId, tokenThresholdPct, Instant.now(),
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    /**
     * Env-map form of {@link #fromEnv} so tests can drive it without
     * mutating the process environment (which Java cannot do portably).
     */
    static LangfuseEvalClient fromConfig(
        Map<String, String> env, String model, String evalRunId, double tokenThresholdPct,
        Instant now, HttpClient http
    ) {
        String authHeader = env.get("LANGFUSE_OTEL_AUTH_HEADER");
        if (authHeader == null || authHeader.isBlank()) {
            return null;
        }
        String baseUrl = deriveBaseUrl(env.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT"));
        String runName = resolveRunName(env, model, now);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("model", model);
        metadata.put("gitSha", env.getOrDefault("GITHUB_SHA", "local"));
        metadata.put("evalRunId", evalRunId);
        metadata.put("tokenThresholdPct", tokenThresholdPct);
        return new LangfuseEvalClient(http, baseUrl, authHeader.trim(), runName,
            "EvalRunner golden-dataset run (core/quality)", metadata);
    }

    /**
     * {@code OTEL_EXPORTER_OTLP_TRACES_ENDPOINT} minus Langfuse's OTLP path -
     * identical to {@code LangfuseScoreClient#deriveScoresUrl}'s stripping,
     * and identical default when the variable is unset, so the eval harness
     * and the live platform can never disagree about where Langfuse is.
     */
    static String deriveBaseUrl(String otlpTracesEndpoint) {
        String endpoint = otlpTracesEndpoint == null || otlpTracesEndpoint.isBlank()
            ? DEFAULT_OTLP_ENDPOINT
            : otlpTracesEndpoint.trim();
        String base = endpoint.endsWith(OTLP_TRACES_SUFFIX)
            ? endpoint.substring(0, endpoint.length() - OTLP_TRACES_SUFFIX.length())
            : endpoint;
        // Tolerate a trailing slash on a hand-configured bare origin so path
        // concatenation below never produces "//api/public/...".
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /**
     * {@code EVAL_RUN_NAME} verbatim when set; otherwise {@code
     * <model>-<sha7|local>-<yyyyMMdd-HHmm UTC>} - unique enough per invocation
     * that two local runs a minute apart never collide into one Langfuse run,
     * and readable enough that "what changed between these two" is answerable
     * from the name alone.
     */
    static String resolveRunName(Map<String, String> env, String model, Instant now) {
        String explicit = env.get(RUN_NAME_ENV_VAR);
        if (explicit != null && !explicit.isBlank()) {
            return explicit.trim();
        }
        String sha = env.get("GITHUB_SHA");
        String shortSha = sha == null || sha.isBlank() ? "local" : sha.trim().substring(0, Math.min(7, sha.trim().length()));
        return model + "-" + shortSha + "-" + RUN_STAMP.format(now);
    }

    String runName() {
        return runName;
    }

    String baseUrl() {
        return baseUrl;
    }

    // ------------------------------------------------------------------
    // Step 1 - dataset sync (idempotent, run start)
    // ------------------------------------------------------------------

    /**
     * Ensures the dataset exists and upserts every case as a dataset item.
     * Only the cases actually about to run are synced (after {@code
     * EVAL_CASE_IDS} filtering) - the PR gate's 8-case subset must not
     * silently re-upsert (and so re-date) the other ~27 items each PR.
     */
    public void syncDataset(List<EvalCase> cases) {
        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("name", DATASET_NAME);
        dataset.put("description", "core/quality golden cases (system prompt + user turn + rubric), synced by EvalRunner");
        dataset.put("metadata", Map.of("source", "core/quality/src/main/resources/golden-dataset.json"));
        int status = post(DATASETS_PATH, dataset);
        // 409: an older Langfuse that doesn't upsert-by-name and already has
        // it - the outcome we wanted anyway.
        if (!isSuccess(status) && status != 409) {
            log.warn("[eval] Langfuse dataset '{}' could not be ensured (HTTP {}) - items will still be attempted",
                DATASET_NAME, status);
        }

        int upserted = 0;
        for (EvalCase evalCase : cases) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("datasetName", DATASET_NAME);
            // Stable id => upsert. Re-running never duplicates an item, and a
            // rubric edit in the JSON flows through on the next run.
            item.put("id", evalCase.id());
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("systemPrompt", evalCase.systemPrompt());
            input.put("userMessage", evalCase.userMessage());
            item.put("input", input);
            item.put("expectedOutput", Map.of("rubric", evalCase.rubric()));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("persona", evalCase.persona());
            metadata.put("category", evalCase.categoryOrDefault());
            item.put("metadata", metadata);
            if (isSuccess(post(DATASET_ITEMS_PATH, item))) {
                upserted++;
            }
        }
        log.info("[eval] Langfuse dataset '{}': {}/{} item(s) upserted (run '{}')",
            DATASET_NAME, upserted, cases.size(), runName);
    }

    // ------------------------------------------------------------------
    // Steps 2 + 3 - run item + scores (per case)
    // ------------------------------------------------------------------

    /**
     * Links {@code result}'s trace into this run and scores it. A result
     * with no trace id (built outside the runner's span) has nothing for
     * Langfuse to attach to and is skipped with a WARN rather than posting a
     * dangling run item.
     */
    public void recordCaseResult(EvalResult result) {
        String traceId = result.traceId();
        String caseId = result.evalCase().id();
        if (traceId == null || traceId.isBlank()) {
            log.warn("[eval] case {} has no trace id - not recorded in Langfuse run '{}'", caseId, runName);
            return;
        }

        Map<String, Object> runItem = new LinkedHashMap<>();
        runItem.put("runName", runName);
        runItem.put("runDescription", runDescription);
        runItem.put("metadata", runMetadata);
        runItem.put("datasetItemId", caseId);
        runItem.put("traceId", traceId);
        post(DATASET_RUN_ITEMS_PATH, runItem);

        postScore(traceId, caseId, "rubric_pass", "BOOLEAN", result.passed() ? 1 : 0, result.judgeReason());
        // Three-way outcome alongside the boolean (2026-09), not instead of
        // it: rubric_pass stays the gate every existing saved Langfuse view
        // filters on, while these two make a correct refusal distinguishable
        // from a correct answer in a run comparison - both are rubric_pass=1,
        // which is precisely the conflation EvalOutcome was added to end.
        postScore(traceId, caseId, "outcome", "CATEGORICAL", result.outcome().name(), result.judgeReason());
        postScore(traceId, caseId, "confidence_targeted_score", "NUMERIC", result.confidenceTargetedScore(), null);
        if (result.totalTokens() >= 0) {
            postScore(traceId, caseId, "total_tokens", "NUMERIC", result.totalTokens(), null);
            // "ok" also covers "no baseline on file" - the report's own
            // semantics (unflagged), see TokenBaseline's honest-absence rule.
            postScore(traceId, caseId, "token_budget_ok", "BOOLEAN",
                result.tokenBudgetExceeded() ? 0 : 1, result.tokenBudgetReason());
        }
    }

    private void postScore(String traceId, String caseId, String name, String dataType, Object value, String comment) {
        Map<String, Object> score = new LinkedHashMap<>();
        // Deterministic id => a retried/re-posted run upserts the score
        // instead of stacking a second one on the same trace.
        score.put("id", runName + ":" + caseId + ":" + name);
        score.put("traceId", traceId);
        score.put("name", name);
        score.put("value", value);
        score.put("dataType", dataType);
        if (comment != null && !comment.isBlank()) {
            score.put("comment", comment);
        }
        post(SCORES_PATH, score);
    }

    /** One line for the end of the run report telling a human where to look. */
    String describeRun() {
        return "[eval] Langfuse experiment: run '" + runName + "' recorded on dataset '" + DATASET_NAME + "' at "
            + baseUrl + " - open Datasets > " + DATASET_NAME + " > Runs there and select this run alongside the "
            + "previous one to diff rubric_pass per item.";
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    /**
     * @return the HTTP status, or {@code -1} when the request never got a
     *     response (connect/read failure, serialization failure). Never
     *     throws - see class javadoc's fail-open contract.
     */
    private int post(String path, Map<String, Object> body) {
        String url = baseUrl + path;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (!isSuccess(response.statusCode()) && response.statusCode() != 409) {
                log.warn("[eval] Langfuse POST {} returned HTTP {}: {}", url, response.statusCode(),
                    abbreviate(response.body()));
            }
            return response.statusCode();
        } catch (IOException | RuntimeException e) {
            log.warn("[eval] Langfuse POST {} failed - {}: {}", url, e.getClass().getSimpleName(), e.getMessage());
            return -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[eval] Langfuse POST {} interrupted", url);
            return -1;
        }
    }

    private static boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.replace('\n', ' ').trim();
        return oneLine.length() > 300 ? oneLine.substring(0, 300) + "..." : oneLine;
    }
}
