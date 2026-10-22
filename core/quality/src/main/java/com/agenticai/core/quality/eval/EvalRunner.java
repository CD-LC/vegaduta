package com.agenticai.core.quality.eval;

import com.agenticai.core.json.JsonMappers;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;

import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Master Roadmap 2026 item #15 (CI golden-dataset eval suite, sec B/C) - entry point. Run via
 * {@code mvn -pl quality -am compile exec:java} (the module's eval-harness.md has the full command
 * + env vars) or directly from {@code .github/workflows/eval.yml}.
 *
 * <p>Deliberately fails OPEN, not closed, when it can't actually run a real check: no {@code
 * ANTHROPIC_API_KEY} means every push/PR/fork that hasn't (or can't) configure that secret would
 * otherwise get a hard-failing required check for a job that was never able to do its job in the
 * first place - see the exit-code contract below and this module's eval-harness.md for the full
 * reasoning, which mirrors why {@link
 * com.agenticai.core.agents.turn.quality.LlmQualityJudgeService} (core/agents) also fails open on
 * an ambiguous judge verdict rather than assuming the worst.
 *
 * <p>Exit codes: {@code 0} - either every case passed, or the run was skipped entirely (no API
 * key). {@code 1} - at least one case actually ran and failed its rubric, or a case errored (judge
 * call itself broke) while the key WAS present, since that's a real signal something's wrong, not
 * an absence of one.
 *
 * <p><b>Tracing/log correlation (infrastructure audit fix).</b> This suite previously ran on a bare
 * {@code ChatClient.builder(chatModel)} entirely outside the Spring container - no tracing, no log
 * correlation. Since this module deliberately has no dependency on core/chat (no Spring DI, no DB -
 * see this module's pom.xml), it cannot reuse {@code LlmMetricsAdvisor}/ {@code
 * AnthropicAgentChatClientFactory} without dragging in a live Postgres dependency this harness is
 * explicitly designed not to need. Instead, {@link #buildOpenTelemetry()} bootstraps a minimal,
 * hand-built OpenTelemetry SDK - no Spring Boot autoconfiguration, no Micrometer bridge, just the
 * raw OTel API/SDK - wired to the same {@code OTEL_EXPORTER_OTLP_TRACES_ENDPOINT}/ {@code
 * LANGFUSE_OTEL_AUTH_HEADER} env vars core/app's Spring Boot process already reads from {@code
 * application.yml} (read directly here since there is no {@code application.yml} in this standalone
 * module). Each case's two real LLM calls (generation + judge) run inside one span per case, tagged
 * with the same Langfuse-recognized OTel attributes {@code
 * com.agenticai.core.chat.LlmMetricsAdvisor} (core/chat) uses. A blank/unset {@code
 * LANGFUSE_OTEL_AUTH_HEADER} - the common case for a CI runner with no Langfuse reachable -
 * degrades to {@link OpenTelemetry#noop()}: spans are still created (so {@link EvalCaseRun#runCase}
 * always has a real trace/span id to log for correlation) but nothing is ever exported over the
 * network, the same "off is a real no-op, not just hidden" posture {@code
 * management.tracing.sampling.probability=0.0} already has in core/app. A per-run correlation id
 * ({@code evalRunId}) is placed in SLF4J's MDC for the whole run, mirroring {@code
 * RequestCorrelationFilter}'s standing pattern elsewhere in this codebase, so every log line -
 * including each case's own trace id - can be grepped together for one run.
 *
 * <p><b>Why no {@code LlmMetricsAdvisor} (re-checked 2026-09-28).</b> The advisor is not a shared
 * bean: provider factories construct it per model around a JPA-backed {@code LlmCallLogService}, so
 * attaching it here would need the live Postgres and tenant context this CLI is designed to run
 * without. Eval spend is attributable anyway: every case's generation + judge token usage is on
 * {@link EvalResult#totalTokens()}, checked against {@link TokenBaseline}, printed in the CI log
 * and - when Langfuse is configured - attached to the case's trace and dataset-run item by {@link
 * LangfuseEvalClient}. What this runner DOES now share with core/chat is the LLM HTTP timeout
 * policy - see {@link EvalLlmHttp#connectTimeout()}.
 *
 * <p><b>Langfuse experiments (2026-09).</b> When the same {@code LANGFUSE_OTEL_AUTH_HEADER} that
 * turns trace export on is present, every run is ALSO mirrored as a Langfuse dataset run via {@link
 * LangfuseEvalClient}: the golden cases are upserted into the {@code vegaduta-golden} dataset at
 * run start, and each case's pass/fail, judge reason and token usage are attached to the trace this
 * runner already exports for it - so "did this prompt edit regress case X" becomes a side-by-side
 * run comparison in the Langfuse UI rather than a manual diff of two CI logs. Strictly additive and
 * fail-open: with the header unset nothing is posted, and any Langfuse error is a WARN that never
 * touches the exit code. {@code EVAL_RUN_NAME} names the run (see eval-harness.md).
 *
 * <p><b>Abstention as a first-class outcome (2026-09).</b> The judge verdict is now three-valued -
 * {@code PASS} / {@code FAIL} / {@code ABSTAIN} - and the verdict is purely descriptive: the judge
 * reports WHAT the assistant did, and this runner decides whether that was right by pairing it with
 * the case's {@link EvalTaskFeasibility}. A correct refusal on an impossible or under-specified
 * task is a PASS and exits 0; a refusal on a task that was actually doable is a failure and exits
 * 1, same as a wrong answer, but scores {@code 0.0} rather than {@code -1.0} on {@link
 * EvalResult#confidenceTargetedScore()} because a refusal misleads nobody. See {@link EvalOutcome}
 * for the incentive argument this rests on - a two-valued verdict pays nothing for a correct "I
 * cannot do this," which is an active push toward confident guessing, not a neutral omission.
 *
 * <p>Nothing about the suite AS IT STANDS changes: every case in {@code golden-dataset.json} today
 * omits {@code feasibility} and therefore grades as {@link EvalTaskFeasibility#FEASIBLE}, exactly
 * as before. The honest consequence of that is that abstention is now measurable but not yet
 * MEASURED - the dataset still contains no impossible or under-specified case, so the only
 * abstention this run can currently observe is a model declining something it should have done.
 * Seeding those cases is the follow-up that turns this from a capability into a number.
 */
public final class EvalRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private static final String DATASET_RESOURCE = "/golden-dataset.json";

    /**
     * Which dataset {@link #loadDataset(String)} reads. {@code EVAL_DATASET} accepts a classpath
     * resource name shipped in this module ({@code golden-dataset.json} default, {@code
     * edge-faq-dataset.json} for the on-device/self-hosted FAQ suite) or an absolute filesystem
     * path for a dataset under development. Same env-var convention as {@code EVAL_MODEL} - this
     * module has no CLI parser.
     */
    private static final String DATASET_ENV_VAR = "EVAL_DATASET";

    private static final String DEFAULT_DATASET_NAME = "golden-dataset.json";

    /**
     * Generation backend switch for the self-hosted/on-device eval path (P3: "prove the
     * WebLLM/Ollama tier is good enough for FAQ traffic"). {@code EVAL_BACKEND=anthropic} (default)
     * keeps the original behaviour; {@code EVAL_BACKEND=openai} points generation at ANY
     * OpenAI-compatible endpoint - Ollama ({@code EVAL_BASE_URL=http://localhost:11434}), a
     * vLLM/llama.cpp server, or a WebLLM proxy - via {@code EVAL_BASE_URL}, {@code EVAL_API_KEY}
     * (Ollama ignores it; any non-empty string works) and {@code EVAL_MODEL}. The JUDGE stays on
     * the hosted model unless {@code EVAL_JUDGE_*} vars redirect it - grading a small model with
     * itself would measure "does it like its own answers", not quality.
     */
    private static final String BACKEND_ENV_VAR = "EVAL_BACKEND";

    /**
     * Same MDC key {@code RequestCorrelationFilter} (core/security) uses for the equivalent
     * per-HTTP-request id.
     */
    private static final String MDC_REQUEST_ID_KEY = "requestId";

    /**
     * Langfuse's documented native OTLP ingestion path (application.yml's
     * management.otlp.tracing.endpoint default).
     */
    private static final String DEFAULT_OTLP_ENDPOINT =
            "http://langfuse-web:3000/api/public/otel/v1/traces";

    /**
     * Token/cost-regression threshold, as a percentage over a case's stored {@link TokenBaseline} -
     * env var, matching this module's existing {@code EVAL_MODEL} convention (this standalone
     * module has no {@code application.yml} to add a property to, see class javadoc's tracing
     * section for why). 25% default: loose enough that ordinary generation-length variance across
     * runs doesn't flap the check, tight enough to still catch a real regression (a dropped
     * prompt-caching config, an accidentally-verbose rubric addition, a model swap that tokenizes
     * far less efficiently).
     */
    private static final String TOKEN_THRESHOLD_ENV_VAR = "EVAL_TOKEN_REGRESSION_THRESHOLD_PCT";

    private static final double DEFAULT_TOKEN_THRESHOLD_PCT = 25.0;

    /**
     * Comma-separated {@link EvalCase#id()} allowlist - same env-var convention as {@code
     * EVAL_MODEL}/{@link #TOKEN_THRESHOLD_ENV_VAR} (this standalone module has no {@code
     * application.yml}/CLI-arg parser to add a flag to instead). Added so a fast, cheap PR-gate
     * workflow (see {@code .github/workflows/eval-pr-gate.yml}) can run a small high-signal subset
     * of the golden dataset on every PR without paying for all ~35 cases x 2 calls each - the
     * nightly workflow ({@code .github/workflows/eval.yml}) leaves this unset and always runs the
     * full dataset, unaffected. Unset/blank means "no filter, run everything," matching every other
     * env var this runner reads.
     */
    private static final String CASE_IDS_ENV_VAR = "EVAL_CASE_IDS";

    public static void main(String[] args) {
        // Per-run correlation id, placed in MDC for the whole run - see
        // class javadoc's tracing/log-correlation section. Set before the
        // ANTHROPIC_API_KEY check too, so even a SKIPPED run's log line
        // carries it (harmless either way, costs nothing).
        String evalRunId = UUID.randomUUID().toString();
        MDC.put(MDC_REQUEST_ID_KEY, evalRunId);

        String backend =
                System.getenv()
                        .getOrDefault(BACKEND_ENV_VAR, "anthropic")
                        .trim()
                        .toLowerCase(java.util.Locale.ROOT);
        boolean openAiBackend = "openai".equals(backend);

        String apiKey;
        if (openAiBackend) {
            // OpenAI-compatible backends (Ollama, vLLM, llama.cpp, a WebLLM
            // proxy) are gated on EVAL_BASE_URL - the endpoint, not a key, is
            // the thing that must exist. EVAL_API_KEY defaults to a placeholder
            // because Ollama accepts any non-empty bearer and some gateways
            // ignore auth entirely.
            if (System.getenv("EVAL_BASE_URL") == null
                    || System.getenv("EVAL_BASE_URL").isBlank()) {
                System.out.println(
                        "[eval] SKIPPED - EVAL_BACKEND=openai but EVAL_BASE_URL is not set. This is"
                            + " not a failure: point it at an OpenAI-compatible endpoint (e.g."
                            + " http://localhost:11434 for Ollama) to run the self-hosted/on-device"
                            + " eval path.");
                System.exit(0);
                return;
            }
            apiKey = System.getenv().getOrDefault("EVAL_API_KEY", "eval-placeholder-key");
        } else {
            apiKey = System.getenv("ANTHROPIC_API_KEY");
            if (apiKey == null || apiKey.isBlank()) {
                System.out.println(
                        "[eval] SKIPPED - ANTHROPIC_API_KEY is not set. This is not a failure: the"
                            + " eval suite needs a real, billed LLM key to run (see"
                            + " core/quality/eval-harness.md). Configure the ANTHROPIC_API_KEY"
                            + " secret to actually exercise the golden dataset.");
                System.exit(0);
                return;
            }
        }

        String model =
                System.getenv()
                        .getOrDefault("EVAL_MODEL", openAiBackend ? "qwen3:4b" : "claude-sonnet-5");
        String datasetName = System.getenv().getOrDefault(DATASET_ENV_VAR, DEFAULT_DATASET_NAME);

        List<EvalCase> cases;
        try {
            cases = loadDataset(datasetName);
        } catch (IOException e) {
            System.err.println(
                    "[eval] FATAL - could not load " + datasetName + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        if (cases.isEmpty()) {
            System.out.println("[eval] golden-dataset.json has no cases - nothing to run.");
            System.exit(0);
            return;
        }

        cases = filterByIds(cases, System.getenv(CASE_IDS_ENV_VAR));
        if (cases.isEmpty()) {
            System.out.println(
                    "[eval] " + CASE_IDS_ENV_VAR + " matched zero cases - nothing to run.");
            System.exit(0);
            return;
        }

        ChatClient chatClient =
                buildChatClient(
                        backend,
                        apiKey,
                        model,
                        openAiBackend ? System.getenv("EVAL_BASE_URL") : null);
        // Judge client: same backend/endpoint as generation by default, or a
        // different model/endpoint entirely via EVAL_JUDGE_* - the documented
        // FAQ-eval posture is generation on the small/self-hosted model with
        // judging on a hosted frontier model.
        ChatClient judgeClient = buildJudgeClient(backend, chatClient);

        double tokenThresholdPct = parseTokenThresholdPct();
        TokenBaseline tokenBaseline = TokenBaseline.load();

        // Null when LANGFUSE_OTEL_AUTH_HEADER is unset - the same variable
        // that gates real trace export below, so a run item is never posted
        // against a trace id nothing exported. See class javadoc. Skipped for
        // non-default datasets: edge-faq cases must NOT be upserted into the
        // vegaduta-golden Langfuse dataset, which is pinned to that file.
        boolean defaultDataset = DEFAULT_DATASET_NAME.equals(datasetName);
        LangfuseEvalClient langfuse =
                defaultDataset
                        ? LangfuseEvalClient.fromEnv(model, evalRunId, tokenThresholdPct)
                        : null;
        if (langfuse != null) {
            // Only the cases about to run (post EVAL_CASE_IDS filter) - see
            // LangfuseEvalClient#syncDataset for why not the whole file.
            langfuse.syncDataset(cases);
        }

        OpenTelemetrySdk openTelemetry = buildOpenTelemetry();
        Tracer tracer = openTelemetry.getTracer("com.agenticai.core.quality.eval.EvalRunner");
        log.info(
                "[eval] starting run {} ({} case(s), model={}, token-regression threshold={}%,"
                    + " langfuseRun={})",
                evalRunId,
                cases.size(),
                model,
                tokenThresholdPct,
                langfuse == null ? "off" : langfuse.runName());

        List<EvalResult> results = new ArrayList<>();
        // Wall-clock ms per case (generation + judge) - parallel to results.
        // Latency is half the FAQ-traffic question for the edge tier ("good
        // enough AND fast enough"), so the report ends with a p50/max line.
        List<Long> latenciesMs = new ArrayList<>();
        // EVAL_REPEATS (default 1 = the original single-sample behaviour, nothing below changes). >
        // 1 runs every
        // case that many times and gates on a strict majority; see EvalRepeats.
        int repeats = EvalRepeats.parseRepeats(System.getenv(EvalRepeats.REPEATS_ENV_VAR));
        Double minPassHatK =
                EvalRepeats.parseMinPassHatK(System.getenv(EvalRepeats.MIN_PASS_HAT_K_ENV_VAR));
        List<EvalRepeats.CaseSummary> repeatSummaries = new ArrayList<>();
        if (repeats > 1) {
            log.info(
                    "[eval] repeats={} - every case runs {}x ({} billed model calls per case), gate"
                        + " = strict majority per case, min mean pass^k={}",
                    repeats,
                    repeats,
                    repeats * 2,
                    minPassHatK == null ? "none" : minPassHatK);
        }
        try {
            for (EvalCase evalCase : cases) {
                long start = System.nanoTime();
                EvalResult result =
                        EvalCaseRun.runCase(chatClient, judgeClient, evalCase, tracer, evalRunId);
                latenciesMs.add((System.nanoTime() - start) / 1_000_000);
                result = checkTokenBudget(result, tokenBaseline, tokenThresholdPct);
                if (repeats > 1) {
                    List<EvalResult> rollouts = new ArrayList<>(repeats);
                    rollouts.add(result);
                    for (int i = 1; i < repeats; i++) {
                        long rolloutStart = System.nanoTime();
                        EvalResult extra =
                                EvalCaseRun.runCase(
                                        chatClient, judgeClient, evalCase, tracer, evalRunId);
                        latenciesMs.add((System.nanoTime() - rolloutStart) / 1_000_000);
                        rollouts.add(checkTokenBudget(extra, tokenBaseline, tokenThresholdPct));
                    }
                    EvalRepeats.CaseSummary summary = EvalRepeats.summarize(rollouts);
                    repeatSummaries.add(summary);
                    // The per-case report, Langfuse score and token check below see the
                    // representative rollout.
                    result = summary.representative();
                }
                results.add(result);
                if (langfuse != null) {
                    // After the token check so token_budget_ok reflects the
                    // final verdict; after span.end() (inside runCase) so
                    // the trace is already exported when Langfuse links it.
                    langfuse.recordCaseResult(result);
                }
            }
        } finally {
            // Short-lived CLI process - flush/close explicitly rather than
            // relying on a JVM shutdown hook, since System.exit below runs
            // right after this. SimpleSpanProcessor (see buildOpenTelemetry)
            // already exports synchronously on span#end(), so this is a
            // belt-and-braces close of the exporter's own HTTP client, not
            // the only thing standing between a span and actually being sent.
            openTelemetry.getSdkTracerProvider().shutdown();
        }

        printReport(results);
        if (repeats > 1) {
            System.out.print(EvalRepeats.formatReport(repeatSummaries, minPassHatK));
        }
        if (!latenciesMs.isEmpty()) {
            List<Long> sorted = new ArrayList<>(latenciesMs);
            java.util.Collections.sort(sorted);
            long p50 = sorted.get(sorted.size() / 2);
            long max = sorted.get(sorted.size() - 1);
            System.out.printf(
                    java.util.Locale.ROOT,
                    "[eval] per-case latency: p50=%dms max=%dms (generation+judge, backend=%s,"
                        + " model=%s)%n",
                    p50,
                    max,
                    backend,
                    model);
        }
        if (langfuse != null) {
            System.out.println(langfuse.describeRun());
        }

        boolean anyFailed =
                repeats > 1
                        ? EvalRepeats.gateFailed(repeatSummaries, minPassHatK)
                        : results.stream().anyMatch(r -> !r.passed() || r.tokenBudgetExceeded());
        System.exit(anyFailed ? 1 : 0);
    }

    /**
     * Parses {@link #TOKEN_THRESHOLD_ENV_VAR}, falling back to {@link #DEFAULT_TOKEN_THRESHOLD_PCT}
     * on unset/blank/unparseable input - never throws, since a malformed env var shouldn't crash
     * the whole run over a secondary check.
     */
    private static double parseTokenThresholdPct() {
        String raw = System.getenv(TOKEN_THRESHOLD_ENV_VAR);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_TOKEN_THRESHOLD_PCT;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            log.warn(
                    "[eval] {}={} is not a valid number - falling back to default {}%",
                    TOKEN_THRESHOLD_ENV_VAR, raw, DEFAULT_TOKEN_THRESHOLD_PCT);
            return DEFAULT_TOKEN_THRESHOLD_PCT;
        }
    }

    /**
     * Compares {@code result}'s token usage against its stored {@link TokenBaseline} entry, if any,
     * and returns a copy flagged accordingly. A case with no baseline on file, or a result with
     * unknown usage (errored before a token count could be read, {@code totalTokens < 0}), is left
     * unflagged - see {@link TokenBaseline}'s javadoc for why an absent baseline is "nothing to
     * compare against" rather than a failure.
     */
    private static EvalResult checkTokenBudget(
            EvalResult result, TokenBaseline baseline, double thresholdPct) {
        if (result.totalTokens() < 0) {
            return result;
        }
        Integer baselineTokens = baseline.get(result.evalCase().id());
        if (baselineTokens == null || baselineTokens <= 0) {
            return result;
        }
        double allowedTokens = baselineTokens * (1.0 + thresholdPct / 100.0);
        if (result.totalTokens() <= allowedTokens) {
            return result;
        }
        double actualPctIncrease =
                ((double) result.totalTokens() - baselineTokens) / baselineTokens * 100.0;
        String reason =
                String.format(
                        "token usage %d exceeds baseline %d by %.1f%% (threshold %.1f%%)",
                        result.totalTokens(), baselineTokens, actualPctIncrease, thresholdPct);
        log.warn("[eval] case {} token-budget regression: {}", result.evalCase().id(), reason);
        return result.withTokenBudget(true, reason);
    }

    /**
     * Bootstraps a standalone OpenTelemetry SDK - see class javadoc for the full "why not just
     * reuse core/chat" reasoning. Reads the exact same env vars core/app's {@code application.yml}
     * does ({@code OTEL_EXPORTER_OTLP_TRACES_ENDPOINT}, {@code LANGFUSE_OTEL_AUTH_HEADER}), config
     * reuse rather than invention. The OTLP exporter is always built (whether or not {@code
     * LANGFUSE_OTEL_AUTH_HEADER} is set) so every case always gets a real trace/span id for log
     * correlation even when nothing is actually reachable to export to - e.g. a plain CI runner
     * with no Langfuse deployed. If the configured endpoint genuinely isn't reachable, the OTLP
     * exporter's own built-in failure handling (log a warning, never throw) absorbs it silently,
     * exactly like {@code management.otlp.tracing.endpoint} being unreachable would in core/app -
     * this run's actual pass/fail result is never affected either way.
     */
    private static OpenTelemetrySdk buildOpenTelemetry() {
        String endpoint =
                System.getenv()
                        .getOrDefault("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", DEFAULT_OTLP_ENDPOINT);
        String authHeader = System.getenv("LANGFUSE_OTEL_AUTH_HEADER");

        var exporterBuilder =
                OtlpHttpSpanExporter.builder()
                        .setEndpoint(endpoint)
                        .setTimeout(Duration.ofSeconds(10));
        if (authHeader != null && !authHeader.isBlank()) {
            exporterBuilder.addHeader("Authorization", authHeader);
        }

        SdkTracerProvider tracerProvider =
                SdkTracerProvider
                        .builder() // SimpleSpanProcessor (synchronous, exports on span#end()), not
                        // BatchSpanProcessor - this is a short-lived CLI run with at most
                        // a handful of spans, not a long-running service, so there is no
                        // benefit to batching and a real risk of losing spans to
                        // System.exit before an async batch would have flushed.
                        .addSpanProcessor(SimpleSpanProcessor.create(exporterBuilder.build()))
                        .build();

        return OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
    }

    /**
     * Package-private (not private) so {@code GoldenDatasetTest} can load the dataset through the
     * exact same code path the real run uses - testing a copy of this logic would let the two
     * silently drift.
     */
    static List<EvalCase> loadDataset() throws IOException {
        return loadDataset(DEFAULT_DATASET_NAME);
    }

    /**
     * Loads the dataset named by {@code nameOrPath} - a classpath resource shipped in this module
     * ({@code "edge-faq-dataset.json"}) or an absolute filesystem path for a dataset under
     * development. The classpath lookup is tried first so a shipped name never accidentally reads a
     * file.
     */
    static List<EvalCase> loadDataset(String nameOrPath) throws IOException {
        ObjectMapper mapper = JsonMappers.plain();
        var type = mapper.getTypeFactory().constructCollectionType(List.class, EvalCase.class);
        String resource = nameOrPath.startsWith("/") ? nameOrPath : "/" + nameOrPath;
        try (InputStream in = EvalRunner.class.getResourceAsStream(resource)) {
            if (in != null) {
                return mapper.readValue(in, type);
            }
        }
        java.nio.file.Path path = java.nio.file.Path.of(nameOrPath);
        if (java.nio.file.Files.exists(path)) {
            return mapper.readValue(path.toFile(), type);
        }
        throw new IOException(
                "Resource " + resource + " not found on classpath and no file at " + nameOrPath);
    }

    /**
     * Filters {@code cases} down to the ids named in {@code rawCsv} (comma- separated, whitespace
     * around each id trimmed), preserving the dataset's original order. {@code null}/blank {@code
     * rawCsv} returns {@code cases} unchanged - this is the "no filter configured" path the nightly
     * workflow always takes. An id in {@code rawCsv} that matches no case is logged as a warning
     * (likely a stale/typo'd id in a workflow file) rather than failing the run - see class
     * javadoc's "fails open" posture. Package-private, not private, so {@code EvalRunnerReportTest}
     * can assert on it without a network call.
     */
    static List<EvalCase> filterByIds(List<EvalCase> cases, String rawCsv) {
        if (rawCsv == null || rawCsv.isBlank()) {
            return cases;
        }
        Set<String> requestedIds = new LinkedHashSet<>();
        for (String id : rawCsv.split(",")) {
            String trimmed = id.trim();
            if (!trimmed.isEmpty()) {
                requestedIds.add(trimmed);
            }
        }
        List<EvalCase> filtered = new ArrayList<>();
        for (EvalCase evalCase : cases) {
            if (requestedIds.remove(evalCase.id())) {
                filtered.add(evalCase);
            }
        }
        for (String unmatched : requestedIds) {
            log.warn(
                    "[eval] {}={} named case id '{}' which does not exist in golden-dataset.json -"
                        + " ignoring",
                    CASE_IDS_ENV_VAR,
                    rawCsv,
                    unmatched);
        }
        return filtered;
    }

    private static ChatClient buildChatClient(
            String backend, String apiKey, String model, String baseUrl) {
        if ("openai".equals(backend)) {
            return ChatClient.builder(
                            EvalModelBuilder.openAiCompat(
                                    baseUrl,
                                    apiKey,
                                    model,
                                    1024,
                                    EvalLlmHttp.connectTimeout(),
                                    EvalLlmHttp.readTimeout()))
                    .build();
        }
        // No temperature/topP set at all - simplest safe choice for a
        // one-shot script that has to work across whichever model
        // EVAL_MODEL names, including ones that reject `temperature`
        // outright (see AnthropicAgentChatClientFactory's own
        // TEMPERATURE_DEPRECATED_MODEL_PREFIXES comment for a real example
        // of that failure mode) - Anthropic just applies its own default.
        return ChatClient.builder(
                        EvalModelBuilder.anthropic(
                                apiKey,
                                model,
                                1024,
                                EvalLlmHttp.connectTimeout(),
                                EvalLlmHttp.readTimeout()))
                .build();
    }

    /**
     * Builds the judge client, defaulting to the generation client itself unless {@code
     * EVAL_JUDGE_*} vars redirect it. The documented split for the edge/self-hosted path:
     * generation on the small model under test ({@code EVAL_BACKEND=openai EVAL_BASE_URL=...
     * EVAL_MODEL=qwen3:4b}), judging on a hosted frontier model ({@code
     * EVAL_JUDGE_BACKEND=anthropic EVAL_JUDGE_API_KEY=$ANTHROPIC_API_KEY
     * EVAL_JUDGE_MODEL=claude-sonnet-5}). A small model grading itself is not a quality signal.
     */
    private static ChatClient buildJudgeClient(
            String generationBackend, ChatClient generationClient) {
        String judgeBackend =
                System.getenv()
                        .getOrDefault("EVAL_JUDGE_BACKEND", generationBackend)
                        .trim()
                        .toLowerCase(java.util.Locale.ROOT);
        String judgeModel = System.getenv("EVAL_JUDGE_MODEL");
        String judgeBaseUrl = System.getenv("EVAL_JUDGE_BASE_URL");
        String judgeApiKey = System.getenv("EVAL_JUDGE_API_KEY");
        if (judgeModel == null
                && judgeBaseUrl == null
                && judgeApiKey == null
                && judgeBackend.equals(generationBackend)) {
            return generationClient;
        }
        String model =
                judgeModel != null && !judgeModel.isBlank()
                        ? judgeModel
                        : System.getenv()
                                .getOrDefault(
                                        "EVAL_MODEL",
                                        "anthropic".equals(judgeBackend)
                                                ? "claude-sonnet-5"
                                                : "qwen3:4b");
        String baseUrl = judgeBaseUrl != null ? judgeBaseUrl : System.getenv("EVAL_BASE_URL");
        String apiKey =
                judgeApiKey != null && !judgeApiKey.isBlank()
                        ? judgeApiKey
                        : "openai".equals(judgeBackend)
                                ? System.getenv()
                                        .getOrDefault("EVAL_API_KEY", "eval-placeholder-key")
                                : System.getenv("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            // No judge credential for the redirected backend - keep the
            // generation client rather than crash the whole run into an
            // auth error on its second call.
            log.warn(
                    "[eval] EVAL_JUDGE_* set but no usable API key for judge backend '{}' - "
                            + "falling back to the generation client for judging",
                    judgeBackend);
            return generationClient;
        }
        return buildChatClient(judgeBackend, apiKey, model, baseUrl);
    }

    private static void printReport(List<EvalResult> results) {
        System.out.println(EvalReport.formatReport(results));
    }

    private EvalRunner() {}
}
