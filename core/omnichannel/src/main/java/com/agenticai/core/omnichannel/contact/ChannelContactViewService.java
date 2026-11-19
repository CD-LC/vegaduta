package com.agenticai.core.omnichannel.contact;

import com.agenticai.core.agents.core.AgentRepository;
import com.agenticai.core.omnichannel.contact.ChannelContactController.ContactResponse;
import com.agenticai.core.omnichannel.contact.ChannelContactController.GroupResponse;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionRepository;
import com.agenticai.core.omnichannel.session.ChannelType;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-facing facade over {@link ChannelContactService}: owns the repository lookups (agent name,
 * session pairing state) and the entity-to-DTO mapping so {@link ChannelContactController}
 * never touches repositories or JPA entities.
 */
@Service
public class ChannelContactViewService {

    private final ChannelContactService contactService;
    private final ChannelSessionRepository sessionRepository;
    private final AgentRepository agentRepository;

    public ChannelContactViewService(
            ChannelContactService contactService,
            ChannelSessionRepository sessionRepository,
            AgentRepository agentRepository) {
        this.contactService = contactService;
        this.sessionRepository = sessionRepository;
        this.agentRepository = agentRepository;
    }

    @Transactional(readOnly = true)
    public List<ContactResponse> listContacts(
            ChannelType channel, UUID groupId, Integer limit, Long offset) {
        return contactService.list(channel, groupId, limit, offset).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public ContactResponse createContact(
            ChannelType channel,
            String identifier,
            String displayName,
            UUID groupId,
            UUID agentId) {
        return toResponse(
                contactService.upsert(channel, identifier, displayName, groupId, agentId, "IMPORT"));
    }

    @Transactional
    public ContactResponse updateContact(
            UUID id, String displayName, UUID groupId, UUID agentId) {
        return toResponse(contactService.update(id, displayName, groupId, agentId));
    }

    @Transactional(readOnly = true)
    public List<GroupResponse> listGroups() {
        return contactService.listGroups().stream()
                .map(
                        g ->
                                new GroupResponse(
                                        g.getId(),
                                        g.getName(),
                                        g.getDescription(),
                                        g.getAgentId(),
                                        agentName(g.getAgentId()),
                                        contactService.memberCount(g.getId())))
                .toList();
    }

    @Transactional
    public GroupResponse createGroup(String name, String description, UUID agentId) {
        ContactGroup g = contactService.createGroup(name, description, agentId);
        return new GroupResponse(
                g.getId(),
                g.getName(),
                g.getDescription(),
                g.getAgentId(),
                agentName(g.getAgentId()),
                0);
    }

    @Transactional
    public GroupResponse updateGroup(UUID id, String name, String description, UUID agentId) {
        ContactGroup g = contactService.updateGroup(id, name, description, agentId);
        return new GroupResponse(
                g.getId(),
                g.getName(),
                g.getDescription(),
                g.getAgentId(),
                agentName(g.getAgentId()),
                contactService.memberCount(id));
    }

    private ContactResponse toResponse(ChannelContact c) {
        ChannelSession session =
                c.getSessionId() != null
                        ? sessionRepository.findById(c.getSessionId()).orElse(null)
                        : null;
        String groupName =
                contactService.findGroup(c.getGroupId()).map(ContactGroup::getName).orElse(null);
        return new ContactResponse(
                c.getId(),
                c.getChannel(),
                c.getIdentifier(),
                c.getDisplayName(),
                c.getGroupId(),
                groupName,
                c.getAgentId(),
                agentName(c.getAgentId()),
                c.getSource(),
                c.getSessionId(),
                session != null && session.isPaired(),
                session != null ? session.getLastMessageAt() : null,
                c.getLastSeenAt(),
                c.getCreatedAt());
    }

    private String agentName(UUID agentId) {
        return agentId != null
                ? agentRepository.findById(agentId).map(a -> a.getName()).orElse(null)
                : null;
    }
}
