package com.agenticai.core.omnichannel.email;

import org.springframework.http.ResponseEntity;
import com.agenticai.core.tenancy.ListPaging;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import com.agenticai.core.tenancy.ListPaging;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only delivery/reply status of {@code bulk_email} sends, per workflow run. Authenticated, tenant-scoped by schema. */
@RestController
@RequestMapping("/api/email/messages")
public class EmailMessageController {

    /** {@code replied} is true for any inbound match; {@code replyKind} says REPLY vs AUTO_REPLY vs BOUNCE. */
    public record EmailMessageResponse(
        UUID id, String runId, String nodeId, String recipient, String subject, String messageId,
        String threadKey, String status, String error, Instant sentAt, Instant repliedAt, String replyKind,
        boolean replied
    ) {
        static EmailMessageResponse of(EmailMessage m) {
            return new EmailMessageResponse(m.getId(), m.getRunId(), m.getNodeId(), m.getRecipient(), m.getSubject(),
                m.getMessageId(), m.getThreadKey(), m.getStatus(), m.getError(), m.getSentAt(), m.getRepliedAt(),
                m.getReplyKind(), m.getRepliedAt() != null);
        }
    }

    private final EmailMessageRepository repository;

    public EmailMessageController(EmailMessageRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ResponseEntity<List<EmailMessageResponse>> list(@RequestParam("runId") String runId, @RequestParam(required = false) Integer limit, @RequestParam(required = false) Long offset) {
        return ListPaging.window(
            repository.findByRunIdOrderByCreatedAtAsc(runId).stream().map(EmailMessageResponse::of).toList(),
            limit, offset, 50, 500);
    }
}
