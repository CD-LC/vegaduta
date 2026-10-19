package com.agenticai.core.sarvam;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

/**
 * The {@code output_audio_codec} overload added for the WhatsApp voice-note reply (WhatsApp rejects
 * WAV). Pins that the field is sent only when asked for, so every pre-existing caller's request
 * body is unchanged.
 */
class SarvamTextToSpeechClientCodecTest {

    private static final byte[] AUDIO = "ID3-fake-mp3".getBytes(StandardCharsets.UTF_8);
    private static final String AUDIO_B64 = Base64.getEncoder().encodeToString(AUDIO);

    private MockRestServiceServer server;
    private SarvamTextToSpeechClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        SarvamCredentialService credentialService = mock(SarvamCredentialService.class);
        SarvamCallLogService callLogService = mock(SarvamCallLogService.class);
        client = new SarvamTextToSpeechClient(builder, credentialService, callLogService);
        when(credentialService.resolveActiveApiKey()).thenReturn("test-key");
    }

    @Test
    void codecIsSentWhenRequested() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.sarvam.ai/text-to-speech"))
            .andExpect(MockRestRequestMatchers.jsonPath("$.output_audio_codec").value("mp3"))
            .andExpect(MockRestRequestMatchers.jsonPath("$.target_language_code").value("ta-IN"))
            .andRespond(MockRestResponseCreators.withSuccess(
                "{\"audios\":[\"" + AUDIO_B64 + "\"]}", MediaType.APPLICATION_JSON));

        SarvamTextToSpeechClient.SynthesisResult result = client.synthesize("Vanakkam.", "ta-IN", null, null, null, "mp3");

        assertArrayEquals(AUDIO, result.wavBytes());
        server.verify();
    }

    @Test
    void codecIsOmittedByEveryExistingOverload() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.sarvam.ai/text-to-speech"))
            .andExpect(MockRestRequestMatchers.jsonPath("$.output_audio_codec").doesNotExist())
            .andRespond(MockRestResponseCreators.withSuccess(
                "{\"audios\":[\"" + AUDIO_B64 + "\"]}", MediaType.APPLICATION_JSON));

        client.synthesize("Hello.", "hi-IN", null, null, "operator-key");

        server.verify();
    }

    @Test
    void blankCodecIsTreatedAsOmitted() {
        server.expect(MockRestRequestMatchers.requestTo("https://api.sarvam.ai/text-to-speech"))
            .andExpect(MockRestRequestMatchers.jsonPath("$.output_audio_codec").doesNotExist())
            .andRespond(MockRestResponseCreators.withSuccess(
                "{\"audios\":[\"" + AUDIO_B64 + "\"]}", MediaType.APPLICATION_JSON));

        client.synthesize("Hello.", "hi-IN", null, null, null, "  ");

        server.verify();
    }
}
