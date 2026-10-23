package com.agenticai.core.omnichannel.x;

import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import org.springframework.stereotype.Component;

/**
 * X text sends for the kernel's {@link ChannelTextSend}: {@link XChannelAdapter}'s action type and
 * payload.
 */
@Component
@ConditionalOnEditionFeature(EditionFeature.SOCIAL_CHANNELS)
public class XTextSend implements ChannelTextSend {

    @Override
    public ChannelType channel() {
        return ChannelType.X;
    }

    @Override
    public String actionType() {
        return XChannelAdapter.ACTION_TYPE;
    }

    @Override
    public String summary(String to) {
        return "Send X DM to " + to;
    }

    @Override
    public Object payload(String to, String subject, String body) {
        return new XChannelAdapter.SendPayload(to, body);
    }
}
