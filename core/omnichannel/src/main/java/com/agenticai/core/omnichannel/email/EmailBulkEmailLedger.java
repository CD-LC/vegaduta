package com.agenticai.core.omnichannel.email;

import com.agenticai.core.omnichannel.outbound.BulkEmailLedger;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The kernel's {@link BulkEmailLedger} over the email channel's own tables and unsubscribe tokens.
 */
@Component
public class EmailBulkEmailLedger implements BulkEmailLedger {

    private final EmailMessageRepository messageRepository;
    private final EmailSuppressionRepository suppressionRepository;
    private final EmailUnsubscribeTokenService unsubscribeTokens;

    public EmailBulkEmailLedger(
            EmailMessageRepository messageRepository,
            EmailSuppressionRepository suppressionRepository,
            EmailUnsubscribeTokenService unsubscribeTokens) {
        this.messageRepository = messageRepository;
        this.suppressionRepository = suppressionRepository;
        this.unsubscribeTokens = unsubscribeTokens;
    }

    @Override
    public Set<String> suppressed(Collection<String> loweredEmails) {
        Set<String> out = new HashSet<>();
        for (EmailSuppression s : suppressionRepository.findByEmailIn(loweredEmails)) {
            out.add(s.getEmail());
        }
        return out;
    }

    @Override
    public boolean hasHumanReplyFrom(String lowerEmail) {
        return messageRepository.existsHumanReplyFrom(lowerEmail);
    }

    @Override
    public void recordSent(
            String runId,
            String nodeId,
            String recipient,
            String subject,
            String messageId,
            String threadKey,
            Instant sentAt) {
        messageRepository.save(
                new EmailMessage(
                        runId,
                        nodeId,
                        recipient,
                        subject,
                        messageId,
                        threadKey,
                        EmailMessage.STATUS_SENT,
                        null,
                        sentAt));
    }

    @Override
    public void recordFailed(
            String runId,
            String nodeId,
            String recipient,
            String subject,
            String threadKey,
            String error) {
        messageRepository.save(
                new EmailMessage(
                        runId,
                        nodeId,
                        recipient,
                        subject,
                        null,
                        threadKey,
                        EmailMessage.STATUS_FAILED,
                        error,
                        null));
    }

    @Override
    public boolean canUnsubscribe() {
        return unsubscribeTokens.isConfigured();
    }

    @Override
    public String unsubscribeUrl(String tenantId, String email) {
        return unsubscribeTokens.unsubscribeUrl(tenantId, email);
    }
}
