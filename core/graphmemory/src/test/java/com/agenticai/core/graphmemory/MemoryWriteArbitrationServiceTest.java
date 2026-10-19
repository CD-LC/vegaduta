package com.agenticai.core.graphmemory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * LongMemEval-lite harness for {@link MemoryWriteArbitrationService} - gap-analysis item 8
 * ("validate and then enable memory write arbitration"). The real LongMemEval benchmark
 * (arXiv:2410.10813) measures a memory system end-to-end over multi-session chat logs; this
 * suite is the deliberately smaller "lite" form the gap doc prescribes before the flag flip:
 * a fixed battery of memory-write scenarios run against a deterministic mocked {@link
 * ChatClient} (the arbitration LLM) and a mocked {@link GraphMemoryService} (the AGE-backed
 * store interface), asserting which STORE operation each candidate fact lands on rather than
 * scoring answer quality.
 *
 * <p>The scenario axis mirrors LongMemEval's own question types, reduced to their write-side
 * essence:
 *
 * <ul>
 *   <li><b>Knowledge update</b> (its "knowledge-update" type): an existing fact about a
 *       subject is contradicted by a newer one - UPDATE must route to {@link
 *       GraphMemoryService#supersede}, which stamps {@code valid_to} on the stale edge so a
 *       point-in-time recall still answers correctly.</li>
 *   <li><b>Single-session preference / new fact:</b> no existing fact covers the pair - ADD
 *       routes to {@link GraphMemoryService#remember}.</li>
 *   <li><b>Abstention/redundancy:</b> the candidate restates an existing fact - DISCARD
 *       writes nothing.</li>
 *   <li><b>Merge:</b> same (subject, relation), compatible object reworded - MERGE takes the
 *       same supersede path as UPDATE by design (see the arbitration prompt's own wording).</li>
 * </ul>
 *
 * <p>The other half of the contract under test is fail-open degradation: every malformed,
 * absent or thrown arbitration verdict must fall back to today's unconditional write, never
 * silently drop a candidate fact.
 *
 * <p>Mocks {@link GraphMemoryService} (the AGE store boundary) rather than running a real
 * AGE-enabled Postgres - the same honestly-disclosed ceiling {@link GraphMemoryServiceTest}
 * and {@link EntityExtractionServiceTest} carry; live-AGE verification of the Cypher itself
 * stays a staging task, exactly as the class javadocs already state.
 */
class MemoryWriteArbitrationServiceTest {

    private static final UUID AGENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String SUBJECT_KEY = "priya@example.com";

    private final GraphMemoryService graphMemoryService = mock(GraphMemoryService.class);
    private final MemoryWriteArbitrationService service = new MemoryWriteArbitrationService();

    private void setEnabled(boolean enabled) {
        // The flag is a @Value-injected private field; unit tests bypass Spring entirely, so
        // this is the same seam every other flag-defaulted service test in the repo uses.
        ReflectionTestUtils.setField(service, "enabled", enabled);
    }

    // ----------------------------------------------------------- flag / wiring fall-through

    @Test
    void disabledFlagFallsThroughToUnconditionalWriteWithoutCallingTheLlm() {
        setEnabled(false);
        ChatClient chatClient = mock(ChatClient.class);
        List<GraphTriple> candidates = List.of(new GraphTriple("Priya", "MANAGER_OF_BY", "Raj"));

        service.arbitrateAndWrite(chatClient, graphMemoryService, AGENT_ID, SUBJECT_KEY, candidates);

        verify(graphMemoryService).remember(AGENT_ID, SUBJECT_KEY, candidates);
        verify(graphMemoryService, never()).supersede(any(), any(), any());
        // The arbitration LLM call is never even attempted when the flag is off - "off is a
        // true no-op", including zero extra model spend.
        verify(chatClient, never()).prompt();
    }

    @Test
    void enabledButNullChatClientFallsThroughToUnconditionalWrite() {
        setEnabled(true);
        List<GraphTriple> candidates = List.of(new GraphTriple("Priya", "MANAGER_OF_BY", "Raj"));

        service.arbitrateAndWrite(null, graphMemoryService, AGENT_ID, SUBJECT_KEY, candidates);

        verify(graphMemoryService).remember(AGENT_ID, SUBJECT_KEY, candidates);
        verify(graphMemoryService, never()).supersede(any(), any(), any());
    }

    @Test
    void nullOrEmptyCandidateListIsANoOp() {
        setEnabled(true);
        ChatClient chatClient = mock(ChatClient.class);

        service.arbitrateAndWrite(chatClient, graphMemoryService, AGENT_ID, SUBJECT_KEY, null);
        service.arbitrateAndWrite(chatClient, graphMemoryService, AGENT_ID, SUBJECT_KEY, List.of());
        service.arbitrateAndWrite(chatClient, null, AGENT_ID, SUBJECT_KEY,
            List.of(new GraphTriple("Priya", "MANAGER_OF_BY", "Raj")));

        verify(graphMemoryService, never()).remember(any(), any(), any());
        verify(graphMemoryService, never()).supersede(any(), any(), any());
        verify(chatClient, never()).prompt();
    }

    // ------------------------------------------------------------ LongMemEval-lite battery

    @Test
    void knowledgeUpdateDecisionsSupersedeInsteadOfAccumulatingStaleFacts() {
        setEnabled(true);
        // LongMemEval "knowledge-update" scenario, write side: the graph already holds
        // (Priya, MANAGER_OF_BY, Raj); a later turn yields (Priya, MANAGER_OF_BY, Rahul).
        // The only correct outcome is supersede - a plain MERGE would leave BOTH edges live
        // and recall would offer two contradictory managers.
        when(graphMemoryService.recallRelated(eq(AGENT_ID), eq(SUBJECT_KEY), anyString(), anyInt()))
            .thenReturn(List.of(new GraphFact("Priya", "MANAGER_OF_BY", "Raj")));
        GraphTriple updated = new GraphTriple("Priya", "MANAGER_OF_BY", "Rahul");
        ChatClient chatClient = mockChatClientReturning(
            "[{\"subject\": \"Priya\", \"relation\": \"MANAGER_OF_BY\", \"object\": \"Rahul\", \"decision\": \"UPDATE\"}]"
        );

        service.arbitrateAndWrite(chatClient, graphMemoryService, AGENT_ID, SUBJECT_KEY, List.of(updated));

        verify(graphMemoryService).supersede(AGENT_ID, SUBJECT_KEY, updated);
        verify(graphMemoryService, never()).remember(any(), any(), any());
    }

    @Test
    void mergeDecisionsTakeTheSameSupersedePathAsUpdate() {
        setEnabled(true);
        // "Priya reports to Rahul" restating the manager fact in new wording - the prompt
        // defines MERGE as UPDATE-equivalent, so the store call must be supersede, not a
        // second edge.
        GraphTriple restated = new GraphTriple("Priya", "MANAGER_OF_BY", "Rahul");
        ChatClient chatClient = mockChatClientReturning(
            "[{\"subject\": \"Priya\", \"relation\": \"MANAGER_OF_BY\", \"object\": \"Rahul\", \"decision\": \"MERGE\"}]"
        );

        service.arbitrateAndWrite(chatClient, graphMemoryService, AGENT_ID, SUBJECT_KEY, List.of(restated));

        verify(graphMemoryService).supersede(AGENT_ID, SUBJECT_KEY, restated);
        verify(graphMemoryService, never()).remember(any(), any(), any());
    }

    @Test
    void addDecisionsAreWrittenViaPlainRemember() {
        setEnabled(true);
        // Genuinely new fact (single-session-preference shape): nothing known about the
        // (subject, relation) pair, so ADD goes through today's unconditional write path.
        GraphTriple newFact = new GraphTriple("Priya", "PREFERS_CONTACT_VIA", "email");
        ChatClient chatClient = mockChatClientReturning(
            "[{\"subject\": \"Priya\", \"relation\": \"PREFERS_CONTACT_VIA\", \"object\": \"email\", \"decision\": \"ADD\"}]"
        );

        service.arbitrateAndWrite(chatClient, graphMemoryService, AGENT_ID, SUBJECT_KEY, List.of(newFact));

        verify(graphMemoryService).remember(AGENT_ID, SUBJECT_KEY, List.of(newFact));
        verify(graphMemoryService, never()).supersede(any(), any(), any());
    }

    @Test
    void discardDecisionsWriteNothing() {
        setEnabled(true);
        // Redundant restatement (same subject, relation AND equivalent object) - the
        // abstention case: a discarded candidate must produce no store write at all.
        // The fact must already be KNOWN for DISCARD to stick - the discard_unverified
        // guard downgrades an unverifiable "redundant" verdict to ADD.
        when(graphMemoryService.recallRelated(eq(AGENT_ID), eq(SUBJECT_KEY), eq("Priya"), eq(1)))
            .thenReturn(List.of(new GraphFact("Priya", "MANAGER_OF_BY", "Raj")));
        ChatClient chatClient = mockChatClientReturning(
            "[{\"subject\": \"Priya\", \"relation\": \"MANAGER_OF_BY\", \"object\": \"Raj\", \"decision\": \"DISCARD\"}]"
        );

        service.arbitrateAndWrite(
            chatClient, graphMemoryService, AGENT_ID, SUBJECT_KEY,
            List.of(new GraphTriple("Priya", "MANAGER_OF_BY", "Raj"))
        );

        verify(graphMemoryService, never()).remember(any(), any(), any());
        verify(graphMemoryService, never()).supersede(any(), any(), any());
    }

    @Test
    void aMixedBatchRoutesEachCandidateToItsOwnStoreOperation() {
        setEnabled(true);
        // One of each decision in a single remember_fact batch - the routing must be
        // per-candidate, not per-batch.
        GraphTriple toAdd = new GraphTriple("Acme", "LOCATED_IN", "Berlin");
        GraphTriple toUpdate = new GraphTriple("Priya", "MANAGER_OF_BY", "Rahul");
        GraphTriple toDiscard = new GraphTriple("Raj", "WORKS_AT", "Acme");
        // The DISCARD arm needs its fact already known - otherwise the
        // discard_unverified guard fails it open to ADD.
        when(graphMemoryService.recallRelated(eq(AGENT_ID), eq(SUBJECT_KEY), eq("Raj"), eq(1)))
            .thenReturn(List.of(new GraphFact("Raj", "WORKS_AT", "Acme")));
        ChatClient chatClient = mockChatClientReturning(
            "[{\"subject\": \"Acme\", \"relation\": \"LOCATED_IN\", \"object\": \"Berlin\", \"decision\": \"ADD\"},"
                + "{\"subject\": \"Priya\", \"relation\": \"MANAGER_OF_BY\", \"object\": \"Rahul\", \"decision\": \"UPDATE\"},"
                + "{\"subject\": \"Raj\", \"relation\": \"WORKS_AT\", \"object\": \"Acme\", \"decision\": \"DISCARD\"}]"
        );

        service.arbitrateAndWrite(
            chatClient, graphMemoryService, AGENT_ID, SUBJECT_KEY, List.of(toAdd, toUpdate, toDiscard)
        );

        verify(graphMemoryService).remember(AGENT_ID, SUBJECT_KEY, List.of(toAdd));
        verify(graphMemoryService).supersede(AGENT_ID, SUBJECT_KEY, toUpdate);
        verify(graphMemoryService, never()).supersede(eq(AGENT_ID), eq(SUBJECT_KEY), eq(toDiscard));
    }

    @Test
    void theArbitrationPromptCarriesAlreadyKnownFactsAsContext() {
        setEnabled(true);
        // The whole point of the harness: the LLM can only decide UPDATE vs ADD if it can SEE
        // the stale fact. Pin the prompt's shape - existing facts listed first, candidates
        // second - so a regression that dropped the context block is caught here, not on a
        // real user's second session.
        when(graphMemoryService.recallRelated(eq(AGENT_ID), eq(SUBJECT_KEY), eq("Priya"), eq(1)))
            .thenReturn(List.of(new GraphFact("Priya", "MANAGER_OF_BY", "Raj")));
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        when(requestSpec.user(userPrompt.capture())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(
            "[{\"subject\": \"Priya\", \"relation\": \"MANAGER_OF_BY\", \"object\": \"Rahul\", \"decision\": \"UPDATE\"}]"
        );

        service.arbitrateAndWrite(
            chatClient, graphMemoryService, AGENT_ID, SUBJECT_KEY,
            List.of(new GraphTriple("Priya", "MANAGER_OF_BY", "Rahul"))
        );

        assertThat(userPrompt.getValue())
            .contains("Already-known facts")
            .contains("- Priya manager of by Raj")        // GraphFact#asSentence's rendering
            .contains("Candidate facts")
            .contains("(Priya, MANAGER_OF_BY, Rahul)");
        verify(graphMemoryService).supersede(eq(AGENT_ID), eq(SUBJECT_KEY), any(GraphTriple.class));
    }

    // ------------------------------------------------------------- fail-open degradation

    @Test
    void blankArbitrationResponseFailsOpenToAddForEveryCandidate() {
        setEnabled(true);
        ChatClient chatClient = mockChatClientReturning("   ");
        GraphTriple fact = new GraphTriple("Priya", "PREFERS_CONTACT_VIA", "email");

        service.arbitrateAndWrite(chatClient, graphMemoryService, AGENT_ID, SUBJECT_KEY, List.of(fact));

        verify(graphMemoryService).remember(AGENT_ID, SUBJECT_KEY, List.of(fact));
        verify(graphMemoryService, never()).supersede(any(), any(), any());
    }

    @Test
    void malformedArbitrationJsonFailsOpenToAddForEveryCandidate() {
        setEnabled(true);
        ChatClient chatClient = mockChatClientReturning("this is not json");
        GraphTriple fact = new GraphTriple("Priya", "PREFERS_CONTACT_VIA", "email");

        service.arbitrateAndWrite(chatClient, graphMemoryService, AGENT_ID, SUBJECT_KEY, List.of(fact));

        verify(graphMemoryService).remember(AGENT_ID, SUBJECT_KEY, List.of(fact));
    }

    @Test
    void anUnrecognizedDecisionValueDefaultsToAdd() {
        setEnabled(true);
        // A model that hallucinates a fifth action (e.g. "NOOP") must degrade to ADD - the
        // fail-open direction is "write it" because losing a fact is worse than a duplicate.
        GraphTriple fact = new GraphTriple("Priya", "PREFERS_CONTACT_VIA", "email");
        ChatClient chatClient = mockChatClientReturning(
            "[{\"subject\": \"Priya\", \"relation\": \"PREFERS_CONTACT_VIA\", \"object\": \"email\", \"decision\": \"NOOP\"}]"
        );

        service.arbitrateAndWrite(chatClient, graphMemoryService, AGENT_ID, SUBJECT_KEY, List.of(fact));

        verify(graphMemoryService).remember(AGENT_ID, SUBJECT_KEY, List.of(fact));
        verify(graphMemoryService, never()).supersede(any(), any(), any());
    }

    @Test
    void aCandidateAbsentFromTheVerdictDefaultsToAdd() {
        setEnabled(true);
        // The model returned a verdict for only ONE of two candidates - the unmatched one
        // must still be written (ADD), never silently dropped.
        GraphTriple decided = new GraphTriple("Priya", "MANAGER_OF_BY", "Rahul");
        GraphTriple undecided = new GraphTriple("Acme", "LOCATED_IN", "Berlin");
        ChatClient chatClient = mockChatClientReturning(
            "[{\"subject\": \"Priya\", \"relation\": \"MANAGER_OF_BY\", \"object\": \"Rahul\", \"decision\": \"UPDATE\"}]"
        );

        service.arbitrateAndWrite(
            chatClient, graphMemoryService, AGENT_ID, SUBJECT_KEY, List.of(decided, undecided)
        );

        verify(graphMemoryService).supersede(AGENT_ID, SUBJECT_KEY, decided);
        verify(graphMemoryService).remember(AGENT_ID, SUBJECT_KEY, List.of(undecided));
    }

    @Test
    void aThrowingArbitrationCallFallsBackToTheUnconditionalWrite() {
        setEnabled(true);
        ChatClient chatClient = mock(ChatClient.class);
        when(chatClient.prompt()).thenThrow(new RuntimeException("provider outage"));
        List<GraphTriple> candidates = List.of(new GraphTriple("Priya", "MANAGER_OF_BY", "Raj"));

        service.arbitrateAndWrite(chatClient, graphMemoryService, AGENT_ID, SUBJECT_KEY, candidates);

        verify(graphMemoryService).remember(AGENT_ID, SUBJECT_KEY, candidates);
        verify(graphMemoryService, never()).supersede(any(), any(), any());
    }

    @Test
    void aThrowingContextLookupFallsBackToTheUnconditionalWrite() {
        setEnabled(true);
        // recallRelated fails open in production (returns List.of()), but a mock or a future
        // regression that throws must still never lose the candidate facts.
        when(graphMemoryService.recallRelated(any(), any(), anyString(), anyInt()))
            .thenThrow(new RuntimeException("graph read blew up"));
        ChatClient chatClient = mock(ChatClient.class);
        List<GraphTriple> candidates = List.of(new GraphTriple("Priya", "MANAGER_OF_BY", "Raj"));

        service.arbitrateAndWrite(chatClient, graphMemoryService, AGENT_ID, SUBJECT_KEY, candidates);

        verify(graphMemoryService).remember(AGENT_ID, SUBJECT_KEY, candidates);
    }

    private static ChatClient mockChatClientReturning(String content) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(content);

        return chatClient;
    }
}
