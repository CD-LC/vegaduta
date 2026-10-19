package com.agenticai.core.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The signed resume URL token: round trip, tamper resistance, key separation and the unconfigured case. */
class WorkflowWaitResumeTokensTest {

    private static final String BASE = "https://api.example.com";

    @Test
    void aTokenRoundTripsTenantRunAndNode() {
        WorkflowWaitResumeTokens tokens = new WorkflowWaitResumeTokens("platform-key", BASE);
        UUID runId = UUID.randomUUID();

        String token = tokens.createToken("tenant_a", runId, "wait-1");
        Optional<WorkflowWaitResumeTokens.Claims> claims = tokens.verify(token);

        assertTrue(claims.isPresent());
        assertEquals("tenant_a", claims.get().tenantId());
        assertEquals(runId, claims.get().runId());
        assertEquals("wait-1", claims.get().nodeId());
    }

    @Test
    void theUrlIsAbsoluteUnderTheWebhookPrefixAndVerifies() {
        WorkflowWaitResumeTokens tokens = new WorkflowWaitResumeTokens("platform-key", BASE + "/");
        UUID runId = UUID.randomUUID();

        String url = tokens.resumeUrl("tenant_a", runId, "wait-1");

        assertTrue(url.startsWith(BASE + "/webhook/workflow-waits/"), url);
        String token = url.substring(url.lastIndexOf('/') + 1);
        assertEquals(runId, tokens.verify(token).orElseThrow().runId());
    }

    @Test
    void aRunScopedUrlCarriesTheWildcardNodeAndVerifies() {
        WorkflowWaitResumeTokens tokens = new WorkflowWaitResumeTokens("platform-key", BASE);
        UUID runId = UUID.randomUUID();

        String url = tokens.runResumeUrl("tenant_a", runId);

        assertTrue(url.startsWith(BASE + "/webhook/workflow-waits/"), url);
        WorkflowWaitResumeTokens.Claims claims = tokens.verify(url.substring(url.lastIndexOf('/') + 1)).orElseThrow();
        assertEquals(runId, claims.runId());
        assertEquals(WorkflowWaitResumeTokens.ANY_NODE, claims.nodeId());
    }

    @Test
    void anyTamperingOrAnotherKeyIsRejected() {
        WorkflowWaitResumeTokens tokens = new WorkflowWaitResumeTokens("platform-key", BASE);
        String token = tokens.createToken("tenant_a", UUID.randomUUID(), "wait-1");

        String flippedPayload = "A" + token.substring(1);
        assertFalse(tokens.verify(flippedPayload).isPresent());
        assertFalse(tokens.verify(token.substring(0, token.length() - 2) + "xx").isPresent());
        assertFalse(tokens.verify(token + "x").isPresent());
        assertFalse(tokens.verify("garbage").isPresent());
        assertFalse(tokens.verify("a.b").isPresent());
        assertFalse(tokens.verify("").isPresent());
        assertFalse(tokens.verify(null).isPresent());

        WorkflowWaitResumeTokens otherKey = new WorkflowWaitResumeTokens("a-different-key", BASE);
        assertFalse(otherKey.verify(token).isPresent(), "a token minted under one key must not verify under another");
    }

    @Test
    void swappingTheRunInASignedPayloadBreaksTheSignature() {
        WorkflowWaitResumeTokens tokens = new WorkflowWaitResumeTokens("platform-key", BASE);
        String mine = tokens.createToken("tenant_a", UUID.randomUUID(), "wait-1");
        String other = tokens.createToken("tenant_a", UUID.randomUUID(), "wait-1");

        String forged = other.substring(0, other.indexOf('.')) + mine.substring(mine.indexOf('.'));

        assertFalse(tokens.verify(forged).isPresent());
    }

    @Test
    void withoutAKeyOrABaseUrlNothingIsMintedOrAccepted() {
        WorkflowWaitResumeTokens noKey = new WorkflowWaitResumeTokens("", BASE);
        assertFalse(noKey.isConfigured());
        assertNull(noKey.resumeUrl("t", UUID.randomUUID(), "n"));
        assertFalse(noKey.verify("anything.sig").isPresent());

        WorkflowWaitResumeTokens noBase = new WorkflowWaitResumeTokens("platform-key", "");
        assertFalse(noBase.isConfigured());
        assertNull(noBase.resumeUrl("t", UUID.randomUUID(), "n"));
    }
}
