package com.agenticai.core.omnichannel.whatsappvoice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.sarvam.SarvamIndicLanguages;
import com.agenticai.core.speech.voicenote.VoiceSpeechException;
import com.agenticai.core.speech.voicenote.VoiceSpeechGateway;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link WhatsAppVoiceNoteService} with fake Meta and Sarvam gateways - no network, no Spring.
 * Covers the outcome of every failure path (each must end as a named outcome, never an exception),
 * the limits, and the voice-reply decisions.
 */
class WhatsAppVoiceNoteServiceTest {

    private static final WhatsAppVoiceNoteService.Credentials CREDS =
            new WhatsAppVoiceNoteService.Credentials("555", "tok");
    private static final WhatsAppAudioRef REF =
            new WhatsAppAudioRef("9001", "audio/ogg; codecs=opus", null, true);
    private static final String HINDI = "नमस्ते कैसे हैं";

    private static final class FakeMedia implements VoiceMediaGateway {
        byte[] audio = TestAudio.oggOpus(48_000L * 5, 0);
        byte[] lastReturned;
        VoiceNoteException downloadError;
        RuntimeException downloadRuntime;
        RuntimeException sendError;
        int downloads;
        final List<String> sent = new ArrayList<>();
        String lastSentMime;
        String lastSentFilename;
        long lastMaxBytes;

        @Override
        public DownloadedMedia download(
                String accessToken, String mediaId, long maxBytes, Duration timeout) {
            downloads++;
            lastMaxBytes = maxBytes;
            if (downloadError != null) {
                throw downloadError;
            }
            if (downloadRuntime != null) {
                throw downloadRuntime;
            }
            // A fresh copy per download, like the real thing - the service zeroes what it was
            // given.
            lastReturned = audio.clone();
            return new DownloadedMedia(lastReturned, "audio/ogg");
        }

        @Override
        public void sendAudio(
                String phoneNumberId,
                String accessToken,
                String to,
                byte[] audio,
                String mimeType,
                String filename) {
            if (sendError != null) {
                throw sendError;
            }
            sent.add(to);
            lastSentMime = mimeType;
            lastSentFilename = filename;
        }
    }

    private static final class FakeSpeech implements VoiceSpeechGateway {
        boolean configured = true;
        long sttCallsToday;
        RuntimeException countError;
        Transcript transcript = new Transcript(HINDI, "hi-IN");
        RuntimeException sttError;
        long sttSleepMs;
        int sttCalls;
        String lastSttFilename;
        String lastSttMode;
        byte[] tts = TestAudio.mp3();
        RuntimeException ttsError;
        long ttsSleepMs;
        int ttsCalls;
        String lastTtsText;
        String lastTtsLanguage;
        String lastTtsCodec;

        @Override
        public boolean isConfigured() {
            return configured;
        }

        @Override
        public long speechToTextCallsSince(Instant since) {
            if (countError != null) {
                throw countError;
            }
            return sttCallsToday;
        }

        @Override
        public Transcript transcribe(byte[] audio, String filename, String model, String mode) {
            sttCalls++;
            lastSttFilename = filename;
            lastSttMode = mode;
            sleep(sttSleepMs);
            if (sttError != null) {
                throw sttError;
            }
            return transcript;
        }

        @Override
        public byte[] synthesize(
                String text, String languageCode, String model, String speaker, String codec) {
            ttsCalls++;
            lastTtsText = text;
            lastTtsLanguage = languageCode;
            lastTtsCodec = codec;
            sleep(ttsSleepMs);
            if (ttsError != null) {
                throw ttsError;
            }
            return tts.clone();
        }

