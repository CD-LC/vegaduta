package com.agenticai.core.omnichannel.media;

import com.agenticai.core.agents.tools.messaging.ChannelMediaSendPort;
import com.agenticai.core.omnichannel.session.ChannelType;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The {@code core/omnichannel} side of {@link ChannelMediaSendPort}'s cross-module inversion - same
 * pattern as {@code ChannelMessageQueryPortImpl} (see that class's javadoc). Dispatches to
 * whichever {@link ChannelMediaSender} bean matches the requested channel; an empty/no-match {@code
 * senders} list is a supported state (no channel forwards media yet), not a startup error - each
 * channel's real sender gets added independently as it's built.
 */
@Component
public class ChannelMediaSendPortImpl implements ChannelMediaSendPort {

    private static final Logger log = LoggerFactory.getLogger(ChannelMediaSendPortImpl.class);

    private final Map<ChannelType, ChannelMediaSender> sendersByChannel;

    public ChannelMediaSendPortImpl(List<ChannelMediaSender> senders) {
        this.sendersByChannel =
                senders.stream()
                        .collect(
                                Collectors.toMap(ChannelMediaSender::channel, Function.identity()));
    }

    @Override
    public SendResult sendMedia(
            String channel,
            String to,
            byte[] bytes,
            String contentType,
            String filename,
            String caption) {
        Optional<ChannelType> resolved = parseChannel(channel);
        if (resolved.isEmpty()) {
            return new SendResult(false, "Unknown channel '" + channel + "'.");
        }
        ChannelMediaSender sender = sendersByChannel.get(resolved.get());
        if (sender == null) {
            return new SendResult(
                    false,
                    resolved.get()
                            + " does not support forwarding attachments yet - only channels with a "
                            + "registered media sender do.");
        }
        try {
            sender.send(to, bytes, contentType, filename, caption);
            return new SendResult(true, "Sent to " + resolved.get() + ".");
        } catch (Exception e) {
            log.warn("Attachment forward to {} failed", resolved.get(), e);
            return new SendResult(
                    false, resolved.get() + " rejected the attachment: " + e.getMessage());
        }
    }

    private static Optional<ChannelType> parseChannel(String channel) {
        if (channel == null) {
            return Optional.empty();
        }
        return Arrays.stream(ChannelType.values())
                .filter(type -> type.name().equalsIgnoreCase(channel.trim()))
                .findFirst();
    }
}
