package com.agenticai.core.providers.addons;

import com.agenticai.core.providers.addons.AddonApi.ChangeResult;
import com.agenticai.core.providers.addons.AddonApi.ErrorBody;
import com.agenticai.core.providers.addons.AddonApi.HostWanted;
import com.agenticai.core.providers.addons.AddonApi.Overview;
import com.agenticai.core.providers.addons.AddonApi.ReportRequest;
import com.agenticai.core.providers.addons.AddonApi.ReportResult;
import com.agenticai.core.providers.addons.AddonApi.View;
import com.agenticai.core.security.TenantContext;
import com.agenticai.core.security.ratelimit.LocalRateLimitFallback;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/addons}: the catalog of optional capabilities with their state, and the requests to
 * switch them on or off.
 *
 * <ul>
 *   <li>Reading ({@code GET /api/addons}, {@code GET /api/addons/{id}}): any signed-in user.
 *   <li>Changing ({@code POST /api/addons/{id}/enable|disable}): see {@link AddonAccess}.
 *   <li>{@code /api/addons/host/**}: the installer on the host, authenticated by a shared secret
 *       ({@link AddonHostAuth}, {@link AddonHostSecurityConfig}), not by a person. Those handlers say
 *       {@code permitAll()} to method security because the secret is checked in the handler itself,
 *       as the first statement, and fails closed.
 * </ul>
 *
 * Errors this class produces are {@link ErrorBody}: {@code {"error": code, "message": text,
 * "details": {...}}}. Role failures keep the platform's standard 401/403 bodies.
 */
@RestController
@RequestMapping("/api/addons")
public class AddonController {

    private static final Logger log = LoggerFactory.getLogger(AddonController.class);

    /** Switch requests per signed-in caller per minute; a person clicking cannot reach it. */
    static final long CHANGES_PER_MINUTE = 30;

    private final AddonService service;
    private final AddonHostAuth hostAuth;
    private final AddonAccess access;

    public AddonController(AddonService service, AddonHostAuth hostAuth, AddonAccess access) {
        this.service = service;
        this.hostAuth = hostAuth;
        this.access = access;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public Overview list(Authentication authentication) {
        return service.overview(access.canChange(authentication));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public View get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/{id}/enable")
    @PreAuthorize("@addonAccess.canChange(authentication)")
    public ChangeResult enable(@PathVariable String id, Authentication authentication) {
        throttle(authentication);
        return service.enable(id, actor(authentication));
    }

    @PostMapping("/{id}/disable")
    @PreAuthorize("@addonAccess.canChange(authentication)")
    public ChangeResult disable(
            @PathVariable String id,
            @RequestParam(name = "cascade", defaultValue = "false") boolean cascade,
            Authentication authentication) {
        throttle(authentication);
        return service.disable(id, cascade, actor(authentication));
    }

    @GetMapping("/host/wanted")
    @PreAuthorize("permitAll()")
    public HostWanted hostWanted(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        hostAuth.require(authorization);
        return service.hostWanted();
    }

    @PostMapping("/host/report")
    @PreAuthorize("permitAll()")
    public ReportResult hostReport(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) ReportRequest request) {
        hostAuth.require(authorization);
        return service.hostReport(request);
    }

    @ExceptionHandler(AddonException.class)
    ResponseEntity<ErrorBody> addonError(AddonException e) {
        return ResponseEntity.status(e.status()).body(new ErrorBody(e.code(), e.getMessage(), e.details()));
    }

    /** Two writers raced on one add-on; the loser retries. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<ErrorBody> raced(OptimisticLockingFailureException e) {
        log.debug("Add-on state changed concurrently: {}", e.toString());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorBody("conflict", "The add-on was changed at the same time. Try again.", java.util.Map.of()));
    }

    private static void throttle(Authentication authentication) {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse("-");
        String caller = tenant + ":" + (authentication == null ? "-" : authentication.getName());
        if (!LocalRateLimitFallback.tryAcquire("addons:change:" + caller, CHANGES_PER_MINUTE, Duration.ofMinutes(1))) {
            throw new AddonException(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "Too many changes. Please slow down and retry shortly.");
        }
    }

    private static String actor(Authentication authentication) {
        return TenantContext.current()
                .map(TenantContext::getSubject)
                .orElseGet(() -> authentication == null ? null : authentication.getName());
    }
}
