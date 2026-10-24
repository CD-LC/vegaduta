package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.hosted.HostedChainLink;
import com.agenticai.core.chat.UpstreamProviderException;
import com.agenticai.core.providers.PlatformHostedLlmProviderService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The window of earlier turns the buffered verifiers see ({@code app.chat.parity.verifier-history-window}),
 * the hosted-pool self-heal cooldown and the structured-call timeout ({@code app.chat.resilience.*}) are
 * settings; their defaults are the old constants (6, 10 minutes, 120 seconds).
 */
class ChatTurnLimitsTest {

    private static List<Message> history(int n) {
        java.util.ArrayList<Message> out = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(i % 2 == 0 ? new UserMessage("turn " + i) : new AssistantMessage("turn " + i));
        }
        return out;
    }

    @Test
    void theVerifierHistoryWindowDefaultsToSix() {
        assertThat(new ChatParityProperties().getVerifierHistoryWindow()).isEqualTo(6);
        assertThat(ChatPromptDirectives.recentHistoryBlock(history(10), 6).lines().count()).isEqualTo(6);
    }

    @Test
    void theVerifiersSeeOnlyTheConfiguredNumberOfEarlierMessages() {
        String block = ChatPromptDirectives.recentHistoryBlock(history(10), 2);

        assertThat(block.lines().toList()).hasSize(2);
        assertThat(block).contains("turn 8").contains("turn 9").doesNotContain("turn 7");
    }

    @Test
    void anEmptyHistoryIsStillNoBlock() {
        assertThat(ChatPromptDirectives.recentHistoryBlock(List.of(), 2)).isNull();
    }

    @Test
    void theResilienceDefaultsAreTheOldConstants() {
        ChatResilienceProperties p = new ChatResilienceProperties();
        assertThat(p.getStructuredCallTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(p.getHostedSelfHealCooldown()).isEqualTo(Duration.ofMinutes(10));
    }

    private static UpstreamProviderException invalidModel() {
        return new UpstreamProviderException(
                404,
                "The LLM provider rejected the request (HTTP 404): models/x is not found for API version v1beta",
                "The selected AI model isn't available from this provider anymore.");
    }

    private static HostedChainLink row(long id) {
        return new HostedChainLink(mock(ChatClient.class), "row:" + id, "Gemini", "x", 0, 0, 0);
    }

    @Test
    void aRowIsHealedAgainOnlyAfterTheConfiguredCooldown() throws Exception {
        PlatformHostedLlmProviderService pool = mock(PlatformHostedLlmProviderService.class);
        HostedChainFailover failover = new HostedChainFailover(
                new StaticListableBeanFactory(Map.of("pool", pool)).getBeanProvider(PlatformHostedLlmProviderService.class),
                mock(FreeTierChatClientService.class),
                Runnable::run);
        failover.useSelfHealCooldown(Duration.ofMillis(1));

        failover.maybeSelfHealInvalidModelRow(row(5), invalidModel());
        Thread.sleep(25);
        failover.maybeSelfHealInvalidModelRow(row(5), invalidModel());

        verify(pool, times(2)).reautoSelectModelForRow(eq(5L), anyString());
    }

    @Test
    void withTheDefaultCooldownARowIsHealedOnce() throws Exception {
        PlatformHostedLlmProviderService pool = mock(PlatformHostedLlmProviderService.class);
        HostedChainFailover failover = new HostedChainFailover(
                new StaticListableBeanFactory(Map.of("pool", pool)).getBeanProvider(PlatformHostedLlmProviderService.class),
                mock(FreeTierChatClientService.class),
                Runnable::run);

        failover.maybeSelfHealInvalidModelRow(row(6), invalidModel());
        Thread.sleep(25);
        failover.maybeSelfHealInvalidModelRow(row(6), invalidModel());

        verify(pool, times(1)).reautoSelectModelForRow(eq(6L), anyString());
    }
}
