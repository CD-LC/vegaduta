package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.guardrails.ApprovalParkProperties;
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
 * {@code app.guardrails.approval-park.*} is bound from the real application.yml, and the yml text equals
 * the code defaults in guardrails/approval-park.properties (the fallback for a context without the yml), so
 * the two cannot drift apart. The texts are what the model reads when a tool call is held for approval:
 * they must say the task resumes and must not tell the model to abandon it.
 */
class ApprovalParkDefaultsTest {

    private static ApprovalParkProperties boundFromApplicationYml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(s -> env.getPropertySources().addLast(s));
        Binder binder = new Binder(
            ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env));
        return binder.bind("app.guardrails.approval-park", ApprovalParkProperties.class).get();
    }

    @Test
    void theYmlBindsAndEqualsTheCodeDefaults() throws Exception {
        ApprovalParkProperties fromYml = boundFromApplicationYml();
        ApprovalParkProperties codeDefaults = new ApprovalParkProperties();

        assertThat(fromYml.getAwaitingMessage()).isEqualTo(codeDefaults.getAwaitingMessage());
        assertThat(fromYml.getQueuedMessage()).isEqualTo(codeDefaults.getQueuedMessage());
        assertThat(fromYml.getQueueFullMessage()).isEqualTo(codeDefaults.getQueueFullMessage());
        assertThat(fromYml.getNotQueuedMessage()).isEqualTo(codeDefaults.getNotQueuedMessage());
        assertThat(fromYml.getRecordFailedMessage()).isEqualTo(codeDefaults.getRecordFailedMessage());
        assertThat(fromYml.getMaxQueuedCalls()).isEqualTo(codeDefaults.getMaxQueuedCalls()).isEqualTo(5);
        assertThat(fromYml.getMaxGoalChars()).isEqualTo(codeDefaults.getMaxGoalChars()).isEqualTo(4000);
        assertThat(fromYml.getGoalCutMarker()).isEqualTo(codeDefaults.getGoalCutMarker()).isEqualTo(" [...message shortened]");
    }

    @Test
    void noDefaultTextTellsTheModelToAbandonTheTaskAndTheParkTextSaysItResumes() throws Exception {
        ApprovalParkProperties p = boundFromApplicationYml();

        for (String text : List.of(p.getAwaitingMessage(), p.getQueuedMessage(), p.getQueueFullMessage(), p.getNotQueuedMessage())) {
            assertThat(text).doesNotContain("STOP NOW").doesNotContainIgnoringCase("do not continue the task");
            assertThat(text).containsIgnoringCase("resumed automatically");
            assertThat(text).contains("{tool}");
        }
        assertThat(p.getAwaitingMessage()).contains("{requestId}").containsIgnoringCase("waiting on this approval");
        assertThat(p.getRecordFailedMessage()).containsIgnoringCase("nothing is waiting");
    }
}
