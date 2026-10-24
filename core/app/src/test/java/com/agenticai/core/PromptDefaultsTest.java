package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.prompts.PromptProperties;
import com.agenticai.core.prompts.PromptResolver;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * {@code app.prompts.*} is bound from the real application.yml and equals the code defaults of {@link
 * PromptProperties} (the fallback for a context without the yml), so the two cannot drift apart. Also
 * the one place that loads EVERY prompt resource on the application's classpath (all modules together)
 * and fails on a broken file, a duplicate key or a text that breaks its own placeholder rules.
 */
class PromptDefaultsTest {

    private static Binder binderOverApplicationYml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env));
    }

    @Test
    void promptPropertiesBindFromTheYmlAndEqualTheCodeDefaults() throws Exception {
        PromptProperties fromYml = binderOverApplicationYml().bind("app.prompts", PromptProperties.class).get();
        PromptProperties code = new PromptProperties();

        assertThat(fromYml.getDefaultLocale()).isEqualTo(code.getDefaultLocale()).isEqualTo("en");
        assertThat(fromYml.getDefaultMaxLength()).isEqualTo(code.getDefaultMaxLength()).isEqualTo(8000);
        assertThat(fromYml.getCharsPerToken()).isEqualTo(code.getCharsPerToken()).isEqualTo(4.0);
        assertThat(fromYml.getSystemPromptTokenBudget()).isEqualTo(code.getSystemPromptTokenBudget()).isZero();
        assertThat(fromYml.getHistoryDefaultLimit()).isEqualTo(code.getHistoryDefaultLimit()).isEqualTo(50);
        assertThat(fromYml.getHistoryMaxLimit()).isEqualTo(code.getHistoryMaxLimit()).isEqualTo(500);
        assertThat(fromYml.getCache().getTtl()).isEqualTo(code.getCache().getTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(fromYml.getCache().getFailureTtl()).isEqualTo(code.getCache().getFailureTtl()).isEqualTo(Duration.ofSeconds(5));
        assertThat(fromYml.getCache().getMaxTenants()).isEqualTo(code.getCache().getMaxTenants()).isEqualTo(10_000);
    }

    @Test
    void everyPromptResourceOfTheApplicationLoadsWithoutProblems() {
        var catalog = PromptResolver.defaultsOnly().catalog();

        assertThat(catalog.problems()).isEmpty();
        assertThat(catalog.all()).isNotEmpty();
        assertThat(catalog.all()).allSatisfy(d -> assertThat(d.key()).startsWith(d.key().substring(0, d.key().indexOf('.') + 1)));
    }
}
