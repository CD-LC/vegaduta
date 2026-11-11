package com.agenticai.core.omnichannel.imessage;

import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Connect, inspect, configure and disconnect the tenant's BlueBubbles server. */
@RestController
@RequestMapping("/api/channels/imessage/credential")
public class ImessageCredentialController {

    private final ImessageCredentialService credentialService;

    public ImessageCredentialController(ImessageCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public ResponseEntity<ImessageCredentialResponse> activate(
            @Valid @RequestBody ImessageActivationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentialService.activate(request));
    }

    @GetMapping
    public ResponseEntity<ImessageCredentialResponse> current() {
        return credentialService
                .current()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * The full webhook token, on explicit request only - the credential read carries a masked
     * preview so the inbound-webhook secret does not ride every GET. Same auth surface as the
     * credential itself (tenant-scoped, authenticated).
     */
    @GetMapping("/webhook-token")
    public Map<String, String> webhookToken() {
        return Map.of("webhookToken", credentialService.revealWebhookToken());
    }

    /** Sender allow-list, groups, unknown-sender policy and inbound mode. */
    @PutMapping("/settings")
    public ImessageCredentialResponse updateSettings(
            @Valid @RequestBody ImessageSettingsRequest request) {
        return credentialService.updateSettings(request);
    }

    /** "Log out of iMessage". Idempotent: 204 whether or not anything was connected. */
    @DeleteMapping
    public ResponseEntity<Void> disconnect() {
        credentialService.disconnect();
        return ResponseEntity.noContent().build();
    }
}
