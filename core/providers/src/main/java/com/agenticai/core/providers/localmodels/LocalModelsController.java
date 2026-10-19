package com.agenticai.core.providers.localmodels;

import com.agenticai.core.security.TenantContext;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hardware-aware local model setup for the console: what this machine can run, which model to
 * pull, whether the pulled model really calls tools. Tenant-admin only (a pull uses disk and
 * bandwidth of the whole install) and rate-limited per caller; see {@link LocalModelsService} and
 * {@link LocalModelRateLimiter}. Talks only to the operator-configured Ollama, never to a URL from
 * the request.
 */
@RestController
@RequestMapping("/api/local-models")
@PreAuthorize(LocalModelsController.TENANT_ADMIN)
public class LocalModelsController {

    static final String TENANT_ADMIN = "hasAnyAuthority('ROLE_tenant-admin', 'ROLE_platform-admin')";

    /** Body of {@code POST /probe} and {@code POST /pull}. */
    public record TagRequest(@NotBlank @Size(max = LocalModelsService.MAX_TAG_LENGTH) String tag) {}

    private final LocalModelsService service;
    private final LocalModelRateLimiter limiter;

    public LocalModelsController(LocalModelsService service, LocalModelRateLimiter limiter) {
        this.service = service;
        this.limiter = limiter;
    }

    @GetMapping("/recommendation")
    public LocalModelsService.Recommended recommendation(Authentication authentication) {
        limiter.checkRead(caller(authentication));
        return service.recommendation();
    }

    @PostMapping("/probe")
    public LocalModelsService.ProbeResult probe(
            @Valid @RequestBody TagRequest request, Authentication authentication) {
        limiter.checkProbe(caller(authentication));
        return service.probe(request.tag());
    }

    @PostMapping("/pull")
    public ResponseEntity<Map<String, String>> pull(
            @Valid @RequestBody TagRequest request, Authentication authentication) {
        limiter.checkPull(caller(authentication));
        String jobId = service.startPull(request.tag());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("jobId", jobId));
    }

    /**
     * "Use this model": repoints the local pool row at {@code tag} (or creates it when none
     * exists) - the runtime switch the troubleshoot page offers, no restart or reinstall.
     * Probe-class rate limit: switching re-measures the row's tool-call grade, which sends a
     * chat request to the model.
     */
    @PostMapping("/use")
    public LocalModelsService.UseResult use(
            @Valid @RequestBody TagRequest request, Authentication authentication) {
        limiter.checkProbe(caller(authentication));
        return service.useModel(request.tag());
    }

    /** Body of {@code POST /warm}; an absent or blank tag means the model the local pool row serves. */
    public record WarmRequest(@Size(max = LocalModelsService.MAX_TAG_LENGTH) String tag) {}

    /**
     * Loads a local model into memory ahead of the first chat and keeps it resident
     * ({@code app.local-models.keep-alive}). The console calls it when a chat surface opens; the
     * answer waits for the load so a caller can show "model ready". Read-class rate limit: it moves
     * no data and sends no prompt.
     */
    @PostMapping("/warm")
    public LocalModelsService.WarmResult warm(
            @Valid @RequestBody(required = false) WarmRequest request, Authentication authentication) {
        limiter.checkRead(caller(authentication));
        return service.warm(request == null ? null : request.tag());
    }

    /** The local model's reasoning switch, keep-alive and loaded context (Providers -> Local model -> "Speed and behaviour"). */
    @GetMapping("/settings")
    public LocalModelsService.Settings settings(Authentication authentication) {
        limiter.checkRead(caller(authentication));
        return service.settings();
    }

    /** Saves the reasoning switch and keep-alive; applies from the next message. Probe-class rate limit: it reloads the model. */
    @PutMapping("/settings")
    public LocalModelsService.Settings updateSettings(
            @Valid @RequestBody LocalModelsService.SettingsUpdate request, Authentication authentication) {
        limiter.checkProbe(caller(authentication));
        return service.updateSettings(request);
    }

    @GetMapping("/pull/{jobId}")
    public LocalModelPullService.Status pullStatus(@PathVariable String jobId, Authentication authentication) {
        limiter.checkRead(caller(authentication));
        return service.pullStatus(jobId);
    }

    /** Tenant and subject: one bucket per signed-in admin, not per address. */
    private static String caller(Authentication authentication) {
        String tenant = TenantContext.current().map(TenantContext::getTenantId).orElse("-");
        String subject = authentication == null ? "-" : authentication.getName();
        return tenant + ":" + subject;
    }
}
