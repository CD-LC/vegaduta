package com.agenticai.core.agents.turn.quality;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.agents.decision.DecisionEngine;
import org.junit.jupiter.api.Test;

/**
 * Cost routing sends a "simple" message to the agent's economy model. "Simple" was decided by English
 * signal words and by sentence terminators that only English-like text has, so a hard question written in
 * Hindi, Russian or Chinese read as short and keyword-free and was handed to the economy model.
 */
class QueryComplexityClassifierRoutingTest {

    private final QueryComplexityClassifier classifier = new QueryComplexityClassifier((DecisionEngine) null, false, 0.75);

    @Test
    void anEnglishGreetingIsStillSimple() {
        assertThat(classifier.isSimple("What are your opening hours?")).isTrue();
    }

    @Test
    void aQuestionInAScriptTheSignalWordsDoNotCoverIsNotJudgedSimple() {
        assertThat(classifier.isSimple("मुझे इस कोड का डीबग कैसे करना है")).isFalse();
        assertThat(classifier.isSimple("Как исправить эту ошибку в коде")).isFalse();
    }

    @Test
    void severalSentencesEndedByNonLatinTerminatorsCountAsSeveralClauses() {
        assertThat(classifier.isSimple("hello。hello。hello。hello")).isFalse();
    }

    @Test
    void theThresholdsAreConfigurationNotConstants() {
        RoutingSimpleProperties tight = new RoutingSimpleProperties();
        tight.setMaxChars(12);

        assertThat(new QueryComplexityClassifier((DecisionEngine) null, false, 0.75, tight).isSimple("What are your opening hours?")).isFalse();
        assertThat(classifier.isSimple("What are your opening hours?")).isTrue();
    }

    @Test
    void aLanguageIsAddedToTheSignalWordsAndTheScriptRuleCanBeRelaxed() {
        RoutingSimpleProperties props = new RoutingSimpleProperties();
        props.setNonLatinSimple(true);
        java.util.Map<String, String> words = new java.util.HashMap<>(props.getSignalWords());
        words.put("ru", "\u043e\u0448\u0438\u0431\u043a\u0443,\u043a\u043e\u0434");
        props.setSignalWords(words);
        QueryComplexityClassifier configured = new QueryComplexityClassifier((DecisionEngine) null, false, 0.75, props);

        assertThat(configured.isSimple("\u041a\u0430\u043a \u0438\u0441\u043f\u0440\u0430\u0432\u0438\u0442\u044c \u044d\u0442\u0443 \u043e\u0448\u0438\u0431\u043a\u0443")).as("a configured signal word is found").isFalse();
        assertThat(configured.isSimple("\u041f\u0440\u0438\u0432\u0435\u0442, \u043a\u0430\u043a \u0434\u0435\u043b\u0430")).as("a greeting is simple once the script rule is relaxed").isTrue();
    }

    @Test
    void theEnglishSignalWordsStillMakeAMessageNotSimple() {
        assertThat(classifier.isSimple("Compare these two plans")).isFalse();
        assertThat(classifier.isSimple("Fix this `regex` please")).isFalse();
    }

    @Test
    void aQuestionInAnotherLatinScriptLanguageIsNotJudgedSimpleByEnglishWords() {
        assertThat(classifier.isSimple("\u00bfC\u00f3mo puedo migrar mi base de datos?")).isFalse();
        assertThat(classifier.isSimple("Wie kann ich die Gebühr zurückerstatten")).isFalse();
    }

    @Test
    void accentedLatinTextCanBeJudgedByLengthAndClausesWhenConfigured() {
        RoutingSimpleProperties props = new RoutingSimpleProperties();
        props.setAccentedLatinSimple(true);
        QueryComplexityClassifier relaxed = new QueryComplexityClassifier((DecisionEngine) null, false, 0.75, props);

        assertThat(relaxed.isSimple("\u00bfC\u00f3mo est\u00e1s?")).isTrue();
    }
}
