package com.agenticai.core.elevenlabs;

import com.agenticai.core.elevenlabs.dto.ElevenLabsCredentialActivationRequest;
import com.agenticai.core.elevenlabs.dto.ElevenLabsCredentialResponse;
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
 * Same three-endpoint shape {@code SarvamController}/{@code TwilioCredentialController} use -
 * activate/current/deactivate.
 */
@RestController
@ConditionalOnEditionFeature(EditionFeature.CLOUD_SPEECH)
@RequestMapping("/api/elevenlabs/credential")
public class ElevenLabsController {

    private final ElevenLabsCredentialService credentialService;

    public ElevenLabsController(ElevenLabsCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public ResponseEntity<ElevenLabsCredentialResponse> activate(
            @Valid @RequestBody ElevenLabsCredentialActivationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentialService.activate(request));
    }

    @GetMapping("/status")
    public ResponseEntity<ElevenLabsCredentialResponse> status() {
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
