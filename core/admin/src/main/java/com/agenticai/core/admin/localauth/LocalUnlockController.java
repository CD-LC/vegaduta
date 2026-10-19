package com.agenticai.core.admin.localauth;

import static com.agenticai.core.admin.localauth.LocalAuthController.noStore;
import static com.agenticai.core.admin.localauth.LocalAuthController.view;

import com.agenticai.core.admin.localauth.LocalAuthController.SessionResponse;
import com.agenticai.core.security.localauth.LocalAuthSecurityConfig;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Passkey, device-PIN and recovery-code sign-in of local mode ({@code /api/auth/local/unlock}); the
 * controller does not exist unless {@code app.auth.mode=local}. The wire contract is the one the web
 * proxy is built to.
 *
 * <p>Three kinds of endpoint, each stated on the handler ({@code @PreAuthorize}) and mirrored by
 * {@link LocalAuthSecurityConfig}: public ones (sign-in, sign-in options, setup with a PIN) where a
 * stale {@code Authorization} header is ignored; bearer-or-setup-token ones (passkey registration),
 * where a bearer is validated when present and otherwise the body must carry a setup token; and
 * bearer-required ones. Responses are {@code no-store}, refusals use the shared {@code {error,
 * message}} shape, and no secret ever appears in a URL.
 */
@RestController
@RequestMapping(LocalUnlockController.BASE)
@ConditionalOnProperty(name = "app.auth.mode", havingValue = "local")
@Profile("!insecure")
public class LocalUnlockController {

    public static final String BASE = LocalAuthSecurityConfig.BASE_PATH + "/unlock";

    /** Header the web proxy sets from the browser's own Origin (core only sees the proxy). */
    static final String ORIGIN_HEADER = "X-Vegaduta-Origin";

    private final LocalAuthService auth;
    private final LocalUnlockService unlock;

    public LocalUnlockController(LocalAuthService auth, LocalUnlockService unlock) {
        this.auth = auth;
        this.unlock = unlock;
    }

    public record UnlockLoginRequest(
            @NotBlank @Pattern(regexp = "pin|passkey|recovery") String type,
            @NotBlank @Size(max = 128) String identifier,
            @NotNull @Size(max = 4) Map<String, @NotNull @Size(max = 65536) String> credentials) {}

    public record SetupPinRequest(
            @NotBlank @Size(max = 256) String token, @NotBlank @Size(max = 64) String pin, @Size(max = 100) String label) {}

    public record RegisterOptionsRequest(@Size(max = 256) String setupToken) {}

    public record RegisterRequest(
            @NotBlank @Size(max = 64) String challengeId,
            @NotBlank @Size(max = 65536) String credential,
            @Size(max = 100) String label,
            @Size(max = 256) String setupToken) {}

    public record DeviceRequest(@NotBlank @Size(max = 64) String pin, @Size(max = 100) String label) {}

    public record SetupPinResponse(
            SessionResponse session, String deviceId, String deviceSecret, List<String> recoveryCodes) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RegisterResponse(
            LocalUnlockService.PasskeyView passkey, SessionResponse session, List<String> recoveryCodes) {}

    public record CodesResponse(List<String> codes) {}

    // ------------------------------------------------------------------ public

    @PostMapping("/login")
    @PreAuthorize("permitAll()")
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody UnlockLoginRequest body, HttpServletRequest request) {
        return noStore(
                ResponseEntity.ok(
                        view(auth.login(body.type(), body.identifier(), body.credentials(), request.getRemoteAddr()))));
    }

    @PostMapping("/passkey/login-options")
    @PreAuthorize("permitAll()")
    public ResponseEntity<LocalPasskeyService.Options> loginOptions(HttpServletRequest request) {
        return noStore(ResponseEntity.ok(unlock.loginOptions(origin(request))));
    }

    @PostMapping("/setup/pin")
    @PreAuthorize("permitAll()")
    public ResponseEntity<SetupPinResponse> setupWithPin(@Valid @RequestBody SetupPinRequest body, HttpServletRequest request) {
        LocalAuthService.PinSetup done = auth.completeSetupWithPin(body.token(), body.pin(), body.label(), request.getRemoteAddr());
        return noStore(ResponseEntity.ok(new SetupPinResponse(view(done.session()), done.deviceId(), done.deviceSecret(), done.recoveryCodes())));
    }

    // ------------------------------------------------------------------ bearer or setup token

    @PostMapping("/passkey/register-options")
    @PreAuthorize("permitAll()")
    public ResponseEntity<LocalPasskeyService.Options> registerOptions(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody(required = false) RegisterOptionsRequest body,
            HttpServletRequest request) {
        String setupToken = body == null ? null : body.setupToken();
        return noStore(ResponseEntity.ok(unlock.registrationOptions(jwt, setupToken, origin(request), request.getRemoteAddr())));
    }

    @PostMapping("/passkey/register")
    @PreAuthorize("permitAll()")
    public ResponseEntity<RegisterResponse> register(
            @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        LocalUnlockService.Registered done =
                unlock.register(jwt, body.challengeId(), body.credential(), body.label(), body.setupToken(), request.getRemoteAddr());
        SessionResponse session = done.session() == null ? null : view(done.session());
        return noStore(ResponseEntity.ok(new RegisterResponse(done.passkey(), session, done.recoveryCodes())));
    }

    // ------------------------------------------------------------------ bearer required

    @GetMapping("/methods")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<LocalUnlockService.Methods> methods(@AuthenticationPrincipal Jwt jwt) {
        return noStore(ResponseEntity.ok(unlock.methods(jwt)));
    }

    @PostMapping("/devices")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<LocalDevices.Enrolled> enrolDevice(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeviceRequest body) {
        return noStore(ResponseEntity.ok(unlock.enrolDevice(jwt, body.pin(), body.label())));
    }

    @DeleteMapping("/devices/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> removeDevice(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        unlock.removeDevice(jwt, id);
        return noStore(ResponseEntity.noContent().build());
    }

    @DeleteMapping("/passkeys/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> removePasskey(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        unlock.removePasskey(jwt, id);
        return noStore(ResponseEntity.noContent().build());
    }

    @PostMapping("/recovery-codes")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<CodesResponse> recoveryCodes(@AuthenticationPrincipal Jwt jwt) {
        return noStore(ResponseEntity.ok(new CodesResponse(unlock.regenerateRecoveryCodes(jwt))));
    }

    @ExceptionHandler(LocalAuthException.class)
    public ResponseEntity<Map<String, Object>> refused(LocalAuthException e) {
        return LocalAuthController.refusal(e);
    }

    private static String origin(HttpServletRequest request) {
        String forwarded = request.getHeader(ORIGIN_HEADER);
        return forwarded != null && !forwarded.isBlank() ? forwarded : request.getHeader("Origin");
    }
}
