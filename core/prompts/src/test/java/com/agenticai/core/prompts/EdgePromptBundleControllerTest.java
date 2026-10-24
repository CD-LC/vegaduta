package com.agenticai.core.prompts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

class EdgePromptBundleControllerTest {

    @SuppressWarnings("unchecked")
    private final ObjectProvider<PromptAgentDirectory> noDirectory = mock(ObjectProvider.class);

    private EdgePromptBundleController controller(PromptResolver resolver) {
        return new EdgePromptBundleController(resolver, noDirectory, new PromptProperties());
    }

    @Test
    void anySignedInUserMayReadItAndNobodyElse() {
        PreAuthorize pre = EdgePromptBundleController.class.getAnnotation(PreAuthorize.class);
        assertThat(pre.value()).isEqualTo("isAuthenticated()");
    }

    @Test
    void theBundleHoldsTheShippedEdgeTextAndNothingFromOtherFamilies() {
        ResponseEntity<EdgePromptBundleController.EdgePromptBundle> response =
                controller(PromptResolver.defaultsOnly()).bundle(null);
        EdgePromptBundleController.EdgePromptBundle bundle = response.getBody();
        assertThat(bundle).isNotNull();
        assertThat(bundle.prompts().keySet())
                .contains(
                        "edge.default_system_prompt",
                        "edge.reasoning_nudge",
                        "edge.refusal.jailbreak",
                        "edge.refusal.pii_email",
                        "edge.refusal.pii_phone",
                        "edge.refusal.pii_ssn",
                        "edge.refusal.pii_card",
                        "edge.refusal.pii_credentials",
                        "edge.localcode_ask.system")
                .allMatch(k -> k.startsWith("edge."));
        assertThat(bundle.prompts().get("edge.default_system_prompt").text()).isEqualTo("You are a helpful assistant.");
        assertThat(bundle.prompts().get("edge.reasoning_nudge").text())
                .isEqualTo(
                        "Answer directly and completely. If the question has multiple parts or steps, work through them"
                                + " in order before giving your final answer.");
        assertThat(bundle.prompts().get("edge.refusal.jailbreak").text())
                .isEqualTo(
                        "Your request appears to contain a prompt injection or jailbreak attempt. "
                                + "Please submit a direct, legitimate request.");
        // Only the nudge may be switched off; the fallback prompt and every refusal always have text.
        assertThat(bundle.prompts().get("edge.reasoning_nudge").switchable()).isTrue();
        assertThat(bundle.prompts().get("edge.default_system_prompt").switchable()).isFalse();
        assertThat(bundle.prompts().get("edge.refusal.jailbreak").switchable()).isFalse();
        assertThat(bundle.version()).isNotBlank();
        assertThat(response.getHeaders().getCacheControl()).contains("private").contains("max-age=30");
    }

    @Test
    void anOverrideChangesTheTextAndTheBundleVersion() {
        PromptResolver defaults = PromptResolver.defaultsOnly();
        PromptResolver overriding = mock(PromptResolver.class);
        when(overriding.catalog()).thenReturn(defaults.catalog());
        when(overriding.resolve(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        inv -> {
                            String key = inv.getArgument(0);
                            ResolvedPrompt base = defaults.resolve(key);
                            if (!key.equals("edge.reasoning_nudge")) {
                                return base;
                            }
                            return new ResolvedPrompt(
                                    key, "Think step by step.", PromptSource.TENANT, PromptScope.TENANT, 3, "en", true, true,
                                    ResolvedPrompt.hashOf("Think step by step."), 5);
                        });
        var plain = controller(defaults).bundle(null).getBody();
        var changed = controller(overriding).bundle(null).getBody();
        assertThat(changed.prompts().get("edge.reasoning_nudge").text()).isEqualTo("Think step by step.");
        assertThat(changed.prompts().get("edge.reasoning_nudge").source()).isEqualTo(PromptSource.TENANT);
        assertThat(changed.prompts().get("edge.reasoning_nudge").version()).isEqualTo(3);
        assertThat(changed.version()).isNotEqualTo(plain.version());
    }

    @Test
    void aSwitchedOffPromptArrivesWithoutTextAndAnUnknownAgentGetsNoAgentScope() {
        PromptResolver defaults = PromptResolver.defaultsOnly();
        PromptResolver resolver = mock(PromptResolver.class);
        when(resolver.catalog()).thenReturn(defaults.catalog());
        UUID agent = UUID.randomUUID();
        java.util.List<PromptContext> seen = new java.util.ArrayList<>();
        when(resolver.resolve(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        inv -> {
                            seen.add(inv.getArgument(1));
                            String key = inv.getArgument(0);
                            if (key.equals("edge.reasoning_nudge")) {
                                return new ResolvedPrompt(
                                        key, null, PromptSource.AGENT, PromptScope.AGENT, 2, "en", false, true, "", 0);
                            }
                            return defaults.resolve(key);
                        });
        PromptAgentDirectory directory = mock(PromptAgentDirectory.class);
        when(directory.canViewInCurrentTenant(agent)).thenReturn(false);
        @SuppressWarnings("unchecked")
        ObjectProvider<PromptAgentDirectory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(directory);

        var body = new EdgePromptBundleController(resolver, provider, new PromptProperties()).bundle(agent).getBody();

        assertThat(body.prompts().get("edge.reasoning_nudge").enabled()).isFalse();
        assertThat(body.prompts().get("edge.reasoning_nudge").text()).isNull();
        assertThat(seen).isNotEmpty().allMatch(c -> c.agentId() == null);
    }

    @Test
    void anAgentOfTheTenantIsPassedOnToTheResolver() {
        PromptResolver defaults = PromptResolver.defaultsOnly();
        PromptResolver resolver = mock(PromptResolver.class);
        when(resolver.catalog()).thenReturn(defaults.catalog());
        UUID agent = UUID.randomUUID();
        java.util.List<PromptContext> seen = new java.util.ArrayList<>();
        when(resolver.resolve(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(
                        inv -> {
                            seen.add(inv.getArgument(1));
                            return defaults.resolve(inv.getArgument(0));
                        });
        PromptAgentDirectory directory = mock(PromptAgentDirectory.class);
        when(directory.canViewInCurrentTenant(agent)).thenReturn(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<PromptAgentDirectory> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(directory);

        new EdgePromptBundleController(resolver, provider, new PromptProperties()).bundle(agent);

        assertThat(seen).isNotEmpty().allMatch(c -> agent.equals(c.agentId()));
    }

    @Test
    void theShippedEdgeFilesAllLoad() {
        assertThat(PromptResolver.defaultsOnly().catalog().problems().toString()).doesNotContain("edge.");
    }
}
