package com.agenticai.core.omnichannel.meta.dto;

/**
 * Only the two values safe to hand to the browser so it can initialize the
 * Facebook JS SDK and call {@code FB.login} - never the platform's App
 * Secret, which stays server-side only (used by {@code
 * MetaEmbeddedSignupService} for the actual token exchange).
 */
public record EmbeddedSignupConfigResponse(String appId, String configId) {
}
