package com.agenticai.core.omnichannel.signal;

import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.springframework.stereotype.Component;

/** Signal text sends for the kernel's {@link ChannelTextSend}: {@link SignalChannelAdapter}'s action type and payload. */
@Component
public class SignalTextSend implements ChannelTextSend {

    @Override
    public ChannelType channel() {
        return ChannelType.SIGNAL;
    }

    @Override
    public String actionType() {
        return SignalChannelAdapter.ACTION_TYPE;
    }

    @Override
    public String summary(String to) {
        return "Send Signal message to " + SignalAddress.mask(to);
    }

    @Override
    public Object payload(String to, String subject, String body) {
        if (!SignalAddress.isAddressable(to)) {
            throw new RecipientNotAddressableException(
                "Signal needs a number like +4915112345678, a UUID or group:<id>.");
        }
        return new SignalChannelAdapter.SendPayload(to, body);
    }
}
