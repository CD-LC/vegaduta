package com.agenticai.core.omnichannel.meta;

import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.springframework.stereotype.Component;

/**
 * Instagram text sends for the kernel's {@link ChannelTextSend}: {@link
 * MetaInstagramChannelAdapter}'s action type and payload.
 */
@Component
public class MetaInstagramTextSend implements ChannelTextSend {

    @Override
    public ChannelType channel() {
        return ChannelType.INSTAGRAM;
    }

    @Override
    public String actionType() {
        return MetaInstagramChannelAdapter.ACTION_TYPE;
    }

    @Override
    public String summary(String to) {
        return "Send Instagram DM to " + to;
    }

    @Override
    public Object payload(String to, String subject, String body) {
        return new MetaInstagramChannelAdapter.SendPayload(to, body);
    }
}
