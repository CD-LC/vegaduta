package com.agenticai.core.agents.assist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.assist.dto.AssistRequest;
import com.agenticai.core.agents.hosted.FreeTierChatClientService;
import com.agenticai.core.chat.ChatClientRegistry;
import com.agenticai.core.chat.gateway.LlmGatewayService;
import com.agenticai.core.providers.ProviderCredentialResolver;
import com.agenticai.core.providers.ProviderModelCatalogService;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.security.TenantPlan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Regression: POST /api/assist/draft answered 400 (NoActiveProviderException)
 * for an UNLIMITED tenant on the hosted pool, because AssistService only
 * routed plan == FREE to the platform pool instead of asking
 * TenantLimits#usesPlatformPool.
 */
class AssistServicePlatformPoolTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void unlimitedTenantOnPlatformPoolUsesHostedPoolNotByok() {
        ProviderCredentialResolver resolver = mock(ProviderCredentialResolver.class);
        TenantLimits limits = mock(TenantLimits.class);
        FreeTierChatClientService pool = mock(FreeTierChatClientService.class);
        ChatClient client = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
        when(limits.plan("t1")).thenReturn(TenantPlan.UNLIMITED);
        when(limits.usesPlatformPool("t1", TenantPlan.UNLIMITED)).thenReturn(true);
        when(pool.buildClientForFreeTenant(eq("t1"), eq(TenantPlan.UNLIMITED), anyInt(), anyDouble(), anyDouble()))
            .thenReturn(client);
        when(client.prompt().system(anyString()).user(anyString()).call().content()).thenReturn("  drafted  ");

        AssistService service = new AssistService(
            resolver, mock(ChatClientRegistry.class), mock(LlmGatewayService.class),
            mock(ProviderModelCatalogService.class), limits, pool, "claude-x", 1024);

        TenantContext.set(TenantContext.of("t1", "u1", java.util.Set.of()));
        String out = service.draft(new AssistRequest("Field", "Instruction", "<html>big</html>")).suggestion();

        assertEquals("drafted", out);
        verifyNoInteractions(resolver);
        verify(pool).buildClientForFreeTenant(eq("t1"), eq(TenantPlan.UNLIMITED), anyInt(), anyDouble(), anyDouble());
    }

    @Test
    void codeEditSendsTheCallersSystemPromptWithNoFormFieldFraming() {
        TenantLimits limits = mock(TenantLimits.class);
        FreeTierChatClientService pool = mock(FreeTierChatClientService.class);
        ChatClient client = mock(ChatClient.class, Answers.RETURNS_DEEP_STUBS);
        when(limits.plan("t1")).thenReturn(TenantPlan.FREE);
        when(limits.usesPlatformPool("t1", TenantPlan.FREE)).thenReturn(true);
        when(pool.buildClientForFreeTenant(eq("t1"), eq(TenantPlan.FREE), anyInt(), anyDouble(), anyDouble()))
            .thenReturn(client);
        when(client.prompt().system("EDIT RULES").user("APP + REQUEST").call().content()).thenReturn(" blocks ");

        AssistService service = new AssistService(
            mock(ProviderCredentialResolver.class), mock(ChatClientRegistry.class), mock(LlmGatewayService.class),
            mock(ProviderModelCatalogService.class), limits, pool, "claude-x", 1024);

        TenantContext.set(TenantContext.of("t1", "u1", java.util.Set.of()));
        String out = service.codeEdit(
            new com.agenticai.core.agents.assist.dto.AssistCodeEditRequest("EDIT RULES", "APP + REQUEST")).suggestion();

        assertEquals("blocks", out);
    }
}
