package com.agenticai.core.agents.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.auxiliary.AuxiliaryModelProperties;
import com.agenticai.core.agents.auxiliary.AuxiliaryModelResolver;
import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.agents.turn.AgentServiceAuxiliaryAgentModels;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.providers.ProviderModelResponse;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import com.agenticai.core.tenancy.TenantAiModelPreferenceService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The evidence behind shipping {@code app.context.compaction.enabled} ON (owner decision 2026-10-23, defaults
 * review row 10): a long history is pushed through the REAL collaborators a chat turn uses - {@link
 * ChatContextCompaction} (switched on, with the numbers {@code application.yml} ships), {@link
 * ContextCompactionEngine}, the real {@link LlmCompactionSummarizer} on the real {@link AuxiliaryModelResolver},
 * and the real {@code restore_tool_result} callback - with only the provider and the log table stubbed.
 *
 * <p>It proves the four things the flip was conditional on: compaction triggers on an oversized history, a
 * cleared tool result is restorable through its placeholder, a failed summary call is skipped safely (every
 * turn kept, no exception, no retry storm), and with no auxiliary compaction model configured the summary is
 * written by the agent's own model.
 */
class ContextCompactionDefaultOnHarnessTest {

    private static final UUID AGENT = UUID.randomUUID();
    private static final String SESSION = "session-1";
    private static final String MODEL = "m";
    /** A small window so a modest history is "oversized"; the engine reads it from the catalog like a real turn. */
    private static final int WINDOW = 16_000;

