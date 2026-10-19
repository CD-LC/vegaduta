package com.agenticai.core.omnichannel.email;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailMessageRepository extends JpaRepository<EmailMessage, UUID> {

    List<EmailMessage> findByRunIdOrderByCreatedAtAsc(String runId);

    List<EmailMessage> findByMessageIdIn(Collection<String> messageIds);

    @Query("select count(m) > 0 from EmailMessage m where lower(m.recipient) = :recipient and m.repliedAt is not null and m.replyKind = 'REPLY'")
    boolean existsHumanReplyFrom(@Param("recipient") String recipientLowerCase);
}
