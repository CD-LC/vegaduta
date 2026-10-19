package com.agenticai.core.omnichannel.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the OUTBOUND half of {@link ChannelMessageLog} for every send that
 * flows through a channel adapter's {@code ApprovalActionExecutor#execute} -
 * the gap called out in {@code OperatorInboxService#transcript}'s javadoc
 * ("agent replies are absent - an honest, documented gap"). The gate's
 * executor chokepoint is the one place every channel send funnels through
 * (agent auto-replies, workflow-trigger replies and console test sends all
 * evaluate the same {@code channel.*.send} action types), so hooking the
 * adapters logs every outbound flavour with no webhook-controller changes.
 *
 * <p><b>Who is NOT logged here:</b> {@code requestedBy} starting with
 * {@code "operator:"} - {@code OperatorInboxService#reply} already writes
 * those rows itself (with the bare operator subject as author), and both
 * writes would fire because an operator reply executes through the same
 * adapters.
 *
 * <p><b>Fail-open by design:</b> the caller invokes this AFTER the channel
 * API send succeeded - a logging failure must never become a
 * {@code RetryableChannelDeliveryException}, because retrying a send that
 * already delivered would double-send the customer message. A transcript
 * gap is recoverable information loss; a duplicate customer-facing message
 * is not. {@link ChannelSessionService#resolveOrCreate} makes the same
 * call in the opposite direction (the operator's transcript completeness
 * beats rollback purity).
 *
 * <p>Sends to a recipient with no {@link ChannelSession} (a console test
 * send to an arbitrary number) write nothing - the log's contract is "the
 * transcript of a mapped conversation", and fabricating a session row for
 * a one-off test target would pollute the inbox's conversation list.
 */
@Service
public class ChannelOutboundLogger {

    private static final Logger log = LoggerFactory.getLogger(ChannelOutboundLogger.class);

    private final ChannelSessionRepository sessionRepository;
    private final ChannelMessageLogRepository messageLogRepository;

    public ChannelOutboundLogger(
        ChannelSessionRepository sessionRepository, ChannelMessageLogRepository messageLogRepository
    ) {
        this.sessionRepository = sessionRepository;
        this.messageLogRepository = messageLogRepository;
    }

    /**
     * Appends one OUTBOUND row to the conversation with {@code
     * recipientExternalId}, if that recipient maps to a session. {@code
     * requestedBy} is the {@code ApprovalRequest}'s requestedBy verbatim:
     * {@code "system:*"} (webhook auto-replies, workflow-trigger replies)
     * becomes the display label {@code "agent"} - the raw value embeds the
     * recipient's external id, which {@code external_id} already carries -
     * while a console test send keeps its real Keycloak subject, the same
     * attribution the approval row records.
     */
    @Transactional
    public void recordSent(ChannelType channel, String recipientExternalId, String body, String requestedBy) {
        try {
            if (recipientExternalId == null || recipientExternalId.isBlank()) {
                return;
            }
            if (requestedBy != null && requestedBy.startsWith("operator:")) {
                return; // OperatorInboxService#reply writes these - see class javadoc.
            }
            sessionRepository.findByChannelAndExternalId(channel, recipientExternalId).ifPresent(session ->
                messageLogRepository.save(new ChannelMessageLog(
                    channel, recipientExternalId, session.getId(), body,
                    ChannelMessageDirection.OUTBOUND, authorLabel(requestedBy)
                ))
            );
        } catch (RuntimeException e) {
            log.warn("Failed to log outbound {} transcript row - the send itself already succeeded", channel, e);
        }
    }

    private static String authorLabel(String requestedBy) {
        if (requestedBy == null || requestedBy.isBlank() || requestedBy.startsWith("system:")) {
            return "agent";
        }
        return requestedBy;
    }
}
