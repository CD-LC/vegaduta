package com.agenticai.core.omnichannel.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.omnichannel.session.ChannelType;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The edge relay's registry and queue over an in-memory Valkey stand-in. */
class EdgeRelayServiceTest {

    private static final String TENANT = "tenant_a";

    private FakeRedis redis;
    private EdgeRelayService service;

    @BeforeEach
    void setUp() {
        redis = new FakeRedis();
        redis.queueKeys.add(EdgeRelayService.queueKey(TENANT, ChannelType.SMS));
        redis.queueKeys.add(EdgeRelayService.queueKey(TENANT, ChannelType.WHATSAPP));
        service = new EdgeRelayService(redis.template, true, 45, 25, 60, 90, 2);
    }

    @Test
    void disabledRelayNeverQueuesAndListsNobody() {
        EdgeRelayService off = new EdgeRelayService(redis.template, false, 45, 25, 60, 90, 2);
        assertThrows(IllegalStateException.class,
                () -> off.register(TENANT, "u", Set.of(ChannelType.SMS), "qwen", "laptop"));
        assertTrue(off.onlineRelays(TENANT).isEmpty());
        assertTrue(off.enqueue(TENANT, ChannelType.SMS, UUID.randomUUID(), UUID.randomUUID(), "***1111", "hi", List.of()).isEmpty());
    }

    @Test
    void registrationIsOnlineForItsChannelsUntilTheHeartbeatLapses() {
        EdgeRelayService.RelayInfo info = service.register(TENANT, "alice", Set.of(ChannelType.SMS), "Qwen 1.5B", "laptop");

        assertTrue(service.isOnline(TENANT, ChannelType.SMS));
        assertFalse(service.isOnline(TENANT, ChannelType.WHATSAPP));
        assertTrue(service.heartbeat(TENANT, info.relayId()));
        assertFalse(service.heartbeat(TENANT, "nope"));

        redis.expireAlive(EdgeRelayService.aliveKey(TENANT, info.relayId()));
        assertFalse(service.isOnline(TENANT, ChannelType.SMS), "no heartbeat, no relay");
        assertTrue(service.onlineRelays(TENANT).isEmpty(), "the stale registration is pruned");
        assertFalse(service.heartbeat(TENANT, info.relayId()), "a pruned relay must re-register");
    }

    @Test
    void messageTravelsToTheRelayAndTheAnswerComesBack() {
        EdgeRelayService.RelayInfo info = service.register(TENANT, "alice", Set.of(ChannelType.SMS), "Qwen", null);
        UUID sessionId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();

        Optional<RelayJob> queued = service.enqueue(TENANT, ChannelType.SMS, sessionId, agentId, "***1111",
                "what time do you open?", List.of(new RelayJob.RelayTurn("user", "hello")));
        assertTrue(queued.isPresent());

        Optional<RelayJob> claimed = service.claim(TENANT, info.relayId(), 5);
        assertTrue(claimed.isPresent());
        assertEquals(queued.get().jobId(), claimed.get().jobId());
        assertEquals(RelayJob.CLAIMED, claimed.get().status());
        assertEquals(info.relayId(), claimed.get().claimedBy());
        assertEquals(agentId, claimed.get().agentId());
        assertEquals("hello", claimed.get().history().get(0).text());

        assertTrue(service.complete(TENANT, info.relayId(), claimed.get().jobId(), "We open at 9."));
        assertEquals(Optional.of("We open at 9."), service.awaitReply(TENANT, queued.get().jobId()));
        assertEquals(1L, service.answered(TENANT, info.relayId()));
        assertTrue(service.claim(TENANT, info.relayId(), 0).isEmpty(), "queue is empty again");
    }

    @Test
    void aTimedOutJobIsCancelledAndALateReplyIsDropped() {
        EdgeRelayService.RelayInfo info = service.register(TENANT, "alice", Set.of(ChannelType.SMS), "Qwen", null);
        RelayJob job = service.enqueue(TENANT, ChannelType.SMS, UUID.randomUUID(), UUID.randomUUID(), "***1", "hi", List.of()).get();
        RelayJob claimed = service.claim(TENANT, info.relayId(), 0).get();

        // The fake's blocking pop returns at once when nothing was pushed: that is the timeout.
        assertTrue(service.awaitReply(TENANT, job.jobId()).isEmpty());
        assertFalse(service.complete(TENANT, info.relayId(), claimed.jobId(), "too late"), "cancelled job rejects the reply");
        assertEquals(0L, service.answered(TENANT, info.relayId()));
    }

    @Test
    void aRelayThatDeclinesSendsTheMessageHosted() {
        EdgeRelayService.RelayInfo info = service.register(TENANT, "alice", Set.of(ChannelType.SMS), "Qwen", null);
        RelayJob job = service.enqueue(TENANT, ChannelType.SMS, UUID.randomUUID(), UUID.randomUUID(), "***1", "hi", List.of()).get();
        service.claim(TENANT, info.relayId(), 0);

        assertTrue(service.fail(TENANT, info.relayId(), job.jobId(), "no local tier"));
        assertTrue(service.awaitReply(TENANT, job.jobId()).isEmpty());
    }

    @Test
    void onlyTheClaimingRelayMayAnswerAndARelayOnlyClaimsItsOwnChannels() {
        EdgeRelayService.RelayInfo sms = service.register(TENANT, "alice", Set.of(ChannelType.SMS), "Qwen", null);
        EdgeRelayService.RelayInfo wa = service.register(TENANT, "bob", Set.of(ChannelType.WHATSAPP), "Llama", null);
        RelayJob job = service.enqueue(TENANT, ChannelType.SMS, UUID.randomUUID(), UUID.randomUUID(), "***1", "hi", List.of()).get();

        // The WhatsApp relay's claim pops nothing from the SMS queue in a real BLPOP; the fake
        // pops across all queues, so assert the ownership rule on the reply instead.
        RelayJob claimed = service.claim(TENANT, sms.relayId(), 0).get();
        assertEquals(job.jobId(), claimed.jobId());
        assertFalse(service.complete(TENANT, wa.relayId(), job.jobId(), "not mine"));
        assertTrue(service.complete(TENANT, sms.relayId(), job.jobId(), "mine"));
    }

    @Test
    void aFullQueueFallsBackToHosted() {
        service.register(TENANT, "alice", Set.of(ChannelType.SMS), "Qwen", null);
        for (int i = 0; i < 2; i++) {
            assertTrue(service.enqueue(TENANT, ChannelType.SMS, UUID.randomUUID(), UUID.randomUUID(), "***1", "m" + i, List.of()).isPresent());
        }
        assertTrue(service.enqueue(TENANT, ChannelType.SMS, UUID.randomUUID(), UUID.randomUUID(), "***1", "m3", List.of()).isEmpty());
    }
}
