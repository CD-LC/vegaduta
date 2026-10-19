package com.agenticai.core.omnichannel.telegram;

import com.agenticai.core.omnichannel.telegram.dto.TelegramActivationRequest;
import com.agenticai.core.omnichannel.telegram.dto.TelegramCredentialResponse;
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
@RequestMapping("/api/channels/telegram/credential")
public class TelegramCredentialController {

    private final TelegramCredentialService credentialService;

    public TelegramCredentialController(TelegramCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    @PostMapping
    public ResponseEntity<TelegramCredentialResponse> activate(@Valid @RequestBody TelegramActivationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(credentialService.activate(request));
    }

    @GetMapping
    public ResponseEntity<TelegramCredentialResponse> current() {
        return credentialService.current()
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * "Log out of Telegram" - see {@link TelegramCredentialService#disconnect}'s
     * javadoc. Idempotent, returns 204 whether or not anything was connected.
     */
    @DeleteMapping
    public ResponseEntity<Void> disconnect() {
        credentialService.disconnect();
        return ResponseEntity.noContent().build();
    }
}
