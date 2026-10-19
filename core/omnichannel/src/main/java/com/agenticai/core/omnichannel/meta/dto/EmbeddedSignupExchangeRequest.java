package com.agenticai.core.omnichannel.meta.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * What the frontend collects from Meta's Embedded Signup popup and posts
 * back here: {@code code} comes from the {@code FB.login} callback itself;
 * {@code phoneNumberId}/{@code wabaId} come from a separate {@code
 * window.postMessage} event Meta's popup fires mid-flow - Meta's JS SDK
 * splits these across two channels, so {@code ChannelsPanel.js} registers
 * listeners for both and only calls this endpoint once it has all three.
 */
public record EmbeddedSignupExchangeRequest(
    @NotBlank String code,
    @NotBlank String phoneNumberId,
    @NotBlank String wabaId
) {
}
