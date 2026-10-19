package com.agenticai.core.tenancy.demo;

import com.agenticai.core.storage.FileStorageService;
import com.agenticai.core.tenancy.PlatformSettingsService;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Everything about demo image generation EXCEPT the provider call itself:
 * the limits, the pre-flight refusals, storing the bytes, and resolving a
 * capability URL back to those bytes.
 *
 * <h2>Why the provider call is not in here</h2>
 * Module graph, nothing deeper. {@code core/tenancy} does not depend on
 * {@code core/providers}, so it cannot reach the platform-hosted LLM pool's
 * credential - and inverting an SPI purely to host one HTTP call would be the
 * ceremony {@code DemoChatController}'s javadoc already declined to pay once.
 * So the split is: this class owns policy and persistence (both of which
 * genuinely belong beside {@link DemoSessionService}), and {@code
 * com.agenticai.core.imagegen.demo.DemoImageController} - which can see both
 * sides - owns the orchestration.
 *
 * <h2>The limits, and why images get their own</h2>
 * A FREE-tier chat turn on the hosted pool costs a rounding error. An image
 * generation costs real, per-call money on a key the PLATFORM pays for, from
 * an endpoint with no login. That asymmetry is why none of the existing demo
 * caps are reused here:
 *
 * <ul>
 *   <li>{@code demo.image.enabled} - the kill switch. Distinct from {@code
 *       demo.enabled} so images can be turned off in seconds during a cost
 *       spike without taking the whole shop window down with them.</li>
 *   <li>{@code demo.image.per-session-limit} (default <b>2</b>) - the hard
 *       per-sandbox cap, against chat's 20.</li>
 *   <li>{@code demo.image.max-prompt-length} (default 300) - short on
 *       purpose. A useful image prompt is a sentence; a 2000-character one is
 *       either a prompt-injection attempt or someone pasting a novel.</li>
 *   <li>{@code demo.image.model} (default {@code gemini-2.5-flash-image}) -
 *       settable so an operator can move to a cheaper model, or off a
 *       deprecated one, without a redeploy. <b>Image models only</b>: video
 *       models are billed per second of output and are categorically not
 *       something to expose to anonymous callers.</li>
 *   <li>{@code demo.image.blocked-terms} - operator additions to {@link
 *       DemoImagePromptGuard}'s built-in screen.</li>
 * </ul>
 *
 * Every one of them reads live from {@link PlatformSettingsService} with the
 * {@code app.demo.image.*} property as the fallback default, which is the
 * same "tunable without a redeploy" shape {@link DemoSessionService} and
 * {@link DemoSessionRateLimiter} already use.
 */
@Service
public class DemoImageService {

    private static final Logger log = LoggerFactory.getLogger(DemoImageService.class);

    /** Object-storage prefix. Distinct from imagegen's {@code generated-images/} so a demo purge never has to distinguish them by anything but the key. */
    private static final String OBJECT_KEY_PREFIX = "demo-images/";

    /**
     * Storage-layer tenant isolation (infra audit fix, 2026-07-27) - {@link
     * #store} runs with NO {@code TenantContext} at all (see {@link
     * DemoGeneratedImage}'s javadoc: this feature is identified by an {@code
     * X-Demo-Token} header, resolved straight to a {@code control_plane} row,
     * never a JWT/schema), so {@code S3FileStorageService}'s normal
     * TenantContext-based key-prefixing (see that class's javadoc) never
     * fires for this call path. Every OTHER object-storage caller in this
     * codebase gets that prefixing for free and needed no call-site change;
     * this one explicit exception embeds the demo sandbox's own schema name
     * (the same value a real tenant's TenantContext would have carried) right
     * in the key, so each sandbox's images still land under their own
     * distinguishable segment of {@code demo-images/} instead of one flat,
     * unscoped bucket of every visitor's images.
     */
    private static String tenantScopedObjectKey(DemoSession session, String extension) {
        return OBJECT_KEY_PREFIX + session.getTenantSchema() + "/" + UUID.randomUUID() + extension;
    }

