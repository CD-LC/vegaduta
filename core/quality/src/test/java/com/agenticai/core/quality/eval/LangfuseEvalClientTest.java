package com.agenticai.core.quality.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Drives {@link LangfuseEvalClient} against a JDK-built-in {@link
 * HttpServer} on an ephemeral loopback port (no Langfuse, no network beyond
 * localhost, no LLM call - this stays inside the module's "billed calls only
 * in the scheduled workflow" boundary) and asserts the exact paths, bodies
 * and auth header Langfuse's public API expects, plus the fail-open contract
 * on 5xx and on an unreachable host.
 */
class LangfuseEvalClientTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUTH = "Basic cGs6c2s=";

    /** One captured request: method, path, Authorization header, parsed JSON body. */
    private record Captured(String method, String path, String authorization, JsonNode body) {
    }

    private HttpServer server;
    private final List<Captured> captured = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger statusToReturn = new AtomicInteger(200);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] raw = exchange.getRequestBody().readAllBytes();
            JsonNode body = raw.length == 0 ? null : JSON.readTree(raw);
            captured.add(new Captured(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                body));
            byte[] reply = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusToReturn.get(), reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        });
        server.start();
    }

    private boolean serverStopped;

    @AfterEach
    void stopServer() {
        if (!serverStopped) {
            server.stop(0);
            serverStopped = true;
        }
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private LangfuseEvalClient client() {
        return new LangfuseEvalClient(
            HttpClient.newHttpClient(), baseUrl(), AUTH,
            "run-1", "test run", Map.of("model", "m", "gitSha", "abc1234"));
    }

    private static EvalCase caseWith(String id) {
        // null feasibility - resolves to FEASIBLE, see EvalCase#feasibilityOrDefault.
        return new EvalCase(id, "tutor", "grounding", "sys " + id, "user " + id, "rubric " + id, null);
    }

    // ------------------------------------------------------------------
    // Config derivation (pure functions)
    // ------------------------------------------------------------------

    @Test
    void deriveBaseUrlStripsLangfuseOtlpPathExactlyLikeLangfuseScoreClient() {
        assertThat(LangfuseEvalClient.deriveBaseUrl("https://langfuse.example.com/api/public/otel/v1/traces"))
            .isEqualTo("https://langfuse.example.com");
        // Unset falls back to the same docker-compose default core/app uses.
        assertThat(LangfuseEvalClient.deriveBaseUrl(null)).isEqualTo("http://langfuse-web:3000");
        assertThat(LangfuseEvalClient.deriveBaseUrl("  ")).isEqualTo("http://langfuse-web:3000");
        // A bare origin (no OTLP suffix) passes through, trailing slash trimmed.
        assertThat(LangfuseEvalClient.deriveBaseUrl("https://lf.example.com/")).isEqualTo("https://lf.example.com");
    }

    @Test
    void runNamePrefersEvalRunNameThenModelShaTimestamp() {
        Instant now = Instant.parse("2026-09-09T04:05:00Z");
        assertThat(LangfuseEvalClient.resolveRunName(Map.of("EVAL_RUN_NAME", " nightly-abc "), "claude-x", now))
            .isEqualTo("nightly-abc");
        assertThat(LangfuseEvalClient.resolveRunName(Map.of("GITHUB_SHA", "0123456789abcdef"), "claude-x", now))
            .isEqualTo("claude-x-0123456-20260909-0405");
        assertThat(LangfuseEvalClient.resolveRunName(Map.of(), "claude-x", now))
            .isEqualTo("claude-x-local-20260909-0405");
    }

    @Test
    void fromConfigIsNullWithoutAuthHeaderSoTheRunnerStaysUntouched() {
        assertThat(LangfuseEvalClient.fromConfig(Map.of(), "m", "run", 25.0, Instant.now(), HttpClient.newHttpClient()))
            .isNull();
        assertThat(LangfuseEvalClient.fromConfig(Map.of("LANGFUSE_OTEL_AUTH_HEADER", "  "), "m", "run", 25.0,
            Instant.now(), HttpClient.newHttpClient())).isNull();

        LangfuseEvalClient configured = LangfuseEvalClient.fromConfig(
            Map.of("LANGFUSE_OTEL_AUTH_HEADER", AUTH,
                "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT", "http://lf:3000/api/public/otel/v1/traces",
                "EVAL_RUN_NAME", "pr-7-abc"),
            "m", "run", 25.0, Instant.now(), HttpClient.newHttpClient());
        assertThat(configured).isNotNull();
        assertThat(configured.baseUrl()).isEqualTo("http://lf:3000");
        assertThat(configured.runName()).isEqualTo("pr-7-abc");
    }

    // ------------------------------------------------------------------
    // Step 1 - dataset sync
    // ------------------------------------------------------------------

    @Test
    void syncDatasetEnsuresDatasetThenUpsertsOneItemPerCase() {
        client().syncDataset(List.of(caseWith("c1"), caseWith("c2")));

        assertThat(captured).hasSize(3);
        Captured dataset = captured.get(0);
        assertThat(dataset.method()).isEqualTo("POST");
        assertThat(dataset.path()).isEqualTo("/api/public/v2/datasets");
        assertThat(dataset.authorization()).isEqualTo(AUTH);
        assertThat(dataset.body().get("name").asText()).isEqualTo("vegaduta-golden");

        Captured item = captured.get(1);
        assertThat(item.path()).isEqualTo("/api/public/dataset-items");
        assertThat(item.authorization()).isEqualTo(AUTH);
        assertThat(item.body().get("datasetName").asText()).isEqualTo("vegaduta-golden");
        // id == case id is what makes a re-run an upsert, not a duplicate.
        assertThat(item.body().get("id").asText()).isEqualTo("c1");
        assertThat(item.body().get("input").get("systemPrompt").asText()).isEqualTo("sys c1");
        assertThat(item.body().get("input").get("userMessage").asText()).isEqualTo("user c1");
        assertThat(item.body().get("expectedOutput").get("rubric").asText()).isEqualTo("rubric c1");
        assertThat(item.body().get("metadata").get("persona").asText()).isEqualTo("tutor");
        assertThat(item.body().get("metadata").get("category").asText()).isEqualTo("grounding");

        assertThat(captured.get(2).body().get("id").asText()).isEqualTo("c2");
    }

    // ------------------------------------------------------------------
    // Steps 2 + 3 - run item and scores
    // ------------------------------------------------------------------

    @Test
    void recordCaseResultLinksTraceIntoRunAndPostsEveryScore() {
        EvalResult result = EvalResult.fail(caseWith("c1"), "answer", "missed the total", 321)
            .withTraceId("0af7651916cd43dd8448eb211c80319c");

        client().recordCaseResult(result);

        // 1 run item + 5 scores: rubric_pass, outcome and
        // confidence_targeted_score (the three-way verdict, 2026-09), then
        // total_tokens and token_budget_ok.
        assertThat(captured).hasSize(6);
        Captured runItem = captured.get(0);
        assertThat(runItem.path()).isEqualTo("/api/public/dataset-run-items");
        assertThat(runItem.body().get("runName").asText()).isEqualTo("run-1");
        assertThat(runItem.body().get("datasetItemId").asText()).isEqualTo("c1");
        assertThat(runItem.body().get("traceId").asText()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(runItem.body().get("metadata").get("gitSha").asText()).isEqualTo("abc1234");

        Captured rubric = captured.get(1);
        assertThat(rubric.path()).isEqualTo("/api/public/scores");
        assertThat(rubric.body().get("id").asText()).isEqualTo("run-1:c1:rubric_pass");
        assertThat(rubric.body().get("traceId").asText()).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(rubric.body().get("name").asText()).isEqualTo("rubric_pass");
        assertThat(rubric.body().get("dataType").asText()).isEqualTo("BOOLEAN");
        assertThat(rubric.body().get("value").asInt()).isZero();
        assertThat(rubric.body().get("comment").asText()).isEqualTo("missed the total");

        // The three-way outcome, posted alongside rubric_pass rather than
        // replacing it - a correct refusal and a correct answer are both
        // rubric_pass=1, and this is what tells them apart in a run
        // comparison. See EvalOutcome.
        Captured outcome = captured.get(2);
        assertThat(outcome.body().get("id").asText()).isEqualTo("run-1:c1:outcome");
        assertThat(outcome.body().get("name").asText()).isEqualTo("outcome");
        assertThat(outcome.body().get("dataType").asText()).isEqualTo("CATEGORICAL");
        assertThat(outcome.body().get("value").asText()).isEqualTo("INCORRECT");
        assertThat(outcome.body().get("comment").asText()).isEqualTo("missed the total");

        Captured score = captured.get(3);
        assertThat(score.body().get("name").asText()).isEqualTo("confidence_targeted_score");
        assertThat(score.body().get("dataType").asText()).isEqualTo("NUMERIC");
        assertThat(score.body().get("value").asDouble()).isEqualTo(-1.0);

        Captured tokens = captured.get(4);
        assertThat(tokens.body().get("name").asText()).isEqualTo("total_tokens");
        assertThat(tokens.body().get("dataType").asText()).isEqualTo("NUMERIC");
        assertThat(tokens.body().get("value").asInt()).isEqualTo(321);

        Captured budget = captured.get(5);
        assertThat(budget.body().get("name").asText()).isEqualTo("token_budget_ok");
        assertThat(budget.body().get("value").asInt()).isEqualTo(1);
        assertThat(budget.body().has("comment")).isFalse();
    }

    @Test
    void aCorrectlyStoppedCaseScoresAsAPassWithTheAbstainedOutcome() {
        // The Langfuse-side half of the abstention change: an impossible case
        // the assistant correctly declined must post rubric_pass=1 AND an
        // outcome that says WHY it passed, so "it refused" and "it answered"
        // are not the same green row in the run table.
        EvalCase impossible = new EvalCase("c-impossible", "tutor", "tool-judgment", "sys", "user", "rubric",
            EvalTaskFeasibility.INFEASIBLE);
        EvalResult result = EvalResult.abstained(impossible, "answer", "declined - no such integration", 210)
            .withTraceId("0af7651916cd43dd8448eb211c80319c");

        client().recordCaseResult(result);

        assertThat(captured.get(1).body().get("name").asText()).isEqualTo("rubric_pass");
        assertThat(captured.get(1).body().get("value").asInt()).isEqualTo(1);
        assertThat(captured.get(2).body().get("value").asText()).isEqualTo("ABSTAINED_OR_ESCALATED");
        assertThat(captured.get(3).body().get("value").asDouble()).isEqualTo(1.0);
    }

    @Test
    void passingResultScoresOneAndOverBudgetFlipsTokenBudgetOk() {
        EvalResult result = EvalResult.pass(caseWith("c1"), "answer", 500)
            .withTokenBudget(true, "token usage 500 exceeds baseline 300")
            .withTraceId("0af7651916cd43dd8448eb211c80319c");

        client().recordCaseResult(result);

        assertThat(captured.get(1).body().get("value").asInt()).isEqualTo(1);
        Captured budget = captured.get(5);
        assertThat(budget.body().get("value").asInt()).isZero();
        assertThat(budget.body().get("comment").asText()).isEqualTo("token usage 500 exceeds baseline 300");
    }

    @Test
    void erroredResultWithUnknownTokensPostsNoTokenScores() {
        // totalTokens == -1 means "never read" - no NUMERIC score fabricated
        // from it, same honest-absence rule as the report. The verdict scores
        // are still posted: an ungradeable case IS a known outcome
        // (INCORRECT), unlike its token usage.
        EvalResult result = EvalResult.error(caseWith("c1"), "judge exploded")
            .withTraceId("0af7651916cd43dd8448eb211c80319c");

        client().recordCaseResult(result);

        assertThat(captured).hasSize(4);
        assertThat(captured.get(0).path()).isEqualTo("/api/public/dataset-run-items");
        assertThat(captured.get(1).body().get("name").asText()).isEqualTo("rubric_pass");
        assertThat(captured.get(1).body().get("comment").asText()).isEqualTo("judge exploded");
        assertThat(captured.get(2).body().get("value").asText()).isEqualTo("INCORRECT");
        assertThat(captured.get(3).body().get("name").asText()).isEqualTo("confidence_targeted_score");
    }

    @Test
    void resultWithoutTraceIdIsSkippedRatherThanPostedDangling() {
        client().recordCaseResult(EvalResult.pass(caseWith("c1"), "answer", 100));

        assertThat(captured).isEmpty();
    }

    // ------------------------------------------------------------------
    // Fail-open
    // ------------------------------------------------------------------

    @Test
    void serverErrorsAreSwallowedAndSubsequentCallsStillAttempted() {
        statusToReturn.set(500);
        LangfuseEvalClient client = client();

        assertThatCode(() -> client.syncDataset(List.of(caseWith("c1"), caseWith("c2")))).doesNotThrowAnyException();
        assertThatCode(() -> client.recordCaseResult(
            EvalResult.pass(caseWith("c1"), "answer", 10).withTraceId("0af7651916cd43dd8448eb211c80319c")))
            .doesNotThrowAnyException();

        // Every call was still made - one failure never short-circuits the run.
        // 3 for syncDataset (1 dataset + 2 items) + 6 for recordCaseResult
        // (1 run item + 5 scores: rubric_pass, outcome,
        // confidence_targeted_score, total_tokens, token_budget_ok).
        assertThat(captured).hasSize(3 + 6);
    }

    @Test
    void unreachableHostNeverThrows() {
        // Stop the server first so the port refuses connections.
        int port = server.getAddress().getPort();
        stopServer();
        LangfuseEvalClient client = new LangfuseEvalClient(
            HttpClient.newHttpClient(), "http://127.0.0.1:" + port, AUTH, "run-1", "d", Map.of());

        assertThatCode(() -> {
            client.syncDataset(List.of(caseWith("c1")));
            client.recordCaseResult(EvalResult.pass(caseWith("c1"), "a", 1).withTraceId("0af7651916cd43dd8448eb211c80319c"));
        }).doesNotThrowAnyException();
    }

    @Test
    void describeRunNamesTheRunAndDataset() {
        assertThat(client().describeRun()).contains("run-1").contains("vegaduta-golden").contains(baseUrl());
    }
}