    private final ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
    private final ProviderModelCatalogService catalog = mock(ProviderModelCatalogService.class);
    private final AgentService agents = mock(AgentService.class);
    private final LlmGatewayService gateway = mock(LlmGatewayService.class);
    private final TenantLimits limits = mock(TenantLimits.class);
    private final FreeTierChatClientService pool = mock(FreeTierChatClientService.class);
    private final ToolCallLogService logs = mock(ToolCallLogService.class);
    private final ChatClient client = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
    private ChatContextCompaction compaction;

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bean);
        return provider;
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        TenantContext.set(TenantContext.of("t1", "u1", Set.of()));
        when(limits.plan("t1")).thenReturn(TenantPlan.PREMIUM);
        when(limits.usesPlatformPool("t1", TenantPlan.PREMIUM)).thenReturn(false);
        when(gateway.resolveClient(anyString(), any(GatewayCallSpec.class))).thenReturn(client);
        when(credentials.resolveActiveKey(any())).thenReturn("key");
        when(catalog.replacementForRetiredModel(any())).thenReturn(Optional.empty());
        when(catalog.findModel(ProviderName.ANTHROPIC, MODEL))
                .thenReturn(
                        Optional.of(new ProviderModelResponse(MODEL, "M", WINDOW, false, false, false, "summary")));

        // The agent being compacted runs on its own provider and model; nothing is configured for compaction.
        Agent agent = mock(Agent.class);
        when(agent.getProviderName()).thenReturn(ProviderName.MISTRAL);
        when(agent.getModel()).thenReturn("agent-own-model");
        when(agents.getEntity(AGENT)).thenReturn(agent);

        ObjectProvider<TenantAiModelPreferenceService> noPreference = mock(ObjectProvider.class);
        AuxiliaryModelResolver auxiliary =
                new AuxiliaryModelResolver(
                        new AuxiliaryModelProperties(),
                        credentials,
                        catalog,
                        new AgentServiceAuxiliaryAgentModels(agents),
                        noPreference,
                        "claude-x");
        LlmCompactionSummarizer summarizer =
                new LlmCompactionSummarizer(auxiliary, gateway, limits, pool, 800, 20_000L, 24_000, 2_000);

        // The numbers application.yml ships for app.context.compaction.* (clear-at 0.6, compact-at 0.85, keep 3 tool
        // results, keep 6 messages, window default 128000, summary 800 tokens, at most 3 summaries per run).
        compaction =
                new ChatContextCompaction(
                        providerOf(summarizer),
                        providerOf(catalog),
                        providerOf(logs),
                        providerOf((io.micrometer.core.instrument.MeterRegistry) null),
                        true,
                        0.6,
                        0.85,
                        3,
                        true,
                        6,
                        128_000,
                        800,
                        3);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void summaryCallReturns(String text) {
        when(client.prompt().system(anyString()).user(anyString()).advisors(any(Consumer.class)).call().content())
                .thenReturn(text);
    }

    private void summaryCallFails() {
        when(client.prompt().system(anyString()).user(anyString()).advisors(any(Consumer.class)).call().content())
                .thenThrow(new IllegalStateException("provider down"));
    }

    /** About 1_500 tokens per message, 60 messages: far above a 16_000-token window. */
    private static List<Message> oversizedChat() {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("memory block"));
        for (int i = 1; i <= 30; i++) {
            messages.add(new UserMessage("u" + i + " " + "q".repeat(6_000)));
            messages.add(new AssistantMessage("a" + i + " " + "r".repeat(6_000)));
        }
        return messages;
    }

    private static List<Message> oversizedToolRun(int rounds) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage("sys"));
        messages.add(new UserMessage("research this"));
        for (int i = 1; i <= rounds; i++) {
            messages.add(
                    AssistantMessage.builder()
                            .content("")
                            .toolCalls(
                                    List.of(
                                            new AssistantMessage.ToolCall(
                                                    "c" + i, "function", "search", "{\"q\":\"topic " + i + "\"}")))
                            .build());
            messages.add(
                    ToolResponseMessage.builder()
                            .responses(
                                    List.of(
                                            new ToolResponseMessage.ToolResponse(
                                                    "c" + i, "search", ("result-" + i + " ").repeat(1_000))))
                            .build());
        }
        return messages;
    }

    private static String responseData(Message toolMessage) {
        return ((ToolResponseMessage) toolMessage).getResponses().get(0).responseData();
    }

    @Test
    void anOversizedHistoryIsCompactedIntoOneSummaryAndTheStoredTurnsAreUntouched() {
        summaryCallReturns("- goal: research topic X\n- decided: use option B");
        CompactionRun run = compaction.openRun(ProviderName.ANTHROPIC, MODEL, AGENT, SESSION, List.of(), null);
        assertThat(run).as("the flag is on, so a turn gets a compaction run").isNotNull();
        List<Message> history = oversizedChat();
        List<Message> before = new ArrayList<>(history);

        List<Message> view = run.prepareMessages(history, "system prompt", "the next user message");

        assertThat(history).as("compaction only changes the prompt view").isEqualTo(before);
        assertThat(view.size()).isLessThan(history.size());
        assertThat(view.get(0)).as("the memory block stays first").isSameAs(history.get(0));
        assertThat(view.get(1)).isInstanceOf(SystemMessage.class);
        assertThat(view.get(1).getText())
                .startsWith(ContextCompactionEngine.SUMMARY_PREFIX)
                .contains("use option B");
        assertThat(view.get(view.size() - 1))
                .as("the newest turn is kept verbatim")
                .isSameAs(history.get(history.size() - 1));
    }

    @Test
    void withNoAuxiliaryCompactionModelConfiguredTheAgentsOwnModelWritesTheSummary() {
        summaryCallReturns("the summary");
        CompactionRun run = compaction.openRun(ProviderName.ANTHROPIC, MODEL, AGENT, SESSION, List.of(), null);

        List<Message> view = run.prepareMessages(oversizedChat(), "system prompt", "next");

        assertThat(view.get(1).getText()).contains("the summary");
        ArgumentCaptor<GatewayCallSpec> spec = ArgumentCaptor.forClass(GatewayCallSpec.class);
        verify(gateway).resolveClient(anyString(), spec.capture());
        assertThat(spec.getValue().providerName()).isEqualTo(ProviderName.MISTRAL);
        assertThat(spec.getValue().model()).isEqualTo("agent-own-model");
        assertThat(spec.getValue().featureKey()).isEqualTo("compaction");
    }

    @Test
    void aFailedSummaryCallIsSkippedSafelyKeepsEveryTurnAndIsNotRetriedOnEveryToolRound() {
        summaryCallFails();
        CompactionRun run = compaction.openRun(ProviderName.ANTHROPIC, MODEL, AGENT, SESSION, List.of(), null);
        List<Message> history = oversizedChat();

        List<Message> first = run.prepareMessages(history, "system prompt", "next");
        List<Message> second = run.transform(history, MODEL);
        List<Message> third = run.transform(history, MODEL);

        assertThat(first).as("the turn proceeds with the full history").isSameAs(history);
        assertThat(second).isSameAs(history);
        assertThat(third).isSameAs(history);
        verify(gateway, times(1)).resolveClient(anyString(), any(GatewayCallSpec.class));
    }

    @Test
    void aBlankSummaryIsTreatedAsAFailureAndKeepsEveryTurn() {
        summaryCallReturns("   ");
        CompactionRun run = compaction.openRun(ProviderName.ANTHROPIC, MODEL, AGENT, SESSION, List.of(), null);
        List<Message> history = oversizedChat();

        assertThat(run.prepareMessages(history, "system prompt", "next")).isSameAs(history);
    }

    @Test
    void anOversizedToolRunClearsOldResultsToPlaceholdersThatRestoreTheOriginalOutput() {
        List<ToolCallback> restoreTools = compaction.restoreToolsFor(AGENT, SESSION);
        assertThat(restoreTools).as("restore_tool_result is offered while compaction is on").hasSize(1);
        CompactionRun run =
                compaction.openRun(ProviderName.ANTHROPIC, MODEL, AGENT, SESSION, restoreTools, null);
        List<Message> history = oversizedToolRun(12);

        List<Message> view = run.transform(history, MODEL);

        assertThat(view).hasSameSizeAs(history);
        String placeholder = responseData(view.get(3));
        assertThat(placeholder).startsWith("{\"context_cleared\":true");
        assertThat(placeholder).contains("restore_tool_result");
        assertThat(responseData(view.get(view.size() - 1)))
                .as("the newest result is still verbatim")
                .isEqualTo(responseData(history.get(history.size() - 1)));

        String args = "{\"q\":\"topic 1\"}";
        String original = responseData(history.get(3));
        Instant now = Instant.now();
        when(logs.findRecentByAgentIdAndSessionIdAndToolName(AGENT, SESSION, "search"))
                .thenReturn(List.of(new ToolCallLog(AGENT, SESSION, "search", args, original, null, now, now)));
        String stepId = ToolResultRestoreIds.stepId("search", args);
        assertThat(placeholder).contains(stepId);

        String restored = restoreTools.get(0).call("{\"stepId\":\"" + stepId + "\"}");

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void theRestoreToolIsBoundToTheRunsOwnAgentAndSession() {
        ToolCallback restore = compaction.restoreToolsFor(AGENT, SESSION).get(0);
        UUID stepOfAnotherRun = UUID.randomUUID();
        when(logs.findByIdAndAgentIdAndSessionId(stepOfAnotherRun, AGENT, SESSION)).thenReturn(Optional.empty());

        String reply = restore.call("{\"stepId\":\"" + stepOfAnotherRun + "\"}");

        assertThat(reply).contains("No stored result");
        verify(logs).findByIdAndAgentIdAndSessionId(stepOfAnotherRun, AGENT, SESSION);
    }

    @Test
    void aSmallHistoryIsNotTouchedEvenThoughTheFlagIsOn() {
        CompactionRun run = compaction.openRun(ProviderName.ANTHROPIC, MODEL, AGENT, SESSION, List.of(), null);
        List<Message> small = List.of(new SystemMessage("memory block"), new UserMessage("hi"), new AssistantMessage("hello"));

        assertThat(run.prepareMessages(small, "system prompt", "next")).isSameAs(small);
        verify(gateway, times(0)).resolveClient(anyString(), any(GatewayCallSpec.class));
    }
}
