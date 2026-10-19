package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.SmsDispatchService;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.type.PhoneNumber;
import org.springframework.stereotype.Service;

/**
 * Plain SMS send - built for Simple mode's Quick Task feature ("type what you want, get the result
 * on WhatsApp, SMS, or a call"), the first thing in this codebase to need SMS rather than WhatsApp.
 *
 * <p>Deliberately reuses the same {@link
 * TwilioCredentialService.DecryptedCredential#whatsAppFromNumber()} as the tenant's outbound sender
 * number rather than adding a dedicated SMS-from-number field to {@code TwilioCredential} - most
 * Twilio numbers capable of WhatsApp are also SMS-capable on the same number, and this keeps the
 * credential activation form unchanged for this first pass. If a tenant's number genuinely can't
 * send SMS, Twilio's own error surfaces through {@link ChannelDeliveryException} same as any other
 * send failure - a dedicated field can be added later if that turns out to matter in practice.
 *
 * <p>No Meta-equivalent here (unlike {@link
 * com.agenticai.core.omnichannel.WhatsAppDispatchService}) - Meta's Cloud API is WhatsApp-only, so
 * Twilio is the only path for SMS regardless of which WhatsApp provider a tenant has activated.
 */
@Service
public class TwilioSmsDispatchService implements SmsDispatchService {

    private final TwilioCredentialService twilioCredentialService;

    public TwilioSmsDispatchService(TwilioCredentialService twilioCredentialService) {
        this.twilioCredentialService = twilioCredentialService;
    }

    @Override
    public String send(String to, String body) {
        com.agenticai.core.security.ImpersonationContext.assertNotImpersonating("sms-send");
        TwilioCredentialService.DecryptedCredential credential =
                twilioCredentialService.resolveActive();
        try {
            TwilioRestClient client = credential.buildOutboundClient();
            Message.creator(
                            new PhoneNumber(to),
                            new PhoneNumber(credential.whatsAppFromNumber()),
                            body)
                    .create(client);
            return "Sent SMS to " + to + " via Twilio.";
        } catch (RuntimeException e) {
            throw new ChannelDeliveryException(
                    "sms", "Twilio SMS send failed: " + e.getMessage(), e);
        }
    }
}
