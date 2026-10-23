package com.agenticai.core.omnichannel.discord;

import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.stereotype.Component;

/**
 * Discord text sends for the kernel's {@link ChannelTextSend}: {@link DiscordChannelAdapter}'s
 * action type and payload.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
public class DiscordTextSend implements ChannelTextSend {

    @Override
    public ChannelType channel() {
        return ChannelType.DISCORD;
    }

    @Override
    public String actionType() {
        return DiscordChannelAdapter.ACTION_TYPE;
    }

    @Override
    public String summary(String to) {
        return "Send Discord message to " + to;
    }

    @Override
    public Object payload(String to, String subject, String body) {
        return new DiscordChannelAdapter.SendPayload(to, body);
    }
}
