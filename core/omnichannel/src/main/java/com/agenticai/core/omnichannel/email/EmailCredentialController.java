package com.agenticai.core.omnichannel.email;

import com.agenticai.core.omnichannel.email.dto.EmailActivationRequest;
import com.agenticai.core.omnichannel.email.dto.EmailCredentialResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/channels/email/credential")
public class EmailCredentialController {

    private final EmailCredentialService credentialService;

    public EmailCredentialController(EmailCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public ResponseEntity<EmailCredentialResponse> activate(@Valid @RequestBody EmailActivationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentialService.activate(request));
    }

    @GetMapping
    public ResponseEntity<EmailCredentialResponse> current() {
        return credentialService.current()
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * "Log out of this mailbox" - see {@link EmailCredentialService#disconnect}'s
     * javadoc. Idempotent, returns 204 whether or not anything was connected.
     */
    @DeleteMapping
    public ResponseEntity<Void> disconnect() {
        credentialService.disconnect();
        return ResponseEntity.noContent().build();
    }
}
