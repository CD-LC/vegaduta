package com.agenticai.core.sarvam.agenttools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agentspi.MediaToolSource;
import com.agenticai.core.sarvam.SarvamCredentialService;
import com.agenticai.core.sarvam.SarvamGeneratedAudioService;
import com.agenticai.core.sarvam.SarvamLanguageIdClient;
import com.agenticai.core.sarvam.SarvamSpeechToTextClient;
import com.agenticai.core.sarvam.SarvamTextToSpeechClient;
import com.agenticai.core.sarvam.SarvamTranslateClient;
import com.agenticai.core.sarvam.SarvamTransliterationClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Sarvam's language tools through the kernel's MediaToolSource: none without an active credential,
 * the optional two only when their beans exist.
 */
class SarvamLanguageToolSourceTest {

    private final SarvamCredentialService credentials = mock(SarvamCredentialService.class);

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }

    private SarvamLanguageToolSource source(boolean withOptional) {
        return new SarvamLanguageToolSource(
                provider(credentials),
                provider(mock(SarvamTranslateClient.class)),
                provider(mock(SarvamSpeechToTextClient.class)),
                provider(mock(SarvamTextToSpeechClient.class)),
                provider(mock(SarvamGeneratedAudioService.class)),
                provider(withOptional ? mock(SarvamTransliterationClient.class) : null),
                provider(withOptional ? mock(SarvamLanguageIdClient.class) : null));
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(t -> t.getToolDefinition().name()).toList();
    }

    @Test
    void isTheLanguageSlot() {
        assertEquals(MediaToolSource.Kind.LANGUAGE, source(true).kind());
    }

    @Test
    void nothingWithoutAnActiveCredential() {
        when(credentials.isActive()).thenReturn(false);
        assertTrue(source(true).rawTools().isEmpty());
    }

    @Test
    void allFourWhenEveryBeanIsPresent() {
        when(credentials.isActive()).thenReturn(true);
        assertEquals(
                List.of(
                        "sarvam_translate",
                        "sarvam_transcribe",
                        "sarvam_transliterate",
                        "sarvam_identify_language"),
                names(source(true).rawTools()));
    }

    @Test
    void translateAndTranscribeSurviveWithoutTheOptionalClients() {
        when(credentials.isActive()).thenReturn(true);
        assertEquals(
                List.of("sarvam_translate", "sarvam_transcribe"), names(source(false).rawTools()));
    }
}
