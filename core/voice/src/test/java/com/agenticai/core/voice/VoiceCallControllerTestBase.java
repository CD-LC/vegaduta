package com.agenticai.core.voice;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.agenticai.core.agents.core.Agent;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.agents.dto.AgentResponse;
import com.agenticai.core.agents.runtime.RuntimeLimits;
import com.agenticai.core.agents.turn.AgentChatService;
import com.agenticai.core.agents.turn.TurnContext;
import com.agenticai.core.elevenlabs.ElevenLabsCallSpeechQuota;
import com.agenticai.core.elevenlabs.ElevenLabsCallSpeechSynthesizer;
import com.agenticai.core.elevenlabs.ElevenLabsClient;
import com.agenticai.core.elevenlabs.ElevenLabsGeneratedAudioService;
import com.agenticai.core.elevenlabs.ElevenLabsVoiceService;
import com.agenticai.core.elevenlabs.VoiceQuotaService;
import com.agenticai.core.knowledge.SidecarClient;
import com.agenticai.core.providers.modality.SpeechToTextProvider;
import com.agenticai.core.providers.modality.SpeechToTextProviderFactory;
import com.agenticai.core.sarvam.SarvamCallSpeechSynthesizer;
import com.agenticai.core.sarvam.SarvamCallSpeechTranscriber;
import com.agenticai.core.sarvam.SarvamGeneratedAudioService;
import com.agenticai.core.sarvam.SarvamSpeechToTextClient;
import com.agenticai.core.sarvam.SarvamTextToSpeechClient;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.TenantLimits;
import com.agenticai.core.speech.call.CallSpeechSynthesizer;
import com.agenticai.core.voice.dto.VoiceTurnAudioChunkEvent;
import com.agenticai.core.voice.dto.VoiceTurnResponse;
import com.agenticai.core.voice.dto.VoiceTurnTranscriptEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockMultipartFile;
import reactor.core.publisher.Flux;

/**
 * Shared fixture for the {@link VoiceCallController} test classes: the vendor mocks, a controller
 * factory over the real live-call speech adapters, and {@link #runTurn}, which drives the {@code
 * turn} SSE stream to completion and reassembles an aggregate view ({@link TurnResult}) so most
 * tests can assert on "the final transcript"/"the final reply text"; a few assert on the event
 * sequence itself where that is the point (e.g. the audio-chunk payload).
 */
abstract class VoiceCallControllerTestBase {

    static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock AgentService agentService;

    @Mock AgentChatService agentChatService;

    @Mock LiveKitTokenService tokenService;

    @Mock SarvamSpeechToTextClient sttClient;

    @Mock SidecarClient sidecarClient;

    @Mock ElevenLabsClient ttsClient;

    @Mock ElevenLabsVoiceService elevenLabsVoiceService;

    @Mock ElevenLabsGeneratedAudioService generatedAudioService;

    @Mock SarvamTextToSpeechClient sarvamTtsClient;

    @Mock SarvamGeneratedAudioService sarvamAudioService;

    @Mock VoiceCallRoomRegistry roomRegistry;

    @Mock SpeechToTextProviderFactory providerFactory;

    @Mock VoiceQuotaService voiceQuotaService;

    @Mock TenantLimits tenantLimits;

    @Mock SpeechToTextProvider mockProvider;

    @Mock Agent mockAgent;

    VoiceCallController controller;
    UUID testAgentId;
    byte[] testAudioBytes;

