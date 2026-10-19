package com.agenticai.core.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.knowledge.SidecarClient;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code app.embeddings.provider=auto}: an Ollama the install registered at run time (the console's
 * local-model wizard) is used when the environment names none, instead of silently falling to the sidecar.
 */
class RegisteredOllamaEmbeddingResolverTest {

    /** The registered Ollama, settable per test. */
    private static final class Registered implements RegisteredOllamaLookup {
        final AtomicReference<String> url = new AtomicReference<>();

        @Override
        public Optional<String> baseUrl() {
            return Optional.ofNullable(url.get());
        }
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RegisteredOllamaLookup> provider(RegisteredOllamaLookup lookup) {
        return new ObjectProvider<>() {
            @Override
            public RegisteredOllamaLookup getObject() {
                return lookup;
            }

            @Override
            public RegisteredOllamaLookup getIfAvailable() {
                return lookup;
            }

            @Override
            public RegisteredOllamaLookup getIfUnique() {
                return lookup;
            }

            @Override
            public RegisteredOllamaLookup getObject(Object... args) {
                return lookup;
            }
        };
    }

    @Test
    void nothingRegisteredMeansTheSidecar() {
        Registered registered = new Registered();
        RegisteredOllamaEmbeddingResolver resolver =
                new RegisteredOllamaEmbeddingResolver(provider(registered), new EmbeddingProperties(), 0);

        assertThat(resolver.resolve()).isNull();
    }

    @Test
    void aRegisteredOllamaIsUsedAndNormalised() {
        Registered registered = new Registered();
        registered.url.set("http://host.docker.internal:11434/v1/");
        RegisteredOllamaEmbeddingResolver resolver =
                new RegisteredOllamaEmbeddingResolver(provider(registered), new EmbeddingProperties(), 0);

        EmbeddingClient client = resolver.resolve();

        assertThat(client).isInstanceOf(OllamaEmbeddingClient.class);
        assertThat(client.provider()).isEqualTo("ollama");
    }

    @Test
    void theAnswerFollowsTheRegistrationWhenTheCacheExpires() {
        Registered registered = new Registered();
        RegisteredOllamaEmbeddingResolver resolver =
                new RegisteredOllamaEmbeddingResolver(provider(registered), new EmbeddingProperties(), 0);
        assertThat(resolver.resolve()).isNull();

        registered.url.set("http://ollama:11434");
        EmbeddingClient first = resolver.resolve();
        assertThat(first).isNotNull();
        assertThat(resolver.resolve()).isSameAs(first); // same URL: the client is reused, not rebuilt

        registered.url.set(null);
        assertThat(resolver.resolve()).isNull();
    }

    @Test
    void aFreshAnswerIsCachedSoTurnsDoNotReadTheDatabaseEveryTime() {
        Registered registered = new Registered();
        registered.url.set("http://ollama:11434");
        RegisteredOllamaEmbeddingResolver resolver =
                new RegisteredOllamaEmbeddingResolver(provider(registered), new EmbeddingProperties());
        EmbeddingClient first = resolver.resolve();

        registered.url.set(null); // within the cache window: not seen yet
        assertThat(resolver.resolve()).isSameAs(first);
    }

    @Test
    void aLookupFailureKeepsTheLastKnownSource() {
        Registered registered = new Registered();
        registered.url.set("http://ollama:11434");
        RegisteredOllamaLookup flaky =
                new RegisteredOllamaLookup() {
                    boolean first = true;

                    @Override
                    public Optional<String> baseUrl() {
                        if (first) {
                            first = false;
                            return registered.baseUrl();
                        }
                        throw new IllegalStateException("database unavailable");
                    }
                };
        RegisteredOllamaEmbeddingResolver resolver =
                new RegisteredOllamaEmbeddingResolver(provider(flaky), new EmbeddingProperties(), 0);
        EmbeddingClient first = resolver.resolve();

        assertThat(resolver.resolve()).isSameAs(first);
    }

    // --- wiring ------------------------------------------------------------------------------------

    @Configuration
    static class RegisteredLookupConfig {
        static final Registered REGISTERED = new Registered();

        @Bean
        RegisteredOllamaLookup lookup() {
            return REGISTERED;
        }
    }

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(
                            OpenAiCompatibleEmbeddingClient.class,
                            OllamaEmbeddingClient.class,
                            RegisteredOllamaEmbeddingResolver.class,
                            SidecarClient.class)
                    .withPropertyValues("app.sidecar.base-url=http://127.0.0.1:1")
                    .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("knowledge"));

    @Test
    void theResolverExistsOnlyForAutoWithNoOllamaInTheEnvironment() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(RegisteredOllamaEmbeddingResolver.class));
        runner.withPropertyValues("app.embeddings.provider=sidecar")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(RegisteredOllamaEmbeddingResolver.class));
        runner.withPropertyValues("app.embeddings.provider=auto", "app.embeddings.ollama.base-url=http://127.0.0.1:1")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(RegisteredOllamaEmbeddingResolver.class));
        runner.withPropertyValues("app.embeddings.provider=auto")
                .run(ctx -> assertThat(ctx).hasSingleBean(RegisteredOllamaEmbeddingResolver.class));
    }

    @Test
    void sidecarClientUsesTheRegisteredOllamaOnceOneExists() {
        RegisteredLookupConfig.REGISTERED.url.set(null);
        runner.withUserConfiguration(RegisteredLookupConfig.class)
                .withPropertyValues("app.embeddings.provider=auto")
                .run(
                        ctx -> {
                            assertThat(ctx).hasNotFailed();
                            SidecarClient sidecar = ctx.getBean(SidecarClient.class);
                            assertThat(sidecar.embeddingProvider()).isEqualTo("sidecar");

                            RegisteredLookupConfig.REGISTERED.url.set("http://127.0.0.1:1");
                            // the resolver caches for 30 s; a fresh context sees the registration at once
                        });
        runner.withUserConfiguration(RegisteredLookupConfig.class)
                .withPropertyValues("app.embeddings.provider=auto")
                .run(
                        ctx -> {
                            SidecarClient sidecar = ctx.getBean(SidecarClient.class);
                            assertThat(sidecar.embeddingProvider()).isEqualTo("ollama");
                            assertThat(sidecar.embeddingFingerprint()).isEqualTo("ollama:nomic-embed-text:768");
                        });
        RegisteredLookupConfig.REGISTERED.url.set(null);
    }
}
