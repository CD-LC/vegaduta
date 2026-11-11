package com.agenticai.core.omnichannel.handoff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.handoff.OperatorInboxController.ConversationPageResponse;
import com.agenticai.core.omnichannel.handoff.OperatorInboxController.EscalateRequest;
import com.agenticai.core.omnichannel.handoff.OperatorInboxController.InboxSessionResponse;
import com.agenticai.core.omnichannel.handoff.OperatorInboxController.ReplyRequest;
import com.agenticai.core.omnichannel.handoff.OperatorInboxController.TranscriptMessageResponse;
import com.agenticai.core.omnichannel.session.ChannelMessageDirection;
import com.agenticai.core.omnichannel.session.ChannelMessageLog;
import com.agenticai.core.omnichannel.session.ChannelSession;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.omnichannel.session.HandoffState;
import com.agenticai.core.security.TenantContext;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;

/** The admin gate, view mapping and masking of {@link OperatorInboxController}. */
class OperatorInboxControllerTest {

    private OperatorInboxService inbox;
    private HandoffEscalationService escalation;
    private OperatorInboxController controller;
    private final UUID id = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        inbox = mock(OperatorInboxService.class);
        escalation = mock(HandoffEscalationService.class);
        controller = new OperatorInboxController(inbox, escalation);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static Authentication admin(String name) {
        return new TestingAuthenticationToken(name, "n/a", "PERM_MANAGE_CHANNELS");
    }

    private static Authentication member() {
        return new TestingAuthenticationToken("member", "n/a", "PERM_VIEW_AGENTS");
    }

    private ChannelSession session(String externalId, boolean paired) {
        ChannelSession s =
                new ChannelSession(
                        ChannelType.WHATSAPP, externalId, "s", UUID.randomUUID(), "hello");
        ReflectionTestUtils.setField(s, "id", id);
        if (paired) {
            s.approvePairing();
        }
        return s;
    }

    @Test
    void theInboxMapsSessionsWithMaskedAddresses() {
        when(inbox.listNeedingAttention())
                .thenReturn(List.of(session("+919812345678", true), session("ab", true)));

        ResponseEntity<List<InboxSessionResponse>> response = controller.inbox(null, null);

        assertThat(response.getBody())
                .extracting(InboxSessionResponse::externalIdMasked)
                .containsExactly("***5678", "***");
        assertThat(response.getBody().get(0).channel()).isEqualTo(ChannelType.WHATSAPP);
    }

    @Test
    void statsPassThrough() {
        OperatorInboxService.InboxStats stats = new OperatorInboxService.InboxStats(1, 2, 3, 4, 10);
        when(inbox.stats()).thenReturn(stats);

        assertThat(controller.stats()).isSameAs(stats);
    }

    @Test
    void eachConversationViewMapsToItsFilter() {
        when(inbox.searchConversations(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 25), 0));

