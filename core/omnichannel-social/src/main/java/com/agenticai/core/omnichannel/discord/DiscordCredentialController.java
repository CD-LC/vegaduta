package com.agenticai.core.omnichannel.discord;

import com.agenticai.core.omnichannel.discord.dto.DiscordActivationRequest;
import com.agenticai.core.omnichannel.discord.dto.DiscordCredentialResponse;
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

@RestController
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
@RequestMapping("/api/channels/discord/credential")
public class DiscordCredentialController {

    private final DiscordCredentialService credentialService;

    public DiscordCredentialController(DiscordCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public ResponseEntity<DiscordCredentialResponse> activate(
            @Valid @RequestBody DiscordActivationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentialService.activate(request));
    }

    @GetMapping
    public ResponseEntity<DiscordCredentialResponse> current() {
        return credentialService
                .current()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * "Log out of Discord" - see {@link DiscordCredentialService#disconnect}'s javadoc. Idempotent,
     * returns 204 whether or not anything was connected.
     */
    @DeleteMapping
    public ResponseEntity<Void> disconnect() {
        credentialService.disconnect();
        return ResponseEntity.noContent().build();
    }
}
