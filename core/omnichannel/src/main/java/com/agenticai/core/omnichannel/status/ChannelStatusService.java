package com.agenticai.core.omnichannel.status;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.devices.MobileDevice;
import com.agenticai.core.devices.MobileDeviceRepository;
import com.agenticai.core.omnichannel.WhatsAppDispatchService;
import com.agenticai.core.omnichannel.session.ChannelDefaultAgent;
import com.agenticai.core.omnichannel.session.ChannelDefaultAgentRepository;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionRepository;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.whatsapp.WhatsAppProvider;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * AXON Channels screen (#472) - a single real aggregate over every channel's own credential service
 * + {@link ChannelSessionRepository}, so the console never has to fire nine separate requests (and
 * nine separate loading states) to paint one grid. Every field here is a real, already-existing
 * value from the channel it names - no invented percentages, no "40+" rounding, no synthetic
 * latency numbers (this platform has never measured per-message round-trip time for any channel, so
 * that mockup field is deliberately omitted rather than faked).
 *
 * <p>Served by {@link ChannelStatusController} and read by the agent control port. It is a service so
 * non-web code never injects a controller: the jobs and worker shapes do not load controllers.
 */
@Service
public class ChannelStatusService {

    private final WhatsAppDispatchService whatsAppDispatchService;
    private final Map<String, ChannelConnection> connections = new HashMap<>();
    private final ChannelSessionRepository channelSessionRepository;
    private final ChannelDefaultAgentRepository channelDefaultAgentRepository;
    private final MobileDeviceRepository mobileDeviceRepository;
    private final AgentRepository agentRepository;

    public ChannelStatusService(
            WhatsAppDispatchService whatsAppDispatchService,
            List<ChannelConnection> connections,
            ChannelSessionRepository channelSessionRepository,
            ChannelDefaultAgentRepository channelDefaultAgentRepository,
            MobileDeviceRepository mobileDeviceRepository,
            AgentRepository agentRepository) {
        this.whatsAppDispatchService = whatsAppDispatchService;
        for (ChannelConnection connection : connections) {
            this.connections.putIfAbsent(connection.key(), connection);
        }
        this.channelSessionRepository = channelSessionRepository;
        this.channelDefaultAgentRepository = channelDefaultAgentRepository;
        this.mobileDeviceRepository = mobileDeviceRepository;
        this.agentRepository = agentRepository;
    }

    public ChannelStatusResponse status() {
        List<ChannelStatusEntry> channels = new ArrayList<>();
        channels.add(whatsappEntry());
        addConnectionEntry(channels, "whatsappqr", "WhatsApp QR", ChannelType.WHATSAPP_QR);
        addConnectionEntry(channels, "telegram", "Telegram", ChannelType.TELEGRAM);
        addConnectionEntry(channels, "instagram", "Instagram DM", ChannelType.INSTAGRAM);
        addConnectionEntry(channels, "messenger", "Messenger", ChannelType.MESSENGER);
        addConnectionEntry(channels, "slack", "Slack", ChannelType.SLACK);
        addConnectionEntry(channels, "discord", "Discord", ChannelType.DISCORD);
        addConnectionEntry(channels, "x", "X - DMs", ChannelType.X);
        addConnectionEntry(channels, "teams", "Microsoft Teams", ChannelType.TEAMS);
        addConnectionEntry(channels, "email", "Email - IMAP", ChannelType.EMAIL);
        addConnectionEntry(channels, "voice", "Voice", ChannelType.VOICE);
        addConnectionEntry(channels, "sms", "SMS", ChannelType.SMS);
        addConnectionEntry(channels, "signal", "Signal", ChannelType.SIGNAL);
        addConnectionEntry(channels, "matrix", "Matrix", ChannelType.MATRIX);
        addConnectionEntry(channels, "imessage", "iMessage", ChannelType.IMESSAGE);
        channels.add(mobileSdkEntry());

        long liveCount = channels.stream().filter(c -> "CONNECTED".equals(c.state())).count();

        return new ChannelStatusResponse(liveCount, channels.size(), channels, recentInbound());
    }

    private ChannelStatusEntry whatsappEntry() {
        // Meta wins over Twilio (WhatsAppDispatchService precedence).
        //
        // #597 fix ("disconnected Twilio but it still shows as connected") -
        // this used twilioCredentialService.current().isPresent(), which
        // returns the stored row regardless of status - TwilioCredentialService
        // deliberately keeps a disconnected row around (status = DISCONNECTED,
        // never deleted) precisely so its own detail panel can still show
        // "was connected as ACxxxx - reconnect below" (see that class's
        // #current javadoc). That's the right check for THAT panel, but wrong
        // here: this method decides what the top-level "is WhatsApp connected,
        // and via what" summary shows, which must track the same ACTIVE-only
        // definition WhatsAppDispatchService.send() and
        // TwilioCredentialService#isActive already use for every real send -
        // otherwise the overview keeps calling a disconnected credential
        // "connected" forever, and (worse) a tenant reading that summary has
        // no way to tell their disconnect actually took effect.
        // The provider the next send would go through (Meta first, then
        // Twilio - WhatsAppDispatchService's own precedence), ACTIVE-only.
        Optional<WhatsAppProvider> provider = whatsAppDispatchService.activeProvider();
        String state = provider.isPresent() ? "CONNECTED" : "NOT_CONNECTED";
        String detail = provider.map(WhatsAppProvider::label).orElse("not connected");
        return channelEntry("whatsapp", "WhatsApp", state, detail, ChannelType.WHATSAPP);
    }

    // Every other channel reports through its own ChannelConnection
    // (ADR-0011 rule a), which applies the same ACTIVE-only #597 rule. A
    // channel with no connection bean is not part of this deployment (a lean
    // personal install registers no Slack, Discord, Teams or X beans), so it
    // gets no tile at all rather than a "+ connect" button that cannot work.
    private void addConnectionEntry(
            List<ChannelStatusEntry> channels, String key, String label, ChannelType type) {
        ChannelConnection connection = connections.get(key);
        if (connection == null) {
            return;
        }
        ChannelConnection.Summary summary = connection.summary();
        channels.add(channelEntry(key, label, summary.state(), summary.detail(), type));
    }

    private ChannelStatusEntry mobileSdkEntry() {
        List<MobileDevice> devices = mobileDeviceRepository.findByOrderByCreatedAtDesc();
        long active = devices.stream().filter(d -> !d.isRevoked()).count();
        if (active == 0) {
            return new ChannelStatusEntry(
                    "mobile-sdk", "Mobile SDK", "NOT_CONNECTED", "+ pair a device", 0, null, null);
        }
        long recentlyActive =
                devices.stream()
                        .filter(
                                d ->
                                        !d.isRevoked()
                                                && d.getLastSeenAt() != null
                                                && d.getLastSeenAt()
                                                        .isAfter(
                                                                Instant.now()
                                                                        .minus(1, ChronoUnit.DAYS)))
                        .count();
        Instant lastSeen =
                devices.stream()
                        .map(MobileDevice::getLastSeenAt)
                        .filter(java.util.Objects::nonNull)
                        .max(Instant::compareTo)
                        .orElse(null);
        return new ChannelStatusEntry(
                "mobile-sdk",
                "Mobile SDK",
                "CONNECTED",
                active + " paired · " + recentlyActive + " active today",
                active,
                lastSeen,
                null);
    }

    private ChannelStatusEntry channelEntry(
            String key, String label, String state, String detail, ChannelType type) {
        long sessionCount = channelSessionRepository.countByChannelAndPairedTrue(type);
        Instant lastMessageAt =
                channelSessionRepository
                        .findFirstByChannelAndPairedTrueOrderByLastMessageAtDesc(type)
                        .map(ChannelSession::getLastMessageAt)
                        .orElse(null);
        // Surfaced so the Channels screen's "ROUTE INBOUND TO" picker can show
        // the agent that's ALREADY configured, instead of always rendering
        // blank/unselected on every page load even after a tenant has saved
        // one (the gap that made saving look broken - PUT .../default-agent
        // always worked, GET just never read it back anywhere).
        UUID defaultAgentId =
                channelDefaultAgentRepository
                        .findByChannel(type)
                        .map(ChannelDefaultAgent::getAgentId)
                        .orElse(null);
        return new ChannelStatusEntry(
                key, label, state, detail, sessionCount, lastMessageAt, defaultAgentId);
    }

    private List<InboundLogEntry> recentInbound() {
        List<InboundLogEntry> entries = new ArrayList<>();
        for (ChannelSession session :
                channelSessionRepository.findTop10ByOrderByLastMessageAtDesc()) {
            String agentName =
                    agentRepository
                            .findById(session.getAgentId())
                            .map(a -> a.getName())
                            .orElse(null);
            entries.add(
                    new InboundLogEntry(
                            session.getChannel(),
                            // Direct feedback: "print the correct number without
                            // masking" - this is an admin-only screen (RECENT INBOUND
                            // is only reachable from the tenant-admin Channels page,
                            // already behind the platform's own auth), and an admin
                            // deciding approve/reject on an unknown sender needs the
                            // real, dialable number, not a 4-digit fragment - masking
                            // only made sense as a general-audience precaution, not
                            // for the person whose job is literally to vet this
                            // number. Was maskExternalId(session.getExternalId()).
                            //
                            // "still extract phone numbers are not visible" follow-up
                            // (migration V51) - externalId alone is sometimes a
                            // WhatsApp LID (opaque "<digits>@lid", not a dialable
                            // number - see ChannelSession's resolvedPhoneNumber
                            // javadoc), frozen in at first contact and never a real
                            // phone number for that session again. Prefer the
                            // opportunistically-resolved real number when one has ever
                            // been captured; fall back to externalId (still correct,
                            // just not always a phone number) otherwise.
                            session.getResolvedPhoneNumber() != null
                                    ? session.getResolvedPhoneNumber()
                                    : session.getExternalId(),
                            agentName,
                            session.isPaired(),
                            shortRef(session.getId()),
                            session.getLastMessageAt(),
                            // #590 follow-up ("wrong agent for telegram message
                            // sending") - the real session id and its currently-bound
                            // agent, so the Channels screen can offer a "reassign" action
                            // right on this row (ChannelPairingController#rebindAgent)
                            // instead of an admin only ever being able to SEE that a
                            // conversation is stuck on the wrong agent, never fix it.
                            // Not masked like externalId - this is an internal
                            // UUID, not a piece of the sender's real-world identity.
                            session.getId(),
                            session.getAgentId(),
                            // Direct feedback: "print more information in inbound - for
                            // approval" - an admin deciding approve/reject on a still-
                            // unpaired row had nothing to go on but a 4-digit masked
                            // number. createdAt (first contact - distinct from
                            // lastMessageAt, which only reflects the most recent retry)
                            // and the pairingCode itself (already generated per
                            // ChannelSession's javadoc, sent to the sender, but never
                            // surfaced back to the admin here) are the two pieces of
                            // context that actually help someone decide "do I know this
                            // number." pairingCode is null once approved
                            // (ChannelSession#approvePairing clears it) - not a lasting
                            // secret worth protecting after that point.
                            session.getCreatedAt(),
                            session.getPairingCode(),
                            // Direct feedback: "print some information like whats the
                            // message" - the most recent inbound text/subject, stored
                            // on ChannelSession.lastMessagePreview (migration V49) via
                            // ChannelSessionService#resolveOrCreate.
                            session.getLastMessagePreview()));
        }
        return entries;
    }

    private static String shortRef(UUID id) {
        return id == null ? "" : id.toString().substring(0, 8);
    }

    public record ChannelStatusEntry(
            String key,
            String label,
            String state,
            String detail,
            long sessionCount,
            Instant lastMessageAt,
            UUID defaultAgentId) {}

    public record InboundLogEntry(
            // "fromMasked" renamed to "from" (#602 follow-up, unmasking) - the
            // value is no longer masked, so the old name would be misleading.
            ChannelType channel,
            String from,
            String agentName,
            boolean mapped,
            String sessionRef,
            Instant lastMessageAt,
            UUID sessionId,
            UUID agentId,
            Instant firstSeenAt,
            String pairingCode,
            String lastMessagePreview) {}

    public record ChannelStatusResponse(
            long liveCount,
            long totalCount,
            List<ChannelStatusEntry> channels,
            List<InboundLogEntry> recentInbound) {}
}
