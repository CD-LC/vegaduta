package com.agenticai.core.omnichannel.whatsappqr;

import com.agenticai.core.omnichannel.outbound.ChannelTextSend;
import com.agenticai.core.omnichannel.session.ChannelType;
import org.springframework.stereotype.Component;

/** WhatsApp QR text sends for the kernel's {@link ChannelTextSend}: {@link WhatsAppQrChannelAdapter}'s action type and payload. */
@Component
public class WhatsAppQrTextSend implements ChannelTextSend {

    @Override
    public ChannelType channel() {
        return ChannelType.WHATSAPP_QR;
    }

    @Override
    public String actionType() {
        return WhatsAppQrChannelAdapter.ACTION_TYPE;
    }

    @Override
    public String summary(String to) {
        return "Send WhatsApp message to " + WhatsAppQrAddress.mask(to);
    }

    @Override
    public Object payload(String to, String subject, String body) {
        if (!WhatsAppQrAddress.isAddressable(to)) {
            throw new RecipientNotAddressableException(
                "WhatsApp QR needs a number like 4915112345678 or a paired chat id.");
        }
        return new WhatsAppQrChannelAdapter.SendPayload(to, body);
    }
}
