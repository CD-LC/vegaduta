package com.agenticai.core.omnichannel.relay;

import com.agenticai.core.omnichannel.session.ChannelMessageDirection;
import com.agenticai.core.omnichannel.session.ChannelMessageLog;
import com.agenticai.core.omnichannel.session.ChannelMessageLogRepository;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.security.TenantContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one call an inbound channel controller makes before it would generate a hosted reply:
 * {@link #answer} returns the relay's text when a browser relay is online for the channel and
 * answered within the reply window, otherwise empty and the controller proceeds exactly as before.
 * Runs on the controller's inbound executor task, which already carries the tenant context.
 */
@Component
public class EdgeRelayHandoff {

    private final EdgeRelayService relay;
    private final ChannelMessageLogRepository messageLog;
    private final int historyTurns;

    public EdgeRelayHandoff(
            EdgeRelayService relay,
            ChannelMessageLogRepository messageLog,
            @Value("${app.omnichannel.edge-relay.history-turns:12}") int historyTurns) {
        this.relay = relay;
        this.messageLog = messageLog;
        this.historyTurns = Math.max(0, Math.min(historyTurns, 50));
    }

    /**
     * @param chatMessage the text the agent would receive (the raw message plus any per-turn hint)
     * @return the relay's reply, or empty when the hosted path should answer
     */
    public Optional<String> answer(ChannelType channel, ChannelSession session, String chatMessage) {
        if (!relay.isEnabled() || session == null || session.getAgentId() == null) {
            return Optional.empty();
        }
        String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
        if (tenantId == null || !relay.isOnline(tenantId, channel)) {
            return Optional.empty();
        }
        Optional<RelayJob> job =
                relay.enqueue(
                        tenantId,
                        channel,
                        session.getId(),
                        session.getAgentId(),
                        mask(session.getExternalId()),
                        chatMessage,
                        recentHistory(session));
        return job.flatMap(j -> relay.awaitReply(tenantId, j.jobId()));
    }

    /**
     * The last {@code history-turns} messages of the session, oldest first, excluding the newest
     * inbound row (that is the message being answered, already in the job as {@code message}).
     */
    private List<RelayJob.RelayTurn> recentHistory(ChannelSession session) {
        if (historyTurns == 0) {
            return List.of();
        }
        List<ChannelMessageLog> newestFirst;
        try {
            newestFirst = messageLog.findTop200ByChannelSessionIdOrderByCreatedAtDesc(session.getId());
        } catch (RuntimeException e) {
            return List.of();
        }
        List<RelayJob.RelayTurn> turns = new ArrayList<>();
        boolean skippedCurrent = false;
        for (ChannelMessageLog row : newestFirst) {
            if (row.getBody() == null || row.getBody().isBlank()) {
                continue;
            }
            boolean inbound = row.getDirection() == null || row.getDirection() == ChannelMessageDirection.INBOUND;
            if (!skippedCurrent && inbound) {
                skippedCurrent = true;
                continue;
            }
            turns.add(new RelayJob.RelayTurn(inbound ? "user" : "assistant", row.getBody()));
            if (turns.size() >= historyTurns) {
                break;
            }
        }
        Collections.reverse(turns);
        return turns;
    }

    static String mask(String externalId) {
        if (externalId == null || externalId.length() < 4) {
            return "***";
        }
        return "***" + externalId.substring(externalId.length() - 4);
    }
}
