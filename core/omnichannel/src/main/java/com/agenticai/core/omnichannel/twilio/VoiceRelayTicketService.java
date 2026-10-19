package com.agenticai.core.omnichannel.twilio;

import com.agenticai.core.crypto.EncryptionService;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Single-call admission ticket for the Conversation Relay WebSocket (security
 * audit 2026-09-18).
 *
 * <p>{@code /webhook/twilio/voice-relay} sits under {@code permitAll()} and
 * used to authenticate nothing: the tenant came from the {@code accountSid} the
 * CLIENT put in its {@code setup} frame and the caller from the client-sent
 * {@code from}. An AccountSid is not a secret (it is in every Twilio URL), so
 * anyone who knew one plus a paired caller number could open the socket and
 * talk to that tenant's agent - its memory, its tools, its budget.
 *
 * <p>The one place a voice call IS authenticated is
 * {@link TwilioVoiceWebhookController#inboundCall}, where the
 * {@code X-Twilio-Signature} proves the request came from Twilio holding the
 * tenant's auth token. That controller now mints a ticket there and hands it to
 * Twilio inside the TwiML; Twilio presents it back on the socket. The ticket is
 * {@link EncryptionService}'s AES-GCM over {@code accountSid|callSid|from|expiry},
 * so it is unforgeable and tamper-evident without any new key to manage, and it
 * is the same on every replica. The relay handler takes the tenant, call and
 * caller FROM THE TICKET and ignores what the frame claims.
 */
@Service
public class VoiceRelayTicketService {

    /** A call is answered within seconds of the webhook; this only has to outlive Twilio's connect. */
    static final Duration TTL = Duration.ofMinutes(5);
    private static final String SEPARATOR = "|";

    private final EncryptionService encryptionService;
    private final Clock clock;

    @Autowired
    public VoiceRelayTicketService(EncryptionService encryptionService) {
        this(encryptionService, Clock.systemUTC());
    }

    VoiceRelayTicketService(EncryptionService encryptionService, Clock clock) {
        this.encryptionService = encryptionService;
        this.clock = clock;
    }

    /** What a verified ticket vouches for - every field came from a signature-checked Twilio webhook. */
    public record Admission(String accountSid, String callSid, String from) {}

    public String mint(String accountSid, String callSid, String from) {
        long expiresAt = clock.instant().plus(TTL).getEpochSecond();
        return encryptionService.encrypt(
            String.join(SEPARATOR, clean(accountSid), clean(callSid), clean(from), Long.toString(expiresAt))
        );
    }

    /** Empty for anything that is not a ticket this deployment minted and that has not expired - never throws. */
    public Optional<Admission> verify(String ticket) {
        if (ticket == null || ticket.isBlank() || ticket.length() > 1024) {
            return Optional.empty();
        }
        try {
            String[] parts = encryptionService.decrypt(ticket).split("\\" + SEPARATOR, -1);
            if (parts.length != 4 || parts[0].isBlank() || parts[1].isBlank()) {
                return Optional.empty();
            }
            if (clock.instant().getEpochSecond() > Long.parseLong(parts[3])) {
                return Optional.empty();
            }
            return Optional.of(new Admission(parts[0], parts[1], parts[2]));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(SEPARATOR, "");
    }
}
