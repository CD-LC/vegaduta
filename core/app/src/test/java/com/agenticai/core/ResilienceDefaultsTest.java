package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.turn.ChatResilienceProperties;
import com.agenticai.core.chat.LlmRetryProperties;
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
 * {@code app.chat.resilience.*} and {@code app.llm.retry.*} are bound from the real application.yml,
 * and the yml equals the code defaults ({@code chat/resilience.properties} and the field initialisers of
 * {@link LlmRetryProperties}), so the two cannot drift apart. The notices are what the person reads when
 * a turn ran out of time or a provider failed after tools ran.
 */
class ResilienceDefaultsTest {

    private static Binder binderOverApplicationYml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env));
    }

    @Test
    void chatResilienceBindsFromTheYmlAndEqualsTheCodeDefaults() throws Exception {
        ChatResilienceProperties fromYml =
            binderOverApplicationYml().bind("app.chat.resilience", ChatResilienceProperties.class).get();
        ChatResilienceProperties code = new ChatResilienceProperties();

        assertThat(fromYml.getToolIdleCeiling()).isEqualTo(code.getToolIdleCeiling()).isEqualTo(Duration.ofMinutes(30));
        assertThat(fromYml.isReplayAfterJournaledEffects()).isEqualTo(code.isReplayAfterJournaledEffects()).isTrue();
        assertThat(fromYml.isRetryAtFastWhenNoToolRan()).isEqualTo(code.isRetryAtFastWhenNoToolRan()).isTrue();
        assertThat(fromYml.getListNone()).isEqualTo(code.getListNone());
        assertThat(fromYml.getDepthTimeoutPartialNotice()).isEqualTo(code.getDepthTimeoutPartialNotice());
        assertThat(fromYml.getDepthTimeoutDegradedNotice()).isEqualTo(code.getDepthTimeoutDegradedNotice());
        assertThat(fromYml.getReplayBlockedNotice()).isEqualTo(code.getReplayBlockedNotice());
    }

    @Test
    void theNoticesSaySomethingTrueAndAreFilledIn() throws Exception {
        ChatResilienceProperties p =
            binderOverApplicationYml().bind("app.chat.resilience", ChatResilienceProperties.class).get();

        String partial = p.renderPartialNotice("CAREFUL", Duration.ofSeconds(240), "send_email", "none");
        assertThat(partial).contains("240-second limit", "careful reply", "send_email", "Nothing was undone")
            .doesNotContain("{");
        assertThat(p.renderDegradedNotice("RIGOROUS")).contains("rigorous").contains("before any tool ran").doesNotContain("{");
        assertThat(p.renderReplayBlockedNotice("send_email")).contains("(send_email)").contains("not repeated")
            .doesNotContain("{");
    }

    @Test
    void llmRetryBindsFromTheYmlAndEqualsTheCodeDefaults() throws Exception {
        LlmRetryProperties fromYml = binderOverApplicationYml().bind("app.llm.retry", LlmRetryProperties.class).get();
        LlmRetryProperties code = new LlmRetryProperties();

        assertThat(fromYml.getMaxAttempts()).isEqualTo(code.getMaxAttempts()).isEqualTo(2);
        assertThat(fromYml.getInitialBackoff()).isEqualTo(code.getInitialBackoff()).isEqualTo(Duration.ofSeconds(1));
        assertThat(fromYml.getMaxBackoff()).isEqualTo(code.getMaxBackoff()).isEqualTo(Duration.ofSeconds(10));
        assertThat(fromYml.getJitter()).isEqualTo(code.getJitter()).isEqualTo(0.5);
        assertThat(fromYml.isRetryRateLimited()).isEqualTo(code.isRetryRateLimited()).isTrue();
        assertThat(fromYml.isHonorRetryAfter()).isEqualTo(code.isHonorRetryAfter()).isTrue();
        assertThat(fromYml.getMaxRetryAfter()).isEqualTo(code.getMaxRetryAfter()).isEqualTo(Duration.ofSeconds(30));
        assertThat(fromYml.isStreamPreFirstToken()).isEqualTo(code.isStreamPreFirstToken()).isTrue();
    }

    @Test
    void theStreamIdleTimeoutStaysTheNinetySecondsTheCodeDefaultsTo() throws Exception {
        // The audit read 240 s in one place and 90 s in another. The yml, the @Value default in
        // ChatTimeouts and the buffered-turn counterpart are pinned here so they cannot drift apart.
        Binder binder = binderOverApplicationYml();

        assertThat(binder.bind("app.llm.http.stream-idle-timeout", Duration.class).get()).isEqualTo(Duration.ofSeconds(90));
        assertThat(binder.bind("app.llm.http.buffered-turn-timeout", Duration.class).get()).isEqualTo(Duration.ofSeconds(240));
    }
}
