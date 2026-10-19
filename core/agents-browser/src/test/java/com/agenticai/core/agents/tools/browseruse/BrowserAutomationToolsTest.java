package com.agenticai.core.agents.tools.browseruse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.tools.classification.BuiltInToolClassifications;
import com.agenticai.core.agents.tools.classification.ToolClassification;
import com.agenticai.core.agents.tools.classification.ToolDeterminism;
import com.agenticai.core.agents.tools.classification.ToolSensitivity;
import com.agenticai.core.agents.tools.classification.ToolTrustTier;
import com.agenticai.core.secrets.SecretVaultService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Flag-off identity for the tool list (default config adds NO tool), the per-flag offer rules, the
 * session manager's untouched behaviour with no lifecycle observers, and the classification entries
 * the two tools must have (an unclassified tool is blocked, so a missing entry would make the
 * feature dead on arrival).
 */
class BrowserAutomationToolsTest {

    private static final UUID AGENT = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private static List<String> namesFor(
            boolean totpFlag, SecretVaultService vault, BrowserRecipeService recipes) {
        BrowserAutomationTools tools =
                new BrowserAutomationTools(provider(recipes), provider(vault), totpFlag, "");
        BrowserUseSessionManager manager = mock(BrowserUseSessionManager.class);
        return tools.create(manager, AGENT, "session-1").stream()
                .map(ToolCallback::getToolDefinition)
                .map(d -> d.name())
                .toList();
    }

    private static BrowserRecipeService recipes(boolean enabled) {
        BrowserRecipeService service = mock(BrowserRecipeService.class);
        when(service.isEnabled()).thenReturn(enabled);
        return service;
    }

    @Test
    void withDefaultFlagsNoToolIsOffered() {
        assertThat(namesFor(false, mock(SecretVaultService.class), recipes(false))).isEmpty();
        assertThat(namesFor(false, null, null)).isEmpty();
    }

    @Test
    void eachFeatureIsOfferedOnlyWhenItsOwnFlagAndCollaboratorsAreOn() {
        assertThat(namesFor(true, mock(SecretVaultService.class), recipes(false)))
                .containsExactly("browser_totp");
        assertThat(namesFor(false, mock(SecretVaultService.class), recipes(true)))
                .containsExactly("run_browser_recipe");
        assertThat(namesFor(true, mock(SecretVaultService.class), recipes(true)))
                .containsExactlyInAnyOrder("browser_totp", "run_browser_recipe");
        // TOTP needs the vault; a recipe replay without one still runs (its secret placeholders
        // simply fail their step)
        assertThat(namesFor(true, null, recipes(false))).isEmpty();
        assertThat(namesFor(true, null, recipes(true))).containsExactly("run_browser_recipe");
    }

    @Test
    void theSessionManagerIsUnchangedWithNoLifecycleObservers() {
        BrowserUseSessionManager manager =
                new BrowserUseSessionManager("false", "", 2, 10, 300, 1_800, 20_000, 15_000);
        manager.setLifecycles(null);
        manager.setLifecycles(List.of());

        assertThat(manager.isEnabled()).isFalse();
        assertThatThrownBy(
                        () ->
                                manager.withSessionPage(
                                        "tenant-1", AGENT, "session-1", page -> page.url()))
                .isInstanceOf(BrowserUseException.class)
                .hasMessageContaining("not enabled");
    }

    @Test
    void bothToolsAreClassifiedAndGatedByDefault() {
        ToolClassification totp =
                BuiltInToolClassifications.all().get(BrowserTotpToolCallback.NAME);
        assertThat(totp).isNotNull();
        assertThat(totp.trust()).isEqualTo(ToolTrustTier.TENANT_SCOPED);
        assertThat(totp.sensitivity()).isEqualTo(ToolSensitivity.CREDENTIALED);
        assertThat(totp.requiresHumanConfirmation()).isTrue();
        assertThat(totp.needsToolContext()).isTrue();

        ToolClassification recipe =
                BuiltInToolClassifications.all().get(RunBrowserRecipeToolCallback.NAME);
        assertThat(recipe).isNotNull();
        assertThat(recipe.trust()).isEqualTo(ToolTrustTier.UNTRUSTED_EXTERNAL);
        assertThat(recipe.sensitivity()).isEqualTo(ToolSensitivity.EGRESS);
        assertThat(recipe.determinism()).isEqualTo(ToolDeterminism.DURABLE);
        assertThat(recipe.requiresHumanConfirmation()).isTrue();
        assertThat(recipe.needsToolContext()).isTrue();
    }
}
