package com.agenticai.core.omnichannel.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.agenticai.core.omnichannel.session.UnknownSenderPolicy;
import org.junit.jupiter.api.Test;

/** {@link SignalCredential}: construction, reactivation, policy updates and disconnect. */
class SignalCredentialTest {

    private SignalCredential credential() {
        return new SignalCredential(
                "http://signal:8080",
                "+15550001111",
                "enc-token",
                UnknownSenderPolicy.PAIR,
                SignalGroupMode.MENTION);
    }

    @Test
    void constructorStoresEveryFieldAndStartsActive() {
        SignalCredential c = credential();
        assertNull(c.getId());
        assertEquals("http://signal:8080", c.getBaseUrl());
        assertEquals("+15550001111", c.getAccountNumber());
        assertEquals("enc-token", c.getEncryptedAuthToken());
        assertEquals(UnknownSenderPolicy.PAIR, c.getUnknownSenders());
        assertEquals(SignalGroupMode.MENTION, c.getGroupMode());
        assertEquals(SignalCredentialStatus.ACTIVE, c.getStatus());
        assertNotNull(c.getCreatedAt());
        assertNotNull(c.getUpdatedAt());
        assertNotNull(c.getLastValidatedAt());
    }

    @Test
    void reactivateReplacesAllFieldsAndRestoresActive() {
        SignalCredential c = credential();
        c.disconnect();

        c.reactivate(
                "http://other:9000",
                "+15559998888",
                "enc-2",
                UnknownSenderPolicy.IGNORE,
                SignalGroupMode.ALL);

        assertEquals("http://other:9000", c.getBaseUrl());
        assertEquals("+15559998888", c.getAccountNumber());
        assertEquals("enc-2", c.getEncryptedAuthToken());
        assertEquals(UnknownSenderPolicy.IGNORE, c.getUnknownSenders());
        assertEquals(SignalGroupMode.ALL, c.getGroupMode());
        assertEquals(SignalCredentialStatus.ACTIVE, c.getStatus());
        assertNotNull(c.getLastValidatedAt());
    }

    @Test
    void updatePolicyChangesOnlyTheNonNullFields() {
        SignalCredential c = credential();

        c.updatePolicy(null, SignalGroupMode.ALL);
        assertEquals(UnknownSenderPolicy.PAIR, c.getUnknownSenders());
        assertEquals(SignalGroupMode.ALL, c.getGroupMode());

        c.updatePolicy(UnknownSenderPolicy.IGNORE, null);
        assertEquals(UnknownSenderPolicy.IGNORE, c.getUnknownSenders());
        assertEquals(SignalGroupMode.ALL, c.getGroupMode());
    }

    @Test
    void disconnectMarksTheRowDisconnected() {
        SignalCredential c = credential();
        c.disconnect();
        assertEquals(SignalCredentialStatus.DISCONNECTED, c.getStatus());
    }
}
