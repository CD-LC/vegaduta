package com.agenticai.core.omnichannel.outbound;

/**
 * An immediate (not approval-gated) text send to one destination on a channel, for workflow Output
 * nodes. Two steps so the caller can validate the destination before claiming its own at-least-once
 * dedup key: {@link #prepare} checks the channel is connected and the destination is addressable
 * now, {@link Prepared#send} delivers. Kernel-owned (ADR-0011 rule a); implemented in the channel's
 * package.
 */
public interface DirectTextDelivery {

    /** The channel key, e.g. {@code teams}. */
    String channel();

    /**
     * @throws RuntimeException the channel's own "not configured" or {@code
     *     ChannelConfigurationException} when the channel is not connected or {@code to} cannot be
     *     addressed yet
     */
    Prepared prepare(String to);

    @FunctionalInterface
    interface Prepared {

        /**
         * @return a short, human-readable log line describing what happened.
         */
        String send(String text);
    }
}