        controller.conversations(null, "waiting", null, 0, 25);
        verify(inbox)
                .searchConversations(
                        null, HandoffState.AWAITING_HUMAN, null, null, PageRequest.of(0, 25));
        controller.conversations(ChannelType.SLACK, "held", "  bob ", 0, 25);
        verify(inbox)
                .searchConversations(
                        ChannelType.SLACK, HandoffState.HUMAN, null, "bob", PageRequest.of(0, 25));
        controller.conversations(null, "agent", " ", 0, 25);
        verify(inbox)
                .searchConversations(null, HandoffState.AGENT, true, null, PageRequest.of(0, 25));
        controller.conversations(null, "pending", null, 0, 25);
        verify(inbox).searchConversations(null, null, false, null, PageRequest.of(0, 25));
        controller.conversations(null, "all", null, 0, 25);
        verify(inbox).searchConversations(null, null, null, null, PageRequest.of(0, 25));
    }

    @Test
    void pagingIsClampedAndAPendingConversationShowsItsPairingCode() {
        ChannelSession pending = session("+919812345678", false);
        ChannelSession paired = session("+919800000000", true);
        when(inbox.searchConversations(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(pending, paired), PageRequest.of(0, 100), 150));

        ConversationPageResponse page = controller.conversations(null, "all", null, -5, 1000);

        verify(inbox).searchConversations(null, null, null, null, PageRequest.of(0, 100));
        assertThat(page.total()).isEqualTo(150);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.items().get(0).pairingCode()).isNotNull();
        assertThat(page.items().get(1).pairingCode()).isNull();
        assertThat(page.items().get(0).paired()).isFalse();
        verify(inbox).searchConversations(any(), any(), any(), any(), eq(PageRequest.of(0, 100)));
    }

    @Test
    void aSizeBelowOneIsRaisedToOne() {
        when(inbox.searchConversations(any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));

        controller.conversations(null, "all", null, 0, 0);

        verify(inbox).searchConversations(null, null, null, null, PageRequest.of(0, 1));
    }

    @Test
    void everyActionIsForbiddenForNonAdminsAndAnonymousCallers() {
        for (Authentication who : new Authentication[] {member(), null}) {
            assertThat(controller.transcript(id, who).getStatusCode().value()).isEqualTo(403);
            assertThat(
                            controller
                                    .escalate(id, new EscalateRequest("r"), who)
                                    .getStatusCode()
                                    .value())
                    .isEqualTo(403);
            assertThat(controller.takeOver(id, who).getStatusCode().value()).isEqualTo(403);
            assertThat(controller.reply(id, new ReplyRequest("b"), who).getStatusCode().value())
                    .isEqualTo(403);
            assertThat(controller.release(id, who).getStatusCode().value()).isEqualTo(403);
        }
        verify(inbox, never()).takeOver(any(), any());
        verify(escalation, never()).escalate(any(), any(), any());
    }

    @Test
    void aPlatformAdminCountsAsAnOperator() {
        Authentication platform =
                new TestingAuthenticationToken("root", "n/a", "ROLE_platform-admin");
        when(inbox.takeOver(id, "root")).thenReturn(session("x1234", true));

        assertThat(controller.takeOver(id, platform).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void theTenantContextSubjectWinsOverTheAuthenticationName() {
        TenantContext.set(TenantContext.of("acme", "ctx-subject", Set.of()));
        when(inbox.takeOver(id, "ctx-subject")).thenReturn(session("x1234", true));

        controller.takeOver(id, admin("auth-name"));

        verify(inbox).takeOver(id, "ctx-subject");
    }

    @Test
    void withoutAContextTheAuthenticationNameIsUsedAndNothingFallsBackToUnknown() {
        when(inbox.release(id, "auth-name")).thenReturn(session("x1234", true));
        when(inbox.reply(id, "hi", "unknown")).thenReturn(session("x1234", true));

        controller.release(id, admin("auth-name"));
        verify(inbox).release(id, "auth-name");

        controller.reply(id, new ReplyRequest("hi"), admin(" "));
        verify(inbox).reply(id, "hi", "unknown");
    }

    @Test
    void escalationAndTranscriptAreMapped() {
        when(escalation.escalate(id, "angry", "auth-name")).thenReturn(session("x1234", true));
        ChannelMessageLog message = mock(ChannelMessageLog.class);
        when(message.getId()).thenReturn(UUID.randomUUID());
        when(message.getDirection()).thenReturn(ChannelMessageDirection.OUTBOUND);
        when(message.getAuthor()).thenReturn("op-1");
        when(message.getBody()).thenReturn("hello");
        when(inbox.transcript(id)).thenReturn(List.of(message));

        ResponseEntity<?> escalated =
                controller.escalate(id, new EscalateRequest("angry"), admin("auth-name"));
        ResponseEntity<?> transcript = controller.transcript(id, admin("auth-name"));

        assertThat(escalated.getStatusCode().value()).isEqualTo(200);
        @SuppressWarnings("unchecked")
        List<TranscriptMessageResponse> messages =
                (List<TranscriptMessageResponse>) transcript.getBody();
        assertThat(messages).extracting(TranscriptMessageResponse::body).containsExactly("hello");
        assertThat(messages.get(0).author()).isEqualTo("op-1");
    }
}
