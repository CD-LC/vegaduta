package com.agenticai.core.sarvam;

import com.agenticai.core.sarvam.dto.SarvamCredentialActivationRequest;
import com.agenticai.core.sarvam.dto.SarvamCredentialResponse;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
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
 * Same three-endpoint shape {@code TwilioCredentialController} uses - activate/current/deactivate,
 * nothing more in Stage 1.
 */
@RestController
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
@RequestMapping("/api/sarvam/credential")
public class SarvamController {

    private final SarvamCredentialService credentialService;

    public SarvamController(SarvamCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public ResponseEntity<SarvamCredentialResponse> activate(
            @Valid @RequestBody SarvamCredentialActivationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentialService.activate(request));
    }

    @GetMapping("/status")
    public ResponseEntity<SarvamCredentialResponse> status() {
        return credentialService
                .current()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @DeleteMapping
    public ResponseEntity<Void> deactivate() {
        credentialService.deactivate();
        return ResponseEntity.noContent().build();
    }
}
