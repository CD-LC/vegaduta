package com.agenticai.core.providers.validation;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.agenticai.core.providers.ProviderEndpoints;
import com.agenticai.core.providers.ProviderName;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Every key validator calls the vendor at the address {@code app.llm.endpoints.<provider>} names - a proxy or a
 * regional endpoint is validated against, not api.openai.com.
 */
class ProviderValidatorEndpointOverrideTest {

    private static ProviderEndpoints proxied() {
        ProviderEndpoints endpoints = new ProviderEndpoints();
        for (ProviderName provider : ProviderName.values()) {
            endpoints.endpoint(provider).ifPresent(e -> e.setBaseUrl("https://proxy.example/" + provider.name().toLowerCase()));
        }
        return endpoints;
    }

    private static void check(
        ProviderName provider, String expectedUrl, BiFunction<RestClient.Builder, ProviderEndpoints, ProviderValidator> factory
    ) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(expectedUrl)).andRespond(withSuccess());

        factory.apply(builder, proxied()).validate("key");

        server.verify();
    }

    @Test
    void openAi() {
        check(ProviderName.OPENAI, "https://proxy.example/openai/v1/models", OpenAIProviderValidator::new);
    }

    @Test
    void anthropic() {
        check(ProviderName.ANTHROPIC, "https://proxy.example/anthropic/v1/models", AnthropicProviderValidator::new);
    }

    @Test
    void grok() {
        check(ProviderName.GROK, "https://proxy.example/grok/v1/models", GrokProviderValidator::new);
    }

    @Test
    void gemini() {
        check(ProviderName.GEMINI, "https://proxy.example/gemini/v1beta/models", GeminiProviderValidator::new);
    }

    @Test
    void mistral() {
        check(ProviderName.MISTRAL, "https://proxy.example/mistral/v1/models", MistralProviderValidator::new);
    }

    @Test
    void deepSeek() {
        check(ProviderName.DEEPSEEK, "https://proxy.example/deepseek/v1/models", DeepSeekProviderValidator::new);
    }

    @Test
    void groq() {
        check(ProviderName.GROQ, "https://proxy.example/groq/v1/models", GroqProviderValidator::new);
    }

    @Test
    void meta() {
        check(ProviderName.META, "https://proxy.example/meta/v1/models", MetaProviderValidator::new);
    }

    @Test
    void openRouter() {
        check(ProviderName.OPENROUTER, "https://proxy.example/openrouter/v1/auth/key", OpenRouterProviderValidator::new);
    }
}
