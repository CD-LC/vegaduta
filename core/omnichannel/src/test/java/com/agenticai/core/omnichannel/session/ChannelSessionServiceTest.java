package com.agenticai.core.omnichannel.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.consent.ConversationConsentGate;
import com.agenticai.core.agents.memory.AgentMemoryService;
import com.agenticai.core.audit.AuditLogService;
import com.agenticai.core.audit.AuditOutcome;
import com.agenticai.core.omnichannel.ChannelConfigurationException;
import com.agenticai.core.omnichannel.contact.ChannelContact;
import com.agenticai.core.omnichannel.contact.ChannelContactService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link ChannelSessionService} is the richest class in the {@code session}
 * package - session resolution/creation, agent rebinding, the pairing gate,
 * the AI Act Article 50 disclosure's exactly-once delivery, and (#815
 * Governance follow-up) GDPR Article 17 right-to-erasure - and had zero
 * coverage despite the erasure path in particular carrying real
 * compliance-relevant behavior: delete order (message logs, then agent
 * memory, then the session, audit record LAST so the trail reflects what
 * actually happened), a graceful-absence path for {@code
 * AgentMemoryService} (profile-gated, may not exist in a given build), and
 * a masked audit message that must never leak the full external identity
 * (phone number / chat id) into a log.
 *
 * <p>{@link ChannelSession} is mocked, not constructed for real, in every
 * test that needs {@link ChannelSession#getId()} to return a known value -
 * {@code id} is a JPA {@code @GeneratedValue} field with no way to set it
 * through the public constructor, which is exactly the situation this
 * codebase's convention (see {@code HumanApprovalGateServiceTest}'s
 * javadoc, mirrored here) calls for mocking an entity rather than
 * constructing it. {@link ObjectProvider} is mocked directly (a plain
 * interface) to drive {@link ChannelSessionService}'s graceful-absence
 * {@code AgentMemoryService} lookup both ways.
 */
class ChannelSessionServiceTest {

    private static final ChannelType CHANNEL = ChannelType.WHATSAPP;
    private static final String EXTERNAL_ID = "+15551234567";

