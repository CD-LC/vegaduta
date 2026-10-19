package com.agenticai.core.omnichannel.email;

import com.agenticai.core.eventbus.WorkflowEventPublisher;
import com.agenticai.core.eventbus.WorkflowEventTypes;
import com.agenticai.core.omnichannel.email.EmailReplyClassifier.Kind;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Matches an inbound email to a {@code bulk_email} send (via {@code
 * In-Reply-To}/{@code References} against {@code email_message.message_id}),
 * stamps {@code replied_at}, suppresses permanently-bouncing recipients and
 * publishes {@link WorkflowEventTypes#EMAIL_REPLY_RECEIVED} /
 * {@link WorkflowEventTypes#EMAIL_BOUNCED} /
 * {@link WorkflowEventTypes#EMAIL_AUTO_REPLY_RECEIVED} carrying the original
 * run/node/recipient. Called from {@link EmailInboundPollingJob} with the
 * tenant context already set.
 *
 * <p>Deliberately independent of the leads/CRM stream: the event carries the
 * recipient email address, which is the join key a CRM consumer needs.
 */
@Service
public class EmailReplyTracker {

    private static final Logger log = LoggerFactory.getLogger(EmailReplyTracker.class);
    private static final int SNIPPET_MAX = 500;

    private final EmailMessageRepository messageRepository;
    private final EmailSuppressionRepository suppressionRepository;
    private final WorkflowEventPublisher eventPublisher;

    // Leads CRM hook - field-injected so the 3-arg constructor used by tests keeps working;
    // null = no CRM wiring, replies are simply not mirrored onto a lead timeline.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.agenticai.core.omnichannel.crm.LeadService leadService;

    public EmailReplyTracker(
        EmailMessageRepository messageRepository,
        EmailSuppressionRepository suppressionRepository,
        WorkflowEventPublisher eventPublisher
    ) {
        this.messageRepository = messageRepository;
        this.suppressionRepository = suppressionRepository;
        this.eventPublisher = eventPublisher;
    }

    /** Result of {@link #track}: the classification and how many bulk sends it matched. */
    public record Outcome(Kind kind, int matched) {
    }

    public Outcome track(EmailReplyClassifier.Headers headers, String fromAddress, String bodyText) {
        Kind kind = EmailReplyClassifier.classify(headers);
        List<String> ids = EmailReplyClassifier.referencedMessageIds(headers.inReplyTo(), headers.references());
        if (ids.isEmpty()) {
            return new Outcome(kind, 0);
        }
        List<EmailMessage> matches;
        try {
            matches = messageRepository.findByMessageIdIn(ids);
        } catch (RuntimeException e) {
            // Reply detection must never break inbound processing (e.g. the V335 tables not yet migrated).
            log.warn("Email reply lookup failed - continuing without reply tracking", e);
            return new Outcome(kind, 0);
        }
        if (matches.isEmpty()) {
            return new Outcome(kind, 0);
        }
        boolean permanent = kind == Kind.BOUNCE && EmailReplyClassifier.isPermanentBounce(bodyText);
        String eventType = switch (kind) {
            case BOUNCE -> WorkflowEventTypes.EMAIL_BOUNCED;
            case AUTO_REPLY -> WorkflowEventTypes.EMAIL_AUTO_REPLY_RECEIVED;
            case REPLY -> WorkflowEventTypes.EMAIL_REPLY_RECEIVED;
        };
        int handled = 0;
        for (EmailMessage original : matches) {
            // A human reply outranks an earlier auto-reply/bounce stamp; otherwise first signal wins.
            boolean alreadyStamped = original.getRepliedAt() != null
                && !(kind == Kind.REPLY && !Kind.REPLY.name().equals(original.getReplyKind()));
            if (alreadyStamped) {
                continue;
            }
            original.markReplied(Instant.now(), kind.name());
            messageRepository.save(original);
            if (permanent) {
                suppress(original.getRecipient());
            }
            publish(eventType, original, fromAddress, headers.subject(), bodyText, permanent);
            if (kind == Kind.REPLY) {
                mirrorToLead(original.getRecipient(), bodyText);
            }
            handled++;
        }
        return new Outcome(kind, handled);
    }

    /** A human reply to an outreach email lands on the matching lead's timeline (no-op if it is not a lead). */
    private void mirrorToLead(String recipient, String bodyText) {
        if (leadService == null) {
            return;
        }
        try {
            String snippet = bodyText == null ? null : bodyText.substring(0, Math.min(bodyText.length(), SNIPPET_MAX));
            leadService.recordEmailReply(recipient, snippet);
        } catch (RuntimeException e) {
            log.warn("Could not mirror email reply onto lead timeline", e);
        }
    }

    private void suppress(String recipient) {
        if (recipient == null) {
            return;
        }
        String email = recipient.trim().toLowerCase(Locale.ROOT);
        try {
            if (!suppressionRepository.existsByEmail(email)) {
                suppressionRepository.save(new EmailSuppression(email, EmailSuppression.REASON_BOUNCE));
            }
        } catch (RuntimeException e) {
            log.warn("Could not record bounce suppression", e);
        }
    }

    private void publish(
        String eventType, EmailMessage original, String fromAddress, String subject, String body, boolean permanent
    ) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("runId", original.getRunId());
        payload.put("nodeId", original.getNodeId());
        payload.put("recipient", original.getRecipient());
        payload.put("messageId", original.getMessageId());
        payload.put("threadKey", original.getThreadKey());
        payload.put("fromAddress", fromAddress);
        payload.put("subject", subject);
        payload.put("snippet", body == null ? "" : (body.length() <= SNIPPET_MAX ? body : body.substring(0, SNIPPET_MAX)));
        if (WorkflowEventTypes.EMAIL_BOUNCED.equals(eventType)) {
            payload.put("permanent", permanent);
        }
        try {
            // HashMap tolerates the null fields Map.of would reject.
            eventPublisher.publish(eventType, original.getRunId() == null ? "email-reply" : original.getRunId(), payload);
        } catch (RuntimeException e) {
            log.warn("Could not publish {} event", eventType, e);
        }
    }
}
