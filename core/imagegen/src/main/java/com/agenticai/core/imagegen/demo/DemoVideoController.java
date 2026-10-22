package com.agenticai.core.imagegen.demo;

import com.agenticai.core.imagegen.ImageGenApiException;
import com.agenticai.core.imagegen.VertexVeoVideoGenerationClient;
import com.agenticai.core.imagegen.VideoGenerationClient;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.tenancy.demo.DemoGeneratedVideo;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionRateLimiter;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import com.agenticai.core.tenancy.demo.DemoVideoService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/demo/video} - short (max 15s by default), tightly-capped video generation inside
 * the no-signup sandbox. Same abuse-control shape and "never a raw error" rule as {@link
 * DemoImageController} - see that class's javadoc, which applies here verbatim - plus one extra
 * layer {@link DemoVideoService#isGlobalBudgetAvailable()} enforces: a platform-wide daily cap,
 * because video draws only from the pool (never a tenant BYOK key - there is no tenant) and is
 * billed per second.
 *
 * <p><b>Synchronous, with a bounded wait</b> - unlike the authenticated {@code
 * VideoGenerationController}, which is a proper enqueue/poll job (see that class's javadoc), this
 * endpoint holds the HTTP request open and polls the provider itself, up to {@link #MAX_WAIT}.
 * Simpler than adding a second job/status table for an anonymous, capability-URL-identified
 * feature, and acceptable here specifically because the clip is capped so short - a visitor
 * watching a 15s-clip spinner for up to a minute is a reasonable trade against building a whole
 * second async surface for a deliberately tiny feature.
 */
@ConditionalOnEditionFeature(EditionFeature.VIDEO_DEMO_MEDIA)
@RestController
@RequestMapping("/api/demo")
public class DemoVideoController {

    private static final Logger log = LoggerFactory.getLogger(DemoVideoController.class);
    private static final String DEMO_TOKEN_HEADER = "X-Demo-Token";
    private static final Duration MAX_WAIT = Duration.ofSeconds(100);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(4);

    private final DemoSessionService demoSessionService;
    private final DemoVideoService demoVideoService;
    private final DemoSessionRateLimiter rateLimiter;
    private final VertexVeoVideoGenerationClient veoClient;

    public DemoVideoController(
            DemoSessionService demoSessionService,
            DemoVideoService demoVideoService,
            DemoSessionRateLimiter rateLimiter,
            VertexVeoVideoGenerationClient veoClient) {
        this.demoSessionService = demoSessionService;
        this.demoVideoService = demoVideoService;
        this.rateLimiter = rateLimiter;
        this.veoClient = veoClient;
    }

    public record DemoVideoRequest(
            @Size(max = 1000, message = "prompt is too long") String prompt) {}

    public record DemoVideoResponse(
            String videoUrl,
            int videosUsed,
            int videoLimit,
            int durationSeconds,
            boolean simulated,
            String message) {}

    @PostMapping("/video")
    public ResponseEntity<DemoVideoResponse> generate(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken,
            @Valid @RequestBody DemoVideoRequest request,
            HttpServletRequest servletRequest) {
        int limit = demoVideoService.perSessionLimit();
        int duration = demoVideoService.maxDurationSeconds();

        Optional<DemoSession> found = demoSessionService.resolve(demoToken);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(
                            new DemoVideoResponse(
                                    null,
                                    0,
                                    limit,
                                    duration,
                                    true,
                                    "This sandbox isn't available any more. You can start a new one."));
        }
        DemoSession session = found.get();

        if (!demoVideoService.isEnabled() || !veoClient.isPoolAvailable()) {
            return sample(
                    session,
                    limit,
                    duration,
                    "Video generation is paused in the sandbox right now. "
                            + "Everything else here still works - and a free account unlocks the full creative toolset.");
        }
        if (session.isExpired(Instant.now())) {
            return sample(
                    session,
                    limit,
                    duration,
                    "Your sandbox has ended. Create a free account to carry on where you left off.");
        }
        // The 24h-editable / view-only split (demo.editable-hours) - same
        // guard the chat endpoint applies, same soft-sentence posture.
        if (demoSessionService.isReadOnly(session, Instant.now())) {
            return sample(
                    session,
                    limit,
                    duration,
                    "This sandbox is now view-only - your work stays right here until "
                            + "it expires. Create a free account to keep generating.");
        }
        if (demoVideoService.isCapReached(session)) {
            return sample(
                    session,
                    limit,
                    duration,
                    "You've used your sandbox video. Create a free account for more - "
                            + "the free plan gives you up to 30s clips.");
        }
        if (!demoVideoService.isGlobalBudgetAvailable()) {
            return sample(
                    session,
                    limit,
                    duration,
                    "Today's free video demo budget is used up across all visitors - come back tomorrow.");
        }

        String prompt = request.prompt() == null ? "" : request.prompt().trim();
        String refusal = demoVideoService.refusalFor(prompt);
        if (refusal != null) {
            return sample(session, limit, duration, refusal);
        }

        String clientIp = servletRequest.getRemoteAddr();
        if (!rateLimiter.tryAcquireVideo(clientIp)) {
            return sample(
                    session,
                    limit,
                    duration,
                    "You've generated a video from this device recently. "
                            + "Create a free account to keep going.");
        }

        // Spent before the call - see DemoImageController's identical ordering note.
        demoVideoService.recordAttempt(session);
        int used = session.getVideoCount() + 1;

        try {
            String model = "veo-3.0-fast-generate-001";
            String operationId =
                    veoClient
                            .submitFromPool(prompt, model, duration, null)
                            .orElseThrow(
                                    () ->
                                            new ImageGenApiException(
                                                    "No Vertex AI video pool slot is available."));

            Optional<VideoGenerationClient.GeneratedVideo> result = Optional.empty();
            Instant deadline = Instant.now().plus(MAX_WAIT);
            while (result.isEmpty() && Instant.now().isBefore(deadline)) {
                Thread.sleep(POLL_INTERVAL.toMillis());
                result = veoClient.pollFromPool(operationId, model);
            }
            VideoGenerationClient.GeneratedVideo video =
                    result.orElseThrow(
                            () ->
                                    new ImageGenApiException(
                                            "Video generation is taking longer than expected."));

            UUID videoId =
                    demoVideoService.store(
                            session,
                            video.bytes(),
                            video.contentType(),
                            prompt,
                            model,
                            duration,
                            clientIp);
            return ResponseEntity.ok(
                    new DemoVideoResponse(
                            "/api/demo/video/" + videoId, used, limit, duration, false, null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.ok(
                    new DemoVideoResponse(
                            null,
                            used,
                            limit,
                            duration,
                            true,
                            "Video generation was interrupted - your sandbox is still fine."));
        } catch (RuntimeException e) {
            log.warn(
                    "Demo video generation failed for sandbox {} - serving a sample",
                    session.getId(),
                    e);
            return ResponseEntity.ok(
                    new DemoVideoResponse(
                            null,
                            used,
                            limit,
                            duration,
                            true,
                            "The video model is busy right now, so there's nothing to show for this one. "
                                    + "Try again in a moment - your sandbox is still fine."));
        }
    }

    @GetMapping("/video/{id}")
    public ResponseEntity<byte[]> serve(@PathVariable("id") UUID id) {
        Optional<DemoGeneratedVideo> video = demoVideoService.find(id);
        if (video.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<byte[]> bytes = demoVideoService.readBytes(video.get());
        if (bytes.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(video.get().getContentType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate().immutable())
                .body(bytes.get());
    }

    private ResponseEntity<DemoVideoResponse> sample(
            DemoSession session, int limit, int duration, String message) {
        return ResponseEntity.ok(
                new DemoVideoResponse(
                        null, session.getVideoCount(), limit, duration, true, message));
    }
}
