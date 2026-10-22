package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Deterministic harness for {@link MemoryWriteArbitrationService}: a fake LLM (scripted from the
 * fixture) and an in-memory {@link GraphMemoryService} stand-in whose {@code supersede} mirrors the
 * real one (drop same subject+relation edges with a different object, then add). The fixture {@code
 * arbitration/arbitration-cases.json} (LongMemEval-style add / update / contradiction / merge /
 * discard / failure cases) asserts the FINAL STORE, so a lost memory or a wrongly kept stale fact
 * fails a case. It does not replace verifying {@code supersede} on real AGE Postgres - see the flip
 * checklist in the service javadoc.
 */
class MemoryWriteArbitrationHarnessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID AGENT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String SUBJECT_KEY = "user-1";

    @TestFactory
    Stream<DynamicTest> fixtureCases() throws Exception {
        List<JsonNode> cases = loadCases();
        return cases.stream()
                .map(
                        c ->
                                DynamicTest.dynamicTest(
                                        c.get("cat").asText() + ": " + c.get("n").asText(),
                                        () -> runCase(c)));
    }

    @Test
    void fixtureHasAtLeastThirtyCasesAcrossEveryCategory() throws Exception {
        List<JsonNode> cases = loadCases();
        assertThat(cases.size()).isGreaterThanOrEqualTo(30);
        Set<String> categories = new LinkedHashSet<>();
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode c : cases) {
            categories.add(c.get("cat").asText());
            assertThat(names.add(c.get("n").asText()))
                    .as("unique case name " + c.get("n"))
                    .isTrue();
        }
        assertThat(categories)
                .contains(
                        "ADD", "UPDATE", "CONTRADICTION", "MERGE", "DISCARD", "FAILURE", "FORMAT");
    }

    @Test
    void disabledFlagWritesEverythingWithoutCallingTheLlm() {
        FakeStore store = new FakeStore();
        ChatClient chat = mock(ChatClient.class);
        MemoryWriteArbitrationService service = new MemoryWriteArbitrationService();
        service.setEnabled(false);

        service.arbitrateAndWrite(
                chat, store, AGENT, SUBJECT_KEY, List.of(new GraphTriple("Sam", "LIKES", "tea")));

        assertThat(store.triples()).containsExactly("Sam|LIKES|tea");
        verify(chat, never()).prompt();
    }

    @Test
    void missingChatClientWritesEverything() {
        FakeStore store = new FakeStore();
        MemoryWriteArbitrationService service = enabledService(null);

        service.arbitrateAndWrite(
                null, store, AGENT, SUBJECT_KEY, List.of(new GraphTriple("Sam", "LIKES", "tea")));

        assertThat(store.triples()).containsExactly("Sam|LIKES|tea");
    }

    @Test
    void nullServiceAndEmptyCandidatesAreNoOps() {
        MemoryWriteArbitrationService service = enabledService(null);
        FakeStore store = new FakeStore();

        service.arbitrateAndWrite(
                mock(ChatClient.class),
                null,
                AGENT,
                SUBJECT_KEY,
                List.of(new GraphTriple("a", "B", "c")));
        service.arbitrateAndWrite(mock(ChatClient.class), store, AGENT, SUBJECT_KEY, List.of());
        service.arbitrateAndWrite(mock(ChatClient.class), store, AGENT, SUBJECT_KEY, null);

        assertThat(store.triples()).isEmpty();
    }

    @Test
    void recallFailureFallsBackToPlainWriteAndCountsIt() {
        FakeStore store =
                new FakeStore() {
                    @Override
                    public List<GraphFact> recallRelated(
                            UUID agentId, String subjectKey, String entityHint, int hops) {
                        throw new IllegalStateException("age down");
                    }
                };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemoryWriteArbitrationService service = enabledService(registry);

        service.arbitrateAndWrite(
                scriptedChat("[]", false),
                store,
                AGENT,
                SUBJECT_KEY,
                List.of(new GraphTriple("Sam", "LIKES", "tea")));

        assertThat(store.triples()).containsExactly("Sam|LIKES|tea");
        assertThat(
                        registry.counter(
                                        "graph.memory.arbitration.fallbacks", "reason", "exception")
                                .count())
                .isEqualTo(1.0);
    }

    @Test
    void existingFactsAreShownToTheModel() {
        FakeStore store = new FakeStore();
        store.remember(
                AGENT, SUBJECT_KEY, List.of(new GraphTriple("Priya", "MANAGER_OF_BY", "Raj")));
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient chat = chatWith(spec, "[]", false);
        MemoryWriteArbitrationService service = enabledService(null);

        service.arbitrateAndWrite(
                chat,
                store,
                AGENT,
                SUBJECT_KEY,
                List.of(new GraphTriple("Priya", "MANAGER_OF_BY", "Rahul")));

        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(spec).user(user.capture());
        assertThat(user.getValue())
                .contains("Priya manager of by Raj")
                .contains("(Priya, MANAGER_OF_BY, Rahul)");
    }

    @Test
    void decisionAndFallbackMetricsAreRecorded() {
        FakeStore store = new FakeStore();
        store.remember(
                AGENT,
                SUBJECT_KEY,
                List.of(
                        new GraphTriple("Priya", "MANAGER_OF_BY", "Raj"),
                        new GraphTriple("Sam", "LIKES", "tea")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemoryWriteArbitrationService service = enabledService(registry);
        // UPDATE (valid), DISCARD of a fact with an existing (Sam, LIKES) -> kept as DISCARD,
        // DISCARD of an unknown (Mia, LIKES) -> downgraded to ADD.
        String response =
                "[{\"subject\":\"Priya\",\"relation\":\"MANAGER_OF_BY\",\"object\":\"Rahul\",\"decision\":\"UPDATE\"},"
                        + "{\"subject\":\"Sam\",\"relation\":\"LIKES\",\"object\":\"tea\",\"decision\":\"DISCARD\"},"
                        + "{\"subject\":\"Mia\",\"relation\":\"LIKES\",\"object\":\"jazz\",\"decision\":\"DISCARD\"}]";

        service.arbitrateAndWrite(
                scriptedChat(response, false),
                store,
                AGENT,
                SUBJECT_KEY,
                List.of(
                        new GraphTriple("Priya", "MANAGER_OF_BY", "Rahul"),
                        new GraphTriple("Sam", "LIKES", "tea"),
                        new GraphTriple("Mia", "LIKES", "jazz")));

        assertThat(
                        registry.counter("graph.memory.arbitration.decisions", "decision", "UPDATE")
                                .count())
                .isEqualTo(1.0);
        assertThat(
                        registry.counter(
                                        "graph.memory.arbitration.decisions", "decision", "DISCARD")
                                .count())
                .isEqualTo(1.0);
        assertThat(
                        registry.counter("graph.memory.arbitration.decisions", "decision", "ADD")
                                .count())
                .isEqualTo(1.0);
        assertThat(
                        registry.counter(
                                        "graph.memory.arbitration.fallbacks",
                                        "reason",
                                        "discard_unverified")
                                .count())
                .isEqualTo(1.0);
        assertThat(store.triples())
                .containsExactlyInAnyOrder(
                        "Priya|MANAGER_OF_BY|Rahul", "Sam|LIKES|tea", "Mia|LIKES|jazz");
    }

    @Test
    void decisionsAreNotCountedWhenAWriteThrowsAndFallbackWritesEverything() {
        FakeStore store =
                new FakeStore() {
                    @Override
                    public void supersede(UUID agentId, String subjectKey, GraphTriple triple) {
                        throw new IllegalStateException("age write failed");
                    }
                };
        store.remember(
                AGENT, SUBJECT_KEY, List.of(new GraphTriple("Priya", "MANAGER_OF_BY", "Raj")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemoryWriteArbitrationService service = enabledService(registry);
        String response =
                "[{\"subject\":\"Sam\",\"relation\":\"LIKES\",\"object\":\"tea\",\"decision\":\"ADD\"},"
                        + "{\"subject\":\"Priya\",\"relation\":\"MANAGER_OF_BY\",\"object\":\"Rahul\",\"decision\":\"UPDATE\"}]";

        service.arbitrateAndWrite(
                scriptedChat(response, false),
                store,
                AGENT,
                SUBJECT_KEY,
                List.of(
                        new GraphTriple("Sam", "LIKES", "tea"),
                        new GraphTriple("Priya", "MANAGER_OF_BY", "Rahul")));

        assertThat(registry.find("graph.memory.arbitration.decisions").counters()).isEmpty();
        assertThat(
                        registry.counter(
                                        "graph.memory.arbitration.fallbacks", "reason", "exception")
                                .count())
                .isEqualTo(1.0);
        assertThat(store.triples()).contains("Sam|LIKES|tea", "Priya|MANAGER_OF_BY|Rahul");
    }

    @Test
    void parseFailureMetricIsRecorded() {
        FakeStore store = new FakeStore();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MemoryWriteArbitrationService service = enabledService(registry);

        service.arbitrateAndWrite(
                scriptedChat("not json", false),
                store,
                AGENT,
                SUBJECT_KEY,
                List.of(new GraphTriple("Sam", "LIKES", "tea")));

        assertThat(
                        registry.counter(
                                        "graph.memory.arbitration.fallbacks",
                                        "reason",
                                        "parse_failure")
                                .count())
                .isEqualTo(1.0);
        assertThat(store.triples()).containsExactly("Sam|LIKES|tea");
    }

    // ------------------------------------------------------------------ harness

    private void runCase(JsonNode c) {
        FakeStore store = new FakeStore();
        List<GraphTriple> existing = triples(c.get("ex"));
        store.remember(AGENT, SUBJECT_KEY, existing);
        List<GraphTriple> candidates = triples(c.get("cand"));

        boolean throwsOnCall = c.path("throws").asBoolean(false);
        String response =
                c.path("nullContent").asBoolean(false) ? null : responseFor(c, candidates);

        enabledService(null)
                .arbitrateAndWrite(
                        scriptedChat(response, throwsOnCall),
                        store,
                        AGENT,
                        SUBJECT_KEY,
                        candidates);

        Set<String> expected = new LinkedHashSet<>();
        for (GraphTriple t : triples(c.get("exp"))) {
            expected.add(key(t));
        }
        assertThat(store.triples())
                .as(c.get("n").asText())
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    /**
     * Raw override, or a JSON array rendered positionally from the candidates + scripted verdicts.
     */
    private String responseFor(JsonNode c, List<GraphTriple> candidates) {
        if (c.has("raw")) {
            return c.get("raw").asText();
        }
        if (!c.has("v")) {
            return null;
        }
        ArrayNode array = MAPPER.createArrayNode();
        for (int i = 0; i < c.get("v").size() && i < candidates.size(); i++) {
            GraphTriple t = candidates.get(i);
            ObjectNode node = array.addObject();
            node.put("subject", t.subject());
            node.put("relation", t.relation());
            node.put("object", t.object());
            node.put("decision", c.get("v").get(i).asText());
        }
        if (c.has("truncateTo")) {
            while (array.size() > c.get("truncateTo").asInt()) {
                array.remove(array.size() - 1);
            }
        }
        if (c.path("rev").asBoolean(false)) {
            ArrayNode reversed = MAPPER.createArrayNode();
            for (int i = array.size() - 1; i >= 0; i--) {
                reversed.add(array.get(i));
            }
            array = reversed;
        }
        String json = array.toString();
        if (c.path("fence").asBoolean(false)) {
            return "```json\n" + json + "\n```";
        }
        if (c.path("prose").asBoolean(false)) {
            return "Here are my decisions:\n" + json + "\nHope that helps.";
        }
        return json;
    }

    private static List<JsonNode> loadCases() throws Exception {
        try (InputStream in =
                MemoryWriteArbitrationHarnessTest.class.getResourceAsStream(
                        "/arbitration/arbitration-cases.json")) {
            assertThat(in).as("fixture on classpath").isNotNull();
            List<JsonNode> out = new ArrayList<>();
            MAPPER.readTree(in).forEach(out::add);
            return out;
        }
    }

    private static List<GraphTriple> triples(JsonNode array) {
        List<GraphTriple> out = new ArrayList<>();
        for (JsonNode t : array) {
            out.add(new GraphTriple(t.get(0).asText(), t.get(1).asText(), t.get(2).asText()));
        }
        return out;
    }

    private static String key(GraphTriple t) {
        return t.subject() + "|" + t.relation() + "|" + t.object();
    }

    private static MemoryWriteArbitrationService enabledService(SimpleMeterRegistry registry) {
        MemoryWriteArbitrationService service = new MemoryWriteArbitrationService();
        service.setEnabled(true);
        if (registry != null) {
            service.setMeterRegistry(registry);
        }
        return service;
    }

    private static ChatClient scriptedChat(String content, boolean throwsOnCall) {
        return chatWith(mock(ChatClient.ChatClientRequestSpec.class), content, throwsOnCall);
    }

    private static ChatClient chatWith(
            ChatClient.ChatClientRequestSpec spec, String content, boolean throwsOnCall) {
        ChatClient chat = mock(ChatClient.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chat.prompt()).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        if (throwsOnCall) {
            when(spec.call()).thenThrow(new IllegalStateException("llm unavailable"));
        } else {
            when(spec.call()).thenReturn(call);
            when(call.content()).thenReturn(content);
        }
        return chat;
    }

    /** In-memory stand-in for the AGE-backed store; no JDBC is touched. */
    private static class FakeStore extends GraphMemoryService {
        private final Set<String> store = new LinkedHashSet<>();

        FakeStore() {
            super(null);
        }

        Set<String> triples() {
            return store;
        }

        @Override
        public void remember(UUID agentId, String subjectKey, List<GraphTriple> triples) {
            if (triples == null) {
                return;
            }
            for (GraphTriple t : triples) {
                store.add(key(t));
            }
        }

        @Override
        public void supersede(UUID agentId, String subjectKey, GraphTriple triple) {
            // case-insensitive subject+relation match, as the real Cypher may be
            String prefix = (triple.subject() + "|" + triple.relation() + "|").toLowerCase();
            store.removeIf(k -> k.toLowerCase().startsWith(prefix) && !k.equals(key(triple)));
            store.add(key(triple));
        }

        @Override
        public List<GraphFact> recallRelated(
                UUID agentId, String subjectKey, String entityHint, int hops) {
            List<GraphFact> out = new ArrayList<>();
            for (String k : store) {
                String[] p = k.split("\\|", 3);
                if (p[0].equalsIgnoreCase(entityHint)) {
                    out.add(new GraphFact(p[0], p[1], p[2]));
                }
            }
            return out;
        }
    }
}
