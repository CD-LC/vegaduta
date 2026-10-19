package com.agenticai.core.omnichannel.signal;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/channels/signal/credential")
public class SignalCredentialController {

    private final SignalCredentialService credentialService;

    public SignalCredentialController(SignalCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public ResponseEntity<SignalCredentialResponse> activate(@Valid @RequestBody SignalActivationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentialService.activate(request));
    }

    @GetMapping
    public ResponseEntity<SignalCredentialResponse> current() {
        return credentialService.current()
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /** Who the agent answers (unknown senders, groups) without re-entering the connection. */
    @PutMapping("/policy")
    public SignalCredentialResponse updatePolicy(@RequestBody SignalPolicyRequest request) {
        return credentialService.updatePolicy(request);
    }

    /** "Log out of Signal" - see {@link SignalCredentialService#disconnect}. Idempotent, 204 either way. */
    @DeleteMapping
    public ResponseEntity<Void> disconnect() {
        credentialService.disconnect();
        return ResponseEntity.noContent().build();
    }
}
