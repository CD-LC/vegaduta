package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.omnichannel.meta.dto.MetaSocialActivationRequest;
import com.agenticai.core.omnichannel.meta.dto.MetaSocialCredentialResponse;
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
 * Mirrors {@code MetaWhatsAppCredentialController} exactly. Gated to {@code
 * ROLE_tenant-admin} on the POST in {@code SecurityConfig}, same posture as
 * every other integration-credential write.
 */
@RestController
@RequestMapping("/api/channels/meta-social/credential")
public class MetaSocialCredentialController {

    private final MetaSocialCredentialService credentialService;

    public MetaSocialCredentialController(MetaSocialCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public ResponseEntity<MetaSocialCredentialResponse> activate(@Valid @RequestBody MetaSocialActivationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentialService.activate(request));
    }

    @GetMapping
    public ResponseEntity<MetaSocialCredentialResponse> current() {
        return credentialService.current()
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * "Log out of this Facebook Page" - see {@link MetaSocialCredentialService#disconnect}'s
     * javadoc (drops BOTH the Messenger and Instagram tenant links).
     * Idempotent, returns 204 whether or not anything was connected.
     */
    @DeleteMapping
    public ResponseEntity<Void> disconnect() {
        credentialService.disconnect();
        return ResponseEntity.noContent().build();
    }
}
