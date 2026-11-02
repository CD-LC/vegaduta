package com.agenticai.core.omnichannel.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.agenticai.core.omnichannel.relay.EdgeRelayController.FailRequest;
import com.agenticai.core.omnichannel.relay.EdgeRelayController.RegisterRequest;
import com.agenticai.core.omnichannel.relay.EdgeRelayController.RegisterResponse;
import com.agenticai.core.omnichannel.relay.EdgeRelayController.ReplyRequest;
import com.agenticai.core.omnichannel.relay.EdgeRelayController.StatusResponse;
import com.agenticai.core.omnichannel.session.ChannelType;
import com.agenticai.core.security.TenantContext;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

/** The tenant gate and status codes of the local-model edge relay endpoints. */
class EdgeRelayControllerTest {

    private EdgeRelayService relay;
    private EdgeRelayController controller;

    @BeforeEach
    void setUp() {
        relay = mock(EdgeRelayService.class);
        controller = new EdgeRelayController(relay);
        when(relay.isEnabled()).thenReturn(true);
        TenantContext.set(TenantContext.of("acme", "user-1", Set.of()));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static int statusOf(Runnable call) {
        try {
            call.run();
            return 0;
        } catch (ResponseStatusException e) {
            return e.getStatusCode().value();
        }
    }

    @Test
    void withoutATenantEveryEndpointIsUnauthorized() {
        TenantContext.clear();

        assertThat(statusOf(() -> controller.status())).isEqualTo(401);
        assertThat(statusOf(() -> controller.heartbeat("r"))).isEqualTo(401);
        assertThat(statusOf(() -> controller.next("r", 1))).isEqualTo(401);
        assertThat(statusOf(() -> controller.reply("j", new ReplyRequest("r", "t"))))
                .isEqualTo(401);
    }

    @Test
    void statusListsOnlineRelaysWithTheirAnsweredCount() {
        EdgeRelayService.RelayInfo info =
                new EdgeRelayService.RelayInfo(
                        "r1", "user-1", Set.of(ChannelType.WHATSAPP), "llama", "laptop", 123L);
        when(relay.onlineRelays("acme")).thenReturn(java.util.List.of(info));
        when(relay.answered("acme", "r1")).thenReturn(4L);

        StatusResponse status = controller.status();

        assertThat(status.enabled()).isTrue();
        assertThat(status.relays()).hasSize(1);
        assertThat(status.relays().get(0).answered()).isEqualTo(4);
        assertThat(status.relays().get(0).deviceLabel()).isEqualTo("laptop");
    }

    @Test
    void registrationIsRefusedWhenDisabledAndOtherwiseReturnsTheTimings() {
        when(relay.isEnabled()).thenReturn(false);
        assertThatThrownBy(
                        () ->
                                controller.register(
                                        new RegisterRequest(Set.of(ChannelType.SLACK), "m", "d")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("disabled");

        when(relay.isEnabled()).thenReturn(true);
        when(relay.register("acme", "user-1", Set.of(ChannelType.SLACK), "m", "d"))
                .thenReturn(
                        new EdgeRelayService.RelayInfo(
                                "r9", "user-1", Set.of(ChannelType.SLACK), "m", "d", 1L));
        when(relay.heartbeatTtl()).thenReturn(Duration.ofSeconds(60));
        when(relay.claimWaitSecondsMax()).thenReturn(25);
        when(relay.replyWait()).thenReturn(Duration.ofSeconds(30));

        RegisterResponse response =
                controller.register(new RegisterRequest(Set.of(ChannelType.SLACK), "m", "d"));

        assertThat(response.relayId()).isEqualTo("r9");
        assertThat(response.heartbeatSeconds()).isEqualTo(30);
        assertThat(response.claimWaitSecondsMax()).isEqualTo(25);
        assertThat(response.replyWaitSeconds()).isEqualTo(30);

        when(relay.heartbeatTtl()).thenReturn(Duration.ofSeconds(4));
        assertThat(
                        controller
                                .register(new RegisterRequest(Set.of(ChannelType.SLACK), "m", "d"))
                                .heartbeatSeconds())
                .isEqualTo(5);
    }

    @Test
    void aSubjectlessContextRegistersAsUnknown() {
        TenantContext.set(TenantContext.of("acme", null, Set.of()));
        when(relay.heartbeatTtl()).thenReturn(Duration.ofSeconds(60));
        when(relay.replyWait()).thenReturn(Duration.ofSeconds(30));
        when(relay.register(
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(
                        new EdgeRelayService.RelayInfo("r", "unknown", Set.of(), null, null, 1L));

        controller.register(new RegisterRequest(Set.of(ChannelType.SLACK), null, null));

        verify(relay).register("acme", "unknown", Set.of(ChannelType.SLACK), null, null);
    }

    @Test
    void heartbeatAndNextTreatAnUnknownRelayAs404() {
        when(relay.heartbeat("acme", "r1")).thenReturn(true);
        controller.heartbeat("r1");
        assertThat(statusOf(() -> controller.heartbeat("gone"))).isEqualTo(404);
        assertThat(statusOf(() -> controller.next("gone", 5))).isEqualTo(404);
    }

    @Test
    void nextReturnsAJobOr204WhenNothingIsWaiting() {
        EdgeRelayService.RelayInfo info =
                new EdgeRelayService.RelayInfo("r1", "user-1", Set.of(), null, null, 1L);
        when(relay.relay("acme", "r1")).thenReturn(Optional.of(info));
        RelayJob job = mock(RelayJob.class);
        when(relay.claim("acme", "r1", 20)).thenReturn(Optional.of(job));
        when(relay.claim("acme", "r1", 1)).thenReturn(Optional.empty());

        ResponseEntity<RelayJob> some = controller.next("r1", 20);
        ResponseEntity<RelayJob> none = controller.next("r1", 1);

        assertThat(some.getStatusCode().value()).isEqualTo(200);
        assertThat(some.getBody()).isSameAs(job);
        assertThat(none.getStatusCode().value()).isEqualTo(204);
        verify(relay, org.mockito.Mockito.never())
                .claim(anyString(), anyString(), org.mockito.ArgumentMatchers.eq(99));
    }

    @Test
    void repliesFailuresAndUnregisterDelegateAndAnAlreadyAnsweredJobIs410() {
        when(relay.complete("acme", "r1", "j1", "hello")).thenReturn(true);
        controller.reply("j1", new ReplyRequest("r1", "hello"));
        assertThat(statusOf(() -> controller.reply("j2", new ReplyRequest("r1", "late"))))
                .isEqualTo(410);

        controller.fail("j1", new FailRequest("r1", "model crashed"));
        verify(relay).fail("acme", "r1", "j1", "model crashed");

        controller.unregister("r1");
        verify(relay).unregister("acme", "r1");
    }
}
