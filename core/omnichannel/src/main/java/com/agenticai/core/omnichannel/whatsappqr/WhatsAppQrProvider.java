package com.agenticai.core.omnichannel.whatsappqr;

import com.agenticai.core.omnichannel.whatsapp.WhatsAppProvider;
import org.springframework.stereotype.Component;

/**
 * The QR bridge as a third {@link WhatsAppProvider}, after Meta Cloud API and Twilio: when it is
 * the tenant's only WhatsApp connection - the expected case on a personal install - {@code
 * WhatsAppDispatchService} sends through it, so the {@code whatsapp} tools, the workflow Output
 * node and the channel-status card all see "WhatsApp connected" without knowing which path
 * carries the bytes. Lowest precedence: an official Business-Platform provider always wins when
 * one is configured (it is the safer sender identity for business traffic).
 *
 * <p>Texts only - a linked device has no template API, no quality rating and no opt-in
 * management, so {@code supportsTemplates()} stays false and {@code sendTemplate} never reaches
 * here.
 */
@Component
public class WhatsAppQrProvider implements WhatsAppProvider {

    private final WhatsAppQrService service;
    private final WhatsAppQrClient client;

    public WhatsAppQrProvider(WhatsAppQrService service, WhatsAppQrClient client) {
        this.service = service;
        this.client = client;
    }

    @Override
    public int precedence() {
        return 30;
    }

    @Override
    public String label() {
        return "WhatsApp QR (linked device)";
    }

    @Override
    public boolean isActive() {
        return service.active().isPresent();
    }

    @Override
    public String sendText(String to, String body) {
        WhatsAppQrHandle handle = service.resolveActive();
        client.send(handle.sessionKey(), to, body);
        return "WhatsApp QR message sent to " + WhatsAppQrAddress.mask(to);
    }

    @Override
    public String probeIdentity() {
        WhatsAppQrHandle handle = service.resolveActive();
        client.sessionStatus(handle.sessionKey());
        return handle.phoneNumber() == null ? "linked device" : handle.phoneNumber();
    }
}
