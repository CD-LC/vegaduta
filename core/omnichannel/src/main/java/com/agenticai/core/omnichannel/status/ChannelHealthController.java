package com.agenticai.core.omnichannel.status;

import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import com.agenticai.core.omnichannel.whatsapp.WhatsAppProvider;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Test connection" - a live probe per channel, the missing half of the connect/disconnect
 * lifecycle: a stored credential can be syntactically valid and still be dead (revoked bot token,
 * rotated app secret, IMAP password changed). Every probe below is the same real, non-billed call
 * activation used to prove the credential in the first place - never a synthetic ping - so a green
 * here means the channel genuinely works right now, and a red comes back with the upstream error a
 * tenant needs to fix it. {@code POST} (not {@code GET}) because these probes mutate nothing but do
 * spend a real outbound call each; gated {@code PERM_MANAGE_CHANNELS} in SecurityConfig alongside
 * the credential writes they validate.
 *
 * <p>Each channel's probe lives in its own package ({@link ChannelConnection}, ADR-0011 rule a);
 * WhatsApp probes whichever {@link WhatsAppProvider} the next send would go through.
 */
@RestController
@RequestMapping("/api/channels/health")
public class ChannelHealthController {

    private final WhatsAppDispatchService whatsAppDispatchService;
    private final Map<String, ChannelConnection> connections = new HashMap<>();

    public ChannelHealthController(
            WhatsAppDispatchService whatsAppDispatchService, List<ChannelConnection> connections) {
        this.whatsAppDispatchService = whatsAppDispatchService;
        for (ChannelConnection connection : connections) {
            this.connections.putIfAbsent(connection.key(), connection);
        }
    }

    @PostMapping("/{key}")
    public ChannelHealthResponse check(@PathVariable String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        long startNanos = System.nanoTime();
        try {
            String identity = probe(normalized);
            return new ChannelHealthResponse(
                    normalized, true, identity, elapsedMs(startNanos), null);
        } catch (ChannelNotConnectedException e) {
            return new ChannelHealthResponse(
                    normalized, false, null, elapsedMs(startNanos), "not connected");
        } catch (IllegalArgumentException e) {
            return new ChannelHealthResponse(
                    normalized, false, null, elapsedMs(startNanos), e.getMessage());
        } catch (RuntimeException e) {
            return new ChannelHealthResponse(
                    normalized, false, null, elapsedMs(startNanos), e.getMessage());
        }
    }

    /**
     * @return a short identity string ("who answered"), never any secret.
     * @throws ChannelNotConnectedException when the channel has no ACTIVE credential at all.
     */
    private String probe(String key) {
        switch (key) {
            case "whatsapp" -> {
                // Same precedence the dispatch path uses - Meta first, then
                // Twilio - so the card tests the provider that would
                // actually carry the next message, not a dead secondary one.
                WhatsAppProvider provider =
                        whatsAppDispatchService
                                .activeProvider()
                                .orElseThrow(ChannelNotConnectedException::new);
                return provider.label() + " - " + provider.probeIdentity();
            }
            case "voice",
                    "sms",
                    "imessage",
                    "telegram",
                    "matrix",
                    "slack",
                    "discord",
                    "teams",
                    "email",
                    "messenger",
                    "instagram",
                    "signal",
                    "x" -> {
                ChannelConnection connection = connections.get(key);
                if (connection == null) {
                    throw new ChannelNotConnectedException();
                }
                return connection.probe();
            }
            case "mobile-sdk" ->
                    throw new IllegalArgumentException(
                            "Mobile SDK has no remote credential to probe - check paired devices on the Devices panel");
            default ->
                    throw new IllegalArgumentException(
                            "Unknown channel '"
                                    + key
                                    + "' - expected one of: whatsapp, telegram, slack, "
                                    + "discord, teams, email, messenger, instagram, x, voice, sms, signal, matrix, imessage, mobile-sdk");
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public record ChannelHealthResponse(
            String key, boolean ok, String identity, long latencyMs, String detail) {}
}
