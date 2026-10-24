package com.agenticai.core.agents.turn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.modality.ModelModalitiesService;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.security.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The turn-level limits that used to be constants of the turn classes - the low-confidence escalation windows
 * and registration lifetime, the chat attachment size limits, the prompt-directive cache lifetime - are {@link
 * ChatLimitsProperties} ({@code app.chat.limits.*}), and their defaults are the old numbers.
 */
class ChatLimitsTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void theDefaultsAreTheOldConstants() {
        ChatLimitsProperties p = new ChatLimitsProperties();
        assertThat(p.getEscalation().getLowConfidenceMaxChars()).isEqualTo(400);
        assertThat(p.getEscalation().getLowConfidenceLeadingChars()).isEqualTo(240);
        assertThat(p.getEscalation().getRegistrationTtl()).isEqualTo(Duration.ofMinutes(10));
        assertThat(p.getAttachments().getMaxImageBytes()).isEqualTo(8L * 1024 * 1024);
        assertThat(p.getAttachments().getMaxAudioBytes()).isEqualTo(15L * 1024 * 1024);
        assertThat(p.getAttachments().getMaxVideoBytes()).isEqualTo(25L * 1024 * 1024);
        assertThat(p.getAttachments().getMaxDocumentBytes()).isEqualTo(10L * 1024 * 1024);
        assertThat(p.getDirectiveCache().getTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.getDirectiveCache().getFailureTtl()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.getDirectiveCache().getMaxTenants()).isEqualTo(10_000);
        assertThat(p.getShareTurnTimeout()).isEqualTo(Duration.ofSeconds(45));
    }

    // ---- low-confidence escalation windows ------------------------------------------------------------

    private static VerifierEscalationRouter router(ChatLimitsProperties limits) {
        VerifierEscalationRouter r = new VerifierEscalationRouter(new SimpleMeterRegistry(), true, false, 2, 0.2);
        if (limits != null) {
            r.useLimits(limits);
        }
        return r;
    }

    @Test
    void aLongerReplyCountsAsLowConfidenceWhenTheLimitIsRaised() {
        String reply = "I don't know. " + "x".repeat(500);

        assertThat(router(null).inspect(reply, null, 0, 0).lowConfidence())
                .as("500+ characters is not a short reply by default")
                .isFalse();

        ChatLimitsProperties wider = new ChatLimitsProperties();
        wider.getEscalation().setLowConfidenceMaxChars(800);
        assertThat(router(wider).inspect(reply, null, 0, 0).lowConfidence()).isTrue();
    }

    @Test
    void theMarkerMustSitInsideTheConfiguredLeadingWindow() {
        String reply = "y".repeat(100) + " I don't know";

        assertThat(router(null).inspect(reply, null, 0, 0).lowConfidence()).isTrue();

        ChatLimitsProperties narrow = new ChatLimitsProperties();
        narrow.getEscalation().setLowConfidenceLeadingChars(50);
        assertThat(router(narrow).inspect(reply, null, 0, 0).lowConfidence()).isFalse();
    }

    @Test
    void aRegistrationExpiresAfterTheConfiguredLifetime() throws Exception {
        ChatLimitsProperties shortLived = new ChatLimitsProperties();
        shortLived.getEscalation().setRegistrationTtl(Duration.ofMillis(1));
        VerifierEscalationRouter r = router(shortLived);
        ChatClient cheapA = mock(ChatClient.class);
        UUID a = UUID.randomUUID();
        r.registerCheap(a, cheapA, () -> mock(ChatClient.class));
        Thread.sleep(25);
        r.registerCheap(UUID.randomUUID(), mock(ChatClient.class), () -> mock(ChatClient.class));

        assertThat(r.escalationFor(a, cheapA)).isEmpty();

        VerifierEscalationRouter normal = router(null);
        normal.registerCheap(a, cheapA, () -> mock(ChatClient.class));
        Thread.sleep(25);
        normal.registerCheap(UUID.randomUUID(), mock(ChatClient.class), () -> mock(ChatClient.class));
        assertThat(normal.escalationFor(a, cheapA)).isPresent();
    }

    // ---- attachment size limits -----------------------------------------------------------------------

    @Test
    void aDocumentOverTheConfiguredLimitIsRefused() {
        ChatAttachmentService service = new ChatAttachmentService(
                mock(AgentService.class),
                mock(ProviderModelCatalogService.class),
                mock(ModelModalitiesService.class),
                mock(FileStorageService.class));
        ChatLimitsProperties tight = new ChatLimitsProperties();
        tight.getAttachments().setMaxDocumentBytes(5L * 1024 * 1024);
        service.useLimits(tight);
        MockMultipartFile pdf = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[6 * 1024 * 1024]);

        assertThatThrownBy(() -> service.upload(UUID.randomUUID(), pdf))
                .isInstanceOf(AttachmentTooLargeException.class)
                .hasMessageContaining("limited to 5MB");
    }

    @Test
    void aDocumentUnderTheDefaultLimitIsNotRefusedForItsSize() {
        AgentService agentService = mock(AgentService.class);
        Agent agent = mock(Agent.class);
        UUID agentId = UUID.randomUUID();
        when(agentService.requireAccess(agentId, PermissionLevel.RUNNER)).thenReturn(agent);
        ChatAttachmentService service = new ChatAttachmentService(
                agentService,
                mock(ProviderModelCatalogService.class),
                mock(ModelModalitiesService.class),
                mock(FileStorageService.class));
        MockMultipartFile pdf = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[9 * 1024 * 1024]);

        try {
            service.upload(agentId, pdf);
        } catch (AttachmentTooLargeException e) {
            throw new AssertionError("9 MB is under the default 10 MB document limit", e);
        } catch (RuntimeException expectedFromTheUnstubbedCollaborators) {
            // The size check passed; what follows needs real collaborators.
        }
    }

    // ---- prompt directive cache ------------------------------------------------------------------------
    // The tenant's directive overrides are cached by the prompt registry (core/prompts) under app.prompts.cache.*;
    // DefaultPromptResolverTest#overridesAreCachedForTheTtlAndEvictedOnDemand covers the lifetime.

    // ---- completed-message event excerpt --------------------------------------------------------------

    @Test
    void theCompletedEventCarriesTheConfiguredExcerpt() {
        com.agenticai.core.eventbus.WorkflowEventPublisher publisher =
                mock(com.agenticai.core.eventbus.WorkflowEventPublisher.class);
        java.util.List<Object> excerpts = new java.util.ArrayList<>();
        org.mockito.Mockito.doAnswer(inv -> {
                    excerpts.add(((Map<?, ?>) inv.getArgument(2)).get("answerExcerpt"));
                    return null;
                })
                .when(publisher)
                .publish(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.<Map<String, Object>>any(), org.mockito.ArgumentMatchers.anyString());
        AgentMessageEvents events = new AgentMessageEvents();
        events.setPublisher(publisher);

        events.emitAgentMessageCompleted(UUID.randomUUID(), "s", "t", "x".repeat(600), "live");
        ChatLimitsProperties shorter = new ChatLimitsProperties();
        shorter.setCompletedEventExcerptChars(10);
        events.useLimits(shorter);
        events.emitAgentMessageCompleted(UUID.randomUUID(), "s", "t", "x".repeat(600), "live");

        assertThat(((String) excerpts.get(0)).length()).as("default").isEqualTo(500);
        assertThat(((String) excerpts.get(1)).length()).isEqualTo(10);
    }

    // ---- economy auxiliary clamp ------------------------------------------------------------------------

    @Test
    void theEconomyAuxiliaryCallIsClampedToTheConfiguredRange() {
        AgentService agentService = mock(AgentService.class);
        com.agenticai.core.providers.ProviderCredentialResolver credentials =
                mock(com.agenticai.core.providers.ProviderCredentialResolver.class);
        com.agenticai.core.chat.gateway.LlmGatewayService gateway =
                mock(com.agenticai.core.chat.gateway.LlmGatewayService.class);
        ChatClientResolver resolver = new ChatClientResolver(
                agentService,
                credentials,
                gateway,
                mock(com.agenticai.core.agents.hosted.FreeTierChatClientService.class),
                mock(com.agenticai.core.security.TenantLimits.class),
                mock(com.agenticai.core.agents.turn.quality.QueryComplexityClassifier.class));
        Agent agent = new Agent("A", "p", com.agenticai.core.providers.ProviderName.ANTHROPIC, "claude-sonnet-5", 4000, 0.7, 1.0,
                false, com.agenticai.core.security.ExperienceMode.ADVANCED);
        agent.setCostRouting(true, com.agenticai.core.providers.ProviderName.ANTHROPIC, "claude-haiku-4-5", null);
        when(credentials.resolveActiveKey(com.agenticai.core.providers.ProviderName.ANTHROPIC)).thenReturn("sk");
        java.util.List<Integer> maxTokens = new java.util.ArrayList<>();
        when(gateway.resolveClient(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(com.agenticai.core.chat.gateway.GatewayCallSpec.class), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    maxTokens.add(((com.agenticai.core.chat.gateway.GatewayCallSpec) inv.getArgument(1)).maxTokens());
                    return mock(ChatClient.class);
                });

        resolver.buildEconomyClientOrNull(agent, "acme", null);
        ChatLimitsProperties wider = new ChatLimitsProperties();
        wider.setEconomyAuxMaxTokens(3000);
        resolver.useLimits(wider);
        resolver.buildEconomyClientOrNull(agent, "acme", null);

        assertThat(maxTokens).containsExactly(1024, 3000);
    }
}
