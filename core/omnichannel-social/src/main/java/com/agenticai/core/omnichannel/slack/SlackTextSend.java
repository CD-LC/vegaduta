package com.agenticai.core.omnichannel.slack;

import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.stereotype.Component;

/**
 * Slack text sends for the kernel's {@link ChannelTextSend}: {@link SlackChannelAdapter}'s action
 * type and payload.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
public class SlackTextSend implements ChannelTextSend {

    @Override
    public ChannelType channel() {
        return ChannelType.SLACK;
    }

    @Override
    public String actionType() {
        return SlackChannelAdapter.ACTION_TYPE;
    }

    @Override
    public String summary(String to) {
        return "Send Slack message to " + to;
    }

    @Override
    public Object payload(String to, String subject, String body) {
        return new SlackChannelAdapter.SendPayload(to, body);
    }
}
