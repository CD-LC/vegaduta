package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.auxiliary.AuxiliaryModelProperties;
import com.agenticai.core.agents.auxiliary.AuxiliaryPurpose;
import com.agenticai.core.agents.turn.quality.RoutingSimpleProperties;
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
 * {@code app.auxiliary-model.*} and {@code app.routing.simple.*} are bound from the real application.yml, and the
 * yml equals the code defaults (the fallback for a context without the yml), so the two cannot drift apart. The
 * background calls (compaction, session summary, assist, judge) set no model by default: they follow the agent.
 */
class AuxiliaryModelDefaultsTest {

    private static Binder binderOverApplicationYml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env));
    }

    @Test
    void auxiliaryModelBindsFromTheYmlAndSetsNoModelByDefault() throws Exception {
        AuxiliaryModelProperties fromYml = binderOverApplicationYml().bind("app.auxiliary-model", AuxiliaryModelProperties.class).get();
        AuxiliaryModelProperties code = new AuxiliaryModelProperties();

        assertThat(fromYml.isUseAgentModel()).isEqualTo(code.isUseAgentModel()).isTrue();
        for (AuxiliaryPurpose purpose : AuxiliaryPurpose.values()) {
            assertThat(fromYml.choiceFor(purpose).isEmpty()).as(purpose.configKey()).isTrue();
            assertThat(code.choiceFor(purpose).isEmpty()).as(purpose.configKey()).isTrue();
        }
    }

    @Test
    void anOperatorCanChooseAProviderAndModelPerPurpose() {
        StandardEnvironment env = new StandardEnvironment();
        env.getSystemProperties().put("app.auxiliary-model.session-summary.provider", "GEMINI");
        env.getSystemProperties().put("app.auxiliary-model.session-summary.model", "gemini-2.5-flash");
        AuxiliaryModelProperties props = new Binder(ConfigurationPropertySources.get(env))
            .bind("app.auxiliary-model", AuxiliaryModelProperties.class).get();

        assertThat(props.choiceFor(AuxiliaryPurpose.SESSION_SUMMARY).getProvider().name()).isEqualTo("GEMINI");
        assertThat(props.choiceFor(AuxiliaryPurpose.SESSION_SUMMARY).getModel()).isEqualTo("gemini-2.5-flash");
        assertThat(props.choiceFor(AuxiliaryPurpose.COMPACTION).isEmpty()).isTrue();
    }

    @Test
    void routingSimpleBindsFromTheYmlAndEqualsTheCodeDefaults() throws Exception {
        RoutingSimpleProperties fromYml = binderOverApplicationYml().bind("app.routing.simple", RoutingSimpleProperties.class).get();
        RoutingSimpleProperties code = new RoutingSimpleProperties();

        assertThat(fromYml.getMaxChars()).isEqualTo(code.getMaxChars()).isEqualTo(160);
        assertThat(fromYml.getMaxClauses()).isEqualTo(code.getMaxClauses()).isEqualTo(2);
        assertThat(fromYml.isNonLatinSimple()).isEqualTo(code.isNonLatinSimple()).isFalse();
        assertThat(fromYml.isAccentedLatinSimple()).isEqualTo(code.isAccentedLatinSimple()).isFalse();
        assertThat(fromYml.getClauseBoundary()).isEqualTo(code.getClauseBoundary());
        assertThat(fromYml.getSignalWords()).containsKey("en").isEqualTo(code.getSignalWords());
    }
}
