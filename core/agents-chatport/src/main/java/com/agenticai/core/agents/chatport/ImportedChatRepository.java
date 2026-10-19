package com.agenticai.core.agents.chatport;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link ImportedChat}. Every query is scoped to the owner subject. */
public interface ImportedChatRepository extends JpaRepository<ImportedChat, UUID> {

    /** List-view projection: everything except the (potentially large) transcript. */
    interface Summary {
        UUID getId();

        String getSource();

        String getTitle();

        Instant getSourceCreatedAt();

        Instant getImportedAt();

        int getMessageCount();

        boolean isTruncated();
    }

    List<Summary> findByOwnerSubjectOrderByImportedAtDesc(String ownerSubject, Pageable pageable);

    Optional<ImportedChat> findByIdAndOwnerSubject(UUID id, String ownerSubject);

    List<ImportedChat> findFullByOwnerSubjectOrderByImportedAtDesc(String ownerSubject, Pageable pageable);

    long countByOwnerSubject(String ownerSubject);

    /** Bytes of transcript JSON this user stores (all chats), for the per-user storage cap. */
    @Query(value = "select coalesce(sum(octet_length(cast(messages as text))), 0) from imported_chat where owner_subject = :owner",
        nativeQuery = true)
    long sumStoredBytes(@Param("owner") String owner);

    /** The ids the other assistant gave the conversations this user has already imported from {@code source}. */
    @Query("select c.sourceId from ImportedChat c where c.ownerSubject = :owner and c.source = :source and c.sourceId is not null")
    List<String> findSourceIds(@Param("owner") String owner, @Param("source") String source);
}
