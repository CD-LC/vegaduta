package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.turn.ChatFinishProperties;
import com.agenticai.core.chat.AgentTurnRepairProperties;
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
 * {@code app.chat.finish.*} and {@code app.agent-turn.repair.*} are bound from the real
 * application.yml, and the yml equals the code defaults in {@code chat/finish.properties} and
 * {@code agent-turn/repair.properties} (the fallback for a context without the yml), so the two
 * cannot drift apart. The nudges are what the model reads after a truncated or malformed reply; the
 * notices are what the person reads instead of silence.
 */
class ChatFinishDefaultsTest {

    private static Binder binderOverApplicationYml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env));
    }

    @Test
    void chatFinishBindsFromTheYmlAndEqualsTheCodeDefaults() throws Exception {
        ChatFinishProperties fromYml = binderOverApplicationYml().bind("app.chat.finish", ChatFinishProperties.class).get();
        ChatFinishProperties code = new ChatFinishProperties();

        assertThat(fromYml.isEnabled()).isEqualTo(code.isEnabled()).isTrue();
        assertThat(fromYml.getMaxContinuations()).isEqualTo(code.getMaxContinuations()).isEqualTo(2);
        assertThat(fromYml.getMaxToolCallRetries()).isEqualTo(code.getMaxToolCallRetries()).isEqualTo(1);
        assertThat(fromYml.getContinueNudge()).isEqualTo(code.getContinueNudge());
        assertThat(fromYml.getRetryToolCallNudge()).isEqualTo(code.getRetryToolCallNudge());
        assertThat(fromYml.getTruncatedNotice()).isEqualTo(code.getTruncatedNotice());
        assertThat(fromYml.getRefusalNotice()).isEqualTo(code.getRefusalNotice());
        assertThat(fromYml.getMalformedToolCallNotice()).isEqualTo(code.getMalformedToolCallNotice());
        assertThat(fromYml.getEmptyReplyMessage()).isEqualTo(code.getEmptyReplyMessage());
    }

    @Test
    void theTextsSaySomethingTrueAndNeverBlameThePersonsWording() throws Exception {
        ChatFinishProperties p = binderOverApplicationYml().bind("app.chat.finish", ChatFinishProperties.class).get();

        assertThat(p.getContinueNudge()).containsIgnoringCase("cut off").containsIgnoringCase("exactly where it stopped");
        assertThat(p.getRetryToolCallNudge()).containsIgnoringCase("not run").containsIgnoringCase("valid arguments");
        assertThat(p.getTruncatedNotice()).containsIgnoringCase("cut off").containsIgnoringCase("limit");
        assertThat(p.getRefusalNotice()).contains("{reason}").containsIgnoringCase("safety system");
        assertThat(p.renderRefusalNotice("SAFETY")).contains("(SAFETY)").doesNotContain("{reason}");
        assertThat(p.getMalformedToolCallNotice()).containsIgnoringCase("nothing was run");
        for (String shownToThePerson : List.of(p.getRefusalNotice(), p.getEmptyReplyMessage(), p.getMalformedToolCallNotice())) {
            assertThat(shownToThePerson).doesNotContainIgnoringCase("rephras");
        }
    }

    @Test
    void agentTurnRepairBindsFromTheYmlAndEqualsTheCodeDefaults() throws Exception {
        AgentTurnRepairProperties fromYml =
            binderOverApplicationYml().bind("app.agent-turn.repair", AgentTurnRepairProperties.class).get();
        AgentTurnRepairProperties code = new AgentTurnRepairProperties();

        assertThat(fromYml.getMaxAttempts()).isEqualTo(code.getMaxAttempts()).isEqualTo(1);
        assertThat(fromYml.getInvalidArgumentsMessage()).isEqualTo(code.getInvalidArgumentsMessage());
        assertThat(fromYml.getSkippedMessage()).isEqualTo(code.getSkippedMessage());
        assertThat(fromYml.getInvalidReasonCutOff()).isEqualTo(code.getInvalidReasonCutOff());
        assertThat(fromYml.getInvalidReasonMalformed()).isEqualTo(code.getInvalidReasonMalformed());
        assertThat(fromYml.getUnusableCallsNotice()).isEqualTo(code.getUnusableCallsNotice());
        assertThat(fromYml.getInvalidArgumentsMessage()).contains("{tool}", "{reason}").containsIgnoringCase("NOT executed");
        assertThat(fromYml.getUnusableCallsNotice()).contains("{tool}", "{reason}").containsIgnoringCase("nothing was run");
    }
}
