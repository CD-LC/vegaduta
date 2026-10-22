package com.agenticai.core.agents.job.output;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetExhaustedException;
import com.agenticai.core.agents.job.AgentJob;
import com.agenticai.core.agents.job.AgentJobProperties;
import com.agenticai.core.agents.job.AgentJobRepository;
import com.agenticai.core.agents.job.AgentJobStatus;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The acceptance criterion PR-6 shipped violating: <b>a job with a schema returns valid parsed JSON
 * or fails explicitly - never silently returns prose that a caller then has to parse.</b>
 *
 * <p>These run the REAL execution path with the REAL coercer stack (validator, prompt renderer,
 * coercer) and mock only the model, so they fail if any link in the chain comes unwired again. That
 * matters more than usual here: every piece of this feature existed and compiled before this test
 * class, and the defect was purely that nothing called any of it. A test on the coercer alone would
 * have passed happily throughout.
 */
class AgentJobStructuredOutputExecutionTest {

    private static final ObjectMapper MAPPER = com.agenticai.core.json.JsonMappers.plain();

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void aConformingFirstAnswerSurfacesParsedJsonOnTheRow() throws Exception {
        AgentJob job = StructuredOutputJobFixtures.queuedJob(StructuredOutputJobFixtures.SCHEMA);
        AgentJobRepository repository = StructuredOutputJobFixtures.repositoryFor(job);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), argThat(r -> !r.suppressAgentTools())))
                .thenReturn(Flux.just(StructuredOutputJobFixtures.CONFORMING));

        run(job, repository, chatService, StructuredOutputJobFixtures.unlimitedToolCalls());

        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
        assertEquals(1, job.getSchemaAttempts());
        assertNotNull(
                job.getResultJson(),
                "a schema'd job must surface the parsed object, not just text");

        // Parsed, not merely non-null: "surface the parsed object on the job
        // result" is the half of the criterion a raw string would silently fail.
        JsonNode parsed = MAPPER.readTree(job.getResultJson());
        assertEquals("three tickets, all billing", parsed.path("summary").asString());
        assertEquals(3, parsed.path("count").asInt());
    }

    @Test
    void theSchemaIsInjectedIntoTheGoalTurnItself() {
        AgentJob job = StructuredOutputJobFixtures.queuedJob(StructuredOutputJobFixtures.SCHEMA);
        AgentJobRepository repository = StructuredOutputJobFixtures.repositoryFor(job);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), argThat(r -> !r.suppressAgentTools())))
                .thenReturn(Flux.just(StructuredOutputJobFixtures.CONFORMING));

        run(job, repository, chatService, StructuredOutputJobFixtures.unlimitedToolCalls());

        ArgumentCaptor<ChatTurnRequest> turns = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chatService).chat(any(TurnContext.class), turns.capture());
        // Asserted on the SCHEMA's own content rather than on the instruction's
        // wording: the prompt copy is deliberately tunable (see
        // SchemaPromptRenderer), the fact that the schema reaches the model is
        // not.
        assertTrue(turns.getValue().userMessage().startsWith(StructuredOutputJobFixtures.GOAL));
        assertTrue(turns.getValue().userMessage().contains("\"summary\""));
        assertTrue(turns.getValue().userMessage().contains("\"count\""));
    }

    @Test
    void aNonConformingAnswerIsRepairedAndTheRowKeepsThePassingAttempt() {
        AgentJob job = StructuredOutputJobFixtures.queuedJob(StructuredOutputJobFixtures.SCHEMA);
        AgentJobRepository repository = StructuredOutputJobFixtures.repositoryFor(job);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), argThat(r -> !r.suppressAgentTools())))
                .thenReturn(Flux.just(StructuredOutputJobFixtures.PROSE));
        when(chatService.chat(any(TurnContext.class), argThat(r -> r.suppressAgentTools())))
                .thenReturn(Flux.just(StructuredOutputJobFixtures.CONFORMING));

        run(job, repository, chatService, StructuredOutputJobFixtures.unlimitedToolCalls());

        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
        assertEquals(2, job.getSchemaAttempts(), "one original answer plus one repair");
        // result_text is the attempt that PASSED, so the row's text and its JSON
        // can never describe two different answers.
        assertEquals(StructuredOutputJobFixtures.CONFORMING, job.getResultText());
        assertNotNull(job.getResultJson());
        // The goal turn is the only tool-enabled chat; the repair goes through
        // chatTextOnly, which withholds the agent's whole tool set so a repair
        // can never re-fire a side-effecting tool, trip a fresh approval gate,
        // or publish an event that feeds a trigger loop (see
        // AgentJobExecution#runOneTurn's textOnly javadoc).
        verify(chatService, times(1))
                .chat(any(TurnContext.class), argThat(r -> !r.suppressAgentTools()));
        verify(chatService, times(1))
                .chat(any(TurnContext.class), argThat(r -> r.suppressAgentTools()));

        // A repair is NOT a goal turn. turns_used stays 1, which is what the
        // durability policy reads to decide whether a job did chargeable work.
        verify(repository, times(1)).incrementTurnsUsed(job.getId());
        assertEquals(1, job.getTurnsUsed());
    }

    /**
     * The regression test for the shipped contract violation. Before this feature was wired, this
     * job SUCCEEDED with {@link StructuredOutputJobFixtures#PROSE} as its result and a null {@code
     * result_json} - and a caller expecting {@code count} would have got whatever its own parser
     * made of a sentence.
     */
    @Test
    void anAnswerThatNeverConformsFailsExplicitlyAndNeverSucceedsWithProse() {
        AgentJob job = StructuredOutputJobFixtures.queuedJob(StructuredOutputJobFixtures.SCHEMA);
        AgentJobRepository repository = StructuredOutputJobFixtures.repositoryFor(job);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), argThat(r -> !r.suppressAgentTools())))
                .thenReturn(Flux.just(StructuredOutputJobFixtures.PROSE));
        when(chatService.chat(any(TurnContext.class), argThat(r -> r.suppressAgentTools())))
                .thenReturn(Flux.just(StructuredOutputJobFixtures.PROSE));

        run(job, repository, chatService, StructuredOutputJobFixtures.unlimitedToolCalls());

        assertEquals(AgentJobStatus.FAILED, job.getStatus());
        assertEquals("SCHEMA_VIOLATION", job.getErrorCode());
        assertNull(job.getResultJson());
        assertTrue(job.getErrorMessage().contains("result schema"));
        // The original answer plus max-repair-attempts (2) repairs, and not one
        // model call more - three paid calls is the documented ceiling. The goal
        // turn is the tool-enabled chat; both repairs are the tool-less
        // chatTextOnly path.
        verify(chatService, times(1))
                .chat(any(TurnContext.class), argThat(r -> !r.suppressAgentTools()));
        verify(chatService, times(2))
                .chat(any(TurnContext.class), argThat(r -> r.suppressAgentTools()));
    }

    /**
     * The common path, and the one this feature must not regress: no schema anywhere means the goal
     * goes to the model untouched and the text is persisted untouched.
     */
    @Test
    void aJobWithNoSchemaIsCompletelyUnaffected() {
        AgentJob job = StructuredOutputJobFixtures.queuedJob(null);
        AgentJobRepository repository = StructuredOutputJobFixtures.repositoryFor(job);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), argThat(r -> !r.suppressAgentTools())))
                .thenReturn(Flux.just(StructuredOutputJobFixtures.PROSE));

        run(job, repository, chatService, StructuredOutputJobFixtures.unlimitedToolCalls());

        assertEquals(AgentJobStatus.SUCCEEDED, job.getStatus());
        assertEquals(StructuredOutputJobFixtures.PROSE, job.getResultText());
        assertNull(job.getResultJson());
        assertEquals(0, job.getSchemaAttempts());

        ArgumentCaptor<ChatTurnRequest> turns2 = ArgumentCaptor.forClass(ChatTurnRequest.class);
        verify(chatService).chat(any(TurnContext.class), turns2.capture());
        assertEquals(
                StructuredOutputJobFixtures.GOAL,
                turns2.getValue().userMessage(),
                "a job without a schema must receive its goal byte-for-byte");
    }

    /**
     * A spend-ceiling stop inside a repair turn must be reported as itself. Relabelling it {@code
     * SCHEMA_VIOLATION} would send an operator to debug a schema that was never the problem, and
     * would hide the one error the platform most needs to report honestly.
     */
    @Test
    void aBudgetExhaustionDuringARepairTurnIsNotReportedAsASchemaViolation() {
        AgentJob job = StructuredOutputJobFixtures.queuedJob(StructuredOutputJobFixtures.SCHEMA);
        AgentJobRepository repository = StructuredOutputJobFixtures.repositoryFor(job);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), argThat(r -> !r.suppressAgentTools())))
                .thenReturn(Flux.just(StructuredOutputJobFixtures.PROSE));
        // The goal turn spent the last of the budget; the repair - which runs
        // through the tool-less chatTextOnly path - is the call the ceiling
        // refuses.
        when(chatService.chat(any(TurnContext.class), argThat(r -> r.suppressAgentTools())))
                .thenThrow(
                        new PerUserBudgetExhaustedException(
                                "Daily budget exhausted",
                                "text",
                                new BigDecimal("0.15"),
                                new BigDecimal("0.15"),
                                3600L));

        run(job, repository, chatService, StructuredOutputJobFixtures.unlimitedToolCalls());

        assertEquals(AgentJobStatus.FAILED, job.getStatus());
        assertEquals("BUDGET_EXHAUSTED", job.getErrorCode());
    }

    /**
     * A repair turn is a real, paid model call - even though it runs through the tool-less {@code
     * chatTextOnly} path - so it obeys the per-job tool ceiling exactly as a goal turn does.
     * Without the shared {@code guardBeforeTurn} check, a schema-repairing job would walk straight
     * past a limit the loop enforced.
     */
    @Test
    void aRepairTurnCannotDodgeThePerJobToolCeiling() {
        AgentJob job = StructuredOutputJobFixtures.queuedJob(StructuredOutputJobFixtures.SCHEMA);
        AgentJobRepository repository = StructuredOutputJobFixtures.repositoryFor(job);
        ToolCallLogRepository toolCallLogRepository = mock(ToolCallLogRepository.class);
        // Under the ceiling for the goal turn, at it by the time the repair is
        // attempted - the job burned its tool budget during the first turn.
        when(toolCallLogRepository.countByAgentIdAndSessionId(any(), any())).thenReturn(0L, 15L);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), argThat(r -> !r.suppressAgentTools())))
                .thenReturn(Flux.just(StructuredOutputJobFixtures.PROSE));

        run(job, repository, chatService, toolCallLogRepository);

        assertEquals(AgentJobStatus.FAILED, job.getStatus());
        assertEquals("TOOL_LIMIT_EXCEEDED", job.getErrorCode());
        // The goal turn only. The repair was refused before it could be paid for.
        verify(chatService, times(1))
                .chat(any(TurnContext.class), argThat(r -> !r.suppressAgentTools()));
    }

    // --------------------------------------------------------------- internals

    private void run(
            AgentJob job,
            AgentJobRepository repository,
            AgentChatService chatService,
            ToolCallLogRepository toolCallLogRepository) {
        AgentJobProperties properties = StructuredOutputJobFixtures.properties();
        StructuredOutputJobFixtures.executionWith(
                        repository,
                        chatService,
                        toolCallLogRepository,
                        properties,
                        StructuredOutputJobFixtures.contract(properties))
                .run(job.getId(), StructuredOutputJobFixtures.TENANT);
    }
}