    @BeforeEach
    void setup() throws Exception {
        MockitoAnnotations.openMocks(this);
        testAgentId = UUID.randomUUID();
        // Must clear VoiceCallController.MIN_VIABLE_AUDIO_BYTES (a header-only
        // WebM/Opus container from a too-quick push-to-talk tap is far
        // smaller than this) - repeated rather than left as a literal
        // "fake audio data" string so it stays obviously well above that
        // floor regardless of what it's tuned to later.
        testAudioBytes = "fake audio data ".repeat(100).getBytes();

        // Default behavior setup
        // AgentService.get() returns an AgentResponse (a record), not an Agent. Convert
        // lazily via thenAnswer rather than thenReturn(AgentResponse.from(mockAgent)):
        // the record captures its component values eagerly, so an eager conversion here
        // would freeze the mock's state at setUp() time and silently ignore every
        // per-test when(mockAgent.getX()) stub below.
        when(agentService.get(testAgentId)).thenAnswer(inv -> AgentResponse.from(mockAgent));
        // The real Agent#getRuntimeLimits() is null-safe (never null); a bare mock returns
        // null, which AgentResponse.from(...) would dereference.
        when(mockAgent.getRuntimeLimits()).thenReturn(RuntimeLimits.none());
        when(mockAgent.getVoiceModelProvider()).thenReturn(null); // Default: no selected provider
        when(mockAgent.getVoiceModel()).thenReturn(null);
        when(elevenLabsVoiceService.hasAnyVoice()).thenReturn(false);
        when(ttsClient.textToSpeech(anyString(), anyString(), isNull()))
                .thenReturn("fake mp3".getBytes());
        // The controller moved to the seven-arg chat overload (trailing
        // stepEvents boolean) - stubbing the old six-arg one leaves the real
        // call unmatched, so the mock returns null and turn() NPEs.
        when(agentChatService.chat(
                        any(TurnContext.class),
                        argThat(
                                r ->
                                        r.userMessage() != null
                                                && r.attachmentKey() == null
                                                && r.attachmentMimeType() == null)))
                .thenReturn(Flux.just("Test reply"));
    }

    /** Aggregate view of a driven-to-completion {@code turn} SSE stream - see the class javadoc. */
    record TurnResult(
            String transcript,
            String transcriptMethod,
            String replyText,
            List<VoiceTurnAudioChunkEvent> audioChunks,
            VoiceTurnResponse done,
            String errorMessage) {}

    /**
     * Subscribes to and fully drains {@code controller.turn(...)}'s SSE stream, reassembling it
     * into a {@link TurnResult}.
     */
    TurnResult runTurn(MockMultipartFile audioFile, String sessionId) throws Exception {
        List<ServerSentEvent<String>> events =
                controller
                        .turn(testAgentId, audioFile, sessionId, null)
                        .collectList()
                        .block(Duration.ofSeconds(10));
        assertNotNull(events, "turn() produced no events at all");

        String transcript = null;
        String transcriptMethod = null;
        StringBuilder replyText = new StringBuilder();
        List<VoiceTurnAudioChunkEvent> audioChunks = new ArrayList<>();
        VoiceTurnResponse done = null;
        String errorMessage = null;

        for (ServerSentEvent<String> event : events) {
            switch (event.event()) {
                case "transcript" -> {
                    VoiceTurnTranscriptEvent parsed =
                            MAPPER.readValue(event.data(), VoiceTurnTranscriptEvent.class);
                    transcript = parsed.transcript();
                    transcriptMethod = parsed.method();
                }
                case "reply-delta" -> replyText.append(event.data());
                case "audio-chunk" ->
                        audioChunks.add(
                                MAPPER.readValue(event.data(), VoiceTurnAudioChunkEvent.class));
                case "done" -> done = MAPPER.readValue(event.data(), VoiceTurnResponse.class);
                case "error" -> errorMessage = event.data();
                default -> fail("Unexpected SSE event type: " + event.event());
            }
        }
        return new TurnResult(
                transcript,
                transcriptMethod,
                replyText.toString(),
                audioChunks,
                done,
                errorMessage);
    }

    /**
     * The controller over the real live-call speech adapters wrapping this test's vendor mocks, so
     * every assertion still pins the ElevenLabs -> Sarvam -> text-only behaviour; a null {@code
     * tts} leaves the ElevenLabs tier out, as a distribution without it would.
     */
    VoiceCallController newController(Optional<SidecarClient> sidecar, ElevenLabsClient tts) {
        List<CallSpeechSynthesizer> synthesizers = new ArrayList<>();
        if (tts != null) {
            synthesizers.add(
                    new ElevenLabsCallSpeechSynthesizer(
                            tts, elevenLabsVoiceService, generatedAudioService));
        }
        synthesizers.add(
                new SarvamCallSpeechSynthesizer(
                        sarvamTtsClient,
                        sarvamAudioService,
                        new ElevenLabsCallSpeechQuota(voiceQuotaService, tenantLimits)));
        return new VoiceCallController(
                agentService,
                agentChatService,
                tokenService,
                sidecar,
                synthesizers,
                Optional.of(new SarvamCallSpeechTranscriber(sttClient)),
                roomRegistry,
                providerFactory);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }
}
