package com.agenticai.core.agents.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.providers.modality.TtsModelProvider;
import com.agenticai.core.providers.modality.VoiceModelProvider;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The flat {@link Agent} accessors are thin delegates onto the embedded value objects; this pins
 * that they behave exactly as the flat fields did (the setter rules, the defensive copies, the
 * "null provider clears the model" rule) and that the value objects are replaced, not mutated.
 */
class AgentValueObjectsTest {

    private static Agent agent() {
        return new Agent("A", "p", ProviderName.ANTHROPIC, "m", 1024, 0.7, 1.0, true, null,
                ReasoningDepth.FAST, TuningMode.MANUAL);
    }

    @Test
    void constructorSeedsGenerationParamsAndKnowledgeBase() {
        Agent a = agent();
        assertThat(a.getMaxTokens()).isEqualTo(1024);
        assertThat(a.getTemperature()).isEqualTo(0.7);
        assertThat(a.getTopP()).isEqualTo(1.0);
        assertThat(a.isKnowledgeBaseEnabled()).isTrue();
        assertThat(a.isSarvamEnabled()).isFalse();
        assertThat(a.getStopSequences()).isEmpty();
    }

    @Test
    void settersReplaceTheValueObjectInsteadOfMutatingIt() {
        Agent a = agent();
        GenerationParams before = a.generationParams();
        a.setGenerationParams(2048, 0.1, 0.5);
        assertThat(a.generationParams()).isNotSameAs(before);
        assertThat(before.getMaxTokens()).isEqualTo(1024);
        assertThat(a.getMaxTokens()).isEqualTo(2048);
        // core change keeps previously set sampling controls
        a.setSamplingParams(40, List.of("END"), 0.5, 0.25, 9);
        a.setGenerationParams(512, 0.3, 0.9);
        assertThat(a.getTopK()).isEqualTo(40);
        assertThat(a.getStopSequences()).containsExactly("END");
        assertThat(a.getSeed()).isEqualTo(9);
    }

    @Test
    void fallbackNullProviderClearsModelAndCountersSurvive() {
        Agent a = agent();
        ReflectionTestUtils.setField(
                a, "fallback", a.fallbackPolicy().withFallback(ProviderName.OPENAI, "gpt"));
        assertThat(a.getFallbackProviderName()).isEqualTo(ProviderName.OPENAI);
        a.setFallbackProvider(null, "ignored");
        assertThat(a.getFallbackProviderName()).isNull();
        assertThat(a.getFallbackModel()).isNull();
        assertThat(a.getFallbackTriggeredCount()).isZero();
    }

    @Test
    void capabilityFlagsAreIndependent() {
        Agent a = agent();
        a.setE2bEnabled(true);
        a.setBiometricConsentEnabled(true);
        a.setKnowledgeBaseEnabled(false);
        assertThat(a.isE2bEnabled()).isTrue();
        assertThat(a.isBiometricConsentEnabled()).isTrue();
        assertThat(a.isKnowledgeBaseEnabled()).isFalse();
        assertThat(a.isSarvamEnabled()).isFalse();
        assertThat(a.isShareMemoryWithTenant()).isFalse();
        assertThat(a.capabilityFlags()).isEqualTo(CapabilityFlags.none().withE2b(true).withBiometricConsent(true));
    }

    @Test
    void cachingAndBudget() {
        Agent a = agent();
        a.setResponseCachingEnabled(true);
        a.setSemanticResponseCachingEnabled(true);
        a.setResponseCachingEnabled(false);
        assertThat(a.isResponseCachingEnabled()).isFalse();
        assertThat(a.isSemanticResponseCachingEnabled()).isTrue();
        a.setRunBudgetUsd(new BigDecimal("1.5"));
        a.setCostRouting(true, ProviderName.OPENAI, "mini", 0.4);
        assertThat(a.getRunBudgetUsd()).isEqualByComparingTo("1.5");
        a.setCostRouting(false, null, "ignored", null);
        assertThat(a.getEconomyModel()).isNull();
        assertThat(a.isCostRoutingEnabled()).isFalse();
        assertThat(a.getRunBudgetUsd()).isEqualByComparingTo("1.5");
    }

    @Test
    void modalityOverridesSurviveHibernateHandingBackNull() {
        Agent a = agent();
        // Hibernate assigns null to an embedded object whose columns are all null.
        ReflectionTestUtils.setField(a, "modalities", null);
        assertThat(a.getVoiceModelProvider()).isNull();
        assertThat(a.getTtsModel()).isNull();
        a.setVoiceModelProvider(VoiceModelProvider.values()[0], "v1");
        assertThat(a.getVoiceModel()).isEqualTo("v1");
        a.setTtsProvider(TtsModelProvider.values()[0], "t1");
        a.setVoiceModelProvider(null, "dropped");
        assertThat(a.getVoiceModel()).isNull();
        assertThat(a.getTtsModel()).isEqualTo("t1");
        a.clearTtsProvider();
        assertThat(a.getTtsProvider()).isNull();
    }
}
