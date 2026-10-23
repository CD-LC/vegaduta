package com.agenticai.core.agents.job.sentinel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.agenticai.core.agents.core.AgentNotFoundException;
import com.agenticai.core.agents.core.AgentService;
import com.agenticai.core.approval.ApprovalNotAuthorizedException;
import com.agenticai.core.approval.ApprovalRequest;
import com.agenticai.core.json.JsonMappers;
import com.agenticai.core.sharing.PermissionLevel;
import com.agenticai.core.sharing.ResourceAccessDeniedException;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@code agent.proposal} approvals were decidable by any member except the requester. Deciding one
 * now takes RUNNER on the proposal's agent (approval admins skip the authorizer, see {@code
 * ApprovalController}).
 */
class SentinelProposalDecisionAuthorizerTest {

    private final AgentService agents = mock(AgentService.class);
    private final SentinelProposalDecisionAuthorizer authorizer =
            new SentinelProposalDecisionAuthorizer(agents, JsonMappers.plain());
    private final UUID agentId = UUID.randomUUID();

    private ApprovalRequest proposal(String payloadJson) {
        return new ApprovalRequest(
                SentinelProposalExecutor.ACTION_TYPE,
                "Sentinel proposes a run",
                payloadJson,
                "agent:" + agentId + ":sentinel:s1");
    }

    @Test
    void supportsOnlyAgentProposals() {
        assertTrue(authorizer.supports(proposal("{}")));
        assertFalse(authorizer.supports(new ApprovalRequest("channel.slack.send", "s", "{}", "u")));
        assertFalse(authorizer.supports(new ApprovalRequest(null, "s", "{}", "u")));
    }

    @Test
    void aMemberWithRunAccessToTheAgentMayDecide() {
        ApprovalRequest request = proposal("{\"agentId\":\"" + agentId + "\",\"goal\":\"g\"}");

        assertDoesNotThrow(() -> authorizer.authorize(request));

        verify(agents).requireAccess(agentId, PermissionLevel.RUNNER);
    }

    @Test
    void aMemberWithoutAccessToTheAgentMayNot() {
        doThrow(new ResourceAccessDeniedException()).when(agents).requireAccess(any(), any());

        assertThrows(
                ApprovalNotAuthorizedException.class,
                () ->
                        authorizer.authorize(
                                proposal("{\"agentId\":\"" + agentId + "\",\"goal\":\"g\"}")));
    }

    @Test
    void aProposalForADeletedAgentIsRefused() {
        doThrow(new AgentNotFoundException(agentId)).when(agents).requireAccess(any(), any());

        assertThrows(
                ApprovalNotAuthorizedException.class,
                () ->
                        authorizer.authorize(
                                proposal("{\"agentId\":\"" + agentId + "\",\"goal\":\"g\"}")));
    }

    @Test
    void anUnreadablePayloadIsRefusedWithoutAnyAccessCheck() {
        for (String payload : new String[] {null, "", "not json", "{}", "{\"agentId\":\"nope\"}"}) {
            assertThrows(
                    ApprovalNotAuthorizedException.class,
                    () -> authorizer.authorize(proposal(payload)),
                    String.valueOf(payload));
        }
        verifyNoInteractions(agents);
    }
}
