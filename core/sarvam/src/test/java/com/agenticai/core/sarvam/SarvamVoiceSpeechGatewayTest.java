package com.agenticai.core.sarvam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.speech.voicenote.VoiceSpeechException;
import com.agenticai.core.speech.voicenote.VoiceSpeechGateway;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The WhatsApp voice-note speech provider: Sarvam's own exceptions become the speech module's
 * provider-neutral {@link VoiceSpeechException}, and the language table is {@link
 * SarvamIndicLanguages}.
 */
class SarvamVoiceSpeechGatewayTest {

    private final SarvamCredentialService credentials = mock(SarvamCredentialService.class);
    private final SarvamSpeechToTextClient stt = mock(SarvamSpeechToTextClient.class);
    private final SarvamTextToSpeechClient tts = mock(SarvamTextToSpeechClient.class);
    private final SarvamCallLogService callLog = mock(SarvamCallLogService.class);
    private final VoiceSpeechGateway gateway =
            new SarvamVoiceSpeechGateway(credentials, stt, tts, callLog);

    @Test
    void transcribesWithAutoDetectAndBlankModelAsTheClientDefault() {
        when(stt.transcribe(any(), eq("v.ogg"), isNull(), eq("codemix"), eq("unknown")))
                .thenReturn(
                        new SarvamSpeechToTextClient.TranscriptionResult("namaste", "hi-IN", "r1"));

        VoiceSpeechGateway.Transcript transcript =
                gateway.transcribe(new byte[] {1}, "v.ogg", " ", "codemix");

        assertEquals("namaste", transcript.text());
        assertEquals("hi-IN", transcript.languageCode());
    }

    @Test
    void aMissingCredentialIsNotConfigured() {
        when(stt.transcribe(any(), anyString(), any(), any(), any()))
                .thenThrow(new SarvamNotConfiguredException());

        VoiceSpeechException e =
                assertThrows(
                        VoiceSpeechException.class,
                        () -> gateway.transcribe(new byte[] {1}, "v.ogg", null, null));

        assertEquals(VoiceSpeechException.Failure.NOT_CONFIGURED, e.getFailure());
    }

    @Test
    void anUpstreamErrorKeepsOnlyTheStatusNotSarvamsText() {
        when(stt.transcribe(any(), anyString(), any(), any(), any()))
                .thenThrow(new SarvamApiException(500, "body with request details"));

        VoiceSpeechException e =
                assertThrows(
                        VoiceSpeechException.class,
                        () -> gateway.transcribe(new byte[] {1}, "v.ogg", null, null));

        assertEquals(VoiceSpeechException.Failure.UPSTREAM_FAILED, e.getFailure());
        assertTrue(e.getMessage().contains("500"), e.getMessage());
        assertFalse(e.getMessage().contains("request details"), e.getMessage());
    }

    @Test
    void theLanguageTableIsSarvams() {
        assertEquals(SarvamIndicLanguages.normalize("hi"), gateway.normalizeLanguage("hi"));
        assertEquals(
                SarvamIndicLanguages.displayName("ta-IN"), gateway.languageDisplayName("ta-IN"));
        assertEquals(SarvamIndicLanguages.ttsLanguageFor("hi-IN"), gateway.ttsLanguageFor("hi-IN"));
        assertEquals(Optional.empty(), gateway.ttsLanguageFor("ur-IN"));
    }
}
