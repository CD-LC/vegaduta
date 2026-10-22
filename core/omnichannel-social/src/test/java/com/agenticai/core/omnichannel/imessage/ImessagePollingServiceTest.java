package com.agenticai.core.omnichannel.imessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.tenancy.TenantResolutionService;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ImessagePollingServiceTest {

    private static final String PAGE =
            "{\"status\":200,\"data\":["
                    + "{\"guid\":\"a\",\"text\":\"one\",\"dateCreated\":2000,\"isFromMe\":false},"
                    + "{\"guid\":\"b\",\"text\":\"two\",\"dateCreated\":3000,\"isFromMe\":false}]}";

    private ImessagePollingService service(
            FakeBlueBubbles bb,
            ImessageCredentialService credentials,
            ImessageUpdateService updates) {
        return new ImessagePollingService(
                mock(TenantResolutionService.class), credentials, bb.client(), updates, 5000);
    }

    @Test
    void aRoundHandsEveryNewMessageToTheUpdateServiceAndAdvancesTheCursor() throws Exception {
        try (FakeBlueBubbles bb =
                new FakeBlueBubbles().on("POST /api/v1/message/query", 200, PAGE)) {
            ImessageCredentialService credentials = mock(ImessageCredentialService.class);
            ImessageUpdateService updates = mock(ImessageUpdateService.class);
            when(updates.acceptVerified(any())).thenReturn(true);
            when(credentials.pollTarget("wid"))
                    .thenReturn(
                            Optional.of(
                                    new ImessageCredentialService.PollTarget(
                                            bb.url(), FakeBlueBubbles.PASSWORD, 1000L)));

            ImessagePollingService.Round round = service(bb, credentials, updates).pollOnce("wid");

            assertThat(round).isEqualTo(ImessagePollingService.Round.IDLE);
            ArgumentCaptor<JsonNode> handled = ArgumentCaptor.forClass(JsonNode.class);
            verify(updates, org.mockito.Mockito.times(2)).acceptVerified(handled.capture());
            assertThat(handled.getAllValues())
                    .extracting(n -> n.path("guid").asText())
                    .containsExactly("a", "b");
            verify(credentials).savePollCursor(3000L);
        }
    }

    @Test
    void theFirstRoundOnlySetsTheCursorAndNeverReplaysHistory() throws Exception {
        try (FakeBlueBubbles bb =
                new FakeBlueBubbles().on("POST /api/v1/message/query", 200, PAGE)) {
            ImessageCredentialService credentials = mock(ImessageCredentialService.class);
            ImessageUpdateService updates = mock(ImessageUpdateService.class);
            when(credentials.pollTarget("wid"))
                    .thenReturn(
                            Optional.of(
                                    new ImessageCredentialService.PollTarget(
                                            bb.url(), FakeBlueBubbles.PASSWORD, null)));

            ImessagePollingService.Round round = service(bb, credentials, updates).pollOnce("wid");

            assertThat(round).isEqualTo(ImessagePollingService.Round.IDLE);
            verify(credentials).savePollCursor(org.mockito.ArgumentMatchers.anyLong());
            verify(updates, never()).acceptVerified(any());
            assertThat(bb.requests).isEmpty();
        }
    }

    @Test
    void aShedMessageHoldsTheCursorBackSoItIsRetried() throws Exception {
        try (FakeBlueBubbles bb =
                new FakeBlueBubbles().on("POST /api/v1/message/query", 200, PAGE)) {
            ImessageCredentialService credentials = mock(ImessageCredentialService.class);
            ImessageUpdateService updates = mock(ImessageUpdateService.class);
            when(updates.acceptVerified(any())).thenReturn(true, false);
            when(credentials.pollTarget("wid"))
                    .thenReturn(
                            Optional.of(
                                    new ImessageCredentialService.PollTarget(
                                            bb.url(), FakeBlueBubbles.PASSWORD, 1000L)));

            ImessagePollingService.Round round = service(bb, credentials, updates).pollOnce("wid");

            assertThat(round).isEqualTo(ImessagePollingService.Round.SHED);
            verify(credentials).savePollCursor(2000L); // only the first message was handled
        }
    }

    @Test
    void aConnectionThatIsGoneOrNotPollingStopsThePoller() throws Exception {
        try (FakeBlueBubbles bb = new FakeBlueBubbles()) {
            ImessageCredentialService credentials = mock(ImessageCredentialService.class);
            when(credentials.pollTarget("wid")).thenReturn(Optional.empty());

            assertThat(service(bb, credentials, mock(ImessageUpdateService.class)).pollOnce("wid"))
                    .isEqualTo(ImessagePollingService.Round.STOP);
        }
    }
}
