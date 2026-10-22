package com.agenticai.core.quality.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Verifies the NDJSON renderings: every line parses standalone, the header carries run/dataset
 * provenance (version binding, hashes, judge), case lines embed JSON columns as parsed values
 * (never double-encoded), and errored cases keep their status rather than being silently dropped.
 */
class EvalJsonlExportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final UUID agentId = UUID.randomUUID();
    private final UUID datasetId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    private EvalDataset dataset() {
        return new EvalDataset(agentId, "Support golden set", "desc", 0.7, false, "tester");
    }

    private EvalRunCaseResult scoredCase(int position) {
        EvalDatasetCase source =
                new EvalDatasetCase(
                        datasetId,
                        "question " + position,
                        "expected " + position,
                        "[\"search\"]",
                        position);
        return EvalRunCaseResult.scored(
                runId,
                source,
                "answer " + position,
                "[{\"toolName\":\"search\",\"input\":\"q\",\"output\":\"r\"}]",
                "{\"ACCURACY\":{\"score\":0.9}}",
                0.9,
                Boolean.TRUE,
                Boolean.TRUE);
    }

    private static List<JsonNode> lines(String body) throws Exception {
        List<JsonNode> out = new java.util.ArrayList<>();
        for (String line : body.split("\n")) {
            if (!line.isBlank()) {
                out.add(MAPPER.readTree(line));
            }
        }
        return out;
    }

    @Test
    void runExportIsHeaderThenOneLinePerCase() throws Exception {
        EvalRun run =
                new EvalRun(datasetId, agentId, "tenant", "tester", "ROLE_tenant-admin", 0.7, 2);
        // Ids are generated on persist; set one so the header carries it.
        org.springframework.test.util.ReflectionTestUtils.setField(run, "id", runId);
        String body = EvalJsonlExport.run(dataset(), run, List.of(scoredCase(0), scoredCase(1)));

        List<JsonNode> parsed = lines(body);
        assertThat(parsed).hasSize(3);

        JsonNode head = parsed.get(0);
        assertThat(head.get("type").asText()).isEqualTo("run");
        assertThat(head.get("schema").asText()).isEqualTo(EvalJsonlExport.RUN_SCHEMA);
        assertThat(head.get("runId").asText()).isEqualTo(runId.toString());
        assertThat(head.get("passThreshold").asDouble()).isEqualTo(0.7);

        JsonNode c0 = parsed.get(1);
        assertThat(c0.get("type").asText()).isEqualTo("case");
        assertThat(c0.get("input").asText()).isEqualTo("question 0");
        assertThat(c0.get("response").asText()).isEqualTo("answer 0");
        assertThat(c0.get("passed").asBoolean()).isTrue();
        // JSON columns embedded parsed, not stringified.
        assertThat(c0.get("expectedTools").isArray()).isTrue();
        assertThat(c0.get("expectedTools").get(0).asText()).isEqualTo("search");
        assertThat(c0.get("toolCalls").isArray()).isTrue();
        assertThat(c0.get("scores").get("ACCURACY").get("score").asDouble()).isEqualTo(0.9);
    }

    @Test
    void runExportKeepsErroredCasesAndUnparseableColumns() throws Exception {
        EvalDatasetCase source = new EvalDatasetCase(datasetId, "q", null, null, 0);
        EvalRunCaseResult errored = EvalRunCaseResult.errored(runId, source, "replay blew up");
        EvalRunCaseResult corrupt =
                EvalRunCaseResult.scored(
                        runId,
                        new EvalDatasetCase(datasetId, "q2", null, null, 1),
                        "a",
                        "not-json",
                        "also-not-json",
                        0.5,
                        null,
                        Boolean.FALSE);
        EvalRun run =
                new EvalRun(datasetId, agentId, "tenant", "tester", "ROLE_tenant-admin", 0.7, 2);

        List<JsonNode> parsed =
                lines(EvalJsonlExport.run(dataset(), run, List.of(errored, corrupt)));

        assertThat(parsed).hasSize(3);
        assertThat(parsed.get(1).get("status").asText()).isEqualTo("ERROR");
        assertThat(parsed.get(1).get("errorMessage").asText()).isEqualTo("replay blew up");
        // Unparseable JSON columns are preserved verbatim under a *Raw key.
        assertThat(parsed.get(2).get("toolCallsRaw").asText()).isEqualTo("not-json");
        assertThat(parsed.get(2).get("scoresRaw").asText()).isEqualTo("also-not-json");
        assertThat(parsed.get(2).has("toolCalls")).isFalse();
    }

    @Test
    void datasetExportIsHeaderThenLiveCases() throws Exception {
        EvalDatasetCase withGrader = new EvalDatasetCase(datasetId, "q1", "a1", null, 0);
        withGrader.applyGraderSpec("{\"type\":\"exact\",\"expected\":\"a1\"}");
        EvalDatasetCase plain = new EvalDatasetCase(datasetId, "q2", null, "[\"t\"]", 1);

        List<JsonNode> parsed =
                lines(EvalJsonlExport.dataset(dataset(), List.of(withGrader, plain)));

        assertThat(parsed).hasSize(3);
        JsonNode head = parsed.get(0);
        assertThat(head.get("type").asText()).isEqualTo("dataset");
        assertThat(head.get("schema").asText()).isEqualTo(EvalJsonlExport.DATASET_SCHEMA);
        assertThat(head.get("name").asText()).isEqualTo("Support golden set");
        assertThat(head.get("caseCount").asInt()).isEqualTo(2);

        assertThat(parsed.get(1).get("input").asText()).isEqualTo("q1");
        assertThat(parsed.get(1).get("grader").get("type").asText()).isEqualTo("exact");
        assertThat(parsed.get(2).has("grader")).isFalse();
        assertThat(parsed.get(2).get("expectedTools").get(0).asText()).isEqualTo("t");
    }
}
