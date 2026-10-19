package com.agenticai.core.workflow.output;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.ChannelDeliveryException;
import com.agenticai.core.omnichannel.outbound.DirectTextDelivery;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Output node "teams" destination (migration V240, 2026-09-23). Unlike {@code
 * TelegramOutputService} there is no platform-shared fallback bot - Teams has no notion of a global
 * bot a tenant's users could be reached through; every send goes through the tenant's own Azure Bot
 * registration ({@link TeamsCredentialService}). The destination is a Bot Framework {@code
 * conversationId}; its companion {@code serviceUrl} comes from {@code teams_conversation_route}
 * (populated by every inbound activity), so a Teams Output node can only reach conversations that
 * have messaged the bot at least once - an honest limit of the Bot Framework connector, not a
 * platform choice.
 */
@Service
public class TeamsOutputService {

    private static final Logger log = LoggerFactory.getLogger(TeamsOutputService.class);

    // The Teams channel's credential + per-conversation serviceUrl lookup and
    // Bot Framework call (ADR-0011 rule a: the channel package owns them).
    private final DirectTextDelivery teams;
    private final WorkflowOutputDedupGuard dedupGuard;

    public TeamsOutputService(
            List<DirectTextDelivery> deliveries, WorkflowOutputDedupGuard dedupGuard) {
        this.teams =
                deliveries.stream()
                        .filter(d -> "teams".equals(d.channel()))
                        .findFirst()
                        .orElse(null);
        this.dedupGuard = dedupGuard;
    }

    /**
     * @param runId the workflow run this send belongs to.
     * @param nodeId the Output node's id within the workflow graph.
     * @return a short, human-readable log line describing what happened, for the run's detailed
     *     trace.
     */
    public String send(String conversationId, String text, String runId, String nodeId) {
        if (!StringUtils.hasText(conversationId)) {
            throw new ChannelConfigurationException(
                    "teams", "Output node is missing a destination conversation ID.");
        }
        if (teams == null) {
            throw new ChannelConfigurationException(
                    "teams", "The Teams channel is not available in this deployment.");
        }
        DirectTextDelivery.Prepared delivery = teams.prepare(conversationId);
        // At-least-once execution can re-run this send verbatim - claim after
        // config validation, release on delivery failure so a retry of a send
        // that never reached Bot Framework isn't suppressed. Same shape as
        // TelegramOutputService above.
        String dedupKey = dedupGuard.identityKey("teams", runId, nodeId, conversationId);
        if (!dedupGuard.tryClaim(dedupKey)) {
            log.info(
                    "Suppressed duplicate workflow-output Teams send (dedup key already claimed within TTL).");
            return "Skipped duplicate Teams message to conversation "
                    + conversationId
                    + " - an identical send already went out moments ago.";
        }
        try {
            return delivery.send(text);
        } catch (ChannelDeliveryException | ChannelConfigurationException e) {
            dedupGuard.release(dedupKey);
            throw e;
        } catch (Exception e) {
            dedupGuard.release(dedupKey);
            throw new ChannelDeliveryException(
                    "teams", "Could not reach Bot Framework: " + e.getMessage());
        }
    }
}
