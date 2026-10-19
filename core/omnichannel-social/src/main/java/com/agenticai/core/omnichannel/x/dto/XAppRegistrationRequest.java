package com.agenticai.core.omnichannel.x.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Step 1 of 2 - see {@code XCredential}'s javadoc. Pasted from the tenant's own X Developer Portal
 * app (Keys and tokens -> OAuth 2.0 Client ID and Client Secret).
 */
public record XAppRegistrationRequest(
        @NotBlank String clientId, @NotBlank @Size(max = 4096) String clientSecret) {}
