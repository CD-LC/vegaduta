package com.agenticai.core.omnichannel.whatsappvoice;

import com.agenticai.core.security.TenantContext;
import com.agenticai.core.speech.voicenote.VoiceSpeechException;
import com.agenticai.core.speech.voicenote.VoiceSpeechGateway;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * WhatsApp voice notes in the sender's own language: transcribe an inbound voice note (Sarvam
 * speech-to-text, language auto-detected, code-mixed capable) so it can run as an ordinary text
 * turn, and optionally speak the reply back (Sarvam text-to-speech) as a WhatsApp audio message.
 *
 * <p>This class owns everything that is NOT the existing inbound path: media download, limits,
 * speech calls, metering, the fallback wording. It does not talk to the agent, the approval gate or
 * the pairing logic - {@code MetaWhatsAppWebhookController} keeps those and calls this at two
 * points (after the sender is paired: {@link #transcribe}; after the text reply was sent: {@link
 * #sendVoiceReply}). It is only a bean when {@code
 * app.omnichannel.whatsapp.voice-notes.enabled=true}; the default deployment has none of it.
 *
 * <p>Fail-safe by construction: no method here throws to its caller, every external step is bounded
 * (download deadline, {@link TimeBoundedRunner} for the speech calls) and attempted ONCE - no
 * retries, so a failing upstream cannot become a storm. A failed inbound step yields an outcome the
 * controller turns into one short text message; a failed voice reply is simply not sent (the text
 * reply already went out).
 *
 * <p>Privacy: audio exists only in heap arrays that are zeroed after use; nothing is written to
 * disk or object storage; logs and counters carry outcome names, sizes and a hashed sender id -
 * never audio, transcript, reply text or a phone number.
 *
 * <p>Metering and entitlement: WhatsApp itself is plan-gated at connection time ({@code
 * TenantLimits.whatsappAllowed}); every Sarvam call is recorded in {@code sarvam_call_log} by the
 * Sarvam clients, and the per-tenant daily cap counts exactly those rows. Only the tenant's own
 * (BYOK) Sarvam key is used - there is no platform-subsidised path for voice notes.
 */
@Service
@ConditionalOnProperty(
        prefix = "app.omnichannel.whatsapp.voice-notes",
        name = "enabled",
        havingValue = "true")
public class WhatsAppVoiceNoteService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppVoiceNoteService.class);

    /**
     * What the controller needs to download media and send audio as the tenant's WhatsApp number.
     */
    public record Credentials(String phoneNumberId, String accessToken) {}

    /**
     * Result of {@link #transcribe}.
     *
     * @param notifyUser false only for a repeated rate-limit denial, where even the apology is
     *     withheld
     */
    public record Transcription(
            VoiceNoteOutcome outcome, String text, String languageCode, boolean notifyUser) {
        public boolean ok() {
            return outcome == VoiceNoteOutcome.TRANSCRIBED;
        }

        static Transcription failure(VoiceNoteOutcome outcome) {
            return new Transcription(outcome, null, null, true);
        }
    }

    private final WhatsAppVoiceNoteSettings settings;
    private final VoiceMediaGateway media;
    private final VoiceSpeechGateway speech;
    private final VoiceNoteMetrics metrics;
    private final Clock clock;
    private final VoiceNoteRateLimiter limiter;
    private final TimeBoundedRunner runner = new TimeBoundedRunner(4, 8);

    @Autowired
    public WhatsAppVoiceNoteService(
            @Value("${app.omnichannel.whatsapp.voice-notes.enabled:false}") boolean enabled,
            @Value("${app.omnichannel.whatsapp.voice-notes.reply-with-voice:false}")
                    boolean replyWithVoice,
            @Value("${app.omnichannel.whatsapp.voice-notes.max-seconds:60}") int maxSeconds,
            @Value("${app.omnichannel.whatsapp.voice-notes.max-bytes:1048576}") long maxBytes,
            @Value("${app.omnichannel.whatsapp.voice-notes.download-timeout-seconds:10}")
                    int downloadTimeoutSeconds,
            @Value("${app.omnichannel.whatsapp.voice-notes.stt-timeout-seconds:25}")
                    int sttTimeoutSeconds,
            @Value("${app.omnichannel.whatsapp.voice-notes.tts-timeout-seconds:25}")
                    int ttsTimeoutSeconds,
            @Value("${app.omnichannel.whatsapp.voice-notes.reply-max-chars:500}") int replyMaxChars,
            @Value("${app.omnichannel.whatsapp.voice-notes.max-per-sender-per-hour:20}")
                    int maxPerSenderPerHour,
            @Value("${app.omnichannel.whatsapp.voice-notes.max-per-tenant-per-day:300}")
                    int maxPerTenantPerDay,
            @Value("${app.omnichannel.whatsapp.voice-notes.verify-sha256:true}")
                    boolean verifySha256,
            @Value("${app.omnichannel.whatsapp.voice-notes.stt-model:}") String sttModel,
            @Value("${app.omnichannel.whatsapp.voice-notes.stt-mode:codemix}") String sttMode,
            @Value("${app.omnichannel.whatsapp.voice-notes.tts-model:}") String ttsModel,
            @Value("${app.omnichannel.whatsapp.voice-notes.tts-speaker:}") String ttsSpeaker,
            @Value("${app.omnichannel.whatsapp.voice-notes.tts-codec:mp3}") String ttsCodec,
            VoiceMediaGateway media,
            ObjectProvider<VoiceSpeechGateway> speech,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this(
                new WhatsAppVoiceNoteSettings(
                        enabled,
                        replyWithVoice,
                        maxSeconds,
                        maxBytes,
                        downloadTimeoutSeconds,
                        sttTimeoutSeconds,
                        ttsTimeoutSeconds,
                        replyMaxChars,
                        maxPerSenderPerHour,
                        maxPerTenantPerDay,
                        verifySha256,
                        sttModel,
                        sttMode,
                        ttsModel,
                        ttsSpeaker,
                        ttsCodec),
                media,
                speech.getIfAvailable(NoSpeechGateway::new),
                new VoiceNoteMetrics(meterRegistry.getIfAvailable()),
                Clock.systemUTC());
    }

    /**
     * Stands in when no {@link VoiceSpeechGateway} bean exists: the only implementation is the
     * Sarvam one, which a lean (personal) install does not register (edition feature {@code
     * CLOUD_SPEECH}). The service then still starts and answers every voice note as "not
     * available", exactly as for a tenant with no speech credential.
     */
    private static final class NoSpeechGateway implements VoiceSpeechGateway {

        private static VoiceSpeechException unavailable() {
            return new VoiceSpeechException(
                    VoiceSpeechException.Failure.NOT_CONFIGURED, "no speech provider is installed");
        }

        @Override
        public boolean isConfigured() {
            return false;
        }

        @Override
        public long speechToTextCallsSince(Instant since) {
            return 0;
        }

        @Override
        public Transcript transcribe(byte[] audio, String filename, String model, String mode) {
            throw unavailable();
        }

        @Override
        public byte[] synthesize(
                String text, String languageCode, String model, String speaker, String codec) {
            throw unavailable();
        }

        @Override
        public String normalizeLanguage(String languageCode) {
            return null;
        }

        @Override
        public String languageDisplayName(String languageCode) {
            return null;
        }

        @Override
        public Optional<String> ttsLanguageFor(String detectedLanguageCode) {
            return Optional.empty();
        }
    }

    /** Test seam: hand-built, injectable clock and metrics. */
    WhatsAppVoiceNoteService(
            WhatsAppVoiceNoteSettings settings,
            VoiceMediaGateway media,
            VoiceSpeechGateway speech,
            VoiceNoteMetrics metrics,
            Clock clock) {
        this.settings = settings;
        this.media = media;
        this.speech = speech;
        this.metrics = metrics;
        this.clock = clock;
        this.limiter = new VoiceNoteRateLimiter(settings.maxPerSenderPerHour(), clock);
    }

    public boolean isEnabled() {
        return settings.enabled();
    }

    // ---- inbound ----

    /**
     * Turns one inbound voice note into text. Call only for a paired sender (it costs money). Never
     * throws; the outcome says what happened and every non-OK outcome has already been counted.
     */
    public Transcription transcribe(WhatsAppAudioRef ref, Credentials credentials, String from) {
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse("");
        String senderHash = hashSender(tenantId, from);
        byte[] audio = null;
        try {
            VoiceNoteRateLimiter.Decision decision = limiter.tryAcquire(senderHash);
            if (decision != VoiceNoteRateLimiter.Decision.ALLOW) {
                metrics.count(VoiceNoteOutcome.RATE_LIMITED);
                log.info("WhatsApp voice note rate-limited (sender {})", senderHash);
                return new Transcription(
                        VoiceNoteOutcome.RATE_LIMITED,
                        null,
                        null,
                        decision == VoiceNoteRateLimiter.Decision.DENY_FIRST);
            }
            if (!speech.isConfigured()) {
                return fail(
                        VoiceNoteOutcome.NOT_AVAILABLE,
                        senderHash,
                        "no active speech-provider credential");
            }
            if (settings.maxPerTenantPerDay() > 0 && dailyCapReached()) {
                return fail(
                        VoiceNoteOutcome.NOT_AVAILABLE,
                        senderHash,
                        "tenant daily voice-note cap reached");
            }

            VoiceMediaGateway.DownloadedMedia downloaded;
            try {
                downloaded =
                        media.download(
                                credentials.accessToken(),
                                ref.mediaId(),
                                settings.maxBytes(),
                                Duration.ofSeconds(settings.downloadTimeoutSeconds()));
            } catch (VoiceNoteException e) {
                return fail(e.getOutcome(), senderHash, e.getMessage());
            } catch (RuntimeException e) {
                return fail(
                        VoiceNoteOutcome.DOWNLOAD_FAILED, senderHash, e.getClass().getSimpleName());
            }
            audio = downloaded.bytes();

            if (audio.length > settings.maxBytes()) {
                return fail(VoiceNoteOutcome.TOO_LARGE, senderHash, "bytes over cap");
            }
            if (settings.verifySha256() && !sha256Matches(audio, ref.sha256())) {
                return fail(VoiceNoteOutcome.INTEGRITY_FAILED, senderHash, "sha256 mismatch");
            }
            AudioFormatSniffer.Kind kind = AudioFormatSniffer.sniff(audio);
            if (!kind.whatsAppAccepts()) {
                return fail(
                        VoiceNoteOutcome.UNSUPPORTED_MEDIA,
                        senderHash,
                        "bytes are not a WhatsApp audio container");
            }
            OptionalDouble seconds = OggOpusProbe.durationSeconds(audio);
            if (seconds.isPresent() && seconds.getAsDouble() > settings.effectiveMaxSeconds()) {
                return fail(VoiceNoteOutcome.TOO_LONG, senderHash, "duration over cap");
            }

            final byte[] toTranscribe = audio;
            final String filename = "voice-note." + kind.extension();
            VoiceSpeechGateway.Transcript transcript;
            try {
                transcript =
                        runner.call(
                                Duration.ofSeconds(settings.sttTimeoutSeconds()),
                                () ->
                                        speech.transcribe(
                                                toTranscribe,
                                                filename,
                                                settings.sttModel(),
                                                settings.sttMode()));
            } catch (TimeoutException e) {
                return fail(VoiceNoteOutcome.STT_TIMEOUT, senderHash, "speech-to-text timed out");
            } catch (RejectedExecutionException e) {
                return fail(VoiceNoteOutcome.OVERLOADED, senderHash, "speech worker pool full");
            } catch (VoiceSpeechException e) {
                VoiceNoteOutcome outcome =
                        e.getFailure() == VoiceSpeechException.Failure.NOT_CONFIGURED
                                ? VoiceNoteOutcome.NOT_AVAILABLE
                                : VoiceNoteOutcome.STT_FAILED;
                return fail(outcome, senderHash, e.getMessage());
            } catch (VoiceNoteException e) {
                return fail(e.getOutcome(), senderHash, e.getMessage());
            } catch (Exception e) {
                return fail(VoiceNoteOutcome.STT_FAILED, senderHash, e.getClass().getSimpleName());
            }

            if (transcript == null || transcript.text() == null || transcript.text().isBlank()) {
                return fail(VoiceNoteOutcome.EMPTY_TRANSCRIPT, senderHash, "no speech recognised");
            }
            metrics.count(VoiceNoteOutcome.TRANSCRIBED);
            log.info(
                    "WhatsApp voice note transcribed (sender {}, bytes {}, language {}, chars {})",
                    senderHash,
                    audio.length,
                    transcript.languageCode(),
                    transcript.text().length());
            return new Transcription(
                    VoiceNoteOutcome.TRANSCRIBED,
                    transcript.text().trim(),
                    speech.normalizeLanguage(transcript.languageCode()),
                    true);
        } catch (RuntimeException e) {
            // Defensive: nothing above should reach here, and this must never escape to the webhook
            // thread.
            return fail(
                    VoiceNoteOutcome.STT_FAILED,
                    senderHash,
                    "unexpected " + e.getClass().getSimpleName());
        } finally {
            if (audio != null) {
                Arrays.fill(audio, (byte) 0);
            }
        }
    }

    /**
     * The single line appended to the user message of a voice-note turn (and ONLY those turns), so
     * the agent answers in the language that was spoken. The platform has no per-turn language
     * hint, and a system-prompt directive would cost every turn; this costs one line, one time,
     * here.
     */
    public String languageHint(String languageCode) {
        String name = speech.languageDisplayName(languageCode);
        String code = speech.normalizeLanguage(languageCode);
        if (name != null && code != null) {
            return "[This message is a voice note transcribed from speech in "
                    + name
                    + " ("
                    + code
                    + "). Reply in the same language and script the person used.]";
        }
        return "[This message is a voice note transcribed from speech. Reply in the language the person spoke.]";
    }

    /**
     * The one short text message sent in place of an answer when the voice note could not be used.
     */
    public String fallbackText(VoiceNoteOutcome outcome) {
        if (outcome == null) {
            return "I could not understand that voice note, please try text";
        }
        return switch (outcome) {
            case RATE_LIMITED ->
                    "I am getting a lot of voice notes at once. Please try again in a little while, or send text.";
            case TOO_LONG, TOO_LARGE ->
                    "That voice note is too long for me. Please send one shorter than "
                            + settings.effectiveMaxSeconds()
                            + " seconds, or send text.";
            case NOT_AVAILABLE ->
                    "Voice notes are not available on this number right now. Please send text.";
            default -> "I could not understand that voice note, please try text";
        };
    }

    // ---- outbound ----

    /**
     * Speaks the reply back as a WhatsApp audio message when {@code reply-with-voice} is on and
     * Sarvam has a voice for the detected language. Called only after the text reply was sent, so a
     * failure here loses nothing. Never throws; each early exit is counted.
     */
    public void sendVoiceReply(
            String replyText, String languageCode, Credentials credentials, String to) {
        if (!settings.replyWithVoice()) {
            return;
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse("");
        String senderHash = hashSender(tenantId, to);
        byte[] audio = null;
        try {
            Optional<String> ttsLanguage = speech.ttsLanguageFor(languageCode);
            if (ttsLanguage.isEmpty()) {
                metrics.count(VoiceNoteOutcome.VOICE_REPLY_NO_LANGUAGE);
                return;
            }
            Optional<String> spoken =
                    SpokenReplyShortener.shorten(replyText, settings.replyMaxChars());
            if (spoken.isEmpty()) {
                metrics.count(VoiceNoteOutcome.VOICE_REPLY_NOTHING_SPEAKABLE);
                return;
            }
            final String text = spoken.get();
            final String language = ttsLanguage.get();
            try {
                audio =
                        runner.call(
                                Duration.ofSeconds(settings.ttsTimeoutSeconds()),
                                () ->
                                        speech.synthesize(
                                                text,
                                                language,
                                                settings.ttsModel(),
                                                settings.ttsSpeaker(),
                                                settings.ttsCodec()));
            } catch (TimeoutException e) {
                metrics.count(VoiceNoteOutcome.VOICE_REPLY_TTS_TIMEOUT);
                log.warn("WhatsApp voice reply: text-to-speech timed out (sender {})", senderHash);
                return;
            } catch (RejectedExecutionException e) {
                metrics.count(VoiceNoteOutcome.OVERLOADED);
                return;
            } catch (Exception e) {
                metrics.count(VoiceNoteOutcome.VOICE_REPLY_TTS_FAILED);
                log.warn(
                        "WhatsApp voice reply: text-to-speech failed (sender {}): {}",
                        senderHash,
                        safeMessage(e));
                return;
            }
            if (audio == null || audio.length == 0) {
                metrics.count(VoiceNoteOutcome.VOICE_REPLY_TTS_FAILED);
                return;
            }
            AudioFormatSniffer.Kind kind = AudioFormatSniffer.sniff(audio);
            if (!kind.whatsAppAccepts()) {
                // Sarvam answered in a container WhatsApp rejects (typically WAV). Transcoding
                // would need
                // ffmpeg or a new dependency, which this feature deliberately does not add: degrade
                // to text-only.
                metrics.count(VoiceNoteOutcome.VOICE_REPLY_FORMAT_UNSUPPORTED);
                log.warn(
                        "WhatsApp voice reply skipped: Sarvam returned {} audio, which WhatsApp does not accept (sender {})",
                        kind,
                        senderHash);
                return;
            }
            try {
                media.sendAudio(
                        credentials.phoneNumberId(),
                        credentials.accessToken(),
                        to,
                        audio,
                        kind.mime(),
                        "reply." + kind.extension());
            } catch (RuntimeException e) {
                metrics.count(VoiceNoteOutcome.VOICE_REPLY_SEND_FAILED);
                log.warn(
                        "WhatsApp voice reply: send failed (sender {}): {}",
                        senderHash,
                        safeMessage(e));
                return;
            }
            metrics.count(VoiceNoteOutcome.VOICE_REPLY_SENT);
            log.info(
                    "WhatsApp voice reply sent (sender {}, bytes {}, language {})",
                    senderHash,
                    audio.length,
                    language);
        } catch (RuntimeException e) {
            metrics.count(VoiceNoteOutcome.VOICE_REPLY_SEND_FAILED);
            log.warn(
                    "WhatsApp voice reply failed unexpectedly (sender {}): {}",
                    senderHash,
                    e.getClass().getSimpleName());
        } finally {
            if (audio != null) {
                Arrays.fill(audio, (byte) 0);
            }
        }
    }

    @PreDestroy
    void shutdown() {
        runner.close();
    }

    // ---- helpers ----

    private Transcription fail(VoiceNoteOutcome outcome, String senderHash, String detail) {
        metrics.count(outcome);
        log.info(
                "WhatsApp voice note not processed: {} (sender {}): {}",
                outcome,
                senderHash,
                detail);
        return Transcription.failure(outcome);
    }

    private boolean dailyCapReached() {
        try {
            long used = speech.speechToTextCallsSince(Instant.now(clock).minus(Duration.ofDays(1)));
            return used >= settings.maxPerTenantPerDay();
        } catch (RuntimeException e) {
            // The count is a guard rail, not the product: if it cannot be read, do not drop a
            // customer's voice note.
            log.warn(
                    "Could not read the Sarvam call-log count for the voice-note daily cap: {}",
                    e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Only exception class + message of OUR layers; upstream bodies are already stripped by the
     * gateways.
     */
    private static String safeMessage(Exception e) {
        return e instanceof VoiceNoteException || e instanceof VoiceSpeechException
                ? e.getMessage()
                : e.getClass().getSimpleName();
    }

    /**
     * First 6 bytes of SHA-256(tenant + ":" + sender) as hex - stable per sender, not reversible to
     * a number.
     */
    static String hashSender(String tenantId, String sender) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((tenantId + ":" + sender).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (Exception e) {
            return "unhashed";
        }
    }

    /**
     * True when nothing was announced, or the announced hash (base64 as Meta's webhook shows, or
     * hex) matches.
     */
    static boolean sha256Matches(byte[] data, String announced) {
        if (announced == null || announced.isBlank()) {
            return true;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            String expected = announced.trim();
            String base64 = Base64.getEncoder().encodeToString(digest);
            return stripPadding(expected).equals(stripPadding(base64))
                    || expected.equalsIgnoreCase(HexFormat.of().formatHex(digest));
        } catch (Exception e) {
            return false;
        }
    }

    private static String stripPadding(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '=') {
            end--;
        }
        return value.substring(0, end);
    }
}