    private ChannelSessionRepository sessionRepository;
    private ChannelDefaultAgentRepository defaultAgentRepository;
    private ChannelMessageLogRepository messageLogRepository;
    private ChannelContactService contactService;
    private AuditLogService auditLogService;
    private InboundSafetyScanService inboundSafetyScanService;
    private ObjectProvider<AgentMemoryService> agentMemoryServiceProvider;
    private ChannelSessionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        sessionRepository = mock(ChannelSessionRepository.class);
        defaultAgentRepository = mock(ChannelDefaultAgentRepository.class);
        messageLogRepository = mock(ChannelMessageLogRepository.class);
        contactService = mock(ChannelContactService.class);
        auditLogService = mock(AuditLogService.class);
        inboundSafetyScanService = mock(InboundSafetyScanService.class);
        agentMemoryServiceProvider = mock(ObjectProvider.class);
        service = new ChannelSessionService(
            sessionRepository,
            defaultAgentRepository,
            messageLogRepository,
            contactService,
            auditLogService,
            inboundSafetyScanService,
            agentMemoryServiceProvider
        );
    }

    // --- resolveOrCreate(): existing session -----------------------------

    @Test
    void resolveOrCreateTouchesAnExistingSessionRatherThanCreatingASecondOne() {
        ChannelSession existing = mock(ChannelSession.class);
        UUID existingId = UUID.randomUUID();
        when(existing.getId()).thenReturn(existingId);
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.of(existing));

        ChannelSession result = service.resolveOrCreate(CHANNEL, EXTERNAL_ID, "hello", "+15559998888");

        assertEquals(existing, result);
        verify(existing).touch("hello", "+15559998888");
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void resolveOrCreateThreeArgOverloadPassesANullResolvedPhoneNumberThrough() {
        ChannelSession existing = mock(ChannelSession.class);
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.of(existing));

        service.resolveOrCreate(CHANNEL, EXTERNAL_ID, "hello");

        verify(existing).touch("hello", null);
    }

    @Test
    void resolveOrCreateTwoArgOverloadPassesANullMessagePreviewAndResolvedPhoneNumber() {
        ChannelSession existing = mock(ChannelSession.class);
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.of(existing));

        service.resolveOrCreate(CHANNEL, EXTERNAL_ID);

        verify(existing).touch(null, null);
        verify(messageLogRepository, never()).save(any());
    }

    @Test
    void resolveOrCreateAppendsAMessageLogRowWhenANonBlankPreviewIsPresent() {
        ChannelSession existing = mock(ChannelSession.class);
        UUID existingId = UUID.randomUUID();
        when(existing.getId()).thenReturn(existingId);
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.of(existing));

        service.resolveOrCreate(CHANNEL, EXTERNAL_ID, "a real inbound message");

        org.mockito.ArgumentCaptor<ChannelMessageLog> captor = org.mockito.ArgumentCaptor.forClass(ChannelMessageLog.class);
        verify(messageLogRepository).save(captor.capture());
        assertEquals(CHANNEL, captor.getValue().getChannel());
        assertEquals(EXTERNAL_ID, captor.getValue().getExternalId());
        assertEquals(existingId, captor.getValue().getChannelSessionId());
        assertEquals("a real inbound message", captor.getValue().getBody());
    }

    @Test
    void resolveOrCreateSkipsTheMessageLogRowWhenThePreviewIsBlank() {
        ChannelSession existing = mock(ChannelSession.class);
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.of(existing));

        service.resolveOrCreate(CHANNEL, EXTERNAL_ID, "   ");

        verify(messageLogRepository, never()).save(any());
    }

    // --- resolveOrCreate(): brand-new session ------------------------------

    @Test
    void resolveOrCreateCreatesABrandNewSessionBoundToTheChannelsDefaultAgentWhenNoneExists() {
        UUID defaultAgentId = UUID.randomUUID();
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.empty());
        when(defaultAgentRepository.findByChannel(CHANNEL)).thenReturn(
            Optional.of(new ChannelDefaultAgent(CHANNEL, defaultAgentId))
        );
        when(sessionRepository.save(any(ChannelSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChannelSession created = service.resolveOrCreate(CHANNEL, EXTERNAL_ID, "first message", "+15550001111");

        assertEquals(CHANNEL, created.getChannel());
        assertEquals(EXTERNAL_ID, created.getExternalId());
        assertEquals(defaultAgentId, created.getAgentId());
        assertEquals("first message", created.getLastMessagePreview());
        assertEquals("+15550001111", created.getResolvedPhoneNumber());
        assertFalse(created.isPaired());
        assertNotNull(created.getSessionId());
    }

    @Test
    void resolveOrCreateThrowsChannelConfigurationExceptionWhenNoDefaultAgentIsConfiguredForANewSession() {
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.empty());
        when(defaultAgentRepository.findByChannel(CHANNEL)).thenReturn(Optional.empty());

        ChannelConfigurationException ex = assertThrows(
            ChannelConfigurationException.class, () -> service.resolveOrCreate(CHANNEL, EXTERNAL_ID, "hi")
        );

        assertTrue(ex.getMessage().contains("whatsapp"));
        verify(sessionRepository, never()).save(any());
    }

    // --- resolveOrCreate(): contact directory routing (V238) ---------------

    @Test
    void resolveOrCreateStartsANewSessionPairedWhenTheSenderIsAKnownContact() {
        UUID contactAgentId = UUID.randomUUID();
        ChannelContact contact = mock(ChannelContact.class);
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.empty());
        when(contactService.findForInbound(CHANNEL, EXTERNAL_ID, "+15550001111")).thenReturn(Optional.of(contact));
        when(contactService.effectiveAgentIdFor(contact)).thenReturn(contactAgentId);
        when(sessionRepository.save(any(ChannelSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChannelSession created = service.resolveOrCreate(CHANNEL, EXTERNAL_ID, "hi", "+15550001111");

        // The contact IS the approval - paired immediately, bound to the
        // contact's own agent, and linked back to the contact row.
        assertTrue(created.isPaired());
        assertEquals(contactAgentId, created.getAgentId());
        verify(contactService).attachToSession(contact, created);
        // The channel default is never even consulted when the contact
        // supplies an agent.
        verify(defaultAgentRepository, never()).findByChannel(CHANNEL);
    }

    @Test
    void resolveOrCreateFallsBackToTheChannelDefaultForAContactWithoutItsOwnAgent() {
        UUID defaultAgentId = UUID.randomUUID();
        ChannelContact contact = mock(ChannelContact.class);
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.empty());
        when(contactService.findForInbound(CHANNEL, EXTERNAL_ID, null)).thenReturn(Optional.of(contact));
        when(contactService.effectiveAgentIdFor(contact)).thenReturn(null);
        when(defaultAgentRepository.findByChannel(CHANNEL)).thenReturn(
            Optional.of(new ChannelDefaultAgent(CHANNEL, defaultAgentId))
        );
        when(sessionRepository.save(any(ChannelSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChannelSession created = service.resolveOrCreate(CHANNEL, EXTERNAL_ID, "hi");

        assertTrue(created.isPaired());
        assertEquals(defaultAgentId, created.getAgentId());
    }

    @Test
    void resolveOrCreateAutoApprovesANewSenderWhenTheChannelModeIsOn() {
        UUID defaultAgentId = UUID.randomUUID();
        ChannelDefaultAgent routing = new ChannelDefaultAgent(CHANNEL, defaultAgentId);
        routing.setAutoApproveNewSenders(true);
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.empty());
        when(contactService.findForInbound(CHANNEL, EXTERNAL_ID, null)).thenReturn(Optional.empty());
        when(defaultAgentRepository.findByChannel(CHANNEL)).thenReturn(Optional.of(routing));
        when(sessionRepository.save(any(ChannelSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChannelSession created = service.resolveOrCreate(CHANNEL, EXTERNAL_ID, "hi");

        assertTrue(created.isPaired());
        assertEquals(defaultAgentId, created.getAgentId());
    }

    @Test
    void resolveOrCreateStillRequiresApprovalForAnUnknownSenderWhenAutoApproveIsOff() {
        UUID defaultAgentId = UUID.randomUUID();
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.empty());
        when(contactService.findForInbound(CHANNEL, EXTERNAL_ID, null)).thenReturn(Optional.empty());
        when(defaultAgentRepository.findByChannel(CHANNEL)).thenReturn(
            Optional.of(new ChannelDefaultAgent(CHANNEL, defaultAgentId))
        );
        when(sessionRepository.save(any(ChannelSession.class))).thenAnswer(inv -> inv.getArgument(0));

        ChannelSession created = service.resolveOrCreate(CHANNEL, EXTERNAL_ID, "hi");

        assertFalse(created.isPaired());
    }

    @Test
    void resolveOrCreateApprovesAPendingSessionWhoseSenderBecameAKnownContact() {
        ChannelSession existing = mock(ChannelSession.class);
        ChannelContact contact = mock(ChannelContact.class);
        when(existing.isPaired()).thenReturn(false);
        when(existing.getResolvedPhoneNumber()).thenReturn("+15550001111");
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.of(existing));
        when(contactService.findForInbound(CHANNEL, EXTERNAL_ID, "+15550001111")).thenReturn(Optional.of(contact));

        service.resolveOrCreate(CHANNEL, EXTERNAL_ID, "hi");

        verify(contactService).attachAndApprove(contact, existing);
    }

    @Test
    void resolveOrCreateLeavesAPairedSessionAloneWhenAContactExists() {
        ChannelSession existing = mock(ChannelSession.class);
        when(existing.isPaired()).thenReturn(true);
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.of(existing));

        service.resolveOrCreate(CHANNEL, EXTERNAL_ID, "hi");

        verify(contactService, never()).attachAndApprove(any(), any());
    }

    // --- defaultAgentIdFor() -------------------------------------------------

    @Test
    void defaultAgentIdForReturnsTheConfiguredAgentId() {
        UUID agentId = UUID.randomUUID();
        when(defaultAgentRepository.findByChannel(CHANNEL)).thenReturn(Optional.of(new ChannelDefaultAgent(CHANNEL, agentId)));

        assertEquals(agentId, service.defaultAgentIdFor(CHANNEL));
    }

    @Test
    void defaultAgentIdForThrowsWithAnActionableMessageWhenUnconfigured() {
        when(defaultAgentRepository.findByChannel(ChannelType.TELEGRAM)).thenReturn(Optional.empty());

        ChannelConfigurationException ex = assertThrows(
            ChannelConfigurationException.class, () -> service.defaultAgentIdFor(ChannelType.TELEGRAM)
        );

        assertTrue(ex.getMessage().contains("/api/channels/telegram/default-agent"));
    }

    // --- setDefaultAgent(): upsert-by-channel ---------------------------------

    @Test
    void setDefaultAgentCreatesANewEntryWhenNoneExistsForTheChannel() {
        UUID agentId = UUID.randomUUID();
        when(defaultAgentRepository.findByChannel(CHANNEL)).thenReturn(Optional.empty());
        when(defaultAgentRepository.save(any(ChannelDefaultAgent.class))).thenAnswer(inv -> inv.getArgument(0));

        service.setDefaultAgent(CHANNEL, agentId);

        org.mockito.ArgumentCaptor<ChannelDefaultAgent> captor = org.mockito.ArgumentCaptor.forClass(ChannelDefaultAgent.class);
        verify(defaultAgentRepository).save(captor.capture());
        assertEquals(agentId, captor.getValue().getAgentId());
    }

    @Test
    void setDefaultAgentUpdatesTheExistingEntryRatherThanCreatingASecondRow() {
        UUID oldAgentId = UUID.randomUUID();
        UUID newAgentId = UUID.randomUUID();
        ChannelDefaultAgent existing = new ChannelDefaultAgent(CHANNEL, oldAgentId);
        when(defaultAgentRepository.findByChannel(CHANNEL)).thenReturn(Optional.of(existing));

        service.setDefaultAgent(CHANNEL, newAgentId);

        assertEquals(newAgentId, existing.getAgentId());
        verify(defaultAgentRepository).save(existing);
    }

    // --- rebindAgent() ---------------------------------------------------------

    @Test
    void rebindAgentRepointsAnExistingSessionToTheNewAgent() {
        UUID sessionId = UUID.randomUUID();
        UUID newAgentId = UUID.randomUUID();
        ChannelSession session = mock(ChannelSession.class);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        service.rebindAgent(sessionId, newAgentId);

        verify(session).rebind(newAgentId);
    }

    @Test
    void rebindAgentThrowsWhenTheSessionDoesNotExist() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        assertThrows(ChannelSessionNotFoundException.class, () -> service.rebindAgent(sessionId, UUID.randomUUID()));
    }

    // --- markPairingNoticeSent() -------------------------------------------

    @Test
    void markPairingNoticeSentFlagsAnExistingSession() {
        UUID sessionId = UUID.randomUUID();
        ChannelSession session = mock(ChannelSession.class);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        service.markPairingNoticeSent(sessionId);

        verify(session).markPairingNoticeSent();
    }

    @Test
    void markPairingNoticeSentIsANoOpForAMissingSessionRatherThanThrowing() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        service.markPairingNoticeSent(sessionId);
        // No exception - ifPresent() on an empty Optional.
    }

    // --- consumeAiDisclosurePrefix(): exactly-once per session -----------------

    @Test
    void consumeAiDisclosurePrefixReturnsTheDisclosureTextOnTheFirstCallForASession() {
        UUID sessionId = UUID.randomUUID();
        ChannelSession session = mock(ChannelSession.class);
        when(session.markAiDisclosureSentIfNeeded()).thenReturn(true);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        String prefix = service.consumeAiDisclosurePrefix(sessionId);

        assertTrue(prefix.contains("AI assistant"));
        assertFalse(prefix.isEmpty());
    }

    @Test
    void consumeAiDisclosurePrefixReturnsAnEmptyStringOnceAlreadySentForThisSession() {
        UUID sessionId = UUID.randomUUID();
        ChannelSession session = mock(ChannelSession.class);
        when(session.markAiDisclosureSentIfNeeded()).thenReturn(false);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        assertEquals("", service.consumeAiDisclosurePrefix(sessionId));
    }

    @Test
    void consumeAiDisclosurePrefixReturnsAnEmptyStringForAMissingSession() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        assertEquals("", service.consumeAiDisclosurePrefix(sessionId));
    }

    // --- listPendingPairings() / approvePairing() / rejectPairing() ------------

    @Test
    void listPendingPairingsDelegatesToTheUnpairedOldestFirstQuery() {
        ChannelSession pending = mock(ChannelSession.class);
        when(sessionRepository.findByPairedFalseOrderByCreatedAtDesc()).thenReturn(List.of(pending));

        assertEquals(List.of(pending), service.listPendingPairings());
    }

    @Test
    void approvePairingApprovesAnExistingSessionAndReturnsIt() {
        UUID sessionId = UUID.randomUUID();
        ChannelSession session = mock(ChannelSession.class);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        ChannelSession result = service.approvePairing(sessionId);

        verify(session).approvePairing();
        assertEquals(session, result);
    }

    @Test
    void approvePairingThrowsForAMissingSession() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        assertThrows(ChannelSessionNotFoundException.class, () -> service.approvePairing(sessionId));
    }

    @Test
    void rejectPairingDeletesAnExistingSession() {
        UUID sessionId = UUID.randomUUID();
        ChannelSession session = mock(ChannelSession.class);
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));

        service.rejectPairing(sessionId);

        verify(sessionRepository).delete(session);
    }

    @Test
    void rejectPairingThrowsForAMissingSessionWithoutCallingDelete() {
        UUID sessionId = UUID.randomUUID();
        when(sessionRepository.findById(sessionId)).thenReturn(Optional.empty());

        assertThrows(ChannelSessionNotFoundException.class, () -> service.rejectPairing(sessionId));
        verify(sessionRepository, never()).delete(any());
    }

    // --- eraseDataSubject(): GDPR Article 17, #815 Governance follow-up --------

    @Test
    void eraseDataSubjectReturnsFalseAndTouchesNothingWhenNoSessionExistsForTheSubject() {
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.empty());

        assertFalse(service.eraseDataSubject(CHANNEL, EXTERNAL_ID));

        verify(messageLogRepository, never()).deleteByChannelAndExternalId(any(), any());
        verify(sessionRepository, never()).delete(any());
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void eraseDataSubjectDeletesMessageLogsAndTheSessionAndRecordsAMaskedAuditEntry() {
        UUID sessionId = UUID.randomUUID();
        ChannelSession session = mock(ChannelSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.of(session));
        when(messageLogRepository.deleteByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(3L);
        when(agentMemoryServiceProvider.getIfAvailable()).thenReturn(null);

        boolean erased = service.eraseDataSubject(CHANNEL, EXTERNAL_ID);

        assertTrue(erased);
        verify(sessionRepository).delete(session);
        org.mockito.ArgumentCaptor<String> detailCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(
            eq("system:data-subject-erasure"), eq("channel_session.erase_data_subject"), eq("channel_session"),
            eq(sessionId.toString()), eq(AuditOutcome.SUCCESS), detailCaptor.capture()
        );
        String detail = detailCaptor.getValue();
        assertTrue(detail.contains("3 message log row(s)"));
        assertTrue(detail.contains("0 agent-memory row(s)"));
        // Masked - last 4 chars of the external id only, never the full value.
        assertTrue(detail.contains("***4567"));
        assertFalse(detail.contains(EXTERNAL_ID));
    }

    @Test
    void eraseDataSubjectGracefullySkipsAgentMemoryErasureWhenTheKnowledgeProfileIsNotActive() {
        // agentMemoryServiceProvider.getIfAvailable() == null models the
        // "knowledge" profile not being active in this build - forgetAllForSubject
        // must never be called on a null service.
        ChannelSession session = mock(ChannelSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.of(session));
        when(messageLogRepository.deleteByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(0L);
        when(agentMemoryServiceProvider.getIfAvailable()).thenReturn(null);

        service.eraseDataSubject(CHANNEL, EXTERNAL_ID);
        // No exception thrown reaching this line already proves the null-safe branch.
    }

    @Test
    void eraseDataSubjectErasesAgentMemoryWhenTheServiceIsAvailableAndFoldsTheCountIntoTheAuditDetail() {
        ChannelSession session = mock(ChannelSession.class);
        UUID sessionId = UUID.randomUUID();
        when(session.getId()).thenReturn(sessionId);
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.of(session));
        when(messageLogRepository.deleteByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(2L);
        AgentMemoryService memoryService = mock(AgentMemoryService.class);
        when(memoryService.forgetAllForSubject(EXTERNAL_ID)).thenReturn(7);
        when(agentMemoryServiceProvider.getIfAvailable()).thenReturn(memoryService);

        service.eraseDataSubject(CHANNEL, EXTERNAL_ID);

        verify(memoryService).forgetAllForSubject(EXTERNAL_ID);
        org.mockito.ArgumentCaptor<String> detailCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(any(), any(), any(), any(), any(), detailCaptor.capture());
        assertTrue(detailCaptor.getValue().contains("7 agent-memory row(s)"));
    }

    @Test
    void eraseDataSubjectMasksAShortExternalIdEntirelyRatherThanIndexingOutOfBounds() {
        // externalId.length() < 4 - the fallback branch ("***" with no suffix)
        // must be used instead of substring()'ing past the string's length.
        String shortId = "abc";
        ChannelSession session = mock(ChannelSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, shortId)).thenReturn(Optional.of(session));
        when(messageLogRepository.deleteByChannelAndExternalId(CHANNEL, shortId)).thenReturn(0L);
        when(agentMemoryServiceProvider.getIfAvailable()).thenReturn(null);

        service.eraseDataSubject(CHANNEL, shortId);

        org.mockito.ArgumentCaptor<String> detailCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(any(), any(), any(), any(), any(), detailCaptor.capture());
        assertTrue(detailCaptor.getValue().endsWith("***"));
    }

    @Test
    void eraseDataSubjectDeletesMessageLogsBeforeDeletingTheSessionItself() {
        // Delete-order invariant per the method's own javadoc: message logs,
        // then agent memory, then the session, audit record last.
        ChannelSession session = mock(ChannelSession.class);
        when(session.getId()).thenReturn(UUID.randomUUID());
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.of(session));
        when(messageLogRepository.deleteByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(1L);
        when(agentMemoryServiceProvider.getIfAvailable()).thenReturn(null);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(messageLogRepository, sessionRepository, auditLogService);

        service.eraseDataSubject(CHANNEL, EXTERNAL_ID);

        inOrder.verify(messageLogRepository).deleteByChannelAndExternalId(CHANNEL, EXTERNAL_ID);
        inOrder.verify(sessionRepository).delete(session);
        inOrder.verify(auditLogService).record(any(), any(), any(), any(), any(), any());
    }

    // --- DPDP-oriented consent step: no transcript before consent ----------

    @SuppressWarnings("unchecked")
    private ConversationConsentGate withConsentGate(boolean mayStore) {
        ConversationConsentGate gate = mock(ConversationConsentGate.class);
        when(gate.mayStoreContent(any(), eq(CHANNEL.name()), eq(EXTERNAL_ID))).thenReturn(mayStore);
        ObjectProvider<ConversationConsentGate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gate);
        service.setConsentGate(provider);
        return gate;
    }

    @Test
    void anUnconsentedSendersMessageIsNeitherLoggedNorKeptAsPreview() {
        ChannelSession existing = mock(ChannelSession.class);
        when(existing.getId()).thenReturn(UUID.randomUUID());
        when(existing.getAgentId()).thenReturn(UUID.randomUUID());
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.of(existing));
        withConsentGate(false);

        service.resolveOrCreate(CHANNEL, EXTERNAL_ID, "my name is Priya and my PAN is ...", null);

        verify(messageLogRepository, never()).save(any());
        verify(existing).clearMessagePreview();
        // The safety scan is handed no text either, so a flagged message is not persisted by it.
        verify(inboundSafetyScanService).apply(existing, null);
    }

    @Test
    void aConsentedSendersMessageIsLoggedAsBefore() {
        ChannelSession existing = mock(ChannelSession.class);
        when(existing.getId()).thenReturn(UUID.randomUUID());
        when(existing.getAgentId()).thenReturn(UUID.randomUUID());
        when(sessionRepository.findByChannelAndExternalId(CHANNEL, EXTERNAL_ID)).thenReturn(Optional.of(existing));
        withConsentGate(true);

        service.resolveOrCreate(CHANNEL, EXTERNAL_ID, "hello", null);

        verify(messageLogRepository).save(any(ChannelMessageLog.class));
        verify(existing, never()).clearMessagePreview();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveOrCreate_overTheRateCapThrowsBeforeTouchingAnyRepository() {
        InboundRateLimiter limiter = mock(InboundRateLimiter.class);
        org.mockito.Mockito.doThrow(new InboundRateLimitedException(30, false))
            .when(limiter).checkOrThrow(CHANNEL, EXTERNAL_ID);
        ObjectProvider<InboundRateLimiter> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(limiter);
        service.setRateLimiter(provider);

        InboundRateLimitedException thrown = assertThrows(
            InboundRateLimitedException.class,
            () -> service.resolveOrCreate(CHANNEL, EXTERNAL_ID, "hello"));

        assertEquals(InboundRateLimitedException.FRIENDLY_MESSAGE, thrown.getMessage());
        org.mockito.Mockito.verifyNoInteractions(sessionRepository, messageLogRepository, contactService);
    }
}
