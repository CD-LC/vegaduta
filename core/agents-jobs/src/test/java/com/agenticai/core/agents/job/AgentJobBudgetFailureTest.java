package com.agenticai.core.agents.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.budget.PerUserBudgetExhaustedException;
import com.agenticai.core.agents.core.TenantUnavailableException;
import com.agenticai.core.agents.job.progress.AgentJobProgressService;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.ChatTurnRequest;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * A job inherits every ceiling a chat turn obeys, because it calls the same {@code chat()} entry
 * point with a real subject and a real tenant context. What this test pins down is the OTHER half
 * of that promise: when a ceiling does fire, the job must fail with the ceiling's own reason and
 * the ceiling's own message - never disguised as a mysterious internal error that an operator will
 * escalate as a platform fault.
 */
class AgentJobBudgetFailureTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void anExhaustedPerUserBudgetFailsTheJobWithTheBudgetReasonVerbatim() {
        String message = "Daily budget of $0.15 for text is exhausted; top up to continue";
        AgentJob job =
                runWith(
                        Flux.error(
                                new PerUserBudgetExhaustedException(
                                        message,
                                        "text",
                                        new BigDecimal("0.15"),
                                        new BigDecimal("0.15"),
                                        3600L)));

        assertEquals(AgentJobStatus.FAILED, job.getStatus());
        assertEquals("BUDGET_EXHAUSTED", job.getErrorCode());
        // Byte-for-byte the message a chat turn shows. Clients branch on the
        // code; humans read the message, and rewording it here would make the
        // job surface disagree with the chat surface about the same cap.
        assertEquals(message, job.getErrorMessage());
        assertNull(job.getResultText());
    }

    @Test
    void aStoppedTenantFailsTheJobWithItsOwnReason() {
        // The kill switch / tenant-status gate, same inheritance, different code.
        AgentJob job = runWith(Flux.error(new TenantUnavailableException("Tenant is suspended")));

        assertEquals(AgentJobStatus.FAILED, job.getStatus());
        assertEquals("TENANT_UNAVAILABLE", job.getErrorCode());
        assertEquals("Tenant is suspended", job.getErrorMessage());
    }

    @Test
    void anUnrecognisedFailureIsStillARowNotAnEscapedException() {
        // A worker that threw into the pool would surface nowhere except a
        // swallowed Future, leaving the row RUNNING until the orphan sweep.
        AgentJob job =
                runWith(Flux.error(new IllegalArgumentException("model refused the request")));

        assertEquals(AgentJobStatus.FAILED, job.getStatus());
        assertEquals("EXECUTION_FAILED", job.getErrorCode());
        assertEquals("model refused the request", job.getErrorMessage());
    }

    @Test
    void aRefusedModelServerUrlFailsFastWithItsOwnCode() {
        // SE-09: the SSRF guard's refusal is deterministic configuration, not a transient fault.
        String message = "This model server URL is not allowed: internal host name";
        AgentJob job =
                runWith(
                        Flux.error(
                                new RuntimeException(
                                        "wrapped",
                                        new com.agenticai.core.chat.LlmHttpClientFactory
                                                .ModelServerUrlNotAllowedException(
                                                message, null))));

        assertEquals(AgentJobStatus.FAILED, job.getStatus());
        assertEquals("MODEL_URL_NOT_ALLOWED", job.getErrorCode());
        assertEquals(message, job.getErrorMessage());
    }

    private AgentJob runWith(Flux<String> turnOutcome) {
        AgentJob job = AgentJobTestSupport.queuedJob(60, 15);
        AgentJobRepository repository = AgentJobTestSupport.repositoryFor(job);
        AgentChatService chatService = mock(AgentChatService.class);
        when(chatService.chat(any(TurnContext.class), any(ChatTurnRequest.class)))
                .thenReturn(turnOutcome);

        new AgentJobExecution(
                        repository,
                        chatService,
                        mock(ToolCallLogRepository.class),
                        AgentJobTestSupport.properties(),
                        new SimpleMeterRegistry(),
                        mock(AgentJobProgressService.class))
                .run(job.getId(), AgentJobTestSupport.TENANT);
        return job;
    }
}
