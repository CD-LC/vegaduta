package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.springframework.stereotype.Component;

/**
 * Messenger text sends for the kernel's {@link ChannelTextSend}: {@link
 * MetaMessengerChannelAdapter}'s action type and payload.
 */
@Component
public class MetaMessengerTextSend implements ChannelTextSend {

    @Override
    public ChannelType channel() {
        return ChannelType.MESSENGER;
    }

    @Override
    public String actionType() {
        return MetaMessengerChannelAdapter.ACTION_TYPE;
    }

    @Override
    public String summary(String to) {
        return "Send Messenger message to " + to;
    }

    @Override
    public Object payload(String to, String subject, String body) {
        return new MetaMessengerChannelAdapter.SendPayload(to, body);
    }
}
