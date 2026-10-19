package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.VoiceCallService;
import com.agenticai.core.speech.SpeechTextNormalizer;
import com.twilio.http.TwilioRestClient;
import com.twilio.rest.api.v2010.account.Call;
import com.twilio.twiml.TwiMLException;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Say;
import com.twilio.type.PhoneNumber;
import com.twilio.type.Twiml;
import org.springframework.stereotype.Service;

/**
 * Outbound phone calls - the third delivery channel for Simple mode's Quick Task feature ("...or on
 * giving a call"), and the first thing in this codebase to originate a call rather than just
 * answer/reply to inbound WhatsApp/SMS traffic. Nothing here existed before this feature - the
 * Twilio SDK's voice APIs were on the classpath (transitively, via the same {@code
 * com.twilio.sdk:twilio} dependency WhatsApp/SMS already use) but completely unused.
 *
 * <p>Uses Twilio's inline {@code twiml} parameter (a {@link Twiml} literal built right here) rather
 * than pointing the call at a {@code url} that Twilio would fetch TwiML from - that would mean
 * standing up a new public webhook endpoint just to say one sentence, real infrastructure this
 * "speak a short result over a phone call" feature doesn't need.
 */
@Service
public class TwilioVoiceCallService implements VoiceCallService {

    private final TwilioCredentialService twilioCredentialService;

    public TwilioVoiceCallService(TwilioCredentialService twilioCredentialService) {
        this.twilioCredentialService = twilioCredentialService;
    }

    /**
     * @param spokenMessage the sentence to say - usually a Quick Task result, i.e. LLM output, so
     *     it is run through {@link SpeechTextNormalizer#normalizeForSpeech} first. {@code <Say>} is
     *     a text-to-speech instruction: any markdown left in here is read out loud as "asterisk
     *     asterisk" (the 2026-07-29 live bug).
     * @return a short, human-readable log line describing what happened.
     */
    @Override
    public String call(String to, String spokenMessage) {
        TwilioCredentialService.DecryptedCredential credential =
                twilioCredentialService.resolveActive();
        try {
            TwilioRestClient client = credential.buildOutboundClient();
            String twiml =
                    new VoiceResponse.Builder()
                            .say(
                                    new Say.Builder(
                                                    SpeechTextNormalizer.normalizeForSpeech(
                                                            spokenMessage))
                                            .build())
                            .build()
                            .toXml();
            Call.creator(
                            new PhoneNumber(to),
                            new PhoneNumber(credential.whatsAppFromNumber()),
                            new Twiml(twiml))
                    .create(client);
            return "Placed a call to " + to + " via Twilio.";
        } catch (TwiMLException e) {
            // Building the <Say> XML failed (e.g. characters Twilio's TwiML
            // XML can't encode) - this is a bug in the message content, not
            // an upstream Twilio failure, so it's kept distinct from
            // ChannelDeliveryException below.
            throw new IllegalArgumentException("Could not build call script: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ChannelDeliveryException("call", "Twilio call failed: " + e.getMessage(), e);
        }
    }
}
