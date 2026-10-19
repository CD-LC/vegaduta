package com.agenticai.core.omnichannel.contact;

import com.agenticai.core.omnichannel.session.ChannelType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelContactRepository extends JpaRepository<ChannelContact, UUID> {

    Optional<ChannelContact> findByChannelAndIdentifier(ChannelType channel, String identifier);

    /**
     * Exact-match lookup over a candidate set (externalId + resolved phone) - the primary inbound
     * path.
     */
    List<ChannelContact> findByChannelAndIdentifierIn(
            ChannelType channel, Collection<String> identifiers);

    /**
     * Digit-normalized fallback - matches imported phone numbers against wire-format external ids
     * ("1555...@s.whatsapp.net").
     */
    List<ChannelContact> findByChannelAndIdentifierDigitsIn(
            ChannelType channel, Collection<String> digits);

    List<ChannelContact> findAllByOrderByCreatedAtDesc();

    List<ChannelContact> findByChannelOrderByCreatedAtDesc(ChannelType channel);

    List<ChannelContact> findByGroupIdOrderByCreatedAtDesc(UUID groupId);

    List<ChannelContact> findAllByOrderByCreatedAtDescIdDesc(Pageable page);

    List<ChannelContact> findByChannelOrderByCreatedAtDescIdDesc(
            ChannelType channel, Pageable page);

    List<ChannelContact> findByGroupIdOrderByCreatedAtDescIdDesc(UUID groupId, Pageable page);

    long countByGroupId(UUID groupId);

    long countByChannel(ChannelType channel);
}