        private static void sleep(long ms) {
            if (ms > 0) {
                try {
                    Thread.sleep(ms);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // The real provider language table: these tests pin Sarvam's normalisation and voice
        // coverage.
        @Override
        public String normalizeLanguage(String languageCode) {
            return SarvamIndicLanguages.normalize(languageCode);
        }

        @Override
        public String languageDisplayName(String languageCode) {
            return SarvamIndicLanguages.displayName(languageCode);
        }

        @Override
        public java.util.Optional<String> ttsLanguageFor(String detectedLanguageCode) {
            return SarvamIndicLanguages.ttsLanguageFor(detectedLanguageCode);
        }
    }

    private final FakeMedia media = new FakeMedia();
    private final FakeSpeech speech = new FakeSpeech();
    private final VoiceNoteMetrics metrics = new VoiceNoteMetrics(null);
    private WhatsAppVoiceNoteService service;

    @AfterEach
    void shutdown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private static WhatsAppVoiceNoteSettings settings(
            boolean replyWithVoice,
            int maxSeconds,
            int sttTimeoutSeconds,
            int ttsTimeoutSeconds,
            int perSender,
            int perTenant,
            boolean verifySha,
            int replyMaxChars) {
        return new WhatsAppVoiceNoteSettings(
                true,
                replyWithVoice,
                maxSeconds,
                1_048_576L,
                5,
                sttTimeoutSeconds,
                ttsTimeoutSeconds,
                replyMaxChars,
                perSender,
                perTenant,
                verifySha,
                "",
                "codemix",
                "",
                "",
                "mp3");
    }

    private WhatsAppVoiceNoteService build(WhatsAppVoiceNoteSettings settings) {
        service = new WhatsAppVoiceNoteService(settings, media, speech, metrics, Clock.systemUTC());
        return service;
    }

    private WhatsAppVoiceNoteService buildDefault() {
        return build(settings(false, 60, 5, 5, 0, 0, true, 500));
    }

    // ---- inbound ----

    @Test
    void happyPathTranscribesAndReportsTheDetectedLanguage() {
        WhatsAppVoiceNoteService.Transcription t =
                buildDefault().transcribe(REF, CREDS, "919999900000");

        assertTrue(t.ok());
        assertEquals(HINDI, t.text());
        assertEquals("hi-IN", t.languageCode());
        assertEquals(1, metrics.tally(VoiceNoteOutcome.TRANSCRIBED));
        assertEquals("voice-note.ogg", speech.lastSttFilename);
        assertEquals("codemix", speech.lastSttMode);
        assertEquals(1_048_576L, media.lastMaxBytes);
    }

    @Test
    void theDownloadedAudioIsZeroedAfterUse() {
        assertTrue(anyNonZero(media.audio));

        buildDefault().transcribe(REF, CREDS, "919999900000");

        assertNotNull(media.lastReturned);
        assertFalse(
                anyNonZero(media.lastReturned),
                "audio bytes must not linger on the heap after the turn");
    }

    @Test
    void theDownloadedAudioIsZeroedEvenWhenTranscriptionFails() {
        speech.sttError = new IllegalStateException("boom");

        WhatsAppVoiceNoteService.Transcription t =
                buildDefault().transcribe(REF, CREDS, "919999900000");

        assertEquals(VoiceNoteOutcome.STT_FAILED, t.outcome());
        assertNotNull(media.lastReturned);
        assertFalse(anyNonZero(media.lastReturned));
    }

    @Test
    void codeMixedTranscriptKeepsItsLatinWordsAndNormalisesTheLanguage() {
        speech.transcript = new VoiceSpeechGateway.Transcript("mera phone number kya hai", "hi");

        WhatsAppVoiceNoteService.Transcription t =
                buildDefault().transcribe(REF, CREDS, "919999900000");

        assertEquals("mera phone number kya hai", t.text());
        assertEquals("hi-IN", t.languageCode());
    }

    @Test
    void notConfiguredStopsBeforeAnyDownload() {
        speech.configured = false;

        WhatsAppVoiceNoteService.Transcription t =
                buildDefault().transcribe(REF, CREDS, "919999900000");

        assertEquals(VoiceNoteOutcome.NOT_AVAILABLE, t.outcome());
        assertEquals(0, media.downloads);
        assertEquals(0, speech.sttCalls);
    }

    @Test
    void tenantDailyCapStopsBeforeAnyDownload() {
        speech.sttCallsToday = 300;
        build(settings(false, 60, 5, 5, 0, 300, true, 500));

        WhatsAppVoiceNoteService.Transcription t = service.transcribe(REF, CREDS, "919999900000");

        assertEquals(VoiceNoteOutcome.NOT_AVAILABLE, t.outcome());
        assertEquals(0, media.downloads);
    }

    @Test
    void unreadableCallLogCountFailsOpenRatherThanDroppingTheVoiceNote() {
        speech.countError = new IllegalStateException("db down");
        build(settings(false, 60, 5, 5, 0, 300, true, 500));

        assertTrue(service.transcribe(REF, CREDS, "919999900000").ok());
    }

    @Test
    void senderRateLimitDeniesAndOnlyTheFirstDenialNotifies() {
        build(settings(false, 60, 5, 5, 1, 0, true, 500));

        assertTrue(service.transcribe(REF, CREDS, "919999900000").ok());
        WhatsAppVoiceNoteService.Transcription second =
                service.transcribe(REF, CREDS, "919999900000");
        WhatsAppVoiceNoteService.Transcription third =
                service.transcribe(REF, CREDS, "919999900000");

        assertEquals(VoiceNoteOutcome.RATE_LIMITED, second.outcome());
        assertTrue(second.notifyUser());
        assertEquals(VoiceNoteOutcome.RATE_LIMITED, third.outcome());
        assertFalse(third.notifyUser());
        assertEquals(1, media.downloads);
        // A different sender is unaffected.
        assertTrue(service.transcribe(REF, CREDS, "918888800000").ok());
    }

    @Test
    void namedDownloadRefusalsPassThroughAndUnknownOnesAreDownloadFailed() {
        media.downloadError = new VoiceNoteException(VoiceNoteOutcome.TOO_LARGE, "too big");
        assertEquals(
                VoiceNoteOutcome.TOO_LARGE, buildDefault().transcribe(REF, CREDS, "1").outcome());

        media.downloadError = null;
        media.downloadRuntime = new IllegalStateException("socket");
        assertEquals(
                VoiceNoteOutcome.DOWNLOAD_FAILED, service.transcribe(REF, CREDS, "2").outcome());

        assertEquals(1, metrics.tally(VoiceNoteOutcome.TOO_LARGE));
        assertEquals(1, metrics.tally(VoiceNoteOutcome.DOWNLOAD_FAILED));
        assertEquals(0, speech.sttCalls);
    }

    @Test
    void integrityMismatchIsRefusedAndVerificationCanBeSwitchedOff() throws Exception {
        WhatsAppAudioRef wrongHash =
                new WhatsAppAudioRef(
                        "9001", null, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", true);

        assertEquals(
                VoiceNoteOutcome.INTEGRITY_FAILED,
                buildDefault().transcribe(wrongHash, CREDS, "1").outcome());
        assertEquals(0, speech.sttCalls);

        service.shutdown();
        build(settings(false, 60, 5, 5, 0, 0, false, 500));
        assertTrue(service.transcribe(wrongHash, CREDS, "1").ok());

        // A correct base64 hash (as in Meta's webhook example) and a correct hex hash both verify.
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(media.audio);
        assertTrue(
                WhatsAppVoiceNoteService.sha256Matches(
                        media.audio, Base64.getEncoder().encodeToString(digest)));
        assertTrue(
                WhatsAppVoiceNoteService.sha256Matches(
                        media.audio, HexFormat.of().formatHex(digest).toUpperCase()));
        assertTrue(WhatsAppVoiceNoteService.sha256Matches(media.audio, null));
        assertFalse(WhatsAppVoiceNoteService.sha256Matches(media.audio, "not-the-hash"));
    }

    @Test
    void bytesThatAreNotAWhatsAppAudioContainerAreRefusedBeforeSpeechIsPaidFor() {
        media.audio = TestAudio.wav();

        WhatsAppVoiceNoteService.Transcription t = buildDefault().transcribe(REF, CREDS, "1");

        assertEquals(VoiceNoteOutcome.UNSUPPORTED_MEDIA, t.outcome());
        assertEquals(0, speech.sttCalls);

        media.audio = "<html>login</html>".getBytes();
        assertEquals(
                VoiceNoteOutcome.UNSUPPORTED_MEDIA, service.transcribe(REF, CREDS, "2").outcome());
        assertEquals(0, speech.sttCalls);
    }

    @Test
    void anOpusNoteLongerThanTheEffectiveCapIsRefusedBeforeSpeech() {
        // Configured 60 s, but Sarvam's synchronous endpoint stops at 30 s: the effective cap is
        // 30.
        media.audio = TestAudio.oggOpus(48_000L * 45, 0);

        WhatsAppVoiceNoteService.Transcription t = buildDefault().transcribe(REF, CREDS, "1");

        assertEquals(VoiceNoteOutcome.TOO_LONG, t.outcome());
        assertEquals(0, speech.sttCalls);
        assertTrue(service.fallbackText(VoiceNoteOutcome.TOO_LONG).contains("30 seconds"));
    }

    @Test
    void aShorterConfiguredCapWinsAndANoteUnderItPasses() {
        build(settings(false, 10, 5, 5, 0, 0, true, 500));

        media.audio = TestAudio.oggOpus(48_000L * 12, 0);
        assertEquals(VoiceNoteOutcome.TOO_LONG, service.transcribe(REF, CREDS, "1").outcome());

        media.audio = TestAudio.oggOpus(48_000L * 8, 0);
        assertTrue(service.transcribe(REF, CREDS, "2").ok());
    }

    @Test
    void anMp3NoteHasNoCheapDurationProbeSoItFallsBackToTheByteCapAlone() {
        media.audio = TestAudio.mp3();

        assertTrue(buildDefault().transcribe(REF, CREDS, "1").ok());
        assertEquals("voice-note.mp3", speech.lastSttFilename);
    }

    @Test
    void speechTimeoutIsBoundedAndNamed() {
        speech.sttSleepMs = 2_500;
        build(settings(false, 60, 1, 5, 0, 0, true, 500));

        long started = System.nanoTime();
        WhatsAppVoiceNoteService.Transcription t = service.transcribe(REF, CREDS, "1");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertEquals(VoiceNoteOutcome.STT_TIMEOUT, t.outcome());
        assertTrue(elapsedMs < 2_200, "returned after " + elapsedMs + " ms");
        assertEquals(1, speech.sttCalls, "exactly one attempt, no retry");
    }

    @Test
    void speechFailuresBecomeNamedOutcomesNotExceptions() {
        speech.sttError =
                new VoiceSpeechException(
                        VoiceSpeechException.Failure.UPSTREAM_FAILED,
                        "Sarvam speech-to-text failed (upstream status 500)");
        assertEquals(
                VoiceNoteOutcome.STT_FAILED, buildDefault().transcribe(REF, CREDS, "1").outcome());

        speech.sttError =
                new VoiceSpeechException(
                        VoiceSpeechException.Failure.NOT_CONFIGURED, "no active Sarvam credential");
        assertEquals(VoiceNoteOutcome.NOT_AVAILABLE, service.transcribe(REF, CREDS, "2").outcome());

        speech.sttError = new IllegalArgumentException("anything");
        assertEquals(VoiceNoteOutcome.STT_FAILED, service.transcribe(REF, CREDS, "3").outcome());
        assertEquals(3, speech.sttCalls, "one attempt per note");
    }

    @Test
    void blankTranscriptIsAnEmptyTranscriptFailure() {
        speech.transcript = new VoiceSpeechGateway.Transcript("   ", "hi-IN");
        assertEquals(
                VoiceNoteOutcome.EMPTY_TRANSCRIPT,
                buildDefault().transcribe(REF, CREDS, "1").outcome());

        speech.transcript = null;
        assertEquals(
                VoiceNoteOutcome.EMPTY_TRANSCRIPT, service.transcribe(REF, CREDS, "2").outcome());
    }

    // ---- wording and hints ----

    @Test
    void fallbackWordingIsShortAndTheDefaultIsTheSpecifiedSentence() {
        buildDefault();

        assertEquals(
                "I could not understand that voice note, please try text",
                service.fallbackText(VoiceNoteOutcome.STT_FAILED));
        assertEquals(
                "I could not understand that voice note, please try text",
                service.fallbackText(VoiceNoteOutcome.DOWNLOAD_FAILED));
        assertEquals(
                "I could not understand that voice note, please try text",
                service.fallbackText(null));
        assertTrue(service.fallbackText(VoiceNoteOutcome.RATE_LIMITED).contains("send text"));
        assertTrue(service.fallbackText(VoiceNoteOutcome.NOT_AVAILABLE).contains("send text"));
    }

    @Test
    void languageHintIsOneLineThatNamesTheSpokenLanguage() {
        buildDefault();

        String hindi = service.languageHint("hi-IN");
        assertTrue(hindi.contains("Hindi"));
        assertTrue(hindi.contains("hi-IN"));
        assertFalse(hindi.contains("\n"));
        assertTrue(service.languageHint("ta").contains("Tamil"));
        // A language we can transcribe but have no name for still gets a generic same-language
        // instruction.
        String unknown = service.languageHint("ur-IN");
        assertFalse(unknown.contains("null"));
        assertTrue(unknown.contains("Reply in the language the person spoke"));
        assertTrue(service.languageHint(null).contains("Reply in the language the person spoke"));
    }

    @Test
    void senderHashIsStableShortAndDoesNotContainTheNumber() {
        String a = WhatsAppVoiceNoteService.hashSender("tenant1", "919999900000");

        assertEquals(a, WhatsAppVoiceNoteService.hashSender("tenant1", "919999900000"));
        assertEquals(12, a.length());
        assertFalse(a.contains("9999900000"));
        assertFalse(a.equals(WhatsAppVoiceNoteService.hashSender("tenant2", "919999900000")));
    }

    // ---- outbound ----

    @Test
    void replyWithVoiceOffMeansNoSpeechCallAtAll() {
        buildDefault()
                .sendVoiceReply("Hello there, how can I help?", "hi-IN", CREDS, "919999900000");

        assertEquals(0, speech.ttsCalls);
        assertTrue(media.sent.isEmpty());
    }

    @Test
    void replyWithVoiceOnSendsAnMp3AudioMessageInTheDetectedLanguage() {
        build(settings(true, 60, 5, 5, 0, 0, true, 500));

        service.sendVoiceReply(
                "Your order ships tomorrow. Track it at https://example.com/t/1 please.",
                "hi-IN",
                CREDS,
                "919999900000");

        assertEquals(List.of("919999900000"), media.sent);
        assertEquals("audio/mpeg", media.lastSentMime);
        assertEquals("reply.mp3", media.lastSentFilename);
        assertEquals("hi-IN", speech.lastTtsLanguage);
        assertEquals("mp3", speech.lastTtsCodec);
        assertFalse(speech.lastTtsText.contains("http"), "URLs are never read out");
        assertEquals(1, metrics.tally(VoiceNoteOutcome.VOICE_REPLY_SENT));
    }

    @Test
    void aLanguageWithNoSarvamVoiceGetsNoAudioReply() {
        build(settings(true, 60, 5, 5, 0, 0, true, 500));

        service.sendVoiceReply("Some reply that is long enough.", "ur-IN", CREDS, "1");
        service.sendVoiceReply("Some reply that is long enough.", null, CREDS, "1");

        assertEquals(0, speech.ttsCalls);
        assertTrue(media.sent.isEmpty());
        assertEquals(2, metrics.tally(VoiceNoteOutcome.VOICE_REPLY_NO_LANGUAGE));
    }

    @Test
    void aReplyThatIsOnlyCodeOrALinkIsNotSpoken() {
        build(settings(true, 60, 5, 5, 0, 0, true, 500));

        service.sendVoiceReply("```\nSELECT * FROM t;\n```", "en-IN", CREDS, "1");
        service.sendVoiceReply("https://example.com/a", "en-IN", CREDS, "1");

        assertEquals(0, speech.ttsCalls);
        assertEquals(2, metrics.tally(VoiceNoteOutcome.VOICE_REPLY_NOTHING_SPEAKABLE));
    }

    @Test
    void aLongReplyIsCutToTheSpokenSummaryLengthBeforeSynthesis() {
        build(settings(true, 60, 5, 5, 0, 0, true, 80));
        String reply = "This is the first sentence of a long answer. ".repeat(20);

        service.sendVoiceReply(reply, "en-IN", CREDS, "1");

        assertNotNull(speech.lastTtsText);
        assertTrue(
                speech.lastTtsText.length() <= 80,
                "spoken " + speech.lastTtsText.length() + " chars");
        assertTrue(speech.lastTtsText.endsWith("."));
    }

    @Test
    void wavFromSarvamIsNotSentBecauseWhatsAppRejectsItAndWeDoNotTranscode() {
        build(settings(true, 60, 5, 5, 0, 0, true, 500));
        speech.tts = TestAudio.wav();

        service.sendVoiceReply("A perfectly speakable answer.", "en-IN", CREDS, "1");

        assertTrue(media.sent.isEmpty());
        assertEquals(1, metrics.tally(VoiceNoteOutcome.VOICE_REPLY_FORMAT_UNSUPPORTED));
    }

    @Test
    void textToSpeechAndSendFailuresAreSwallowedAndCounted() {
        build(settings(true, 60, 5, 5, 0, 0, true, 500));

        speech.ttsError =
                new VoiceSpeechException(
                        VoiceSpeechException.Failure.UPSTREAM_FAILED,
                        "Sarvam text-to-speech failed (upstream status 500)");
        service.sendVoiceReply("A perfectly speakable answer.", "en-IN", CREDS, "1");
        assertEquals(1, metrics.tally(VoiceNoteOutcome.VOICE_REPLY_TTS_FAILED));

        speech.ttsError = null;
        media.sendError = new IllegalStateException("meta said no");
        service.sendVoiceReply("A perfectly speakable answer.", "en-IN", CREDS, "1");
        assertEquals(1, metrics.tally(VoiceNoteOutcome.VOICE_REPLY_SEND_FAILED));

        assertEquals(2, speech.ttsCalls, "one TTS attempt per reply, no retry");
        assertTrue(media.sent.isEmpty());
    }

    @Test
    void textToSpeechTimeoutIsBounded() {
        speech.ttsSleepMs = 2_500;
        build(settings(true, 60, 5, 1, 0, 0, true, 500));

        long started = System.nanoTime();
        service.sendVoiceReply("A perfectly speakable answer.", "en-IN", CREDS, "1");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        assertTrue(elapsedMs < 2_200, "returned after " + elapsedMs + " ms");
        assertEquals(1, metrics.tally(VoiceNoteOutcome.VOICE_REPLY_TTS_TIMEOUT));
        assertTrue(media.sent.isEmpty());
    }

    @Test
    void emptyAudioFromSarvamIsAFailureNotASend() {
        build(settings(true, 60, 5, 5, 0, 0, true, 500));
        speech.tts = new byte[0];

        service.sendVoiceReply("A perfectly speakable answer.", "en-IN", CREDS, "1");

        assertTrue(media.sent.isEmpty());
        assertEquals(1, metrics.tally(VoiceNoteOutcome.VOICE_REPLY_TTS_FAILED));
        assertNull(media.lastSentMime);
    }

    private static boolean anyNonZero(byte[] bytes) {
        for (byte b : bytes) {
            if (b != 0) {
                return true;
            }
        }
        return false;
    }
}
