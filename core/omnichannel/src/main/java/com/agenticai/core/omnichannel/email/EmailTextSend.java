package com.agenticai.core.omnichannel.email;

import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.springframework.stereotype.Component;

/**
 * Email text sends for the kernel's {@link ChannelTextSend}: {@link EmailChannelAdapter}'s action
 * type and payload.
 */
@Component
public class EmailTextSend implements ChannelTextSend {

    @Override
    public ChannelType channel() {
        return ChannelType.EMAIL;
    }

    @Override
    public String actionType() {
        return EmailChannelAdapter.ACTION_TYPE;
    }

    @Override
    public String summary(String to) {
        return "Send email to " + to;
    }

    @Override
    public Object payload(String to, String subject, String body) {
        return new EmailChannelAdapter.SendPayload(to, subject, body);
    }
}
