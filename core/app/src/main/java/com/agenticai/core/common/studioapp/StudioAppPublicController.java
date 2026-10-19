package com.agenticai.core.common.studioapp;

import com.agenticai.core.common.studioapp.StudioAppInvokeService.InvokeResult;
import com.agenticai.core.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/public/apps/{slug}} - the PUBLIC half of Phone-App-Studio
 * publishing. Anonymous (permitAll at the filter chain); the unguessable slug
 * is the capability, exactly like {@code /api/share/agents/**} and {@code
 * /api/share/workflows/**} already work - see {@code AgentShareController}'s
 * javadoc for the model this mirrors: resolve slug -> owning tenant schema ->
 * set {@link TenantContext} by hand -> dispatch inside the owner's own
 * plan/budget -> always clear.
 *
 * <p>What this adds over a bare share link: a whole APP is the unit. The
 * manifest below is what {@code run-app.html} fetches to render a short
 * {@code #a=slug} link; {@code auth/*} runs the app's own end-user accounts
 * (the builder's customers - see {@link StudioAppUser}, platform-native, never
 * Keycloak/console users); {@code call/{alias}} invokes whichever agent or
 * workflow the owner wired behind that alias; {@code ai/chat} is the app's
 * hosted-AI block. Per-connection {@code requiresAuth} and the app's {@code
 * authMode} are enforced here on every dispatch.
 *
 * <p>Never returns tenant-identifying detail on failure - unknown, malformed,
 * disabled and deleted slugs all share the same 404 {@code "gone"} body, the
 * established indistinguishable posture.
 */
@RestController
@RequestMapping("/api/public/apps")
public class StudioAppPublicController {

    private static final Logger log = LoggerFactory.getLogger(StudioAppPublicController.class);

    private final StudioAppService appService;
    private final StudioAppInvokeService invokeService;
    private final StudioAppRateLimiter rateLimiter;

    public StudioAppPublicController(
        StudioAppService appService,
        StudioAppInvokeService invokeService,
        StudioAppRateLimiter rateLimiter
    ) {
        this.appService = appService;
        this.invokeService = invokeService;
        this.rateLimiter = rateLimiter;
    }

    // ---------- request/response shapes ----------

    public record ConnectionView(String alias, String kind, String displayName, boolean requiresAuth) {
    }

    /** What the runner needs to render + wire the app. {@code html} is included so a {@code #a=slug} link is the whole app - no URL-length ceiling. */
    public record AppManifest(
        String slug, String name, String html, int version,
        String authMode, boolean aiEnabled, List<ConnectionView> connections
    ) {
    }

    public record SignupRequest(
        @Size(max = 254) String email,
        @Size(max = 128) String password,
        @Size(max = 120) String name
    ) {
    }

    public record LoginRequest(@Size(max = 254) String email, @Size(max = 128) String password) {
    }

    public record UserView(String id, String email, String displayName) {
        static UserView of(StudioAppUser u) {
            return new UserView(u.getId().toString(), u.getEmail(), u.getDisplayName());
        }
    }

    public record AuthResponse(String token, UserView user) {
    }

    public record CallRequest(
        @Size(max = 2000) String input,
        Map<String, Object> data
    ) {
    }

    public record CallResponse(String text) {
    }

    public record AiChatRequest(@Size(max = 4000) String prompt) {
    }

    public record AiChatResponse(String text) {
    }

    // ---------- endpoints ----------

    /** App manifest + HTML for the runner. 404 for anything not live. */
    @GetMapping("/{slug}")
    public ResponseEntity<?> manifest(@PathVariable String slug) {
        Optional<StudioApp> resolved = appService.resolvePublicApp(slug);
        if (resolved.isEmpty()) {
            return gone();
        }
        try {
            StudioApp app = resolved.get();
            List<ConnectionView> connections = appService.publicConnections(app).stream()
                .map(c -> new ConnectionView(c.getAlias(), c.getKind().name(), c.getDisplayName(), c.isRequiresAuth()))
                .toList();
            return ResponseEntity.ok(new AppManifest(
                app.getSlug(), app.getName(), app.getHtml(), app.getPublishedVersion(),
                app.getAuthMode().name(), app.isAiEnabled(), connections));
        } finally {
            TenantContext.clear();
        }
    }

    /** End-user signup - only while the app actually uses auth. */
    @PostMapping("/{slug}/auth/signup")
    public ResponseEntity<?> signup(
        @PathVariable String slug, @Valid @RequestBody(required = false) SignupRequest request,
        HttpServletRequest servletRequest
    ) {
        if (!rateLimiter.tryAcquireAuth(clientIp(servletRequest))) {
            return rateLimited();
        }
        Optional<StudioApp> resolved = appService.resolvePublicApp(slug);
        if (resolved.isEmpty()) {
            return gone();
        }
        try {
            StudioApp app = resolved.get();
            if (app.getAuthMode() == StudioApp.AuthMode.NONE) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "auth_not_enabled"));
            }
            StudioAppService.AuthResult result = appService.signup(
                app, request == null ? null : request.email(),
                request == null ? null : request.password(),
                request == null ? null : request.name());
            return ResponseEntity.ok(new AuthResponse(result.token(), UserView.of(result.user())));
        } catch (StudioAppService.StudioAppException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        } finally {
            TenantContext.clear();
        }
    }

    @PostMapping("/{slug}/auth/login")
    public ResponseEntity<?> login(
        @PathVariable String slug, @Valid @RequestBody(required = false) LoginRequest request,
        HttpServletRequest servletRequest
    ) {
        if (!rateLimiter.tryAcquireAuth(clientIp(servletRequest))) {
            return rateLimited();
        }
        Optional<StudioApp> resolved = appService.resolvePublicApp(slug);
        if (resolved.isEmpty()) {
            return gone();
        }
        try {
            StudioApp app = resolved.get();
            if (app.getAuthMode() == StudioApp.AuthMode.NONE) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "auth_not_enabled"));
            }
            StudioAppService.AuthResult result = appService.login(
                app, request == null ? null : request.email(), request == null ? null : request.password());
            return ResponseEntity.ok(new AuthResponse(result.token(), UserView.of(result.user())));
        } catch (StudioAppService.StudioAppException e) {
            // invalid_credentials deliberately shares one shape with bad_email - no enumeration oracle.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", e.getMessage()));
        } finally {
            TenantContext.clear();
        }
    }

    /** Who the caller's Bearer token belongs to - the app's "am I signed in" check. */
    @GetMapping("/{slug}/auth/me")
    public ResponseEntity<?> me(
        @PathVariable String slug,
        @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        Optional<StudioApp> resolved = appService.resolvePublicApp(slug);
        if (resolved.isEmpty()) {
            return gone();
        }
        try {
            StudioApp app = resolved.get();
            return appService.authenticate(app, authorization)
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok(Map.of("user", UserView.of(u))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "signed_out")));
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Stateless tokens - nothing to revoke server-side; the endpoint exists so
     * the runner has a hook that also drops its cached copy. Per-user DISABLED
     * status takes effect on the very next call regardless.
     */
    @PostMapping("/{slug}/auth/logout")
    public ResponseEntity<?> logout(@PathVariable String slug) {
        return ResponseEntity.noContent().build();
    }

    /** {@code vd.call(alias, input)} - the app invoking one wired agent/workflow. */
    @PostMapping("/{slug}/call/{alias}")
    public ResponseEntity<?> call(
        @PathVariable String slug, @PathVariable String alias,
        @Valid @RequestBody(required = false) CallRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        HttpServletRequest servletRequest
    ) {
        String ip = clientIp(servletRequest);
        if (!rateLimiter.tryAcquireCall(ip)) {
            return rateLimited();
        }
        Optional<StudioApp> resolved = appService.resolvePublicApp(slug);
        if (resolved.isEmpty()) {
            return gone();
        }
        try {
            StudioApp app = resolved.get();
            Optional<StudioAppConnection> connection = appService.publicConnections(app).stream()
                .filter(c -> c.getAlias().equals(alias))
                .findFirst();
            if (connection.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "unknown_alias"));
            }
            Optional<StudioAppUser> user = appService.authenticate(app, authorization);
            if (appService.authRequired(app, connection.get()) && user.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "sign_in_required"));
            }
            InvokeResult result = invokeService.call(
                app, connection.get(),
                request == null ? null : request.input(),
                request == null ? null : request.data(),
                user.orElse(null), ip);
            if (result instanceof InvokeResult.Ok ok) {
                return ResponseEntity.ok(new CallResponse(ok.text()));
            }
            InvokeResult.Failed failed = (InvokeResult.Failed) result;
            HttpStatus status = switch (failed.code()) {
                case "quota_exhausted" -> HttpStatus.PAYMENT_REQUIRED;
                case "expired" -> HttpStatus.GONE;
                case "invalid" -> HttpStatus.UNPROCESSABLE_ENTITY;
                default -> HttpStatus.SERVICE_UNAVAILABLE;
            };
            return ResponseEntity.status(status).body(Map.of("error", failed.code(), "message", failed.message()));
        } finally {
            TenantContext.clear();
        }
    }

    /** {@code vd.ai.chat(prompt)} - the app's hosted-AI block. */
    @PostMapping("/{slug}/ai/chat")
    public ResponseEntity<?> aiChat(
        @PathVariable String slug,
        @Valid @RequestBody(required = false) AiChatRequest request,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        HttpServletRequest servletRequest
    ) {
        String ip = clientIp(servletRequest);
        if (!rateLimiter.tryAcquireAi(ip)) {
            return rateLimited();
        }
        Optional<StudioApp> resolved = appService.resolvePublicApp(slug);
        if (resolved.isEmpty()) {
            return gone();
        }
        try {
            StudioApp app = resolved.get();
            if (!app.isAiEnabled()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "ai_not_enabled"));
            }
            Optional<StudioAppUser> user = appService.authenticate(app, authorization);
            if (app.getAuthMode() == StudioApp.AuthMode.REQUIRED && user.isEmpty()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "sign_in_required"));
            }
            InvokeResult result = invokeService.aiChat(app, request == null ? null : request.prompt(), user.orElse(null));
            if (result instanceof InvokeResult.Ok ok) {
                return ResponseEntity.ok(new AiChatResponse(ok.text()));
            }
            InvokeResult.Failed failed = (InvokeResult.Failed) result;
            HttpStatus status = "quota_exhausted".equals(failed.code())
                ? HttpStatus.PAYMENT_REQUIRED : HttpStatus.SERVICE_UNAVAILABLE;
            return ResponseEntity.status(status).body(Map.of("error", failed.code(), "message", failed.message()));
        } finally {
            TenantContext.clear();
        }
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(StudioAppService.StudioAppException.class)
    public ResponseEntity<?> onDomainError(StudioAppService.StudioAppException e) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
    }

    // ------------------------------------------------------------------

    private static ResponseEntity<Map<String, String>> gone() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "gone"));
    }

    private static ResponseEntity<Map<String, String>> rateLimited() {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("error", "rate_limited"));
    }

    private static String clientIp(HttpServletRequest request) {
        return request == null ? null : request.getRemoteAddr();
    }
}
