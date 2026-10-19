package com.agenticai.core.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.knowledge.SidecarClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

/**
 * Which embedder is active. The contract that matters: the default and every existing value keep
 * the sidecar / openai-compatible behaviour, and {@code auto} prefers a configured cloud key, then
 * Ollama, then the sidecar - never two embedders at once.
 */
class EmbeddingProviderSelectionTest {

    private static MockEnvironment env(String... kv) {
        MockEnvironment e = new MockEnvironment();
        for (int i = 0; i < kv.length; i += 2) {
            e.setProperty(kv[i], kv[i + 1]);
        }
        return e;
    }

    @Test
    void defaultAndUnknownValuesStayOnTheSidecar() {
        assertThat(EmbeddingProviderSelection.resolve(env())).isEqualTo("sidecar");
        assertThat(EmbeddingProviderSelection.resolve(env("app.embeddings.provider", "sidecar"))).isEqualTo("sidecar");
        assertThat(EmbeddingProviderSelection.resolve(env("app.embeddings.provider", "bogus"))).isEqualTo("sidecar");
    }

    @Test
    void explicitProvidersAreHonouredCaseInsensitively() {
        assertThat(EmbeddingProviderSelection.resolve(env("app.embeddings.provider", "OpenAI-Compatible"))).isEqualTo("openai-compatible");
        assertThat(EmbeddingProviderSelection.resolve(env("app.embeddings.provider", "ollama"))).isEqualTo("ollama");
    }

    @Test
    void autoPicksOllamaWhenConfiguredAndNoCloudKey() {
        assertThat(EmbeddingProviderSelection.resolve(env(
            "app.embeddings.provider", "auto", "app.embeddings.ollama.base-url", "http://localhost:11434"))).isEqualTo("ollama");
    }

    @Test
    void autoPrefersAConfiguredCloudKey() {
        assertThat(EmbeddingProviderSelection.resolve(env(
            "app.embeddings.provider", "auto",
            "app.embeddings.ollama.base-url", "http://localhost:11434",
            "app.embeddings.base-url", "https://api.openai.com/v1",
            "app.embeddings.api-key", "sk-test"))).isEqualTo("openai-compatible");
    }

    @Test
    void autoWithAKeyButNoBaseUrlIsNotACompleteCloudConfig() {
        assertThat(EmbeddingProviderSelection.resolve(env(
            "app.embeddings.provider", "auto",
            "app.embeddings.ollama.base-url", "http://localhost:11434",
            "app.embeddings.api-key", "sk-test"))).isEqualTo("ollama");
    }

    @Test
    void autoFallsBackToTheSidecarWhenNothingIsConfigured() {
        assertThat(EmbeddingProviderSelection.resolve(env("app.embeddings.provider", "auto"))).isEqualTo("sidecar");
        assertThat(EmbeddingProviderSelection.resolve(env(
            "app.embeddings.provider", "auto", "app.embeddings.ollama.base-url", "  "))).isEqualTo("sidecar");
    }

    @Test
    void autoReusesTheLocalLlmBootstrapOllama() {
        MockEnvironment e = env(
            "app.embeddings.provider", "auto",
            "app.local-llm.bootstrap.enabled", "true",
            "app.local-llm.bootstrap.base-url", "http://ollama:11434/v1/");
        assertThat(EmbeddingProviderSelection.resolve(e)).isEqualTo("ollama");
        assertThat(EmbeddingProviderSelection.ollamaBaseUrl(e)).isEqualTo("http://ollama:11434");
    }

    @Test
    void aDisabledBootstrapIsNotAnOllamaSignal() {
        assertThat(EmbeddingProviderSelection.resolve(env(
            "app.embeddings.provider", "auto",
            "app.local-llm.bootstrap.enabled", "false",
            "app.local-llm.bootstrap.base-url", "http://ollama:11434"))).isEqualTo("sidecar");
    }

    @Test
    void baseUrlNormalisationDropsTrailingSlashAndV1() {
        assertThat(EmbeddingProviderSelection.normalizeOllamaBaseUrl(" http://h:11434/v1/ ")).isEqualTo("http://h:11434");
        assertThat(EmbeddingProviderSelection.normalizeOllamaBaseUrl("http://h:11434///")).isEqualTo("http://h:11434");
    }

    // --- wiring: exactly one (or no) EmbeddingClient bean ----------------------------------------

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(OpenAiCompatibleEmbeddingClient.class, OllamaEmbeddingClient.class, SidecarClient.class)
        .withPropertyValues("app.sidecar.base-url=http://127.0.0.1:1")
        .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("knowledge"));

    @Test
    void defaultContextHasNoEmbeddingClientBean() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(EmbeddingClient.class);
            assertThat(ctx.getBean(SidecarClient.class).embeddingProvider()).isEqualTo("sidecar");
        });
    }

    @Test
    void autoWithOllamaWiresOnlyTheOllamaClient() {
        runner.withPropertyValues(
                "app.embeddings.provider=auto",
                "app.embeddings.ollama.base-url=http://127.0.0.1:1")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(EmbeddingClient.class);
                assertThat(ctx).hasSingleBean(OllamaEmbeddingClient.class);
                SidecarClient sidecar = ctx.getBean(SidecarClient.class);
                assertThat(sidecar.embeddingProvider()).isEqualTo("ollama");
                assertThat(sidecar.embeddingFingerprint()).isEqualTo("ollama:nomic-embed-text:768");
            });
    }

    @Test
    void autoWithACloudKeyWiresOnlyTheOpenAiCompatibleClient() {
        runner.withPropertyValues(
                "app.embeddings.provider=auto",
                "app.embeddings.ollama.base-url=http://127.0.0.1:1",
                "app.embeddings.base-url=http://127.0.0.1:1/v1",
                "app.embeddings.api-key=sk-test",
                "app.embeddings.model=all-minilm",
                "app.embeddings.dimensions=384")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(OpenAiCompatibleEmbeddingClient.class);
                assertThat(ctx).doesNotHaveBean(OllamaEmbeddingClient.class);
            });
    }

    @Test
    void autoWithNothingConfiguredBootsOnTheSidecar() {
        runner.withPropertyValues("app.embeddings.provider=auto").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(EmbeddingClient.class);
        });
    }

    @Test
    void aMisconfiguredOllamaFailsStartupWithTheFix() {
        runner.withPropertyValues(
                "app.embeddings.provider=ollama",
                "app.embeddings.ollama.model=bge-m3",
                "app.embeddings.ollama.base-url=http://127.0.0.1:1")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("allow-truncation");
            });
    }
}
