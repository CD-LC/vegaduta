package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.omnichannel.meta.dto.MetaActivationRequest;
import com.agenticai.core.omnichannel.meta.dto.MetaCredentialResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mirrors {@code TwilioCredentialController} exactly - a tenant pastes the
 * four values Meta's own dashboard gave them (see {@code
 * MetaWhatsAppCredentialService}'s javadoc for exactly where to find each
 * one). Gated to {@code ROLE_tenant-admin} on the POST in {@code
 * SecurityConfig}, same posture as every other integration-credential write.
 */
@RestController
@RequestMapping("/api/channels/meta-whatsapp/credential")
public class MetaWhatsAppCredentialController {

    private final MetaWhatsAppCredentialService credentialService;

    public MetaWhatsAppCredentialController(MetaWhatsAppCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public ResponseEntity<MetaCredentialResponse> activate(@Valid @RequestBody MetaActivationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentialService.activate(request));
    }

    @GetMapping
    public ResponseEntity<MetaCredentialResponse> current() {
        return credentialService.current()
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * "Log out of WhatsApp (Meta)" - see {@link MetaWhatsAppCredentialService#disconnect}'s
     * javadoc. Idempotent, returns 204 whether or not anything was connected.
     */
    @DeleteMapping
    public ResponseEntity<Void> disconnect() {
        credentialService.disconnect();
        return ResponseEntity.noContent().build();
    }
}
