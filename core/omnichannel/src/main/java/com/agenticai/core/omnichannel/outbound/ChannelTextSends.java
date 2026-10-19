package com.agenticai.core.omnichannel.outbound;

import com.agenticai.core.omnichannel.session.ChannelType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Every {@link ChannelTextSend} bean, by channel. */
@Component
public class ChannelTextSends {

    private final Map<ChannelType, ChannelTextSend> byChannel = new EnumMap<>(ChannelType.class);

    public ChannelTextSends(List<ChannelTextSend> sends) {
        for (ChannelTextSend send : sends) {
            byChannel.putIfAbsent(send.channel(), send);
        }
    }

    public Optional<ChannelTextSend> forChannel(ChannelType channel) {
        return Optional.ofNullable(byChannel.get(channel));
    }
}
