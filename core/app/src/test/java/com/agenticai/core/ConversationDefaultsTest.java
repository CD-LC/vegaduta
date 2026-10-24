package com.agenticai.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.turn.ChatResilienceProperties;
import com.agenticai.core.agents.turn.ContinuationProperties;
import com.agenticai.core.agents.turn.FollowUpProperties;
import com.agenticai.core.agents.turn.quality.RoutingSimpleProperties;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * {@code app.chat.continuation.*}, {@code app.chat.follow-up.*}, {@code app.routing.simple.*} and the
 * thought-signature keys of {@code app.chat.resilience.*} are bound from the real application.yml, and the
 * yml equals the code defaults in the {@code chat/*.properties} resources (the fallback for a context
 * without the yml), so the two cannot drift apart. The regular expressions are what decides whether a
 * reply is re-prompted or a message goes to the economy model: each must compile.
 */
class ConversationDefaultsTest {

    private static Binder binderOverApplicationYml() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        List<PropertySource<?>> sources =
            new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env));
    }

    @Test
    void continuationBindsFromTheYmlAndEqualsTheCodeDefaults() throws Exception {
        ContinuationProperties fromYml =
            binderOverApplicationYml().bind("app.chat.continuation", ContinuationProperties.class).get();
        ContinuationProperties code = new ContinuationProperties();

        assertThat(fromYml.isHeuristicEnabled()).isEqualTo(code.isHeuristicEnabled()).isTrue();
        assertThat(fromYml.getHeuristicScope()).isEqualTo(code.getHeuristicScope())
            .isEqualTo(ContinuationProperties.Scope.SMALL_MODELS);
        assertThat(fromYml.getHeuristicProviders()).isEqualTo(code.getHeuristicProviders()).isNotEmpty();
        assertThat(fromYml.getHeuristicToolGrades()).isEqualTo(code.getHeuristicToolGrades()).isNotEmpty();
        assertThat(fromYml.getTailChars()).isEqualTo(code.getTailChars()).isEqualTo(400);
        assertThat(fromYml.getDefaultLocale()).isEqualTo(code.getDefaultLocale()).isEqualTo("en");
        assertThat(fromYml.getIntent()).isEqualTo(code.getIntent());
        assertThat(fromYml.getClosing()).isEqualTo(code.getClosing());
        assertThat(fromYml.getDone()).isEqualTo(code.getDone());
        assertThat(fromYml.getAsks()).isEqualTo(code.getAsks());
        assertThat(fromYml.getUnfinishedEnding()).isEqualTo(code.getUnfinishedEnding());
        assertThat(fromYml.getNudge()).isEqualTo(code.getNudge());
    }

    @Test
    void everyContinuationPatternCompilesAndTheNudgeTellsTheModelToAct() throws Exception {
        ContinuationProperties p = binderOverApplicationYml().bind("app.chat.continuation", ContinuationProperties.class).get();

        for (var patterns : List.of(p.getIntent(), p.getClosing(), p.getDone(), p.getAsks(), p.getUnfinishedEnding())) {
            assertThat(patterns).isNotEmpty();
            patterns.values().forEach(regex -> assertThat(Pattern.compile(regex)).isNotNull());
        }
        assertThat(p.getNudge().get(p.getDefaultLocale())).containsIgnoringCase("call the tools").containsIgnoringCase("do it");
    }

    @Test
    void followUpBindsFromTheYmlAndEqualsTheCodeDefaults() throws Exception {
        FollowUpProperties fromYml = binderOverApplicationYml().bind("app.chat.follow-up", FollowUpProperties.class).get();
        FollowUpProperties code = new FollowUpProperties();

        assertThat(fromYml.isStickyToolsEnabled()).isEqualTo(code.isStickyToolsEnabled()).isTrue();
        assertThat(fromYml.getShortMessageChars()).isEqualTo(code.getShortMessageChars()).isEqualTo(40);
        assertThat(fromYml.getStickyToolsWindow()).isEqualTo(code.getStickyToolsWindow()).isEqualTo(Duration.ofHours(2));
        assertThat(fromYml.getMaxStickyTools()).isEqualTo(code.getMaxStickyTools()).isEqualTo(12);
        assertThat(fromYml.isKeepPrimaryModel()).isEqualTo(code.isKeepPrimaryModel()).isTrue();
    }

    @Test
    void routingSimpleBindsFromTheYmlAndEqualsTheCodeDefaults() throws Exception {
        RoutingSimpleProperties fromYml = binderOverApplicationYml().bind("app.routing.simple", RoutingSimpleProperties.class).get();
        RoutingSimpleProperties code = new RoutingSimpleProperties();

        assertThat(fromYml.getMaxChars()).isEqualTo(code.getMaxChars()).isEqualTo(160);
        assertThat(fromYml.getMaxClauses()).isEqualTo(code.getMaxClauses()).isEqualTo(2);
        assertThat(fromYml.getClauseBoundary()).isEqualTo(code.getClauseBoundary());
        assertThat(Pattern.compile(fromYml.getClauseBoundary()).split("hello。hello")).hasSize(2);
        assertThat(fromYml.isNonLatinSimple()).isEqualTo(code.isNonLatinSimple()).isFalse();
        assertThat(fromYml.getSignalWords()).isEqualTo(code.getSignalWords());
    }

    @Test
    void theThoughtSignatureDegradeBindsFromTheYmlAndEqualsTheCodeDefaults() throws Exception {
        ChatResilienceProperties fromYml = binderOverApplicationYml().bind("app.chat.resilience", ChatResilienceProperties.class).get();
        ChatResilienceProperties code = new ChatResilienceProperties();

        assertThat(fromYml.isThoughtSignatureDegrade()).isEqualTo(code.isThoughtSignatureDegrade()).isFalse();
        assertThat(fromYml.getThoughtSignatureEvidenceChars()).isEqualTo(code.getThoughtSignatureEvidenceChars()).isEqualTo(8000);
        assertThat(fromYml.getThoughtSignatureNotice()).isEqualTo(code.getThoughtSignatureNotice());
        assertThat(fromYml.getThoughtSignatureEvidenceNote()).isEqualTo(code.getThoughtSignatureEvidenceNote());
        assertThat(fromYml.getThoughtSignatureNotice()).contains("{tools}").containsIgnoringCase("without any tools");
        assertThat(fromYml.getThoughtSignatureEvidenceNote()).contains("{evidence}");
    }
}
