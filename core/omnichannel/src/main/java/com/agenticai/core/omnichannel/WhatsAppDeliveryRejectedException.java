package com.agenticai.core.omnichannel;

/**
 * Meta's Cloud API refused a WhatsApp send and returned an error code (131049, 131050, 132015...).
 * {@code metaCode} is 0 when the response carried none.
 */
public class WhatsAppDeliveryRejectedException extends ChannelDeliveryException {

    private final int metaCode;

    public WhatsAppDeliveryRejectedException(String message, int metaCode) {
        super("whatsapp", message);
        this.metaCode = metaCode;
    }

    public int getMetaCode() {
        return metaCode;
    }
}
