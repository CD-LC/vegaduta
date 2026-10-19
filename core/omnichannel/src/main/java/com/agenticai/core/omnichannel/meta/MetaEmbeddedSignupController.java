package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.omnichannel.meta.dto.EmbeddedSignupConfigResponse;
import com.agenticai.core.omnichannel.meta.dto.EmbeddedSignupExchangeRequest;
import com.agenticai.core.omnichannel.meta.dto.MetaCredentialResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guided WhatsApp connection via Meta's Embedded Signup - see {@code
 * MetaEmbeddedSignupService}'s javadoc for the full flow and what the
 * platform operator must configure before this is available at all. Sits
 * alongside, and never replaces, {@code MetaWhatsAppCredentialController}'s
 * manual BYOK endpoint.
 */
@RestController
@RequestMapping("/api/channels/meta-whatsapp/embedded-signup")
public class MetaEmbeddedSignupController {

    private final MetaEmbeddedSignupService signupService;

    public MetaEmbeddedSignupController(MetaEmbeddedSignupService signupService) {
        this.signupService = signupService;
    }

    /**
     * Whether the guided button should even be shown - {@code
     * ChannelsPanel.js} calls this on load and falls back to "not available,
     * use manual setup" text if it 400s (see {@code
     * MetaEmbeddedSignupNotConfiguredException}) rather than showing a
     * broken button.
     */
    @GetMapping("/config")
    public EmbeddedSignupConfigResponse config() {
        return signupService.config();
    }

    @PostMapping("/exchange")
    public ResponseEntity<MetaCredentialResponse> exchange(@Valid @RequestBody EmbeddedSignupExchangeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(signupService.exchange(request));
    }
}
