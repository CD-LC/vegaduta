package com.agenticai.core.omnichannel.meta.dto;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code instagramBusinessAccountId} is deliberately optional - a tenant who
 * only wants Messenger (no Instagram product added to their Meta App yet)
 * leaves it blank, and {@link com.agenticai.core.omnichannel.meta.MetaSocialCredential#hasInstagram()}
 * is what everywhere downstream checks before treating Instagram as
 * available for that tenant.
 */
public record MetaSocialActivationRequest(
    @NotBlank String pageId,
    @NotBlank @Size(max = 4096) String pageAccessToken,
    @NotBlank @Size(max = 4096) String appSecret,
    String instagramBusinessAccountId
) {
    public boolean hasInstagram() {
        return instagramBusinessAccountId != null && !instagramBusinessAccountId.isBlank();
    }
}
