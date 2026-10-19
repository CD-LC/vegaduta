package com.agenticai.core.archrules;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticai.core.providers.ExternalProviderCredentialSource;
import com.agenticai.core.providers.modality.SpeechToTextProvider;
import com.agenticai.core.providers.modality.TextToSpeechProvider;
import com.agenticai.core.providers.modality.VideoToTextProvider;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.stereotype.Component;

/**
 * ADR-0011 phase 2: the modality providers and the Sarvam credential source are
 * now discovered by type rather than wired by name. This pins that the FULL
 * distribution (app's classpath, i.e. every vendor module) component-scans
 * exactly the same provider set as before the move - only the vendor adapters'
 * packages changed. Provider ids are pinned by the vendor modules' own tests
 * (SarvamProviderPluginsTest, ElevenLabsTextToSpeechProviderTest) and the
 * lookup by providers' ModalityProviderConfigurationTest. No context, no DB:
 * same classpath-scan approach as {@link UniqueBeanNamesTest}.
 */
class ProviderPluginDiscoveryTest {

    private static Set<String> scannedComponentsOfType(Class<?> type) {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter((reader, factory) ->
            new AnnotationTypeFilter(Component.class).match(reader, factory)
                && new AssignableTypeFilter(type).match(reader, factory));
        Set<String> names = new TreeSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.agenticai")) {
            names.add(bd.getBeanClassName());
        }
        return names;
    }

    @Test
    void textToSpeechProvidersAreTheSameTwoAsBefore() {
        assertThat(scannedComponentsOfType(TextToSpeechProvider.class)).containsExactly(
            "com.agenticai.core.elevenlabs.ElevenLabsTextToSpeechProvider",
            "com.agenticai.core.sarvam.SarvamTextToSpeechProvider");
    }

    @Test
    void speechToTextProvidersAreTheSameTwoAsBefore() {
        assertThat(scannedComponentsOfType(SpeechToTextProvider.class)).containsExactly(
            "com.agenticai.core.providers.modality.SidecarWhisperProvider",
            "com.agenticai.core.sarvam.SarvamSpeechToTextProvider");
    }

    @Test
    void videoToTextProvidersAreUnchanged() {
        // The scanner evaluates @Conditional against an empty environment, so
        // OpenAIWhisperVideoProvider (@ConditionalOnProperty openai.api-key) is
        // absent here exactly as on a deployment without that key.
        assertThat(scannedComponentsOfType(VideoToTextProvider.class)).containsExactly(
            "com.agenticai.core.providers.modality.SidecarWhisperVideoProvider");
    }

    @Test
    void sarvamIsTheOnlyExternalCredentialSource() {
        assertThat(scannedComponentsOfType(ExternalProviderCredentialSource.class))
            .containsExactly("com.agenticai.core.sarvam.SarvamProviderCredentialSource");
    }
}
