package com.agenticai.core.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.providers.LocalOpenAiCredential;
import com.agenticai.core.providers.ProviderName;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;

/**
 * {@link LocalOpenAiAgentChatClientFactory} driven end to end against a canned transport, as {@link
 * LlmFactoryWireParityTest} does for the other factories: URL normalisation (the documented {@code
 * /v1} suffix must not double up), optional bearer auth, use-time SSRF check, and the provider the
 * metrics advisor records.
 */
class LocalOpenAiAgentChatClientFactoryTest {

    private static final String REPLY =
            "{\"id\":\"c1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,"
                + "\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";

    /** The terminal interceptor the SDK model gets from LlmHttpClientFactory#transport. */
    private final CannedLlmTransport transport = new CannedLlmTransport(() -> REPLY);

    private final LlmHttpClientFactory http = mock(LlmHttpClientFactory.class);

    @SuppressWarnings("unchecked")
    private LocalOpenAiAgentChatClientFactory factory() {
        when(http.transport(anyBoolean())).thenReturn(transport);
        when(http.readTimeout()).thenReturn(Duration.ofSeconds(30));
        ObjectProvider<ObservationRegistry> obs = mock(ObjectProvider.class);
        when(obs.getIfAvailable(any())).thenReturn(ObservationRegistry.NOOP);
        ObjectProvider<DefaultAgentAdvisor> adv = mock(ObjectProvider.class);
        when(adv.orderedStream())
                .thenAnswer(i -> java.util.stream.Stream.<DefaultAgentAdvisor>empty());
        LlmModelSupport support =
                new LlmModelSupport(
                        obs,
                        adv,
                        2,
                        Duration.ofMillis(1),
                        Duration.ofMillis(2),
                        true,
                        "MISTRAL",
                        "low",
                        16,
                        Duration.ofMinutes(30),
                        false);
        return new LocalOpenAiAgentChatClientFactory(
                mock(LlmCallMetricsService.class),
                mock(LlmCallLogService.class),
                new SimpleMeterRegistry(),
                http,
                new ResilientToolCallingManagerFactory(JsonMappers.plain()),
                support);
    }

    private void chat(String baseUrl, String key) {
        factory()
                .create(new LocalOpenAiCredential(baseUrl, key).encode(), "qwen3:8b", 256, 0.2, 0.9)
                .prompt()
                .user("hi")
                .call()
                .content();
    }

    @Test
    void servesTheProviderItIsRegisteredFor() {
        assertThat(factory().supportedProvider()).isEqualTo(ProviderName.LOCAL_OPENAI);
    }

    @Test
    void aDocumentedV1BaseUrlDoesNotDoubleUpTheVersionSegment() {
        chat("http://localhost:8000/v1", null);

        assertThat(transport.captured()).hasSize(1);
        assertThat(transport.captured().get(0).url())
                .isEqualTo("http://localhost:8000/v1/chat/completions");
        verify(http).requireAllowedTenantBaseUrl("http://localhost:8000");
    }

    @Test
    void aBareHostAndASubPathBothResolveToTheOpenAiPath() {
        chat("http://lmstudio.lan:1234/", null);
        chat("https://gw.example.com/openai/v1/", null);

        assertThat(transport.captured().get(0).url())
                .isEqualTo("http://lmstudio.lan:1234/v1/chat/completions");
        assertThat(transport.captured().get(1).url())
                .isEqualTo("https://gw.example.com/openai/v1/chat/completions");
    }

    @Test
    void noKeyMeansNoAuthorizationHeaderAndAKeyMeansBearer() {
        chat("http://localhost:8080/v1", "   ");
        chat("http://localhost:8080/v1", " secret-key ");

        assertThat(transport.captured().get(0).headers().get("Authorization")).isNull();
        assertThat(transport.captured().get(1).headers().get("Authorization"))
                .isEqualTo("Bearer secret-key");
    }

    @Test
    void sendsTheModelAndSamplingToTheServer() {
        chat("http://localhost:8000/v1", null);

        String body = transport.captured().get(0).body();
        assertThat(body).contains("\"model\":\"qwen3:8b\"").contains("\"max_tokens\":256");
    }

    @Test
    void aBaseUrlTheGuardRefusesNeverReachesTheNetwork() {
        doThrow(new LlmHttpClientFactory.ModelServerUrlNotAllowedException("blocked", null))
                .when(http)
                .requireAllowedTenantBaseUrl(any());

        assertThatThrownBy(() -> chat("http://169.254.169.254/v1", null))
                .isInstanceOf(LlmHttpClientFactory.ModelServerUrlNotAllowedException.class);
        assertThat(transport.captured()).isEmpty();
    }
}
