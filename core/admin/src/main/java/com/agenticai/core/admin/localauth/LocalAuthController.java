package com.agenticai.core.admin.localauth;

import com.agenticai.core.admin.localauth.LocalSessionService.Session;
import com.agenticai.core.security.localauth.LocalAuthSecurityConfig;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in endpoints of local mode ({@code app.auth.mode=local}); the controller does not exist in
 * any other mode, so the default Keycloak deployment has no {@code /api/auth/local/*} surface.
 *
 * <p>Authorization is stated on every handler ({@code @PreAuthorize}, which the authorization
 * coverage test requires) and mirrored by the dedicated filter chain in {@link
 * LocalAuthSecurityConfig}: sign-in, refresh, logout, setup and the public config are open because
 * the caller has no token yet; only {@code change-password} needs a valid access token. Request
 * bodies carry the secrets (never the URL), responses are {@code no-store}, and a refusal always
 * uses the same shape {@code {error, message}} with a stable {@code error} code.
 *
 * <p>The refresh token is returned in the response body so any client (CLI, SDK, the web console's
 * server-side proxy) can hold it as it sees fit; the web console keeps it in an HttpOnly cookie and
 * never hands it to browser JavaScript.
 */
@RestController
@RequestMapping(LocalAuthSecurityConfig.BASE_PATH)
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
@Profile("!insecure")
public class LocalAuthController {

    private final LocalAuthService auth;
    private final LocalSessionService sessions;

    public LocalAuthController(LocalAuthService auth, LocalSessionService sessions) {
        this.auth = auth;
        this.sessions = sessions;
    }

    public record LoginRequest(
            @NotBlank @Size(max = 320) String identifier,
            @NotBlank @Size(max = 1024) String password,
            @Size(max = 32) String type) {}

    public record SetupRequest(
            @NotBlank @Size(max = 256) String token, @NotBlank @Size(max = 1024) String password) {}

    public record RefreshRequest(@NotBlank @Size(max = 256) String refreshToken) {}

    public record ChangePasswordRequest(
            @NotBlank @Size(max = 1024) String currentPassword,
            @NotBlank @Size(max = 1024) String newPassword) {}

    public record UserView(
            String id, String email, String firstName, String lastName, String tenantId, Set<String> roles) {}

    public record SessionResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn,
            boolean passwordChangeRequired,
            UserView user) {}

    public record ConfigResponse(
            String mode, boolean setupRequired, List<String> credentialTypes, int passwordMinLength) {}

    @GetMapping("/config")
    @PreAuthorize("permitAll()")
    public ResponseEntity<ConfigResponse> config() {
        LocalAuthService.Status status = auth.status();
        return noStore(
                ResponseEntity.ok(
                        new ConfigResponse("local", status.setupRequired(), status.credentialTypes(), status.passwordMinLength())));
    }

    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        Session session =
                auth.login(body.type(), body.identifier(), Map.of("password", body.password()), request.getRemoteAddr());
        return noStore(ResponseEntity.ok(view(session)));
    }

    @PostMapping("/setup")
    @PreAuthorize("permitAll()")
    public ResponseEntity<SessionResponse> setup(@Valid @RequestBody SetupRequest body, HttpServletRequest request) {
        return noStore(ResponseEntity.ok(view(auth.completeSetup(body.token(), body.password(), request.getRemoteAddr()))));
    }

    @PostMapping("/refresh")
    @PreAuthorize("permitAll()")
    public ResponseEntity<SessionResponse> refresh(@Valid @RequestBody RefreshRequest body) {
        return noStore(ResponseEntity.ok(view(sessions.refresh(body.refreshToken()))));
    }

    @PostMapping("/logout")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> logout(@RequestBody(required = false) RefreshRequest body) {
        if (body != null) {
            sessions.logout(body.refreshToken());
        }
        return noStore(ResponseEntity.noContent().build());
    }

    @PostMapping("/change-password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<SessionResponse> changePassword(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangePasswordRequest body,
            HttpServletRequest request) {
        Session session =
                auth.changePassword(jwt.getSubject(), body.currentPassword(), body.newPassword(), request.getRemoteAddr());
        return noStore(ResponseEntity.ok(view(session)));
    }

    @ExceptionHandler(LocalAuthException.class)
    public ResponseEntity<Map<String, Object>> refused(LocalAuthException e) {
        return refusal(e);
    }

    /** The one refusal shape, shared with {@link LocalUnlockController}. */
    static ResponseEntity<Map<String, Object>> refusal(LocalAuthException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.code());
        body.put("message", e.getMessage());
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status()).cacheControl(CacheControl.noStore());
        if (e.retryAfterSeconds() != null) {
            body.put("retryAfterSeconds", e.retryAfterSeconds());
            response.header("Retry-After", String.valueOf(e.retryAfterSeconds()));
        }
        return response.body(body);
    }

    static SessionResponse view(Session s) {
        LocalAccount a = s.account();
        Instant now = Instant.now();
        return new SessionResponse(
                s.accessToken(),
                "Bearer",
                Math.max(0, Duration.between(now, s.accessExpiresAt()).toSeconds()),
                s.refreshToken(),
                Math.max(0, Duration.between(now, s.refreshExpiresAt()).toSeconds()),
                a.mustChangePassword(),
                new UserView(a.id(), a.email(), a.firstName(), a.lastName(), a.tenantId(), a.roles()));
    }

    static <T> ResponseEntity<T> noStore(ResponseEntity<T> response) {
        return ResponseEntity.status(response.getStatusCode())
                .cacheControl(CacheControl.noStore())
                .headers(response.getHeaders())
                .body(response.getBody());
    }
}
