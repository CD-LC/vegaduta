package com.agenticai.core.omnichannel.imessage;

import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.springframework.stereotype.Component;

/** iMessage text sends for the kernel's {@link ChannelTextSend}: {@link ImessageChannelAdapter}'s action type and payload. */
@Component
public class ImessageTextSend implements ChannelTextSend {

    @Override
    public ChannelType channel() {
        return ChannelType.IMESSAGE;
    }

    @Override
    public String actionType() {
        return ImessageChannelAdapter.ACTION_TYPE;
    }

    @Override
    public String summary(String to) {
        return "Send iMessage to " + to;
    }

    @Override
    public Object payload(String to, String subject, String body) {
        return new ImessageChannelAdapter.SendPayload(to, body);
    }
}
