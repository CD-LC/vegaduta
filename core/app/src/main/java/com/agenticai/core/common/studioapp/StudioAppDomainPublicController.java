package com.agenticai.core.common.studioapp;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Anonymous host resolution for custom domains (covered by the existing {@code /api/public/apps/**}
 * permitAll).
 *
 * <ul>
 *   <li>{@code GET by-host?host=} - slug of the LIVE app for a VERIFIED host.
 *   <li>{@code GET domain-check?domain=} - Caddy on-demand-TLS "ask" endpoint: 200 only for
 *       verified hosts, 404 otherwise, so certificates are never issued for arbitrary names. Wire
 *       in Caddy as {@code on_demand_tls { ask https://api.<domain>/api/public/apps/domain-check }}
 *       (Caddy appends {@code ?domain=<name>}). deploy/caddy is not edited here.
 * </ul>
 *
 * Literal paths win over {@code /{slug}} in StudioAppPublicController.
 */
@RestController
public class StudioAppDomainPublicController {

    private final StudioAppDomainService service;
    private final StudioAppRateLimiter rateLimiter;

    public StudioAppDomainPublicController(
            StudioAppDomainService service, StudioAppRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/api/public/apps/by-host")
    public ResponseEntity<?> byHost(@RequestParam("host") String host, HttpServletRequest request) {
        if (!rateLimiter.tryAcquireCall(request.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "rate_limited"));
        }
        Optional<String> slug = service.resolveSlugForHost(host);
        return slug.<ResponseEntity<?>>map(s -> ResponseEntity.ok(Map.of("slug", s)))
                .orElseGet(
                        () ->
                                ResponseEntity.status(HttpStatus.NOT_FOUND)
                                        .body(Map.of("error", "gone")));
    }

    @GetMapping("/api/public/apps/domain-check")
    public ResponseEntity<Void> domainCheck(@RequestParam("domain") String domain) {
        // Not rate limited per IP: Caddy is the only caller and must never be throttled into
        // refusing certs.
        return service.resolveSlugForHost(domain).isPresent()
                ? ResponseEntity.ok().build()
                : ResponseEntity.notFound().build();
    }
}
