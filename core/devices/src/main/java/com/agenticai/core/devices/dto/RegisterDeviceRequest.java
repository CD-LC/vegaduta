package com.agenticai.core.devices.dto;

import com.agenticai.core.devices.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * #157 follow-up ("reuse existing Keycloak IdP brokering"): the Mobile SDK
 * app's alternative to the pairing-code flow ({@link ClaimRequest}) - the
 * caller already carries a Keycloak JWT (signed in via AppAuth against the
 * {@code agentic-ai-mobile} client), so tenant identity is resolved from
 * that token the same way any other authenticated console request is,
 * rather than a short-lived 6-digit code typed in by hand.
 */
public record RegisterDeviceRequest(@NotBlank String deviceName, @NotNull DevicePlatform platform) {
}