    /**
     * Hard ceiling on what will be accepted back from the provider and pushed
     * into object storage, independent of any setting. A 1024x1024 PNG is
     * comfortably under 2 MB; anything an order of magnitude past that is a
     * malformed or hostile response, not an image this feature asked for.
     */
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;

    private final DemoSessionRecorder recorder;
    private final DemoGeneratedImageRepository imageRepository;
    private final FileStorageService fileStorageService;
    private final PlatformSettingsService platformSettings;
    // Optional SPI: whether the platform image pool can actually serve a call
    // (implemented in core/imagegen, which can see the pool credential
    // resolver; absent there -> treat as available, per its javadoc).
    private final Optional<DemoImageAvailability> imageAvailability;
    private final boolean defaultEnabled;
    private final int defaultPerSessionLimit;
    private final int defaultMaxPromptLength;
    private final String defaultModel;
    private final int defaultLifetimeDays;

    public DemoImageService(
        DemoSessionRecorder recorder,
        DemoGeneratedImageRepository imageRepository,
        FileStorageService fileStorageService,
        PlatformSettingsService platformSettings,
        Optional<DemoImageAvailability> imageAvailability,
        @Value("${app.demo.image.enabled:true}") boolean defaultEnabled,
        @Value("${app.demo.image.per-session-limit:2}") int defaultPerSessionLimit,
        @Value("${app.demo.image.max-prompt-length:300}") int defaultMaxPromptLength,
        @Value("${app.demo.image.model:gemini-2.5-flash-image}") String defaultModel,
        // Same key/default DemoSessionService reads - an image generation is
        // activity too, and must extend the sandbox's deadline the same way
        // a chat turn does (DemoSession#touchActivity).
        @Value("${app.demo.lifetime-days:5}") int defaultLifetimeDays
    ) {
        this.recorder = recorder;
        this.imageRepository = imageRepository;
        this.fileStorageService = fileStorageService;
        this.platformSettings = platformSettings;
        this.imageAvailability = imageAvailability == null ? Optional.empty() : imageAvailability;
        this.defaultEnabled = defaultEnabled;
        this.defaultPerSessionLimit = defaultPerSessionLimit;
        this.defaultMaxPromptLength = defaultMaxPromptLength;
        this.defaultModel = defaultModel;
        this.defaultLifetimeDays = defaultLifetimeDays;
    }

    // --- settings -------------------------------------------------------

    public boolean isEnabled() {
        return platformSettings.getBoolean("demo.image.enabled", defaultEnabled);
    }

    /**
     * Whether the pool behind the endpoint can serve a call at all. With no
     * provider row configured this is false FOREVER, not "busy" - and the
     * difference matters: a permanently-declining button must read as paused
     * (and be hidden, via the session response) rather than invite attempts
     * that each burn one of the sandbox's two images.
     */
    public boolean providerAvailable() {
        return imageAvailability.map(DemoImageAvailability::available).orElse(true);
    }

    /**
     * The offerability check: switched on AND actually able to produce an
     * image. This is what {@code imageEnabled} on the session response and
     * the controller's pre-flight both mean — the /try page hides the
     * affordance entirely rather than present a button that can only decline
     * (demoClient.ts's own contract).
     */
    public boolean canServe() {
        return isEnabled() && providerAvailable();
    }

    public int perSessionLimit() {
        return Math.max(0, platformSettings.getInt("demo.image.per-session-limit", defaultPerSessionLimit));
    }

    public int maxPromptLength() {
        return Math.max(1, platformSettings.getInt("demo.image.max-prompt-length", defaultMaxPromptLength));
    }

    public String model() {
        String configured = platformSettings.getString("demo.image.model", defaultModel);
        return configured == null || configured.isBlank() ? defaultModel : configured.trim();
    }

    public boolean isCapReached(DemoSession session) {
        return session.getImageCount() >= perSessionLimit();
    }

    // --- pre-flight -----------------------------------------------------

