package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.omnichannel.status.ChannelNotConnectedException;
import com.twilio.rest.api.v2010.Account;
import org.springframework.stereotype.Component;

/**
 * The live "test connection" check for everything that rides on the tenant's Twilio credential
 * (WhatsApp via Twilio, Voice): a non-billed account fetch.
 */
@Component
public class TwilioAccountProbe {

    private final TwilioCredentialService credentialService;

    public TwilioAccountProbe(TwilioCredentialService credentialService) {
        this.credentialService = credentialService;
    }

    /**
     * @return the account's friendly name, or its SID; never a secret.
     */
    public String accountIdentity() {
        try {
            TwilioCredentialService.DecryptedCredential c = credentialService.resolveActive();
            Account account = Account.fetcher(c.accountSid()).fetch(c.buildOutboundClient());
            return account.getFriendlyName() != null ? account.getFriendlyName() : c.accountSid();
        } catch (TwilioNotConfiguredException e) {
            throw new ChannelNotConnectedException();
        }
    }
}
