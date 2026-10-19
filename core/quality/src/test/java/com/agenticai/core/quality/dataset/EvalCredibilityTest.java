package com.agenticai.core.quality.dataset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.quality.grader.CodeGrader;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** V375: agent-config snapshot, grader-aware dataset hash, run bundle, research seed. */
class EvalCredibilityTest {

    private static final UUID AGENT_ID = UUID.randomUUID();
    private static final UUID DATASET_ID = UUID.randomUUID();

    // ---- AgentConfigSnapshot ------------------------------------------------

    private static AgentConfigSnapshot snapshot(String promptHash, List<String> tools, Double temp) {
        return new AgentConfigSnapshot("gpt-4o", temp, 1.0, 1024, 7, promptHash, tools);
    }

    @Test
    void snapshotHashIsStableAndSensitiveToEveryComponent() {
        String base = snapshot("p1", List.of("a", "b"), 0.2).hash();
        assertEquals(base, snapshot("p1", List.of("a", "b"), 0.2).hash());
        assertEquals(64, base.length());
        assertNotEquals(base, snapshot("p2", List.of("a", "b"), 0.2).hash());   // prompt changed
        assertNotEquals(base, snapshot("p1", List.of("a"), 0.2).hash());        // tool removed
        assertNotEquals(base, snapshot("p1", List.of("a", "b"), 0.7).hash());   // temperature changed
    }

    @Test
    void snapshotCanonicalJsonHasSortedKeysAndNoPromptText() {
        String json = snapshot("deadbeef", List.of("t"), 0.5).canonicalJson();
        assertTrue(json.startsWith("{\"maxTokens\":1024,\"model\":\"gpt-4o\",\"seed\":7"), json);
        assertTrue(json.contains("\"systemPromptSha256\":\"deadbeef\""));
        assertTrue(json.contains("\"tools\":[\"t\"]"));
    }

    @Test
    void applyAgentSnapshotFillsTheRunRowAndHashMatchesTheJson() {
        EvalRun run = new EvalRun(DATASET_ID, AGENT_ID, "tenant", "user", "ROLE_USER", 0.7, 1);
        assertNull(run.getAgentConfigHash());
        AgentConfigSnapshot snap = snapshot("p1", List.of("a"), 0.3);
        run.applyAgentSnapshot(snap);
        assertEquals("gpt-4o", run.getAgentModel());
        assertEquals(0.3, run.getAgentTemperature());
        assertEquals(7, run.getAgentSeed());
        assertEquals(snap.hash(), run.getAgentConfigHash());
        assertEquals(CanonicalJson.sha256Hex(run.getAgentConfigJson()), run.getAgentConfigHash());
    }

    // ---- DatasetContentHash (grader-aware) ----------------------------------

    @Test
    void graderChangesTheDatasetHashButAbsentGraderLeavesItUntouched() {
        EvalDataset dataset = new EvalDataset(AGENT_ID, "d", null, 0.7, true, "t");
        EvalDatasetCase plain = new EvalDatasetCase(DATASET_ID, "q", "a", null, 0);
        String before = DatasetContentHash.of(dataset, List.of(plain));

        EvalDatasetCase graded = new EvalDatasetCase(DATASET_ID, "q", "a", null, 0);
        graded.applyGraderSpec("{\"type\":\"exact\",\"value\":\"a\"}");
        String withGrader = DatasetContentHash.of(dataset, List.of(graded));
        assertNotEquals(before, withGrader);

        graded.applyGraderSpec("{\"type\":\"exact\",\"value\":\"b\"}");
        assertNotEquals(withGrader, DatasetContentHash.of(dataset, List.of(graded)));

        graded.applyGraderSpec(null);
        assertEquals(before, DatasetContentHash.of(dataset, List.of(graded)));
    }

