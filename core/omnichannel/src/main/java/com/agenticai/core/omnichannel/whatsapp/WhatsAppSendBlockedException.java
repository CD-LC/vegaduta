package com.agenticai.core.omnichannel.whatsapp;

/**
 * A WhatsApp template send was refused by our own policy guard; nothing reached Meta. Mapped to 400
 * by ClientRequestExceptionHandler. Deliberately not a ChannelException: that base lives in the
 * package that depends on this one.
 */
public class WhatsAppSendBlockedException extends RuntimeException {

    public WhatsAppSendBlockedException(String message) {
        super(message);
    }
}
