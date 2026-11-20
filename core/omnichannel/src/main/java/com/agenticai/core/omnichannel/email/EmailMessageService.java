package com.agenticai.core.omnichannel.email;

import com.agenticai.core.omnichannel.email.EmailMessageController.EmailMessageResponse;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the {@code bulk_email} delivery ledger; keeps the repository and entity out of the
 * controller.
 */
@Service
public class EmailMessageService {

    private final EmailMessageRepository repository;

    public EmailMessageService(EmailMessageRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<EmailMessageResponse> listByRun(String runId) {
        return repository.findByRunIdOrderByCreatedAtAsc(runId).stream()
                .map(EmailMessageService::toResponse)
                .toList();
    }

    private static EmailMessageResponse toResponse(EmailMessage m) {
        return new EmailMessageResponse(
                m.getId(),
                m.getRunId(),
                m.getNodeId(),
                m.getRecipient(),
                m.getSubject(),
                m.getMessageId(),
                m.getThreadKey(),
                m.getStatus(),
                m.getError(),
                m.getSentAt(),
                m.getRepliedAt(),
                m.getReplyKind(),
                m.getRepliedAt() != null);
    }
}
