package com.agenticai.core.voice.realtime;

import com.agenticai.core.crypto.EncryptionService;
import com.agenticai.core.security.edition.ConditionalOnEditionFeature;
import com.agenticai.core.security.edition.EditionFeature;
import com.agenticai.core.security.ticket.SingleUseTicketRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Admission ticket for the realtime-voice WebSocket relay - the exact {@code
 * VoiceRelayTicketService} pattern (2026-09-18 security audit): {@code /webhook/voice/realtime} is
 * a {@code permitAll} endpoint because browser WebSocket handshakes cannot carry the Keycloak JWT
 * as an Authorization header (the browser {@code WebSocket} API has no headers). So the
 * authenticated {@code POST /api/voice/realtime/sessions} mints a short-lived {@link
 * EncryptionService} AES-GCM ticket binding tenant schema + agent + provider + subject + expiry,
 * and the relay takes its routing from the ticket - never from what the frame claims.
 *
 * <p>The ticket is also single-use: {@link #verifyAndConsume} burns it on the first accepted setup
 * frame, so one that leaks (a proxy log, a browser history, a screen share) cannot open a second
 * session within its five minutes. The ticket is AES-GCM, so tampering is rejected by the
 * authentication tag rather than by a comparison; there is no secret string here to compare in
 * constant time.
 */
@ConditionalOnEditionFeature(EditionFeature.VOICE_REALTIME_SHARE)
@Service
public class RealtimeVoiceTicketService {

    /** Long enough to click "call" and connect; short enough to matter. */
    static final Duration TTL = Duration.ofMinutes(5);

    private static final String SEPARATOR = "|";

    private final EncryptionService encryptionService;
    private final Clock clock;
    private final SingleUseTicketRegistry consumed;

    @Autowired
    public RealtimeVoiceTicketService(EncryptionService encryptionService) {
        this(encryptionService, Clock.systemUTC());
    }

    RealtimeVoiceTicketService(EncryptionService encryptionService, Clock clock) {
        this.encryptionService = encryptionService;
        this.clock = clock;
        this.consumed = new SingleUseTicketRegistry(clock, 100_000);
    }

    /**
     * What a verified ticket vouches for - every field was set server-side by the session-create
     * endpoint.
     */
    public record Admission(
            String tenantSchema, UUID agentId, RealtimeProvider provider, String subject) {}

    public String mint(
            String tenantSchema, UUID agentId, RealtimeProvider provider, String subject) {
        long expiresAt = clock.instant().plus(TTL).getEpochSecond();
        return encryptionService.encrypt(
                String.join(
                        SEPARATOR,
                        clean(tenantSchema),
                        agentId.toString(),
                        provider.name(),
                        clean(subject),
                        Long.toString(expiresAt)));
    }

    /**
     * Empty for anything that is not an unexpired ticket this deployment minted - never throws.
     * Does not burn it.
     */
    public Optional<Admission> verify(String ticket) {
        return parse(ticket).map(Parsed::admission);
    }

    /**
     * {@link #verify} that also burns the ticket: the first call with a valid ticket returns its
     * admission, every later call with the same ticket returns empty. This is what the relay uses.
     */
    public Optional<Admission> verifyAndConsume(String ticket) {
        Optional<Parsed> parsed = parse(ticket);
        if (parsed.isEmpty() || !consumed.tryConsume(ticket, parsed.get().expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(parsed.get().admission());
    }

    private record Parsed(Admission admission, long expiresAt) {}

    private Optional<Parsed> parse(String ticket) {
        if (ticket == null || ticket.isBlank() || ticket.length() > 2048) {
            return Optional.empty();
        }
        try {
            String[] parts = encryptionService.decrypt(ticket).split("\\" + SEPARATOR, -1);
            if (parts.length != 5
                    || parts[0].isBlank()
                    || parts[1].isBlank()
                    || parts[2].isBlank()) {
                return Optional.empty();
            }
            long expiresAt = Long.parseLong(parts[4]);
            if (clock.instant().getEpochSecond() > expiresAt) {
                return Optional.empty();
            }
            return Optional.of(
                    new Parsed(
                            new Admission(
                                    parts[0],
                                    UUID.fromString(parts[1]),
                                    RealtimeProvider.valueOf(parts[2]),
                                    parts[3]),
                            expiresAt));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replace(SEPARATOR, "");
    }
}