    /**
     * The single "may this prompt be sent upstream?" check. Length first (it
     * is free), then {@link DemoImagePromptGuard}.
     *
     * @return {@code null} to proceed, otherwise the visitor-facing sentence
     *     to return with {@code simulated: true}. Never an exception: the
     *     sandbox's cardinal rule is that nothing here surfaces as an error.
     */
    public String refusalFor(String prompt) {
        String trimmed = prompt == null ? "" : prompt.trim();
        int max = maxPromptLength();
        if (trimmed.length() > max) {
            return "That prompt is a bit long for the sandbox - keep it under " + max
                + " characters and describe one image.";
        }
        return DemoImagePromptGuard.refusalFor(trimmed, platformSettings.getString("demo.image.blocked-terms", ""));
    }

    /** Spends one of the sandbox's images up-front - see {@link DemoSessionRecorder#recordImageAttempt}. */
    public void recordAttempt(DemoSession session) {
        int lifetimeDays = platformSettings.getInt("demo.lifetime-days", defaultLifetimeDays);
        java.time.Instant now = java.time.Instant.now();
        recorder.recordImageAttempt(session.getId(), now, now.plus(java.time.Duration.ofDays(lifetimeDays)));
    }

    // --- persistence ----------------------------------------------------

    /**
     * Uploads the bytes to object storage and records the control-plane
     * pointer.
     *
     * @return the new row's id - the caller turns it into
     *     {@code /api/demo/image/{id}}, which IS the capability URL (see
     *     {@link DemoGeneratedImage}).
     * @throws IllegalArgumentException if the provider handed back something
     *     that is not a plausible image. Caught by the controller and
     *     degraded to a sample, like every other failure on this path.
     */
    public UUID store(DemoSession session, byte[] bytes, String contentType, String prompt, String model, String ip) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("empty image payload");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("image payload of " + bytes.length + " bytes exceeds the demo ceiling");
        }
        String safeType = normalizeContentType(contentType);
        String objectKey = tenantScopedObjectKey(session, extensionFor(safeType));
        fileStorageService.put(objectKey, bytes, safeType);
        return recorder.recordImageRow(
            session.getId(), objectKey, safeType, truncatePrompt(prompt), model, ip
        );
    }

    /** Capability-URL resolution for {@code GET /api/demo/image/{id}}. */
    public Optional<DemoGeneratedImage> find(UUID id) {
        return id == null ? Optional.empty() : imageRepository.findById(id);
    }

    /** @return the stored bytes, or empty if object storage cannot produce them (a serving failure, not a generation one). */
    public Optional<byte[]> readBytes(DemoGeneratedImage image) {
        try (var in = fileStorageService.get(image.getObjectKey())) {
            return Optional.of(in.readAllBytes());
        } catch (Exception e) {
            // Object key logged, never the prompt - and certainly never a key
            // or token. A missing object is an operational problem to see in
            // the logs, not something to surface to the visitor.
            log.warn("Demo image {} could not be read from object storage (key {})", image.getId(), image.getObjectKey(), e);
            return Optional.empty();
        }
    }

    // --- helpers --------------------------------------------------------

    /**
     * Only ever emits one of three known-safe image types. The provider's
     * declared {@code mimeType} is attacker-influenced input in the sense
     * that it ends up in a {@code Content-Type} header on a response this app
     * serves - letting an arbitrary string through (e.g. {@code text/html})
     * would turn the image endpoint into a stored-XSS vector on this origin.
     */
    private static String normalizeContentType(String raw) {
        if (raw == null) {
            return "image/png";
        }
        String lower = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("image/jpeg") || lower.startsWith("image/jpg")) {
            return "image/jpeg";
        }
        if (lower.startsWith("image/webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".png";
        };
    }

    /** The column is TEXT, but an anonymous free-text field has no business being unbounded on disk either. */
    private static String truncatePrompt(String prompt) {
        String value = prompt == null ? "" : prompt.trim();
        return value.length() > 500 ? value.substring(0, 500) : value;
    }
}
