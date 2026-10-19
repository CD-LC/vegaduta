package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import com.agenticai.core.omnichannel.whatsapp.WhatsAppProvider;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Message;
import com.twilio.rest.api.v2010.account.MessageCreator;
import com.twilio.type.PhoneNumber;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * WhatsApp through Twilio - used when the tenant has no Meta Cloud API connection. Once a tenant
 * disconnects Twilio ({@code TwilioCredentialService#disconnect}), {@link #isActive()} is false and
 * a send fails with a clear configuration error. Delivery status comes back on {@code
 * /webhook/twilio/status} when a public base URL is configured.
 */
@Component
public class TwilioWhatsAppProvider implements WhatsAppProvider {

    private static final String STATUS_CALLBACK_PATH = "/webhook/twilio/status";

    private final TwilioCredentialService credentialService;
    private final TwilioAccountProbe accountProbe;
    private final String publicBaseUrl;

    public TwilioWhatsAppProvider(
            TwilioCredentialService credentialService,
            TwilioAccountProbe accountProbe,
            @Value("${app.omnichannel.public-base-url:}") String publicBaseUrl) {
        this.credentialService = credentialService;
        this.accountProbe = accountProbe;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Override
    public int precedence() {
        return 20;
    }

    @Override
    public String label() {
        return "Twilio";
    }

    @Override
    public boolean isActive() {
        return credentialService.isActive();
    }

    @Override
    public String sendText(String to, String body) {
        TwilioCredentialService.DecryptedCredential credential = credentialService.resolveActive();
        try {
            TwilioRestClient client = credential.buildOutboundClient();
            MessageCreator creator =
                    Message.creator(
                            new PhoneNumber("whatsapp:" + to),
                            new PhoneNumber("whatsapp:" + credential.whatsAppFromNumber()),
                            body);
            if (StringUtils.hasText(publicBaseUrl)) {
                creator.setStatusCallback(
                        URI.create(publicBaseUrl.replaceAll("/+$", "") + STATUS_CALLBACK_PATH));
            }
            Message message = creator.create(client);
            return "Sent \""
                    + truncate(body, 160)
                    + "\" to "
                    + to
                    + " via Twilio (SID "
                    + message.getSid()
                    + ", status "
                    + message.getStatus()
                    + ").";
        } catch (RuntimeException e) {
            throw new ChannelDeliveryException(
                    "whatsapp", "Twilio send failed: " + e.getMessage(), e);
        }
    }

    @Override
    public String probeIdentity() {
        if (!credentialService.isActive()) {
            throw new ChannelNotConnectedException();
        }
        return accountProbe.accountIdentity();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
