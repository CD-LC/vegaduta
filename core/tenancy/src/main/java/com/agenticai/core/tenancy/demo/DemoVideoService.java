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
 * Video counterpart of {@link DemoImageService} - same split (policy +
 * persistence here, the provider call in {@code
 * com.agenticai.core.imagegen.demo.DemoVideoController}, which can see both
 * this and {@code core/providers}). See that class's javadoc for the full
 * module-graph reasoning; it applies unchanged here.
 *
 * <h2>Why video's limits are so much tighter than image's</h2>
 * An image generation costs a rounding error per call. Video (Veo-class
 * models) is billed <b>per second of output</b>, so the caps below are
 * deliberately stingier at every layer, on top of everything {@link
 * DemoImageService} already does:
 * <ul>
 *   <li>{@code demo.video.enabled} - its own kill switch, independent of
 *       {@code demo.image.enabled}.</li>
 *   <li>{@code demo.video.per-session-limit} (default <b>1</b>) - effectively
 *       "one clip, ever, per sandbox".</li>
 *   <li>{@code demo.video.max-duration-seconds} (default <b>15</b>) - the
 *       hard ceiling this whole feature exists to enforce; see the approved
 *       plan's note on this being a deliberately revisitable default.</li>
 *   <li>{@code demo.video.global-daily-cap} (default <b>20</b>) - unlike
 *       image generation, video draws from ONE platform-wide daily counter
 *       across every anonymous visitor, not just a per-session/per-IP limit -
 *       {@link #isGlobalBudgetAvailable} is the {@code budget_exhausted}
 *       backstop the same way {@code DemoMagicWorkflow}'s daily budget
 *       already backstops the live-studio path.</li>
 * </ul>
 */
@Service
public class DemoVideoService {

    private static final Logger log = LoggerFactory.getLogger(DemoVideoService.class);
    private static final String OBJECT_KEY_PREFIX = "demo-videos/";
    private static final int MAX_VIDEO_BYTES = 60 * 1024 * 1024;

    private final DemoSessionRecorder recorder;
    private final DemoGeneratedVideoRepository videoRepository;
    private final FileStorageService fileStorageService;
    private final PlatformSettingsService platformSettings;
    private final boolean defaultEnabled;
    private final int defaultPerSessionLimit;
    private final int defaultMaxPromptLength;
    private final int defaultMaxDurationSeconds;
    private final int defaultGlobalDailyCap;
    private final int defaultLifetimeDays;

    public DemoVideoService(
        DemoSessionRecorder recorder,
        DemoGeneratedVideoRepository videoRepository,
        FileStorageService fileStorageService,
        PlatformSettingsService platformSettings,
        @Value("${app.demo.video.enabled:false}") boolean defaultEnabled,
        @Value("${app.demo.video.per-session-limit:1}") int defaultPerSessionLimit,
        @Value("${app.demo.video.max-prompt-length:300}") int defaultMaxPromptLength,
        @Value("${app.demo.video.max-duration-seconds:15}") int defaultMaxDurationSeconds,
        @Value("${app.demo.video.global-daily-cap:20}") int defaultGlobalDailyCap,
        @Value("${app.demo.lifetime-days:5}") int defaultLifetimeDays
    ) {
        this.recorder = recorder;
        this.videoRepository = videoRepository;
        this.fileStorageService = fileStorageService;
        this.platformSettings = platformSettings;
        this.defaultEnabled = defaultEnabled;
        this.defaultPerSessionLimit = defaultPerSessionLimit;
        this.defaultMaxPromptLength = defaultMaxPromptLength;
        this.defaultMaxDurationSeconds = defaultMaxDurationSeconds;
        this.defaultGlobalDailyCap = defaultGlobalDailyCap;
        this.defaultLifetimeDays = defaultLifetimeDays;
    }

    public boolean isEnabled() {
        return platformSettings.getBoolean("demo.video.enabled", defaultEnabled);
    }

    public int perSessionLimit() {
        return Math.max(0, platformSettings.getInt("demo.video.per-session-limit", defaultPerSessionLimit));
    }

    public int maxPromptLength() {
        return Math.max(1, platformSettings.getInt("demo.video.max-prompt-length", defaultMaxPromptLength));
    }

    public int maxDurationSeconds() {
        return Math.max(1, platformSettings.getInt("demo.video.max-duration-seconds", defaultMaxDurationSeconds));
    }

    public boolean isCapReached(DemoSession session) {
        return session.getVideoCount() >= perSessionLimit();
    }

    /** The platform-wide daily budget backstop - see the class javadoc. */
    public boolean isGlobalBudgetAvailable() {
        int cap = Math.max(0, platformSettings.getInt("demo.video.global-daily-cap", defaultGlobalDailyCap));
        long usedToday = videoRepository.countByCreatedAtAfter(Instant.now().minus(Duration.ofDays(1)));
        return usedToday < cap;
    }

    /** @return {@code null} to proceed, otherwise the visitor-facing refusal sentence. */
    public String refusalFor(String prompt) {
        String trimmed = prompt == null ? "" : prompt.trim();
        int max = maxPromptLength();
        if (trimmed.length() > max) {
            return "That prompt is a bit long for a 15-second sandbox clip - keep it under " + max + " characters and describe one scene.";
        }
        return DemoImagePromptGuard.refusalFor(trimmed, platformSettings.getString("demo.video.blocked-terms", ""));
    }

    public void recordAttempt(DemoSession session) {
        int lifetimeDays = platformSettings.getInt("demo.lifetime-days", defaultLifetimeDays);
        Instant now = Instant.now();
        recorder.recordVideoAttempt(session.getId(), now, now.plus(Duration.ofDays(lifetimeDays)));
    }

    public UUID store(DemoSession session, byte[] bytes, String contentType, String prompt, String model, int durationSeconds, String ip) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("empty video payload");
        }
        if (bytes.length > MAX_VIDEO_BYTES) {
            throw new IllegalArgumentException("video payload of " + bytes.length + " bytes exceeds the demo ceiling");
        }
        String safeType = "video/webm".equals(contentType) ? "video/webm" : "video/mp4";
        String objectKey = OBJECT_KEY_PREFIX + session.getTenantSchema() + "/" + UUID.randomUUID() + (safeType.equals("video/webm") ? ".webm" : ".mp4");
        fileStorageService.put(objectKey, bytes, safeType);
        return recorder.recordVideoRow(session.getId(), objectKey, safeType, truncatePrompt(prompt), model, durationSeconds, ip);
    }

    public Optional<DemoGeneratedVideo> find(UUID id) {
        return id == null ? Optional.empty() : videoRepository.findById(id);
    }

    public Optional<byte[]> readBytes(DemoGeneratedVideo video) {
        try (var in = fileStorageService.get(video.getObjectKey())) {
            return Optional.of(in.readAllBytes());
        } catch (Exception e) {
            log.warn("Demo video {} could not be read from object storage (key {})", video.getId(), video.getObjectKey(), e);
            return Optional.empty();
        }
    }

    private static String truncatePrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
