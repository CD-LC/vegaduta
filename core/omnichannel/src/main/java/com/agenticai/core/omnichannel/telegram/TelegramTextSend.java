package com.agenticai.core.omnichannel.telegram;

import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.springframework.stereotype.Component;

/**
 * Telegram text sends for the kernel's {@link ChannelTextSend}: {@link TelegramChannelAdapter}'s
 * action type and payload.
 */
@Component
public class TelegramTextSend implements ChannelTextSend {

    @Override
    public ChannelType channel() {
        return ChannelType.TELEGRAM;
    }

    @Override
    public String actionType() {
        return TelegramChannelAdapter.ACTION_TYPE;
    }

    @Override
    public String summary(String to) {
        return "Send Telegram message to " + to;
    }

    @Override
    public Object payload(String to, String subject, String body) {
        return new TelegramChannelAdapter.SendPayload(to, body);
    }
}
