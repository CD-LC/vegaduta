package com.agenticai.core.omnichannel.contact;

import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionRepository;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.tenancy.ListPaging;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The tenant's contact directory for inbound channels - the backend of /admin/contacts. Three jobs:
 *
 * <p><b>1. Inbound lookup.</b> {@link #findForInbound} is called from {@code
 * ChannelSessionService#resolveOrCreate} on every inbound message. A hit means "this sender is
 * known" - the session starts (or is flipped to) paired, and routes by contact agent -> group agent
 * -> channel default.
 *
 * <p><b>2. Directory CRUD + import.</b> Contacts can be typed in one at a time or bulk-imported
 * from CSV/XLSX/pasted lines. Upsert semantics on (channel, identifier) - re-importing the same
 * number updates it rather than duplicating or erroring.
 *
 * <p><b>3. Session reconciliation.</b> Creating/importing a contact also approves-and-links any
 * still-pending session for that sender, so importing a number fixes a sender who is ALREADY
 * sitting in the pairing queue - not just their next session. The reverse direction ({@link
 * #recordApprovedSession}) keeps the directory complete: approving a pending sender on the pairing
 * screen records them as an INBOUND contact.
 */
@Service
public class ChannelContactService {

    /**
     * Minimum digit count for the digits-only match - short digit strings ("42@lid" -> "42")
     * false-positive too easily.
     */
    private static final int MIN_DIGITS_MATCH = 6;

    private final ChannelContactRepository contactRepository;
    private final ContactGroupRepository groupRepository;
    private final ChannelSessionRepository sessionRepository;

    public ChannelContactService(
            ChannelContactRepository contactRepository,
            ContactGroupRepository groupRepository,
            ChannelSessionRepository sessionRepository) {
        this.contactRepository = contactRepository;
        this.groupRepository = groupRepository;
        this.sessionRepository = sessionRepository;
    }

    // ------------------------------------------------------------------
    // Inbound lookup (hot path - called once per inbound message)
    // ------------------------------------------------------------------

    /**
     * Finds the known contact for an inbound sender, if any. Two passes: exact match on externalId
     * / resolved phone number (covers Telegram chat ids, {@code ...@lid}, emails, already-canonical
     * numbers), then a digit-only fallback so an imported "+1 (555) 123-4567" still matches a
     * session whose external_id is {@code "15551234567@s.whatsapp.net"}.
     */
    @Transactional(readOnly = true)
    public Optional<ChannelContact> findForInbound(
            ChannelType channel, String externalId, String resolvedPhoneNumber) {
        Set<String> exact = new LinkedHashSet<>();
        if (externalId != null && !externalId.isBlank()) {
            exact.add(externalId);
        }
        if (resolvedPhoneNumber != null && !resolvedPhoneNumber.isBlank()) {
            exact.add(resolvedPhoneNumber);
        }
        if (!exact.isEmpty()) {
            List<ChannelContact> hits =
                    contactRepository.findByChannelAndIdentifierIn(channel, exact);
            if (!hits.isEmpty()) {
                return Optional.of(hits.get(0));
            }
        }
        Set<String> digits = new LinkedHashSet<>();
        addDigits(digits, externalId);
        addDigits(digits, resolvedPhoneNumber);
        if (!digits.isEmpty()) {
            return contactRepository.findByChannelAndIdentifierDigitsIn(channel, digits).stream()
                    .findFirst();
        }
        return Optional.empty();
    }

    private static void addDigits(Set<String> sink, String value) {
        String digits = ChannelContact.digitsOf(value);
        if (digits != null && digits.length() >= MIN_DIGITS_MATCH) {
            sink.add(digits);
        }
    }

    /**
     * The agent a known contact routes to: their own override first, then their group's. Null when
     * neither is set - the caller then falls back to the channel's default agent (which may itself
     * be unset and throw, same as before this feature existed).
     */
    @Transactional(readOnly = true)
    public UUID effectiveAgentIdFor(ChannelContact contact) {
        if (contact.getAgentId() != null) {
            return contact.getAgentId();
        }
        if (contact.getGroupId() != null) {
            return groupRepository
                    .findById(contact.getGroupId())
                    .map(ContactGroup::getAgentId)
                    .orElse(null);
        }
        return null;
    }

    /**
     * Called by {@code resolveOrCreate} right after a NEW session is saved for a known contact -
     * the contact row records which live session it maps to (and when).
     */
    @Transactional
    public void attachToSession(ChannelContact contact, ChannelSession session) {
        contact.linkSession(session.getId());
        contactRepository.save(contact);
    }

    /**
     * A still-pending session whose sender is now a known contact: the contact IS the approval, so
     * pair the session, re-point it at the contact's effective agent when one is set (the pending
     * row was stamped with the channel default at first contact), and link the two rows.
     */
    @Transactional
    public void attachAndApprove(ChannelContact contact, ChannelSession session) {
        session.approvePairing();
        UUID effectiveAgent = effectiveAgentIdFor(contact);
        if (effectiveAgent != null) {
            session.rebind(effectiveAgent);
        }
        contact.linkSession(session.getId());
        contactRepository.save(contact);
    }

    /**
     * Reverse direction of {@link #attachAndApprove}: an admin approving a pending sender on the
     * pairing screen has just vetted them - record a contact (source INBOUND) so the directory
     * stays complete without the admin re-entering the number by hand. Uses the resolved real phone
     * number when one exists (more useful than a wire-format externalId like {@code ...@lid});
     * no-op when a contact already covers this sender.
     */
    @Transactional
    public void recordApprovedSession(ChannelSession session) {
        String identifier =
                session.getResolvedPhoneNumber() != null
                        ? session.getResolvedPhoneNumber()
                        : session.getExternalId();
        ChannelContact contact =
                findForInbound(
                                session.getChannel(),
                                session.getExternalId(),
                                session.getResolvedPhoneNumber())
                        .orElseGet(
                                () ->
                                        contactRepository.save(
                                                new ChannelContact(
                                                        session.getChannel(),
                                                        identifier,
                                                        null,
                                                        null,
                                                        null,
                                                        "INBOUND")));
        contact.linkSession(session.getId());
        contactRepository.save(contact);
    }

    // ------------------------------------------------------------------
    // Directory CRUD
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ChannelContact> list(ChannelType channel, UUID groupId) {
        // UNBOUNDED by design: internal "process every contact" callers need all rows.
        // The HTTP list uses the capped overload below.
        if (groupId != null) {
            return contactRepository.findByGroupIdOrderByCreatedAtDesc(groupId);
        }
        if (channel != null) {
            return contactRepository.findByChannelOrderByCreatedAtDesc(channel);
        }
        return contactRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * Real total for the same filter as {@link #list(ChannelType, UUID, Integer)} (X-Total-Count).
     */
    @Transactional(readOnly = true)
    public long count(ChannelType channel, UUID groupId) {
        if (groupId != null) {
            return contactRepository.countByGroupId(groupId);
        }
        if (channel != null) {
            return contactRepository.countByChannel(channel);
        }
        return contactRepository.count();
    }

    /** Default / hard-max row counts for the directory list. */
    static final int LIST_DEFAULT_LIMIT = 500;

    static final int LIST_MAX_LIMIT = 2000;

    /**
     * Newest {@code limit} contacts (null = default 500, clamped to [1, 2000]), same order as
     * before.
     */
    @Transactional(readOnly = true)
    public List<ChannelContact> list(ChannelType channel, UUID groupId, Integer limit) {
        return list(channel, groupId, limit, null);
    }

    /**
     * As above, skipping the newest {@code offset} rows (null/negative = 0) to reach older
     * contacts.
     */
    @Transactional(readOnly = true)
    public List<ChannelContact> list(
            ChannelType channel, UUID groupId, Integer limit, Long offset) {
        var page = ListPaging.page(limit, offset, LIST_DEFAULT_LIMIT, LIST_MAX_LIMIT);
        if (groupId != null) {
            return contactRepository.findByGroupIdOrderByCreatedAtDescIdDesc(groupId, page);
        }
        if (channel != null) {
            return contactRepository.findByChannelOrderByCreatedAtDescIdDesc(channel, page);
        }
        return contactRepository.findAllByOrderByCreatedAtDescIdDesc(page);
    }

    /**
     * Upsert on (channel, identifier): an existing row is updated rather than duplicated or
     * erroring - the same number re-imported or re-added by hand is an edit, not a conflict. Then
     * reconciles any pending session for this sender (see class javadoc, job 3).
     */
    @Transactional
    public ChannelContact upsert(
            ChannelType channel,
            String identifier,
            String displayName,
            UUID groupId,
            UUID agentId,
            String source) {
        ChannelContact contact =
                upsertWithoutReconcile(
                        channel,
                        requireIdentifier(identifier),
                        displayName,
                        groupId,
                        agentId,
                        source);
        approveMatchingPendingSession(contact);
        return contact;
    }

    /**
     * The bare upsert - {@link #importRows} reconciles pending sessions itself against a
     * once-fetched list rather than re-querying per row.
     */
    private ChannelContact upsertWithoutReconcile(
            ChannelType channel,
            String identifier,
            String displayName,
            UUID groupId,
            UUID agentId,
            String source) {
        return contactRepository
                .findByChannelAndIdentifier(channel, identifier)
                .map(
                        existing -> {
                            existing.update(displayName, groupId, agentId);
                            return contactRepository.save(existing);
                        })
                .orElseGet(
                        () ->
                                contactRepository.save(
                                        new ChannelContact(
                                                channel,
                                                identifier,
                                                displayName,
                                                groupId,
                                                agentId,
                                                source)));
    }

    @Transactional
    public ChannelContact update(UUID id, String displayName, UUID groupId, UUID agentId) {
        ChannelContact contact = requireContact(id);
        contact.update(displayName, groupId, agentId);
        ChannelContact saved = contactRepository.save(contact);
        // Changing a contact's routing re-points their LIVE session too -
        // otherwise an admin moving a contact to another group/agent would
        // only take effect on some hypothetical next session.
        UUID effectiveAgent = effectiveAgentIdFor(saved);
        if (saved.getSessionId() != null && effectiveAgent != null) {
            sessionRepository
                    .findById(saved.getSessionId())
                    .ifPresent(s -> s.rebind(effectiveAgent));
        }
        approveMatchingPendingSession(saved);
        return saved;
    }

    @Transactional
    public void delete(UUID id) {
        contactRepository.delete(requireContact(id));
    }

    /**
     * If a sender matching this contact is sitting in the pending-pairing queue, approve and link
     * them now - importing/adding a contact should fix someone who already messaged in, not just
     * their next session.
     */
    private void approveMatchingPendingSession(ChannelContact contact) {
        sessionRepository.findByChannelAndPairedFalse(contact.getChannel()).stream()
                .filter(s -> matches(contact, s))
                .findFirst()
                .ifPresent(s -> attachAndApprove(contact, s));
    }

    /**
     * Same match rule as {@link #findForInbound}, applied to a session row (used when reconciling a
     * saved contact against pending sessions).
     */
    private static boolean matches(ChannelContact contact, ChannelSession session) {
        if (contact.getChannel() != session.getChannel()) {
            return false;
        }
        if (contact.getIdentifier().equals(session.getExternalId())
                || contact.getIdentifier().equals(session.getResolvedPhoneNumber())) {
            return true;
        }
        String contactDigits = contact.getIdentifierDigits();
        if (contactDigits == null || contactDigits.length() < MIN_DIGITS_MATCH) {
            return false;
        }
        String externalDigits = ChannelContact.digitsOf(session.getExternalId());
        String resolvedDigits = ChannelContact.digitsOf(session.getResolvedPhoneNumber());
        return contactDigits.equals(externalDigits) || contactDigits.equals(resolvedDigits);
    }

    // ------------------------------------------------------------------
    // Groups
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ContactGroup> listGroups() {
        return groupRepository.findAll();
    }

    @Transactional(readOnly = true)
    public long memberCount(UUID groupId) {
        return contactRepository.countByGroupId(groupId);
    }

    @Transactional
    public ContactGroup createGroup(String name, String description, UUID agentId) {
        String trimmed = requireName(name);
        groupRepository
                .findByNameIgnoreCase(trimmed)
                .ifPresent(
                        existing -> {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "A group named '" + trimmed + "' already exists");
                        });
        return groupRepository.save(new ContactGroup(trimmed, description, agentId));
    }

    @Transactional
    public ContactGroup updateGroup(UUID id, String name, String description, UUID agentId) {
        ContactGroup group = requireGroup(id);
        String trimmed = requireName(name);
        groupRepository
                .findByNameIgnoreCase(trimmed)
                .filter(other -> !other.getId().equals(id))
                .ifPresent(
                        other -> {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT,
                                    "A group named '" + trimmed + "' already exists");
                        });
        group.update(trimmed, description, agentId);
        return groupRepository.save(group);
    }

    @Transactional
    public void deleteGroup(UUID id) {
        // Members keep their rows - the FK is ON DELETE SET NULL (V238), so
        // deleting a group un-groups its contacts rather than deleting them.
        groupRepository.delete(requireGroup(id));
    }

    @Transactional(readOnly = true)
    public Optional<ContactGroup> findGroup(UUID id) {
        return id == null ? Optional.empty() : groupRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<ContactGroup> findGroupByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return groupRepository.findByNameIgnoreCase(name.trim());
    }

    // ------------------------------------------------------------------
    // Import
    // ------------------------------------------------------------------

    /**
     * Bulk-import rows into the directory. Each row is {identifier, name?, groupName?}; a named
     * group that doesn't exist yet is created (assigned no agent - the admin edits it afterwards),
     * so a sheet can carry its own grouping instead of forcing a pre-created group id. Returns
     * per-row outcomes rather than failing the whole file on one bad line.
     */
    @Transactional
    public ImportResult importRows(ChannelType channel, UUID groupId, Collection<ImportRow> rows) {
        int created = 0;
        int updated = 0;
        List<String> errors = new ArrayList<>();
        // Pending sessions fetched ONCE for the whole file - upsert() would
        // otherwise re-query per row, and a sheet can carry thousands.
        List<ChannelSession> pendingSessions =
                sessionRepository.findByChannelAndPairedFalse(channel);
        int rowNo = 0;
        for (ImportRow row : rows) {
            rowNo++;
            try {
                UUID effectiveGroupId = groupId;
                if (row.groupName() != null && !row.groupName().isBlank()) {
                    ContactGroup group =
                            findGroupByName(row.groupName())
                                    .orElseGet(
                                            () ->
                                                    groupRepository.save(
                                                            new ContactGroup(
                                                                    row.groupName().trim(),
                                                                    null,
                                                                    null)));
                    effectiveGroupId = group.getId();
                }
                String trimmed = requireIdentifier(row.identifier());
                boolean existed =
                        contactRepository.findByChannelAndIdentifier(channel, trimmed).isPresent();
                ChannelContact contact =
                        upsertWithoutReconcile(
                                channel, trimmed, row.name(), effectiveGroupId, null, "IMPORT");
                pendingSessions.stream()
                        .filter(s -> matches(contact, s))
                        .findFirst()
                        .ifPresent(
                                s -> {
                                    attachAndApprove(contact, s);
                                    pendingSessions.remove(s);
                                });
                if (existed) {
                    updated++;
                } else {
                    created++;
                }
            } catch (ResponseStatusException e) {
                errors.add("Row " + rowNo + ": " + e.getReason());
            }
        }
        return new ImportResult(created, updated, errors);
    }

    public record ImportRow(String identifier, String name, String groupName) {}

    public record ImportResult(int created, int updated, List<String> errors) {}

    // ------------------------------------------------------------------

    public ChannelContact requireContact(UUID id) {
        return contactRepository
                .findById(id)
                .orElseThrow(
                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such contact"));
    }

    public ContactGroup requireGroup(UUID id) {
        return groupRepository
                .findById(id)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "No such contact group"));
    }

    private static String requireIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Identifier (phone number, chat id or address) is required");
        }
        String trimmed = identifier.trim();
        if (trimmed.length() > 128) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Identifier is too long (max 128 chars)");
        }
        return trimmed;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Group name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 128) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Group name is too long (max 128 chars)");
        }
        return trimmed;
    }
}
