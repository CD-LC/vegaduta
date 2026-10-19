package com.agenticai.core.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.knowledge.SidecarClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Non-breaking-contract guard: with no {@code app.embeddings.*} set, there
 * is NO {@link EmbeddingClient} bean and {@link SidecarClient} embeds via the
 * Python sidecar exactly as before. The OpenAI-compatible provider is opt-in
 * only and fails fast when misconfigured.
 */
class EmbeddingSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(OpenAiCompatibleEmbeddingClient.class, SidecarClient.class)
        .withPropertyValues("app.sidecar.base-url=http://127.0.0.1:1")
        .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("knowledge"));

    @Test
    void defaultIsSidecar() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(EmbeddingClient.class);
            assertThat(ctx.getBean(SidecarClient.class).embeddingProvider()).isEqualTo("sidecar");
        });
    }

    @Test
    void explicitSidecarIsSidecar() {
        runner.withPropertyValues("app.embeddings.provider=sidecar").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(EmbeddingClient.class);
            assertThat(ctx.getBean(SidecarClient.class).embeddingProvider()).isEqualTo("sidecar");
        });
    }

    @Test
    void openAiCompatibleIsOptIn() {
        runner.withPropertyValues(
                "app.embeddings.provider=openai-compatible",
                "app.embeddings.base-url=http://127.0.0.1:1/v1",
                "app.embeddings.model=all-minilm",
                "app.embeddings.dimensions=384")
            .run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx).hasSingleBean(EmbeddingClient.class);
                assertThat(ctx.getBean(SidecarClient.class).embeddingProvider()).isEqualTo("openai-compatible");
            });
    }

    @Test
    void misconfiguredProviderFailsStartupWithClearMessage() {
        runner.withPropertyValues("app.embeddings.provider=openai-compatible", "app.embeddings.dimensions=1536")
            .run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).rootCause()
                    .hasMessageContaining("app.embeddings.provider=openai-compatible is misconfigured");
            });
    }
}
