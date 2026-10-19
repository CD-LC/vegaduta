package com.agenticai.core.agents.tools.messaging;

/**
 * "Forward a generated attachment to any channel" - same cross-module inversion as {@link
 * ChannelMessageQueryPort} for the same reason (see that interface's javadoc): the real per-channel
 * send logic lives in {@code core/omnichannel}, which already depends on {@code core/agents}, so
 * this port is defined here and implemented there, wired together by Spring at runtime with no
 * compile-time dependency in either direction beyond the one that already exists.
 *
 * <p>Deliberately takes a plain {@code String} channel name (case- insensitive against the
 * implementation's own channel enum), not a typed enum, for the same reason {@link
 * ChannelMessageQueryPort} does.
 */
public interface ChannelMediaSendPort {

    /**
     * @param channel channel name, e.g. {@code "SLACK"} - case-insensitive.
     * @param to recipient identifier in whatever shape that channel uses (a Slack channel id, a
     *     phone number, an email address, etc.)
     * @param bytes the raw attachment content.
     * @param contentType e.g. {@code "image/png"}, {@code "audio/mpeg"}.
     * @param filename a display filename - required by some channel APIs, ignored by others.
     * @param caption optional text to send alongside the attachment.
     * @return the outcome - never throws for an ordinary delivery failure, see {@link SendResult}.
     */
    SendResult sendMedia(
            String channel,
            String to,
            byte[] bytes,
            String contentType,
            String filename,
            String caption);

    /**
     * @param sent false for both "this channel doesn't support media yet" and "the send itself
     *     failed" - {@code detail} distinguishes them for the caller/tool-output message. Never
     *     carries a raw exception or provider credential in {@code detail}.
     */
    record SendResult(boolean sent, String detail) {}
}
