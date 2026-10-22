package com.agenticai.core.platform.impersonation;

import com.agenticai.core.platform.impersonation.ImpersonationService.ViewAsSessionInfo;
import com.agenticai.core.security.ImpersonationContext;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /api/view-as/session} - called by the web app's view-as banner WITH the {@code
 * X-View-As} header. By the time this runs {@code ViewAsFilter} has already verified the grant, the
 * admin and the expiry, and the request runs as the target user; this just names the session for
 * the banner. The web client exits view-as on 401/410 from here, so only those are used: no header
 * (no session on this request) = 401 {@code view_as_invalid}, a session row that vanished = 410
 * {@code view_as_expired}. Never 404.
 */
@ConditionalOnEditionFeature(EditionFeature.OPERATOR_CONSOLE)
@RestController
@Profile("security")
public class ViewAsSessionController {

    private final ImpersonationService impersonation;

    public ViewAsSessionController(ImpersonationService impersonation) {
        this.impersonation = impersonation;
    }

    @GetMapping("/api/view-as/session")
    public ViewAsSessionInfo session() {
        ImpersonationContext.Session current =
                ImpersonationContext.current()
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "view_as_invalid"));
        return impersonation
                .info(current.sessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "view_as_expired"));
    }
}
