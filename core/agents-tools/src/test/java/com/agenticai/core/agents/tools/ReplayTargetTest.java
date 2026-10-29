package com.agenticai.core.agents.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agenticai.core.agents.memory.SessionMemoryService;
import com.agenticai.core.approval.ApprovalRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The replay envelope vs. legacy payload shapes, and the refusal of what cannot be replayed. */
class ReplayTargetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    private static ApprovalRequest request(String payload, String requestedBy) {
        ApprovalRequest request = mock(ApprovalRequest.class);
        when(request.getId()).thenReturn(REQUEST_ID);
        when(request.getActionType())
                .thenReturn(AgentToolCallApprovalExecutor.ACTION_TYPE_PREFIX + "send_file");
        when(request.getPayloadJson()).thenReturn(payload);
        when(request.getRequestedBy()).thenReturn(requestedBy);
        return request;
    }

    @Test
    void anEnvelopePayloadResolvesEveryField() {
        String payload =
                "{\"replay\":{\"agentId\":\""
                        + AGENT_ID
                        + "\",\"toolName\":\"send_file\",\"sessionId\":\"s-1\","
                        + "\"tenantId\":\"tenant-a\",\"subject\":\"alice@x.com\","
                        + "\"toolDigest\":\"d39\",\"memorySessionId\":\"c1:abc\"},"
                        + "\"arguments\":{\"fileId\":\"f-1\",\"to\":\"bob\"}}";
        ReplayTarget target = ReplayTarget.resolve(request(payload, "whoever"), MAPPER);

        assertEquals(AGENT_ID, target.agentId());
        assertEquals("s-1", target.sessionId());
        assertEquals("send_file", target.toolName());
        assertEquals("{\"fileId\":\"f-1\",\"to\":\"bob\"}", target.argumentsJson());
        assertEquals("tenant-a", target.tenantId());
        assertEquals("alice@x.com", target.subject());
        assertEquals("d39", target.toolDigest());
        assertEquals("c1:abc", target.memorySessionId());
    }

    @Test
    void theEnvelopeFallsBackToArgumentsRawAndASyntheticSession() {
        String payload =
                "{\"replay\":{\"agentId\":\""
                        + AGENT_ID
                        + "\",\"toolName\":\"ping\"},\"argumentsRaw\":\"{\\\"q\\\":1}\"}";
        ReplayTarget target = ReplayTarget.resolve(request(payload, null), MAPPER);

        assertEquals("{\"q\":1}", target.argumentsJson());
        assertEquals(
                ReplayTarget.FALLBACK_SESSION_PREFIX + REQUEST_ID,
                target.sessionId(),
                "a request naming no session gets the synthetic id the executor can skip");
    }

    @Test
    void anEnvelopeWithoutAValidAgentIdCannotReplay() {
        String payload = "{\"replay\":{\"agentId\":\"not-a-uuid\",\"toolName\":\"t\"}}";
        assertThrows(AgentToolReplayException.class, () -> ReplayTarget.resolve(request(payload, null), MAPPER));

        String missing = "{\"replay\":{\"toolName\":\"t\"}}";
        assertThrows(AgentToolReplayException.class, () -> ReplayTarget.resolve(request(missing, null), MAPPER));
    }

    @Test
    void aLegacyPayloadRecoversToolAgentAndSessionFromLabels() {
        ReplayTarget target =
                ReplayTarget.resolve(
                        request(
                                "{\"fileId\":\"f-9\"}",
                                "agent:" + AGENT_ID + ":session:sess-42"),
                        MAPPER);

        assertEquals("send_file", target.toolName(), "the tool comes from the action type");
        assertEquals(AGENT_ID, target.agentId());
        assertEquals("sess-42", target.sessionId());
        assertEquals("{\"fileId\":\"f-9\"}", target.argumentsJson());
        assertNull(target.tenantId());
        assertNull(target.memorySessionId());
    }

    @Test
    void aLegacyPayloadWithAReplayKeyOfItsOwnIsNotAnEnvelope() {
        // The discriminator is replay.toolName, not the bare "replay" key.
        ReplayTarget target =
                ReplayTarget.resolve(
                        request(
                                "{\"replay\":{\"level\":2}}",
                                "agent:" + AGENT_ID + ":session:s"),
                        MAPPER);
        assertEquals("send_file", target.toolName());
        assertEquals("s", target.sessionId());
    }

    @Test
    void anUnidentifiedLegacyRequestCannotReplay() {
        AgentToolReplayException e =
                assertThrows(
                        AgentToolReplayException.class,
                        () -> ReplayTarget.resolve(request("{}", "admin@x.com"), MAPPER));
        assertTrue(e.getMessage().contains(String.valueOf(REQUEST_ID)));
    }

    @Test
    void anUnparseablePayloadCannotReplay() {
        assertThrows(
                AgentToolReplayException.class,
                () ->
                        ReplayTarget.resolve(
                                request("{not json", "agent:" + AGENT_ID + ":session:s"), MAPPER));
    }

    @Test
    void aBlankLegacyPayloadBecomesEmptyArguments() {
        ReplayTarget target =
                ReplayTarget.resolve(
                        request(null, "agent:" + AGENT_ID + ":session:s"), MAPPER);
        assertEquals("{}", target.argumentsJson());
    }

    @Test
    void memorySessionIdIsCapturedOrDerived() {
        ReplayTarget captured =
                new ReplayTarget(AGENT_ID, "s", "t", "{}", "tenant-a", "alice", null, "c1:xyz");
        assertEquals("c1:xyz", captured.memorySessionIdOrDerived());

        ReplayTarget derivable =
                new ReplayTarget(AGENT_ID, "s", "t", "{}", "tenant-a", "alice", null, null);
        assertEquals(
                SessionMemoryService.scopedSessionId("tenant-a", AGENT_ID, "alice", "s"),
                derivable.memorySessionIdOrDerived());

        ReplayTarget underivable = new ReplayTarget(AGENT_ID, "s", "t", "{}", null, null, null, null);
        assertNull(underivable.memorySessionIdOrDerived());
    }

    @Test
    void replaySubjectFallsBackToASystemLabel() {
        assertEquals(
                "alice",
                new ReplayTarget(AGENT_ID, "s", "t", "{}", null, "alice", null, null)
                        .replaySubject());
        assertEquals(
                "system:approval-replay",
                new ReplayTarget(AGENT_ID, "s", "t", "{}", null, " ", null, null).replaySubject());
    }
}