    // ---- EvalRunBundle -------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void bundleDigestCoversTheCanonicalContentAndDetectsEdits() {
        EvalDataset dataset = new EvalDataset(AGENT_ID, "research", null, 0.7, true, "t");
        dataset.markContentHash("h-now");
        EvalDatasetCase c = new EvalDatasetCase(DATASET_ID, "Capital of Japan?", "Tokyo", null, 0);
        c.applyGraderSpec("{\"type\":\"regex\",\"pattern\":\"tokyo\",\"ignoreCase\":true}");
        EvalRun run = new EvalRun(DATASET_ID, AGENT_ID, "tenant", "secret-user", "ROLE_USER", 0.7, 1);
        run.applyAgentSnapshot(snapshot("p1", List.of("web_search"), 0.0));
        EvalRunCaseResult result = EvalRunCaseResult.scored(
            run.getId(), c, "Tokyo", null, "{\"CODE_GRADER\":{\"score\":1.0,\"status\":\"EVALUATED\"}}",
            1.0, null, Boolean.TRUE);

        EvalRunBundle.Bundle bundle = EvalRunBundle.build(dataset, List.of(c), run, List.of(result));
        assertEquals(EvalRunBundle.SCHEMA, bundle.schema());
        assertEquals(64, bundle.sha256().length());
        // Verification recipe from the javadoc: canonicalise 'content', hash it.
        assertEquals(CanonicalJson.sha256Hex(CanonicalJson.write(bundle.content())), bundle.sha256());

        Map<String, Object> agent = (Map<String, Object>) bundle.content().get("agent");
        assertEquals("gpt-4o", agent.get("model"));
        assertEquals(run.getAgentConfigHash(), agent.get("configHash"));
        assertTrue(agent.get("config") instanceof Map, "config nests as JSON, not an escaped string");
        List<Object> cases = (List<Object>) bundle.live().get("cases");
        assertTrue(((Map<String, Object>) cases.get(0)).get("grader") instanceof Map);
        assertTrue(!bundle.content().containsKey("cases"), "live cases are not part of the hashed content");

        // Renaming / editing the dataset afterwards must not change the digest.
        dataset.updateDefinition("renamed", "now with a description", 0.9, false);
        dataset.markContentHash("h-later");
        c.applyGraderSpec(null);
        assertEquals(bundle.sha256(),
            EvalRunBundle.build(dataset, List.of(c), run, List.of(result)).sha256());
        // Requester identity must not leak into a shareable bundle.
        assertTrue(!CanonicalJson.write(bundle.content()).contains("secret-user"));

        // Same inputs -> same digest; a changed response -> different digest.
        assertEquals(bundle.sha256(),
            EvalRunBundle.build(dataset, List.of(c), run, List.of(result)).sha256());
        EvalRunCaseResult tampered = EvalRunCaseResult.scored(
            run.getId(), c, "Kyoto", null, null, 0.0, null, Boolean.FALSE);
        assertNotEquals(bundle.sha256(),
            EvalRunBundle.build(dataset, List.of(c), run, List.of(tampered)).sha256());
    }

    @Test
    void bundleOfAPreV375RunHasNullAgentSnapshotNotAFailure() {
        EvalDataset dataset = new EvalDataset(AGENT_ID, "d", null, 0.7, false, "t");
        EvalRun run = new EvalRun(DATASET_ID, AGENT_ID, "tenant", "user", "ROLE_USER", 0.7, 0);
        EvalRunBundle.Bundle bundle = EvalRunBundle.build(dataset, List.of(), run, List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> agent = (Map<String, Object>) bundle.content().get("agent");
        assertNull(agent.get("configHash"));
        assertNotNull(bundle.sha256());
    }

    // ---- Research seed -------------------------------------------------------

    @Test
    void researchSeedIsWellFormedAndEveryReferenceAnswerPassesItsOwnGrader() {
        JsonNode seed = ResearchSeedService.loadSeed();
        assertTrue(seed.path("toolExecutionEnabled").asBoolean());
        JsonNode tasks = seed.path("tasks");
        assertTrue(tasks.size() >= 10, "starter set should carry at least ten tasks");
        Set<String> ids = new HashSet<>();
        for (JsonNode task : tasks) {
            assertTrue(ids.add(task.path("id").asText()), "duplicate id " + task.path("id").asText());
            assertTrue(task.path("question").asText().length() > 20);
            String answer = task.path("answer").asText();
            CodeGrader grader = CodeGrader.parse(task.path("grader").toString()); // parses cleanly
            assertTrue(grader.grade(answer).passed(),
                "reference answer must satisfy its own grader: " + task.path("id").asText());
            assertTrue(!grader.grade("I could not find an answer.").passed(),
                "grader must not pass a non-answer: " + task.path("id").asText());
        }
    }
}
