package com.agenticai.core.agents.share;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SEC-005: share visitors are server-issued, HMAC-signed and never derived from the
 * client IP; forged, unsigned or foreign-share tokens are ignored (a new visitor is
 * issued), and the long-term-memory subject is per visitor, not per share.
 */
class ShareVisitorSessionsTest {

    private static final String SHARE = "AbCdEfGhIjKlMnOpQrStUvWx";
    private final ShareVisitorSessions sessions = new ShareVisitorSessions("", "test-encryption-key-material");

    @Test
    void twoVisitorsWithoutTokensGetDistinctSessionsAndSubjects() {
        // Before the fix, two visitors behind one IP (same NAT/office egress) got the SAME
        // "share:<id>:<ipHash>" conversation. Nothing IP-derived exists any more: each
        // tokenless request is a new random visitor.
        ShareVisitorSessions.Visitor a = sessions.resolveOrIssue(SHARE, null);
        ShareVisitorSessions.Visitor b = sessions.resolveOrIssue(SHARE, null);

        assertThat(a.issued()).isTrue();
        assertThat(b.issued()).isTrue();
        assertThat(a.visitorId()).isNotEqualTo(b.visitorId());
        assertThat(ShareVisitorSessions.chatSessionId(SHARE, a.visitorId()))
            .isNotEqualTo(ShareVisitorSessions.chatSessionId(SHARE, b.visitorId()));
        UUID shareRowId = UUID.randomUUID();
        assertThat(ShareVisitorSessions.memorySubject(shareRowId, a.visitorId()))
            .isNotEqualTo(ShareVisitorSessions.memorySubject(shareRowId, b.visitorId()))
            .isNotEqualTo("agent-share:" + shareRowId);
    }

    @Test
    void aValidTokenResumesTheSameVisitor() {
        ShareVisitorSessions.Visitor first = sessions.issue(SHARE);
        ShareVisitorSessions.Visitor again = sessions.resolveOrIssue(SHARE, first.token());
        assertThat(again.issued()).isFalse();
        assertThat(again.visitorId()).isEqualTo(first.visitorId());
        assertThat(again.token()).isEqualTo(first.token());
    }

    @Test
    void forgedUnsignedAndLegacyIpShapedKeysAreIgnoredAndANewVisitorIssued() {
        ShareVisitorSessions.Visitor victim = sessions.issue(SHARE);
        String victimId = victim.visitorId();
        String[] forgeries = {
            "share:" + SHARE + ":0123456789ab",                  // the old ipHash-shaped key
            "sv1." + victimId + ".AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", // right id, bad MAC
            "sv1." + victimId,                                   // no MAC
            "sv2." + victimId + "." + victim.token().substring(victim.token().lastIndexOf('.') + 1),
            victim.token() + "x",
            "",
            "garbage",
        };
        for (String forged : forgeries) {
            ShareVisitorSessions.Visitor v = sessions.resolveOrIssue(SHARE, forged);
            assertThat(v.issued()).as("forged token %s must not be honoured", forged).isTrue();
            assertThat(v.visitorId()).isNotEqualTo(victimId);
        }
    }

    @Test
    void aTokenMintedForAnotherShareIsRejected() {
        ShareVisitorSessions.Visitor elsewhere = sessions.issue("ZZZZZZZZZZZZZZZZZZZZZZZZ");
        assertThat(sessions.verify(SHARE, elsewhere.token())).isEmpty();
        assertThat(sessions.resolveOrIssue(SHARE, elsewhere.token()).visitorId()).isNotEqualTo(elsewhere.visitorId());
    }

    @Test
    void aTokenSignedWithADifferentServerKeyIsRejected() {
        ShareVisitorSessions otherDeployment = new ShareVisitorSessions("", "some-other-key");
        ShareVisitorSessions.Visitor foreign = otherDeployment.issue(SHARE);
        assertThat(sessions.verify(SHARE, foreign.token())).isEmpty();
    }

    @Test
    void keyDerivationIsStableAcrossInstancesSoTokensSurviveRestartsAndReplicas() {
        ShareVisitorSessions replica = new ShareVisitorSessions("", "test-encryption-key-material");
        ShareVisitorSessions.Visitor v = sessions.issue(SHARE);
        assertThat(replica.verify(SHARE, v.token())).contains(v.visitorId());
        // A dedicated secret takes precedence over the encryption key.
        ShareVisitorSessions dedicated = new ShareVisitorSessions("dedicated", "test-encryption-key-material");
        assertThat(dedicated.verify(SHARE, v.token())).isEmpty();
    }

    @Test
    void visitorIdsCarryAtLeast128BitsOfRandomness() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String id = sessions.issue(SHARE).visitorId();
            assertThat(id).hasSize(22); // 16 bytes, base64url without padding
            assertThat(seen.add(id)).isTrue();
        }
    }
}
