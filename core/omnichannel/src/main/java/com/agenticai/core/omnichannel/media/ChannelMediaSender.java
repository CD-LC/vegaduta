package com.agenticai.core.omnichannel.media;

import com.agenticai.core.omnichannel.session.ChannelType;

/**
 * One implementation per channel that can actually deliver a media
 * attachment (as opposed to text-only messages) - {@link ChannelMediaSendPortImpl}
 * dispatches to whichever bean's {@link #channel()} matches the request.
 * Not every {@link ChannelType} needs an implementation: a channel with no
 * bean here simply isn't forwardable yet, and {@link ChannelMediaSendPortImpl}
 * reports that honestly rather than throwing or silently no-op'ing - same
 * "never overclaim a capability that isn't actually wired up" norm {@link
 * com.agenticai.core.omnichannel.trigger.WorkflowTriggerChannel}'s javadoc
 * documents for CALL.
 *
 * <p>Each implementation owns translating {@code bytes}/{@code contentType}
 * into whatever that channel's own media-send API actually requires (a
 * public media URL for Twilio, a two-step upload for Slack's newer Files
 * API, multipart for Discord, a {@code MimeMessage} attachment for email,
 * etc.) - there is deliberately no shared HTTP-building code here, since
 * every provider's media contract is different enough that abstracting it
 * further would just be indirection with no reuse behind it.
 */
public interface ChannelMediaSender {

    ChannelType channel();

    /**
     * @return true if the provider accepted the send. A false/exception-free
     *     "not sent" (see {@link ChannelMediaSendPortImpl}) is for "this
     *     channel has no sender at all" - an implementation that IS present
     *     should throw on a real delivery failure so the caller sees an
     *     actionable error rather than a silent no-op.
     */
    void send(String to, byte[] bytes, String contentType, String filename, String caption) throws Exception;
}
