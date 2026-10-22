package com.agenticai.core.agents.demo;

import com.agenticai.core.agentspi.MusicComposer;
import com.agenticai.core.providers.PlatformToolCredentialService;
import com.agenticai.core.providers.PlatformToolKey;
import com.agenticai.core.providers.modelcost.MediaModality;
import com.agenticai.core.providers.modelcost.MediaModelCostService;
import com.agenticai.core.tenancy.demo.DemoGeneratedMusic;
import com.agenticai.core.tenancy.demo.DemoMusicService;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionRateLimiter;
import com.agenticai.core.tenancy.demo.DemoSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 * {@code POST /api/demo/music} + {@code GET /api/demo/music/{id}} - a short music clip inside the
 * no-signup sandbox. The music sibling of {@code DemoImageController}/{@code DemoVideoController},
 * with the same contract:
 *
 * <ul>
 *   <li><b>Never an error to a visitor.</b> Every refusal and every failure is HTTP 200 with {@code
 *       simulated: true} and a sentence; the only non-200 is 404 for an unknown sandbox token.
 *   <li><b>Every control before the billable call, cheapest first:</b> token, kill switch +
 *       platform key present, expiry, read-only, per-sandbox cap, platform-wide daily budget,
 *       prompt screen, and only then the per-IP limiter ({@link
 *       DemoSessionRateLimiter#tryAcquireMusic}) - the same limiter, mechanism and ordering {@code
 *       /api/demo/image} uses, so a refused prompt costs the visitor neither allowance nor a
 *       rate-limit token. The allowance is spent BEFORE the provider call.
 *   <li><b>Capability URL.</b> {@code GET /music/{id}} takes no token; the random v4 UUID is the
 *       credential, exactly as for demo images.
 * </ul>
 *
 * <p>It lives in {@code core/agents} rather than beside {@link DemoMusicService} for the
 * module-graph reason {@code DemoImageService}'s javadoc gives: this is the first module that can
 * see the sandbox policy ({@code core/tenancy}), the platform ElevenLabs credential ({@code
 * core/providers}) and the ElevenLabs client at once.
 *
 * <p><b>Cost.</b> $0.15 per generated minute (ElevenLabs list price, 2026-09-20): a default 15s
 * clip is ~$0.04, and {@code demo.music.global-daily-cap} (40) bounds the whole endpoint at
 * ~$1.50/day. The response names the model and says it was the low-cost pick, so the page can tell
 * the visitor so honestly.
 *
 * <p><b>No composer.</b> The only {@link MusicComposer} is the ElevenLabs one, which a lean (personal)
 * install does not register (edition feature {@code CLOUD_SPEECH}). It is therefore injected lazily:
 * with none present the controller still exists and answers exactly like a missing platform key
 * (HTTP 200, {@code simulated: true}, "paused"), and {@code /music/status} says {@code enabled:
 * false}.
 */
@RestController
@RequestMapping("/api/demo")
public class DemoMusicController {

    private static final Logger log = LoggerFactory.getLogger(DemoMusicController.class);
    private static final String DEMO_TOKEN_HEADER = "X-Demo-Token";
    private static final String PROVIDER = "ELEVENLABS";

    private final DemoSessionService demoSessionService;
    private final DemoMusicService demoMusicService;
    private final DemoSessionRateLimiter rateLimiter;
    private final ObjectProvider<MusicComposer> musicComposer;
    private final PlatformToolCredentialService platformCredentials;
    private final MediaModelCostService modelCosts;

    public DemoMusicController(
            DemoSessionService demoSessionService,
            DemoMusicService demoMusicService,
            DemoSessionRateLimiter rateLimiter,
            ObjectProvider<MusicComposer> musicComposer,
            PlatformToolCredentialService platformCredentials,
            MediaModelCostService modelCosts) {
        this.demoSessionService = demoSessionService;
        this.demoMusicService = demoMusicService;
        this.rateLimiter = rateLimiter;
        this.musicComposer = musicComposer;
        this.platformCredentials = platformCredentials;
        this.modelCosts = modelCosts;
    }

    public record DemoMusicRequest(
            @Size(max = 1000, message = "prompt is too long") String prompt) {}

    /**
     * @param musicUrl API-relative capability URL, {@code null} when {@code simulated}
     * @param model the model that composed the clip, {@code null} when {@code simulated}
     * @param lowCostModel always true for a real clip: the sandbox only ever uses the cheapest
     *     listed model
     */
    public record DemoMusicResponse(
            String musicUrl,
            int musicUsed,
            int musicLimit,
            int durationSeconds,
            boolean simulated,
            String message,
            String model,
            boolean lowCostModel) {}

    @PostMapping("/music")
    public ResponseEntity<DemoMusicResponse> generate(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken,
            @Valid @RequestBody DemoMusicRequest request,
            HttpServletRequest servletRequest) {
        int limit = demoMusicService.perSessionLimit();
        int seconds = demoMusicService.clipSeconds();

        Optional<DemoSession> found = demoSessionService.resolve(demoToken);
        if (found.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(
                            new DemoMusicResponse(
                                    null,
                                    0,
                                    limit,
                                    seconds,
                                    true,
                                    "This sandbox isn't available any more. You can start a new one.",
                                    null,
                                    false));
        }
        DemoSession session = found.get();

        MusicComposer composer = musicComposer.getIfAvailable();
        Optional<String> platformKey =
                composer != null && demoMusicService.isEnabled()
                        ? platformCredentials.resolveActiveApiKey(PlatformToolKey.ELEVENLABS)
                        : Optional.empty();
        if (platformKey.isEmpty()) {
            return sample(
                    session,
                    limit,
                    seconds,
                    "Music generation is paused in the sandbox right now. "
                            + "Everything else here still works - and a free account unlocks the full creative toolset.");
        }
        if (session.isExpired(Instant.now())) {
            return sample(
                    session,
                    limit,
                    seconds,
                    "Your sandbox has ended. Create a free account to carry on where you left off.");
        }
        if (demoSessionService.isReadOnly(session, Instant.now())) {
            return sample(
                    session,
                    limit,
                    seconds,
                    "This sandbox is now view-only - your work stays right here until "
                            + "it expires. Create a free account to keep generating.");
        }
        if (demoMusicService.isCapReached(session)) {
            return sample(
                    session,
                    limit,
                    seconds,
                    "You've used your sandbox music clip. Create a free account for more, and longer ones.");
        }
        if (!demoMusicService.isGlobalBudgetAvailable()) {
            return sample(
                    session,
                    limit,
                    seconds,
                    "Today's free music demo budget is used up across all visitors - come back tomorrow.");
        }

        String prompt = request.prompt() == null ? "" : request.prompt().trim();
        String refusal = demoMusicService.refusalFor(prompt);
        if (refusal != null) {
            return sample(session, limit, seconds, refusal);
        }

        String clientIp = servletRequest.getRemoteAddr();
        if (!rateLimiter.tryAcquireMusic(clientIp)) {
            return sample(
                    session,
                    limit,
                    seconds,
                    "You've generated music from this device recently. "
                            + "Create a free account to keep going.");
        }

        // Spent before the call - see DemoImageController's identical ordering note.
        demoMusicService.recordAttempt(session);
        int used = session.getMusicCount() + 1;

        try {
            ComposedClip clip = compose(composer, prompt, seconds, platformKey.get());
            UUID musicId =
                    demoMusicService.store(
                            session, clip.bytes(), prompt, clip.model(), seconds, clientIp);
            return ResponseEntity.ok(
                    new DemoMusicResponse(
                            "/api/demo/music/" + musicId,
                            used,
                            limit,
                            seconds,
                            false,
                            null,
                            clip.model(),
                            true));
        } catch (RuntimeException e) {
            log.warn(
                    "Demo music generation failed for sandbox {} - serving a sample",
                    session.getId(),
                    e);
            return ResponseEntity.ok(
                    new DemoMusicResponse(
                            null,
                            used,
                            limit,
                            seconds,
                            true,
                            "The music model is busy right now, so there's nothing to play for this one. "
                                    + "Try again in a moment - your sandbox is still fine.",
                            null,
                            false));
        }
    }

    /**
     * What the page needs to decide whether to offer a music mode at all - free to call, spends
     * nothing.
     */
    public record DemoMusicStatus(
            boolean enabled, int musicUsed, int musicLimit, int clipSeconds) {}

    /**
     * {@code enabled} is the same three-part truth {@link #generate} checks first (kill switch, a
     * platform key, a non-zero allowance), so the page never offers a mode whose every request
     * would come back "paused". An unknown token is simply "not enabled" - no 404, nothing to
     * handle.
     */
    @GetMapping("/music/status")
    public DemoMusicStatus status(
            @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken) {
        int limit = demoMusicService.perSessionLimit();
        int seconds = demoMusicService.clipSeconds();
        Optional<DemoSession> found = demoSessionService.resolve(demoToken);
        if (found.isEmpty()) {
            return new DemoMusicStatus(false, 0, limit, seconds);
        }
        boolean enabled =
                limit > 0
                        && musicComposer.getIfAvailable() != null
                        && demoMusicService.isEnabled()
                        && platformCredentials
                                .resolveActiveApiKey(PlatformToolKey.ELEVENLABS)
                                .isPresent();
        return new DemoMusicStatus(enabled, found.get().getMusicCount(), limit, seconds);
    }

    @GetMapping("/music/{id}")
    public ResponseEntity<byte[]> serve(@PathVariable("id") UUID id) {
        Optional<DemoGeneratedMusic> music = demoMusicService.find(id);
        if (music.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<byte[]> bytes = demoMusicService.readBytes(music.get());
        if (bytes.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(music.get().getContentType()))
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate().immutable())
                .body(bytes.get());
    }

    private record ComposedClip(byte[] bytes, String model) {}

    /**
     * Cheapest listed music model first; a model/request-shaped 4xx moves to the next one, anything
     * else stops - same rule as {@code MusicGenerationService}.
     */
    private ComposedClip compose(
            MusicComposer composer, String prompt, int seconds, String apiKey) {
        List<MediaModelCostService.ModelChoice> candidates =
                modelCosts.cheapestFirst(MediaModality.MUSIC, PROVIDER);
        MusicComposer.ComposeFailedException lastFailure = null;
        for (MediaModelCostService.ModelChoice candidate : candidates) {
            try {
                return new ComposedClip(
                        composer.compose(
                                prompt, seconds * 1000, candidate.modelId(), false, apiKey),
                        candidate.modelId());
            } catch (MusicComposer.ComposeFailedException e) {
                lastFailure = e;
                int status = e.getUpstreamStatus();
                if (status != 400 && status != 404 && status != 422) {
                    throw e;
                }
            }
        }
        throw lastFailure != null
                ? lastFailure
                : new IllegalStateException("No music model is listed in the cost table.");
    }

    private ResponseEntity<DemoMusicResponse> sample(
            DemoSession session, int limit, int seconds, String message) {
        return ResponseEntity.ok(
                new DemoMusicResponse(
                        null, session.getMusicCount(), limit, seconds, true, message, null, false));
    }
}
