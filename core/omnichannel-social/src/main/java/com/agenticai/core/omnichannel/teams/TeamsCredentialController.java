package com.agenticai.core.omnichannel.teams;

import com.agenticai.core.omnichannel.teams.dto.TeamsActivationRequest;
import com.agenticai.core.omnichannel.teams.dto.TeamsCredentialResponse;
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
@RequestMapping("/api/channels/teams/credential")
public class TeamsCredentialController {

    private final TeamsCredentialService credentialService;

    public TeamsCredentialController(TeamsCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public ResponseEntity<TeamsCredentialResponse> activate(
            @Valid @RequestBody TeamsActivationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentialService.activate(request));
    }

    @GetMapping
    public ResponseEntity<TeamsCredentialResponse> current() {
        return credentialService
                .current()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * "Log out of Teams" - see {@link TeamsCredentialService#disconnect}'s javadoc. Idempotent,
     * returns 204 whether or not anything was connected.
     */
    @DeleteMapping
    public ResponseEntity<Void> disconnect() {
        credentialService.disconnect();
        return ResponseEntity.noContent().build();
    }
}
