package com.agenticai.core.agents.collab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.DelegateOptions;
import com.agenticai.core.prompts.DefaultPromptResolver;
import com.agenticai.core.prompts.InMemoryPromptOverrideStore;
import com.agenticai.core.prompts.PromptOverride;
import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import com.agenticai.core.prompts.PromptScope;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Audit gap H29: the directive every {@code run_subtasks} worker runs under was a Java literal. It is the prompt
 * {@code delegation.swarm_worker} (and {@code delegation.swarm_worker.role} for the role sentence) now: the shipped
 * text is byte for byte the old literal, and a tenant's or the calling agent's override reaches the worker turn that
 * the real tool starts.
 */
class SwarmWorkerDirectivePromptTest {

    /** The literal that used to be {@code RunSubtasksToolCallback.WORKER_DIRECTIVE}. */
    private static final String OLD_WORKER_DIRECTIVE =
            "You are a worker running one sub-task for another agent. Do only the sub-task in the"
                    + " user message, with the tools you have, and finish it. You cannot ask"
                    + " questions, delegate, schedule or message anyone: if something needed is"
                    + " missing, say exactly what is missing. Reply with the result alone, plainly,"
                    + " in the form the sub-task asks for. Never invent results.";

    private static final TenantContext TENANT = TenantContext.of("t1", "owner", Set.of());

    private final AgentChatService chatService = mock(AgentChatService.class);
    private final UUID agentId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private final InMemoryPromptOverrideStore store = new InMemoryPromptOverrideStore();
    private final DefaultPromptResolver resolver =
            new DefaultPromptResolver(PromptResolver.defaultsOnly().catalog(), store, new PromptProperties());

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private void override(PromptScope scope, UUID agent, String key, String text) {
        TenantContext.set(TENANT);
        store.put(new PromptOverride(
                scope, scope == PromptScope.AGENT ? agent.toString() : "", key, "en", text, true, 1, "owner", Instant.now()));
        resolver.evictAll();
        TenantContext.clear();
    }

    /** Runs one sub-task through the real tool and returns the addendum the worker turn was started with. */
    private String workerAddendum(UUID callingAgent, String role) {
        when(chatService.delegateCall(any(), any(), any(), any(), any())).thenReturn("done");
        RunSubtasksToolCallback tool =
                new RunSubtasksToolCallback(chatService, callingAgent, TENANT, null, null, "chat-1").withPromptResolver(resolver);

        tool.call("{\"tasks\":[{\"brief\":\"b\"" + (role == null ? "" : ",\"role\":\"" + role + "\"") + "}]}");

        ArgumentCaptor<DelegateOptions> options = ArgumentCaptor.forClass(DelegateOptions.class);
        verify(chatService, org.mockito.Mockito.atLeastOnce()).delegateCall(any(), any(), any(), any(), options.capture());
        return options.getValue().systemAddendum();
    }

    @Test
    void theShippedDirectiveIsByteIdenticalToTheOldLiteralWithAndWithoutARole() {
        PromptResolver shipped = PromptResolver.defaultsOnly();

        assertThat(shipped.catalog().problems()).isEmpty();
        assertThat(shipped.text(RunSubtasksToolCallback.WORKER_DIRECTIVE_KEY)).isEqualTo(OLD_WORKER_DIRECTIVE);
        assertThat(workerAddendum(agentId, null)).isEqualTo(OLD_WORKER_DIRECTIVE);
        assertThat(workerAddendum(agentId, "analyst")).isEqualTo(OLD_WORKER_DIRECTIVE + " Your role: analyst.");
    }

    @Test
    void aTenantOverrideReachesTheWorkerTurnAndAnAgentOverrideBeatsItForThatAgentOnly() {
        override(PromptScope.TENANT, null, RunSubtasksToolCallback.WORKER_DIRECTIVE_KEY, "House worker rules.");
        assertThat(workerAddendum(agentId, null)).isEqualTo("House worker rules.");

        override(PromptScope.AGENT, agentId, RunSubtasksToolCallback.WORKER_DIRECTIVE_KEY, "Agent worker rules.");
        assertThat(workerAddendum(agentId, null)).isEqualTo("Agent worker rules.");
        assertThat(workerAddendum(UUID.randomUUID(), null)).isEqualTo("House worker rules.");
    }

    @Test
    void theRoleSentenceIsItsOwnKey() {
        override(PromptScope.TENANT, null, RunSubtasksToolCallback.WORKER_ROLE_KEY, "Act as {role}.");

        assertThat(workerAddendum(agentId, "analyst")).isEqualTo(OLD_WORKER_DIRECTIVE + " Act as analyst.");
    }

    @Test
    void aDirectiveCannotBeSwitchedOffIntoAWorkerWithNoInstructions() {
        TenantContext.set(TENANT);
        store.put(new PromptOverride(
                PromptScope.TENANT, "", RunSubtasksToolCallback.WORKER_DIRECTIVE_KEY, "en", null, false, 1, "owner", Instant.now()));
        resolver.evictAll();
        TenantContext.clear();

        assertThat(workerAddendum(agentId, null)).isEqualTo(OLD_WORKER_DIRECTIVE);
    }
}
