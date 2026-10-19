package com.agenticai.core.agents.turn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.agents.turn.quality.QueryComplexityClassifier;
import com.agenticai.core.chat.gateway.GatewayCallSpec;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.ExperienceMode;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Per-turn BYOK client build in {@link ChatClientResolver}: with the hot-path flags off it makes
 * exactly the calls it always made (one credential read and one hosted-pool check per build); with
 * them on, a second turn of the same tenant is served from the caches - and the gateway (budget /
 * rate limit) is still consulted every turn.
 */
class ChatClientResolverHotpathTest {

    private final AgentService agentService = mock(AgentService.class);
    private final ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
    private final LlmGatewayService gateway = mock(LlmGatewayService.class);
    private final TenantLimits limits = mock(TenantLimits.class);
    private ChatClientResolver resolver;
    private Agent agent;

    @BeforeEach
    void setUp() {
        resolver =
                new ChatClientResolver(
                        agentService,
                        credentials,
                        gateway,
                        mock(FreeTierChatClientService.class),
                        limits,
                        mock(QueryComplexityClassifier.class));
        agent =
                new Agent(
                        "A",
                        "prompt",
                        ProviderName.ANTHROPIC,
                        "claude-sonnet-5",
                        1024,
                        0.7,
                        1.0,
                        false,
                        ExperienceMode.ADVANCED);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("claude-sonnet-5");
        when(limits.usesPlatformPool("acme", TenantPlan.PREMIUM)).thenReturn(false);
        when(credentials.resolveActiveKey(ProviderName.ANTHROPIC)).thenReturn("sk-1");
        when(gateway.resolveClient(eq("acme"), any(GatewayCallSpec.class), any()))
                .thenReturn(mock(ChatClient.class));
    }

    private void twoTurns() {
        resolver.buildChatClientFor(agent, "hi", null, "acme", TenantPlan.PREMIUM, 0.7, 1.0);
        resolver.buildChatClientFor(agent, "hi", null, "acme", TenantPlan.PREMIUM, 0.7, 1.0);
    }

    @Test
    void flagsOffDoOneCredentialReadAndOnePoolCheckPerTurn() {
        twoTurns();
        verify(credentials, times(2)).resolveActiveKey(ProviderName.ANTHROPIC);
        verify(limits, times(2)).usesPlatformPool("acme", TenantPlan.PREMIUM);
        verify(gateway, times(2)).resolveClient(eq("acme"), any(GatewayCallSpec.class), any());
    }

    @Test
    void flagsOnServeTheSecondTurnFromCachesButStillCallTheGateway() {
        resolver.useHotpath(
                new ChatHotpath(Duration.ofSeconds(10), Duration.ofSeconds(30), false, false));
        twoTurns();
        verify(credentials, times(1)).resolveActiveKey(ProviderName.ANTHROPIC);
        verify(limits, times(1)).usesPlatformPool("acme", TenantPlan.PREMIUM);
        // budget/rate-limit governance lives behind the gateway and must run on every turn
        verify(gateway, times(2)).resolveClient(eq("acme"), any(GatewayCallSpec.class), any());
    }
}
