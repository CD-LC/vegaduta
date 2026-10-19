package com.agenticai.core.omnichannel.outbound;

import java.util.Optional;

/**
 * A channel's tenant-connected bot token, for callers that speak the channel's public bot API
 * themselves (the workflow Telegram Output node, which falls back to the platform relay bot).
 * Kernel-owned (ADR-0011 rule a); implemented in the channel's package.
 */
public interface TenantBotToken {

    /** The channel key, e.g. {@code telegram}. */
    String channel();

    /** Empty when the tenant has no usable bot connected (never throws). */
    Optional<String> activeBotToken();
}
