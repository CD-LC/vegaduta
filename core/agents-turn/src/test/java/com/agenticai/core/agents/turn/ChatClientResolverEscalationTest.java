package com.agenticai.core.agents.turn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

/**
 * L4 wiring in {@link ChatClientResolver}: an economy-routed turn registers its cheap client with
 * the {@link VerifierEscalationRouter} only when the router is active; flag off / kill switch / no
 * router means the resolver returns the same client and registers nothing (behaviour identical to
 * before L4).
 */
class ChatClientResolverEscalationTest {

    private final AgentService agentService = mock(AgentService.class);
    private final ProviderCredentialResolver credentials = mock(ProviderCredentialResolver.class);
    private final LlmGatewayService gateway = mock(LlmGatewayService.class);
    private final TenantLimits limits = mock(TenantLimits.class);
    private final QueryComplexityClassifier classifier = mock(QueryComplexityClassifier.class);
    private final ChatClient cheap = mock(ChatClient.class);
    private final ChatClient strong = mock(ChatClient.class);
    private ChatClientResolver resolver;
    private Agent agent;
    private UUID agentId;

    @BeforeEach
    void setUp() throws Exception {
        resolver =
                new ChatClientResolver(
                        agentService,
                        credentials,
                        gateway,
                        mock(FreeTierChatClientService.class),
                        limits,
                        classifier);
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
        agent.setCostRouting(true, ProviderName.OPENAI, "gpt-mini", null);
        agentId = UUID.randomUUID();
        Field id = Agent.class.getDeclaredField("id");
        id.setAccessible(true);
        id.set(agent, agentId);
        when(agentService.resolveEffectiveModel(agent)).thenReturn("claude-sonnet-5");
        when(limits.usesPlatformPool("acme", TenantPlan.PREMIUM)).thenReturn(false);
        when(credentials.resolveActiveKey(ProviderName.ANTHROPIC)).thenReturn("sk-a");
        when(credentials.resolveActiveKey(ProviderName.OPENAI)).thenReturn("sk-o");
        when(classifier.isSimple(any(), any())).thenReturn(true);
        when(gateway.resolveClient(eq("acme"), any(GatewayCallSpec.class), any()))
                .thenAnswer(
                        inv -> {
                            GatewayCallSpec spec = inv.getArgument(1);
                            return spec.providerName() == ProviderName.OPENAI ? cheap : strong;
                        });
    }

    private ChatClient build() {
        return resolver.buildChatClientFor(agent, "hi", null, "acme", TenantPlan.PREMIUM, 0.7, 1.0);
    }

    @Test
    void activeRouterRegistersEconomyClientAndPrimarySupplier() {
        VerifierEscalationRouter router =
                new VerifierEscalationRouter(new SimpleMeterRegistry(), true, false, "", 2, 0.2);
        resolver.useEscalationRouter(router);
        ChatClient used = build();
        assertSame(cheap, used);
        assertTrue(router.escalationFor(agentId, used).isPresent());
        assertSame(strong, router.escalationFor(agentId, used).get().get());
    }

    @Test
    void flagOffRegistersNothingAndReturnsSameClient() {
        VerifierEscalationRouter router =
                new VerifierEscalationRouter(new SimpleMeterRegistry(), false, false, "", 2, 0.2);
        resolver.useEscalationRouter(router);
        ChatClient used = build();
        assertSame(cheap, used);
        assertTrue(router.escalationFor(agentId, used).isEmpty());
    }

    @Test
    void killSwitchRegistersNothingAndReturnsSameClient() {
        VerifierEscalationRouter router =
                new VerifierEscalationRouter(new SimpleMeterRegistry(), true, true, "", 2, 0.2);
        resolver.useEscalationRouter(router);
        ChatClient used = build();
        assertSame(cheap, used);
        assertTrue(router.escalationFor(agentId, used).isEmpty());
    }

    @Test
    void noRouterWiredIsUnchanged() {
        assertSame(cheap, build());
    }

    @Test
    void nonEconomyTurnIsNeverRegistered() {
        VerifierEscalationRouter router =
                new VerifierEscalationRouter(new SimpleMeterRegistry(), true, false, "", 2, 0.2);
        resolver.useEscalationRouter(router);
        when(classifier.isSimple(any(), any())).thenReturn(false);
        ChatClient used = build();
        assertSame(strong, used);
        assertEquals(true, router.escalationFor(agentId, used).isEmpty());
    }
}
