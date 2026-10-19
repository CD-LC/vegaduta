package com.agenticai.core.tenancy.demo;

import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Everything about demo music generation EXCEPT the provider call itself -
 * the music counterpart of {@link DemoImageService}/{@link DemoVideoService},
 * split the same way and for the same module-graph reason (see {@link
 * DemoImageService}'s javadoc): this class owns policy and persistence, and
 * {@code com.agenticai.core.agents.demo.DemoMusicController}, which can see
 * the platform ElevenLabs credential, owns the orchestration.
 *
 * <h2>The limits</h2>
 * Eleven Music bills per generated MINUTE ($0.15/min list, 2026-09-20), so the
 * clip length is the cost lever and every cap below multiplies against it. At
 * the defaults one clip is 15s = ~$0.04, and the platform-wide ceiling is
 * {@code global-daily-cap} x that = ~$1.50/day no matter how many visitors
 * arrive - the same "one shared daily counter" backstop video has.
 *
 * <ul>
 *   <li>{@code demo.music.enabled} (default true) - kill switch, distinct
 *       from {@code demo.enabled} and from image/video.</li>
 *   <li>{@code demo.music.per-session-limit} (default <b>1</b>).</li>
 *   <li>{@code demo.music.clip-seconds} (default <b>15</b>, hard-clamped to
 *       3-30 here regardless of the setting).</li>
 *   <li>{@code demo.music.global-daily-cap} (default <b>40</b>).</li>
 *   <li>{@code demo.music.max-prompt-length} (default 300) and {@code
 *       demo.music.blocked-terms}, screened by {@link DemoImagePromptGuard}
 *       exactly as image/video prompts are.</li>
 * </ul>
 */
@Service
public class DemoMusicService {

    private static final Logger log = LoggerFactory.getLogger(DemoMusicService.class);
    private static final String OBJECT_KEY_PREFIX = "demo-music/";
    private static final String MP3_CONTENT_TYPE = "audio/mpeg";

    /** A 30s 128kbps MP3 is ~0.5 MB; anything an order of magnitude past that is not what this feature asked for. */
    private static final int MAX_MUSIC_BYTES = 8 * 1024 * 1024;
    private static final int MIN_CLIP_SECONDS = 3;
    /** Independent of any setting: an operator typo must not turn an anonymous endpoint into a 10-minute-track generator. */
    private static final int MAX_CLIP_SECONDS = 30;

    private final DemoSessionRecorder recorder;
    private final DemoGeneratedMusicRepository musicRepository;
    private final FileStorageService fileStorageService;
    private final PlatformSettingsService platformSettings;
    private final boolean defaultEnabled;
    private final int defaultPerSessionLimit;
    private final int defaultMaxPromptLength;
    private final int defaultClipSeconds;
    private final int defaultGlobalDailyCap;
    private final int defaultLifetimeDays;

    public DemoMusicService(
        DemoSessionRecorder recorder,
        DemoGeneratedMusicRepository musicRepository,
        FileStorageService fileStorageService,
        PlatformSettingsService platformSettings,
        @Value("${app.demo.music.enabled:true}") boolean defaultEnabled,
        @Value("${app.demo.music.per-session-limit:1}") int defaultPerSessionLimit,
        @Value("${app.demo.music.max-prompt-length:300}") int defaultMaxPromptLength,
        @Value("${app.demo.music.clip-seconds:15}") int defaultClipSeconds,
        @Value("${app.demo.music.global-daily-cap:40}") int defaultGlobalDailyCap,
        @Value("${app.demo.lifetime-days:5}") int defaultLifetimeDays
    ) {
        this.recorder = recorder;
        this.musicRepository = musicRepository;
        this.fileStorageService = fileStorageService;
        this.platformSettings = platformSettings;
        this.defaultEnabled = defaultEnabled;
        this.defaultPerSessionLimit = defaultPerSessionLimit;
        this.defaultMaxPromptLength = defaultMaxPromptLength;
        this.defaultClipSeconds = defaultClipSeconds;
        this.defaultGlobalDailyCap = defaultGlobalDailyCap;
        this.defaultLifetimeDays = defaultLifetimeDays;
    }

    public boolean isEnabled() {
        return platformSettings.getBoolean("demo.music.enabled", defaultEnabled);
    }

    public int perSessionLimit() {
        return Math.max(0, platformSettings.getInt("demo.music.per-session-limit", defaultPerSessionLimit));
    }

    public int maxPromptLength() {
        return Math.max(1, platformSettings.getInt("demo.music.max-prompt-length", defaultMaxPromptLength));
    }

    public int clipSeconds() {
        int configured = platformSettings.getInt("demo.music.clip-seconds", defaultClipSeconds);
        return Math.min(MAX_CLIP_SECONDS, Math.max(MIN_CLIP_SECONDS, configured));
    }

    public boolean isCapReached(DemoSession session) {
        return session.getMusicCount() >= perSessionLimit();
    }

    /** Platform-wide daily ceiling across ALL sandboxes - the hard bound on what this endpoint can cost in a day. */
    public boolean isGlobalBudgetAvailable() {
        int cap = Math.max(0, platformSettings.getInt("demo.music.global-daily-cap", defaultGlobalDailyCap));
        long usedToday = musicRepository.countByCreatedAtAfter(Instant.now().minus(Duration.ofDays(1)));
        return usedToday < cap;
    }

    /** @return {@code null} to proceed, otherwise the visitor-facing sentence to return with {@code simulated: true}. Never an exception. */
    public String refusalFor(String prompt) {
        String trimmed = prompt == null ? "" : prompt.trim();
        if (trimmed.isEmpty()) {
            return "Describe the music you'd like - a mood, a genre, an instrument or two.";
        }
        int max = maxPromptLength();
        if (trimmed.length() > max) {
            return "That prompt is a bit long for a sandbox clip - keep it under " + max + " characters and describe one piece of music.";
        }
        return DemoImagePromptGuard.refusalFor(trimmed, platformSettings.getString("demo.music.blocked-terms", ""));
    }

    /** Spends the sandbox's music allowance up-front - see {@link DemoSessionRecorder#recordMusicAttempt}. */
    public void recordAttempt(DemoSession session) {
        int lifetimeDays = platformSettings.getInt("demo.lifetime-days", defaultLifetimeDays);
        Instant now = Instant.now();
        recorder.recordMusicAttempt(session.getId(), now, now.plus(Duration.ofDays(lifetimeDays)));
    }

    /**
     * @return the new row's id - {@code /api/demo/music/{id}} IS the capability URL.
     * @throws IllegalArgumentException if the provider handed back something implausible; the controller degrades it to a sample.
     */
    public UUID store(DemoSession session, byte[] bytes, String prompt, String model, int durationSeconds, String ip) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("empty music payload");
        }
        if (bytes.length > MAX_MUSIC_BYTES) {
            throw new IllegalArgumentException("music payload of " + bytes.length + " bytes exceeds the demo ceiling");
        }
        // The content type is fixed, never the provider's declared one - it
        // ends up in a Content-Type header on an unauthenticated response
        // (same stored-XSS reasoning as DemoImageService#normalizeContentType).
        String objectKey = OBJECT_KEY_PREFIX + session.getTenantSchema() + "/" + UUID.randomUUID() + ".mp3";
        fileStorageService.put(objectKey, bytes, MP3_CONTENT_TYPE);
        return recorder.recordMusicRow(session.getId(), objectKey, MP3_CONTENT_TYPE, truncatePrompt(prompt), model, durationSeconds, ip);
    }

    public Optional<DemoGeneratedMusic> find(UUID id) {
        return id == null ? Optional.empty() : musicRepository.findById(id);
    }

    public Optional<byte[]> readBytes(DemoGeneratedMusic music) {
        try (var in = fileStorageService.get(music.getObjectKey())) {
            return Optional.of(in.readAllBytes());
        } catch (Exception e) {
            log.warn("Demo music {} could not be read from object storage (key {})", music.getId(), music.getObjectKey(), e);
            return Optional.empty();
        }
    }

    private static String truncatePrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
