package com.agenticai.core.providers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@code app.llm.endpoints.*}: the single place each vendor's address lives. The defaults are the literals the
 * validators, the live model fetcher, the hosted pool and the chat-client factories used to carry separately.
 */
class ProviderEndpointsTest {

    private static ProviderEndpoints bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
            .bind("app.llm.endpoints", org.springframework.boot.context.properties.bind.Bindable.of(ProviderEndpoints.class))
            .orElseGet(ProviderEndpoints::new);
    }

    @Test
    void theDefaultsAreTheVendorsPublicEndpointsAsBefore() {
        ProviderEndpoints endpoints = new ProviderEndpoints();

        assertEquals("https://api.openai.com", endpoints.baseUrl(ProviderName.OPENAI));
        assertEquals("https://api.x.ai", endpoints.baseUrl(ProviderName.GROK));
        assertEquals("https://api.groq.com/openai", endpoints.baseUrl(ProviderName.GROQ));
        assertEquals("https://openrouter.ai/api", endpoints.baseUrl(ProviderName.OPENROUTER));
        assertEquals("https://api.sarvam.ai", endpoints.baseUrl(ProviderName.SARVAM));
        assertEquals("https://api.openai.com/v1/models", endpoints.modelsUrl(ProviderName.OPENAI));
        assertEquals("https://api.anthropic.com/v1/models", endpoints.modelsUrl(ProviderName.ANTHROPIC));
        assertEquals("https://api.groq.com/openai/v1/models", endpoints.modelsUrl(ProviderName.GROQ));
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models", endpoints.modelsUrl(ProviderName.GEMINI));
        assertEquals("https://api.x.ai/v1/language-models", endpoints.grokLanguageModelsUrl());
        assertEquals("https://openrouter.ai/api/v1/auth/key", endpoints.openrouterAuthKeyUrl());
        assertEquals(
            "/v1beta/openai/chat/completions", endpoints.getGemini().getCompletionsPath());
    }

    @Test
    void aBaseUrlOverrideMovesEveryUrlOfThatVendorAndNoOther() {
        ProviderEndpoints endpoints = bind(Map.of(
            "app.llm.endpoints.openai.base-url", "https://llm-proxy.corp.example/openai/",
            "app.llm.endpoints.grok.base-url", "https://eu.x.ai"));

        assertEquals("https://llm-proxy.corp.example/openai", endpoints.baseUrl(ProviderName.OPENAI));
        assertEquals("https://llm-proxy.corp.example/openai/v1/models", endpoints.modelsUrl(ProviderName.OPENAI));
        assertEquals("https://eu.x.ai/v1/language-models", endpoints.grokLanguageModelsUrl());
        assertEquals("https://api.mistral.ai", endpoints.baseUrl(ProviderName.MISTRAL));
        assertEquals("https://openrouter.ai/api/v1/auth/key", endpoints.openrouterAuthKeyUrl());
    }

    @Test
    void aBlankValueFallsBackToTheDefaultSoAnEmptyEnvVariableNeverBreaksTheVendor() {
        ProviderEndpoints endpoints = bind(Map.of(
            "app.llm.endpoints.mistral.base-url", "  ",
            "app.llm.endpoints.mistral.models-path", ""));

        assertEquals("https://api.mistral.ai/v1/models", endpoints.modelsUrl(ProviderName.MISTRAL));
    }

    @Test
    void aPathWithoutALeadingSlashGetsOne() {
        ProviderEndpoints endpoints = bind(Map.of("app.llm.endpoints.deepseek.models-path", "models"));

        assertEquals("https://api.deepseek.com/models", endpoints.modelsUrl(ProviderName.DEEPSEEK));
    }

    @Test
    void aBaseUrlThatIsNotHttpFailsStartupInsteadOfBeingUsed() {
        assertThrows(Exception.class, () -> bind(Map.of("app.llm.endpoints.openai.base-url", "file:///etc/passwd")));
        assertThrows(Exception.class, () -> bind(Map.of("app.llm.endpoints.openai.base-url", "api.openai.com")));
        assertThrows(Exception.class, () -> bind(Map.of("app.llm.endpoints.openai.base-url", "ftp://host")));
    }

    @Test
    void aProviderWithoutAVendorEndpointHasNoEntry() {
        ProviderEndpoints endpoints = new ProviderEndpoints();

        assertTrue(endpoints.endpoint(ProviderName.OLLAMA).isEmpty());
        assertTrue(endpoints.endpoint(ProviderName.AZURE_OPENAI).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> endpoints.baseUrl(ProviderName.OLLAMA));
    }
}
