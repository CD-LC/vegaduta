package com.agenticai.core.omnichannel.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.contact.ChannelContactService.ImportResult;
import com.agenticai.core.omnichannel.contact.ChannelContactService.ImportRow;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelSessionRepository;
import com.agenticai.core.omnichannel.session.ChannelType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/** Contact matching, pairing reconciliation, groups and CSV-style import. */
class ChannelContactServiceRulesTest {

    private ChannelContactRepository contacts;
    private ContactGroupRepository groups;
    private ChannelSessionRepository sessions;
    private ChannelContactService service;

    @BeforeEach
    void setUp() {
        contacts = mock(ChannelContactRepository.class);
        groups = mock(ContactGroupRepository.class);
        sessions = mock(ChannelSessionRepository.class);
        service = new ChannelContactService(contacts, groups, sessions);
        when(contacts.save(any(ChannelContact.class))).thenAnswer(inv -> inv.getArgument(0));
        when(groups.save(any(ContactGroup.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static ChannelContact contact(String identifier, UUID groupId, UUID agentId) {
        ChannelContact c =
                new ChannelContact(
                        ChannelType.WHATSAPP, identifier, "Asha", groupId, agentId, "MANUAL");
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        return c;
    }

    private static ChannelSession pending(String externalId) {
        ChannelSession s =
                new ChannelSession(ChannelType.WHATSAPP, externalId, "sess", UUID.randomUUID());
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        return s;
    }

    private static ContactGroup group(String name, UUID agentId) {
        ContactGroup g = new ContactGroup(name, null, agentId);
        ReflectionTestUtils.setField(g, "id", UUID.randomUUID());
        return g;
    }

    @Test
    void anExactIdentifierMatchWinsBeforeTheDigitFallback() {
        ChannelContact hit = contact("+919812345678", null, null);
        when(contacts.findByChannelAndIdentifierIn(eq(ChannelType.WHATSAPP), anyCollection()))
                .thenReturn(List.of(hit));

        assertThat(
                        service.findForInbound(
                                ChannelType.WHATSAPP,
                                "919812345678@s.whatsapp.net",
                                "+919812345678"))
                .contains(hit);
        verify(contacts, never()).findByChannelAndIdentifierDigitsIn(any(), anyCollection());
    }

    @Test
    void wireFormatIdsFallBackToDigitMatchingAndShortNumbersNeverMatch() {
        ChannelContact hit = contact("+91 98123 45678", null, null);
        when(contacts.findByChannelAndIdentifierIn(any(), anyCollection())).thenReturn(List.of());
        when(contacts.findByChannelAndIdentifierDigitsIn(
                        eq(ChannelType.WHATSAPP), eq(Set.of("919812345678"))))
                .thenReturn(List.of(hit));

        assertThat(
                        service.findForInbound(
                                ChannelType.WHATSAPP, "919812345678@s.whatsapp.net", null))
                .contains(hit);
        assertThat(service.findForInbound(ChannelType.WHATSAPP, "12345", " ")).isEmpty();
        assertThat(service.findForInbound(ChannelType.WHATSAPP, null, null)).isEmpty();
    }

    @Test
    void theEffectiveAgentIsTheContactsOwnThenItsGroupsThenNone() {
        UUID own = UUID.randomUUID();
        UUID viaGroup = UUID.randomUUID();
        ContactGroup g = group("VIP", viaGroup);
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));

        assertThat(service.effectiveAgentIdFor(contact("a", g.getId(), own))).isEqualTo(own);
        assertThat(service.effectiveAgentIdFor(contact("a", g.getId(), null))).isEqualTo(viaGroup);
        assertThat(service.effectiveAgentIdFor(contact("a", UUID.randomUUID(), null))).isNull();
        assertThat(service.effectiveAgentIdFor(contact("a", null, null))).isNull();
    }

    @Test
    void attachingLinksTheSessionAndApprovingAlsoPairsAndRebinds() {
        UUID agent = UUID.randomUUID();
        ChannelContact c = contact("x", null, agent);
        ChannelSession s = pending("x");

        service.attachToSession(c, s);
        assertThat(c.getSessionId()).isEqualTo(s.getId());
        assertThat(s.isPaired()).isFalse();

        ChannelContact other = contact("x", null, agent);
        service.attachAndApprove(other, s);
        assertThat(s.isPaired()).isTrue();
        assertThat(s.getAgentId()).isEqualTo(agent);
        assertThat(other.getSessionId()).isEqualTo(s.getId());
    }

    @Test
    void approvingWithoutAnAgentKeepsTheSessionsOwn() {
        ChannelSession s = pending("x");
        UUID original = s.getAgentId();

        service.attachAndApprove(contact("x", null, null), s);

        assertThat(s.getAgentId()).isEqualTo(original);
    }

    @Test
    void anApprovedSessionCreatesAnInboundContactOnlyWhenNoneExists() {
        ChannelSession s =
                new ChannelSession(
                        ChannelType.WHATSAPP,
                        "wire-id",
                        "sess",
                        UUID.randomUUID(),
                        "hi",
                        "+919812345678");
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());

        service.recordApprovedSession(s);

        verify(contacts, org.mockito.Mockito.times(2)).save(any(ChannelContact.class));

        ChannelContact existing = contact("+919812345678", null, null);
        when(contacts.findByChannelAndIdentifierIn(any(), anyCollection()))
                .thenReturn(List.of(existing));
        service.recordApprovedSession(s);
        assertThat(existing.getSessionId()).isEqualTo(s.getId());
    }

    @Test
    void listsPickTheNarrowestFilter() {
        UUID groupId = UUID.randomUUID();
        service.list(null, groupId);
        verify(contacts).findByGroupIdOrderByCreatedAtDesc(groupId);
        service.list(ChannelType.SLACK, null);
        verify(contacts).findByChannelOrderByCreatedAtDesc(ChannelType.SLACK);
        service.list(null, null);
        verify(contacts).findAllByOrderByCreatedAtDesc();

        service.count(null, groupId);
        verify(contacts).countByGroupId(groupId);
        service.count(ChannelType.SLACK, null);
        verify(contacts).countByChannel(ChannelType.SLACK);
        service.count(null, null);
        verify(contacts).count();
    }

    @Test
    void upsertCreatesOrUpdatesAndApprovesAMatchingPendingSession() {
        ChannelSession waiting = pending("+919812345678");
        when(sessions.findByChannelAndPairedFalse(ChannelType.WHATSAPP))
                .thenReturn(List.of(waiting));

        ChannelContact created =
                service.upsert(
                        ChannelType.WHATSAPP, "  +919812345678 ", "Asha", null, null, "MANUAL");

        assertThat(created.getIdentifier()).isEqualTo("+919812345678");
        assertThat(waiting.isPaired()).isTrue();

        ChannelContact existing = contact("+911111111111", null, null);
        when(contacts.findByChannelAndIdentifier(ChannelType.WHATSAPP, "+911111111111"))
                .thenReturn(Optional.of(existing));
        ChannelContact updated =
                service.upsert(
                        ChannelType.WHATSAPP, "+911111111111", "Renamed", null, null, "MANUAL");
        assertThat(updated.getDisplayName()).isEqualTo("Renamed");
    }

    @Test
    void identifiersMustBePresentAndShortEnough() {
        assertThatThrownBy(() -> service.upsert(ChannelType.WHATSAPP, " ", "n", null, null, "s"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("required");
        assertThatThrownBy(() -> service.upsert(ChannelType.WHATSAPP, null, "n", null, null, "s"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(
                        () ->
                                service.upsert(
                                        ChannelType.WHATSAPP,
                                        "9".repeat(129),
                                        "n",
                                        null,
                                        null,
                                        "s"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("too long");
    }

    @Test
    void sessionsMatchOnExactIdResolvedPhoneOrDigitsButNotAcrossChannels() {
        ChannelSession byDigits = pending("919812345678@s.whatsapp.net");
        ChannelSession short1 = pending("12345");
        ChannelSession other =
                new ChannelSession(ChannelType.SLACK, "+919812345678", "s", UUID.randomUUID());
        ReflectionTestUtils.setField(other, "id", UUID.randomUUID());
        when(sessions.findByChannelAndPairedFalse(ChannelType.WHATSAPP))
                .thenReturn(List.of(short1, byDigits));

        service.upsert(ChannelType.WHATSAPP, "+91 98123 45678", "Asha", null, null, "MANUAL");
        assertThat(byDigits.isPaired()).isTrue();
        assertThat(short1.isPaired()).isFalse();

        ChannelContact tiny = contact("12345", null, null);
        when(contacts.findById(tiny.getId())).thenReturn(Optional.of(tiny));
        when(sessions.findByChannelAndPairedFalse(ChannelType.WHATSAPP))
                .thenReturn(List.of(pending("99999")));
        service.update(tiny.getId(), "t", null, null);
    }

    @Test
    void updatingRebindsTheLinkedSessionToTheNewEffectiveAgent() {
        UUID agent = UUID.randomUUID();
        ChannelContact c = contact("x", null, null);
        UUID sessionId = UUID.randomUUID();
        c.linkSession(sessionId);
        ChannelSession linked = pending("x");
        when(contacts.findById(c.getId())).thenReturn(Optional.of(c));
        when(sessions.findById(sessionId)).thenReturn(Optional.of(linked));

        service.update(c.getId(), "Asha", null, agent);

        assertThat(linked.getAgentId()).isEqualTo(agent);
    }

    @Test
    void missingContactsAndGroupsAre404() {
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.update(id, "n", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No such contact");
        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.updateGroup(id, "n", null, null))
                .hasMessageContaining("No such contact group");
        assertThatThrownBy(() -> service.deleteGroup(id))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void deleteRemovesTheContactAndTheGroup() {
        ChannelContact c = contact("x", null, null);
        ContactGroup g = group("G", null);
        when(contacts.findById(c.getId())).thenReturn(Optional.of(c));
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));

        service.delete(c.getId());
        service.deleteGroup(g.getId());

        verify(contacts).delete(c);
        verify(groups).delete(g);
    }

    @Test
    void groupNamesAreTrimmedUniqueAndBounded() {
        assertThatThrownBy(() -> service.createGroup(" ", null, null))
                .hasMessageContaining("required");
        assertThatThrownBy(() -> service.createGroup("n".repeat(129), null, null))
                .hasMessageContaining("too long");

        when(groups.findByNameIgnoreCase("VIP")).thenReturn(Optional.of(group("VIP", null)));
        assertThatThrownBy(() -> service.createGroup(" VIP ", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists");

        when(groups.findByNameIgnoreCase("New")).thenReturn(Optional.empty());
        assertThat(service.createGroup(" New ", "d", null).getName()).isEqualTo("New");
    }

    @Test
    void renamingAGroupMayKeepItsOwnNameButNotTakeAnothers() {
        ContactGroup mine = group("Mine", null);
        ContactGroup other = group("Other", null);
        when(groups.findById(mine.getId())).thenReturn(Optional.of(mine));
        when(groups.findByNameIgnoreCase("Mine")).thenReturn(Optional.of(mine));
        when(groups.findByNameIgnoreCase("Other")).thenReturn(Optional.of(other));

        assertThat(service.updateGroup(mine.getId(), "Mine", "desc", null).getDescription())
                .isEqualTo("desc");
        assertThatThrownBy(() -> service.updateGroup(mine.getId(), "Other", null, null))
                .hasMessageContaining("already exists");
    }

    @Test
    void groupLookupsTolerateNullsAndBlanks() {
        ContactGroup g = group("VIP", null);
        when(groups.findById(g.getId())).thenReturn(Optional.of(g));
        when(groups.findByNameIgnoreCase("VIP")).thenReturn(Optional.of(g));
        when(groups.findAll()).thenReturn(List.of(g));

        assertThat(service.findGroup(null)).isEmpty();
        assertThat(service.findGroup(g.getId())).contains(g);
        assertThat(service.findGroupByName(" ")).isEmpty();
        assertThat(service.findGroupByName(null)).isEmpty();
        assertThat(service.findGroupByName(" VIP ")).contains(g);
        assertThat(service.listGroups()).containsExactly(g);
        service.memberCount(g.getId());
        verify(contacts).countByGroupId(g.getId());
    }

    @Test
    void importCountsCreatedUpdatedAndCollectsRowErrors() {
        when(sessions.findByChannelAndPairedFalse(ChannelType.WHATSAPP))
                .thenReturn(new ArrayList<>());
        when(contacts.findByChannelAndIdentifier(ChannelType.WHATSAPP, "+911111111111"))
                .thenReturn(Optional.of(contact("+911111111111", null, null)));
        when(groups.findByNameIgnoreCase("VIP")).thenReturn(Optional.empty());

        ImportResult result =
                service.importRows(
                        ChannelType.WHATSAPP,
                        null,
                        List.of(
                                new ImportRow("+911111111111", "Existing", null),
                                new ImportRow("+912222222222", "New", "VIP"),
                                new ImportRow(" ", "Broken", null)));

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).startsWith("Row 3:");
        verify(groups).save(any(ContactGroup.class));
    }

    @Test
    void importApprovesAPendingSessionOnce() {
        ChannelSession waiting = pending("+913333333333");
        List<ChannelSession> pendingList = new ArrayList<>(List.of(waiting));
        when(sessions.findByChannelAndPairedFalse(ChannelType.WHATSAPP)).thenReturn(pendingList);

        service.importRows(
                ChannelType.WHATSAPP,
                UUID.randomUUID(),
                List.of(new ImportRow("+913333333333", "N", "")));

        assertThat(waiting.isPaired()).isTrue();
        assertThat(pendingList).isEmpty();
        verify(sessions, never()).findById(any());
    }
}
