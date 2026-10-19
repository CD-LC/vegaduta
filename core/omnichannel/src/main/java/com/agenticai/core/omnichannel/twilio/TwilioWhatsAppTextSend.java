package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.springframework.stereotype.Component;

/**
 * WhatsApp text sends for the kernel's {@link ChannelTextSend}: {@link
 * TwilioWhatsAppChannelAdapter}'s action type and payload, whichever way the tenant's WhatsApp is
 * connected - that adapter's executor routes through {@code WhatsAppDispatchService}, which picks
 * Meta or Twilio per tenant.
 */
@Component
public class TwilioWhatsAppTextSend implements ChannelTextSend {

    @Override
    public ChannelType channel() {
        return ChannelType.WHATSAPP;
    }

    @Override
    public String actionType() {
        return TwilioWhatsAppChannelAdapter.ACTION_TYPE;
    }

    @Override
    public String summary(String to) {
        return "Send WhatsApp message to " + to;
    }

    @Override
    public Object payload(String to, String subject, String body) {
        return new TwilioWhatsAppChannelAdapter.SendPayload(to, body);
    }
}
