package com.agenticai.core.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * {@link AccessDeniedHandler} for the main filter chain: writes the same
 * {@code {timestamp,status,error}} JSON body {@code TenantContextFilter}
 * hand-builds for its own 403s (so the web console's error handling renders
 * a permission denial identically to any other failure), then notifies the
 * {@link AccessDeniedEventListener} SPI so the denial lands in the tenant's
 * audit log as an {@code authz.denied} event.
 *
 * <p>Why this exists: until now every authorization failure was a silent
 * 403 - a scoped member probing an admin surface left no trace anywhere an
 * operator could see. Denied attempts are exactly the signal an access-review
 * wants ("who keeps trying to reach billing?"), so they are audited with the
 * same {@link com.agenticai.core.audit.AuditOutcome#FAILURE} posture
 * {@code UserAdminController} already uses for refused self-edits.
 *
 * <p>Listener failures never change the response - the 403 is written first
 * and a broken audit sink must not turn a clean denial into a 500.
 */
public class AuditingAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(AuditingAccessDeniedHandler.class);

    private final List<AccessDeniedEventListener> listeners;
    private final String tenantClaim;

    public AuditingAccessDeniedHandler(List<AccessDeniedEventListener> listeners) {
        this(listeners, JwtClaimPaths.DEFAULT_TENANT_CLAIM);
    }

    /**
     * @param tenantClaim the JWT claim carrying the tenant/schema id the denial
     *     is audited against - {@code app.security.oidc.tenant-claim} (see
     *     {@link OidcClaimMapping}); blank falls back to the Keycloak default.
     */
    public AuditingAccessDeniedHandler(List<AccessDeniedEventListener> listeners, String tenantClaim) {
        this.listeners = listeners == null ? List.of() : List.copyOf(listeners);
        this.tenantClaim =
                (tenantClaim == null || tenantClaim.isBlank())
                        ? JwtClaimPaths.DEFAULT_TENANT_CLAIM
                        : tenantClaim;
    }

    @Override
    public void handle(
        HttpServletRequest request,
        HttpServletResponse response,
        AccessDeniedException accessDeniedException
    ) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
            "{\"timestamp\":\"" + java.time.Instant.now()
                + "\",\"status\":403,\"error\":\"You do not have permission to perform this action.\"}"
        );

        if (listeners.isEmpty()) {
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String tenantId = null;
        String subject = null;
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            tenantId = JwtClaimPaths.valueAsString(jwtAuth.getToken(), tenantClaim);
            subject = jwtAuth.getToken().getSubject();
        } else if (authentication != null) {
            subject = authentication.getName();
        }
        for (AccessDeniedEventListener listener : listeners) {
            try {
                listener.onAccessDenied(
                    tenantId, subject, request.getMethod(), LogMasking.path(request.getRequestURI()), accessDeniedException.getMessage());
            } catch (RuntimeException e) {
                log.warn("AccessDeniedEventListener {} threw while recording a denial of {} {} - continuing",
                    listener.getClass().getSimpleName(), request.getMethod(), LogMasking.path(request.getRequestURI()), e);
            }
        }
    }
}
