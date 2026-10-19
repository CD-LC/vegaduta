package com.agenticai.core.platform.impersonation;

import com.agenticai.core.platform.TenantDeletionController;
import com.agenticai.core.platform.impersonation.ImpersonationService.SessionPage;
import com.agenticai.core.platform.impersonation.ImpersonationService.StartedSession;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform console: start / end / list read-only "view as user" sessions.
 * Role-gated by the blanket {@code /api/platform/** -> ROLE_platform-admin}
 * rule; starting one additionally needs {@code X-Master-Key}
 * ({@link TenantDeletionController#authenticateMasterKey}). Ending one does not
 * (ending only ever reduces access). See {@link ImpersonationService}.
 */
@RestController
@RequestMapping("/api/platform/impersonation")
@Profile("security")
public class PlatformImpersonationController {

    private final ImpersonationService impersonation;
    private final String masterKey;

    public PlatformImpersonationController(ImpersonationService impersonation,
                                           @Value("${app.platform.master-key:}") String masterKey) {
        this.impersonation = impersonation;
        this.masterKey = masterKey;
    }

    public record StartRequest(UUID tenantId, String userId, String reason) {
    }

    @PostMapping
    public ResponseEntity<StartedSession> start(
        @RequestHeader(value = "X-Master-Key", required = false) String presentedMasterKey,
        @RequestBody StartRequest body
    ) {
        TenantDeletionController.authenticateMasterKey(masterKey, presentedMasterKey);
        if (body == null) {
            throw new IllegalArgumentException("Request body is required.");
        }
        Admin admin = currentAdmin();
        StartedSession started = impersonation.start(admin.subject(), admin.email(), body.tenantId(), body.userId(),
            body.reason());
        return ResponseEntity.status(HttpStatus.CREATED).body(started);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> end(@PathVariable("id") UUID id) {
        Admin admin = currentAdmin();
        impersonation.end(id, admin.subject(), admin.email());
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public SessionPage list(@RequestParam(name = "active", required = false) Boolean active,
                            @RequestParam(name = "page", required = false) Integer page,
                            @RequestParam(name = "limit", required = false) Integer limit) {
        return impersonation.list(active, page, limit);
    }

    record Admin(String subject, String email) {
    }

    static Admin currentAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt) {
            return new Admin(jwt.getToken().getSubject(), jwt.getToken().getClaimAsString("email"));
        }
        return new Admin(auth == null ? null : auth.getName(), null);
    }
}
