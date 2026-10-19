package com.agenticai.core.imagegen.demo;

import com.agenticai.core.imagegen.GeminiImageGenerationClient;
import com.agenticai.core.tenancy.demo.DemoGeneratedImage;
import com.agenticai.core.tenancy.demo.DemoImageService;
import com.agenticai.core.tenancy.demo.DemoSession;
import com.agenticai.core.tenancy.demo.DemoSessionRateLimiter;
import com.agenticai.core.tenancy.demo.DemoSessionService;
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
 * {@code POST /api/demo/image} (generate) and {@code GET /api/demo/image/{id}}
 * (serve) - AI image generation inside the no-signup sandbox.
 *
 * <p><b>Why it lives here and not in {@code core/tenancy}'s demo package:</b>
 * the same module-graph call {@code DemoChatController} documents. Generation
 * needs the platform-hosted pool credential ({@code core/providers}) and the
 * demo session/limits ({@code core/tenancy}) in one place; {@code
 * core/tenancy} cannot see {@code core/providers}, this module can see both.
 *
 * <h2>The two endpoints have DIFFERENT identity models, on purpose</h2>
 * <ul>
 *   <li>The POST is identified by {@code X-Demo-Token}, exactly like {@code
 *       /api/demo/chat}. That is the endpoint that spends money, so it is the
 *       one that must be attributable to a sandbox with an allowance.</li>
 *   <li>The GET is identified by <b>the image id itself</b>, because a
 *       browser cannot attach a custom header to an {@code <img src>}. The id
 *       is a random v4 UUID and possession of it is the credential - a
 *       capability URL, the same posture as a workflow webhook path. It is
 *       worth being precise about what that does and does not protect:
 *       guessing one is infeasible, but anyone the URL is shared with can
 *       fetch the image. For "an image an anonymous visitor asked a sandbox
 *       to draw", that is the correct amount of protection - there is no
 *       account, no personal data, and nothing to escalate to. It is
 *       deliberately NOT the posture {@code GeneratedImageController} takes
 *       for authenticated tenant images, which stay behind a real JWT.</li>
 * </ul>
 *
 * <h2>The hard requirement, inherited verbatim from the chat endpoint</h2>
 * <b>This never returns an error to the visitor.</b> Provider outage, missing
 * pool row, safety block, decode failure, storage failure, unexpected NPE -
 * every one of them lands on {@code simulated: true} plus a plain sentence,
 * and the {@code /try} page renders a labelled placeholder instead of an
 * image. A broken shop window is worse than no shop window.
 *
 * <h2>Abuse controls on the POST, in the order they run</h2>
 * <ol>
 *   <li>{@code demo.image.enabled} kill switch - and the sandbox must resolve
 *       from a live, unexpired token.</li>
 *   <li>Per-sandbox cap, default <b>2</b> ({@code
 *       demo.image.per-session-limit}).</li>
 *   <li>Prompt length + keyword screen - both free, both before any spend.</li>
 *   <li>Per-IP Redis limit, default 6/24h ({@code
 *       DemoSessionRateLimiter#tryAcquireImage}) - taken only for a request
 *       that is actually about to call the provider, so a refused prompt
 *       costs the visitor nothing.</li>
 *   <li>The allowance is spent BEFORE the call - see {@code
 *       DemoSessionRecorder#recordImageAttempt} for why a free failure is a
 *       farming vector.</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/demo")
public class DemoImageController {

    private static final Logger log = LoggerFactory.getLogger(DemoImageController.class);

    private static final String DEMO_TOKEN_HEADER = "X-Demo-Token";

    private final DemoSessionService demoSessionService;
    private final DemoImageService demoImageService;
    private final DemoSessionRateLimiter rateLimiter;
    private final GeminiImageGenerationClient imageClient;

    public DemoImageController(
        DemoSessionService demoSessionService,
        DemoImageService demoImageService,
        DemoSessionRateLimiter rateLimiter,
        GeminiImageGenerationClient imageClient
    ) {
        this.demoSessionService = demoSessionService;
        this.demoImageService = demoImageService;
        this.rateLimiter = rateLimiter;
        this.imageClient = imageClient;
    }

    /**
     * @param prompt bounded at the DTO level too (1000) purely so an absurd
     *     body is rejected before it reaches the real, settings-driven cap in
     *     {@code DemoImageService#refusalFor} - which is the one that
     *     produces a friendly sentence rather than a validation error.
     */
    public record DemoImageRequest(
        @Size(max = 1000, message = "prompt is too long") String prompt
    ) {
    }

    /**
     * @param imageUrl the capability URL to render, or {@code null} when
     *     {@code simulated} is true - there is no image to show and the page
     *     renders its own placeholder rather than being handed a URL that
     *     404s. <b>API-relative</b> ({@code /api/demo/image/<id>}), not
     *     absolute: web and core are served from different subdomains
     *     ({@code app.} / {@code api.}), and the server has no business
     *     guessing its own public origin - {@code demoClient.ts} joins this
     *     onto the same {@code NEXT_PUBLIC_API_BASE_URL} every other demo
     *     call already uses.
     * @param simulated true whenever no real image was produced, for ANY
     *     reason (refusal, quota, outage). The UI must label it - presenting
     *     a placeholder as generated output would be exactly the fabricated
     *     demo this whole surface exists to replace.
     * @param message the visitor-facing sentence. Always present when
     *     {@code simulated}; null on success.
     */
    public record DemoImageResponse(
        String imageUrl,
        int imagesUsed,
        int imageLimit,
        boolean simulated,
        String message
    ) {
    }

    @PostMapping("/image")
    public ResponseEntity<DemoImageResponse> generate(
        @RequestHeader(value = DEMO_TOKEN_HEADER, required = false) String demoToken,
        @Valid @RequestBody DemoImageRequest request,
        HttpServletRequest servletRequest
    ) {
        int limit = demoImageService.perSessionLimit();

        Optional<DemoSession> found = demoSessionService.resolve(demoToken);
        if (found.isEmpty()) {
            // The one genuinely non-generation case: there is no sandbox. 404
            // so the page resets itself cleanly, matching /api/demo/chat.
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new DemoImageResponse(
                null, 0, limit, true, "This sandbox isn't available any more. You can start a new one."
            ));
        }
        DemoSession session = found.get();

        // canServe() = the flag AND a pool that can answer. When no provider
        // row exists the old code still spent the allowance below
        // (recordAttempt runs before the call, deliberately) and every click
        // burned one of the visitor's two images on a guaranteed failure.
        // Declining here is free: no rate-limit token, no allowance spent.
        if (!demoImageService.canServe()) {
            return sample(session, limit, "Image generation is paused in the sandbox right now. "
                + "Everything else here still works - and a free account gets you the full toolset.");
        }
        if (session.isExpired(Instant.now())) {
            return sample(session, limit, "Your sandbox has ended. Create a free account to carry on where you left off.");
        }
        // The 24h-editable / view-only split (demo.editable-hours) - same
        // guard the chat endpoint applies, same soft-sentence posture.
        if (demoSessionService.isReadOnly(session, Instant.now())) {
            return sample(session, limit, "This sandbox is now view-only - your work stays right here until it "
                + "expires. Create a free account to keep generating.");
        }
        if (demoImageService.isCapReached(session)) {
            return sample(session, limit, "You've used " + limit + " of " + limit
                + " sandbox images. Create a free account to generate more - the free plan gives you your own workspace.");
        }

        String prompt = request.prompt() == null ? "" : request.prompt().trim();
        String refusal = demoImageService.refusalFor(prompt);
        if (refusal != null) {
            // Refusals are free: no allowance spent, no rate-limit token
            // taken, no provider call. Nothing was charged, so nothing is
            // charged for.
            return sample(session, limit, refusal);
        }

        String clientIp = servletRequest.getRemoteAddr();
        if (!rateLimiter.tryAcquireImage(clientIp)) {
            return sample(session, limit, "You've generated a few images from this device today. "
                + "Create a free account to keep going - it takes a minute.");
        }

        // Spent before the call. See the class javadoc's ordering note.
        demoImageService.recordAttempt(session);
        int used = session.getImageCount() + 1;

        String model = demoImageService.model();
        try {
            GeminiImageGenerationClient.GeneratedImageBytes generated = imageClient.generate(prompt, model);
            UUID imageId = demoImageService.store(
                session, generated.bytes(), generated.mimeType(), prompt, generated.model(), clientIp
            );
            return ResponseEntity.ok(new DemoImageResponse(
                "/api/demo/image/" + imageId, used, limit, false, null
            ));
        } catch (RuntimeException e) {
            // Catches everything on purpose - no pool row, a Google 429/5xx, a
            // safety block, an undecodable payload, object storage being down,
            // an unexpected NPE. Logged at WARN with the sandbox id (never the
            // prompt, never a key) so it stays diagnosable server-side.
            log.warn("Demo image generation failed for sandbox {} (model {}) - serving a sample", session.getId(), model, e);
            return ResponseEntity.ok(new DemoImageResponse(
                null, used, limit, true,
                "The image model is busy right now, so there's nothing to show for this one. "
                    + "Try again in a moment - your sandbox is still fine."
            ));
        }
    }

    /**
     * Serves a generated image by its capability id. See the class javadoc for
     * why this endpoint has no token check.
     *
     * <p>{@code Content-Type} is one of three values normalized by {@code
     * DemoImageService#store} before the row was ever written - never the
     * provider's raw string - so this cannot be turned into an HTML response
     * on this origin. {@code X-Content-Type-Options: nosniff} closes the
     * matching browser-side sniffing route, and the response is inline (not
     * an attachment, unlike {@code GeneratedImageController}) because the
     * whole point is for an {@code <img>} to render it.
     */
    @GetMapping("/image/{id}")
    public ResponseEntity<byte[]> serve(@PathVariable("id") UUID id) {
        Optional<DemoGeneratedImage> image = demoImageService.find(id);
        if (image.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Optional<byte[]> bytes = demoImageService.readBytes(image.get());
        if (bytes.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(image.get().getContentType()))
            .header("X-Content-Type-Options", "nosniff")
            // Immutable content behind a random id - safe to cache hard, and
            // worth it: the /try page re-renders this <img> on every state
            // change and a re-fetch per keystroke would be silly.
            .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePrivate().immutable())
            .body(bytes.get());
    }

    /** 200 with {@code simulated: true} - never a 4xx/5xx. See the class javadoc's hard requirement. */
    private ResponseEntity<DemoImageResponse> sample(DemoSession session, int limit, String message) {
        return ResponseEntity.ok(new DemoImageResponse(
            null, session.getImageCount(), limit, true, message
        ));
    }
}
