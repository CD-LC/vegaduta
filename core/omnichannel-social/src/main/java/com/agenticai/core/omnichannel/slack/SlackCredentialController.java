package com.agenticai.core.omnichannel.slack;

import com.agenticai.core.omnichannel.slack.dto.SlackActivationRequest;
import com.agenticai.core.omnichannel.slack.dto.SlackCredentialResponse;
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
@RequestMapping("/api/channels/slack/credential")
public class SlackCredentialController {

    private final SlackCredentialService credentialService;

    public SlackCredentialController(SlackCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public ResponseEntity<SlackCredentialResponse> activate(
            @Valid @RequestBody SlackActivationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentialService.activate(request));
    }

    @GetMapping
    public ResponseEntity<SlackCredentialResponse> current() {
        return credentialService
                .current()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * "Log out of Slack" - see {@link SlackCredentialService#disconnect}'s javadoc. Idempotent (a
     * repeat call when nothing's connected is a harmless no-op), so this returns 204 either way
     * rather than distinguishing "disconnected" from "there was nothing to disconnect".
     */
    @DeleteMapping
    public ResponseEntity<Void> disconnect() {
        credentialService.disconnect();
        return ResponseEntity.noContent().build();
    }
}
