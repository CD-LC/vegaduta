package com.agenticai.core.omnichannel.signal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.omnichannel.ChannelConfigurationException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** {@link SignalAddress}: number normalization, addressability, wire recipient form and masking. */
class SignalAddressTest {

    @Test
    void normalizeNumberStripsSeparatorsAndKeepsE164() {
        assertEquals("+15551234567", SignalAddress.normalizeNumber("+1 (555) 123-4567"));
        assertEquals("+4915112345678", SignalAddress.normalizeNumber(" +49 151 12345678 "));
    }

    @Test
    void normalizeNumberRejectsNonE164Input() {
        for (String bad : new String[] {null, "", "5551234567", "+0123", "abc", "+1-555"}) {
            assertThrows(
                    ChannelConfigurationException.class,
                    () -> SignalAddress.normalizeNumber(bad),
                    "input: " + bad);
        }
    }

    @Test
    void isGroupOnlyForTheGroupPrefix() {
        assertTrue(SignalAddress.isGroup("group:abc12345"));
        assertFalse(SignalAddress.isGroup("+15551234567"));
        assertFalse(SignalAddress.isGroup(null));
        assertEquals("group:abc12345", SignalAddress.groupExternalId("abc12345"));
    }

    @Test
    void isAddressableAcceptsNumbersUuidsAndWellFormedGroups() {
        assertTrue(SignalAddress.isAddressable("+15551234567"));
        assertTrue(SignalAddress.isAddressable("123e4567-e89b-12d3-a456-426614174000"));
        assertTrue(SignalAddress.isAddressable("group:abc12345"));
        // A UUID is matched case-insensitively.
        assertTrue(SignalAddress.isAddressable("123E4567-E89B-12D3-A456-426614174000"));
    }

    @Test
    void isAddressableRejectsEverythingElse() {
        assertFalse(SignalAddress.isAddressable(null));
        assertFalse(SignalAddress.isAddressable("garbage"));
        assertFalse(SignalAddress.isAddressable("+0555"));
        assertFalse(SignalAddress.isAddressable("group:xy"));
        assertFalse(SignalAddress.isAddressable("group:" + "a".repeat(57)));
    }

    @Test
    void toRecipientPassesAPersonThroughUnchanged() {
        assertEquals("+15551234567", SignalAddress.toRecipient("+15551234567"));
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        assertEquals(uuid, SignalAddress.toRecipient(uuid));
    }

    @Test
    void toRecipientEncodesAGroupIdAsSignalCliExpects() {
        String internal = "abc12345";
        String expected =
                "group."
                        + Base64.getEncoder()
                                .encodeToString(internal.getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, SignalAddress.toRecipient("group:" + internal));
    }

    @Test
    void toRecipientRejectsAnUnaddressableTarget() {
        assertThrows(ChannelConfigurationException.class, () -> SignalAddress.toRecipient("nope"));
    }

    @Test
    void validGroupInternalIdBoundsLengthAndAlphabet() {
        assertTrue(SignalAddress.validGroupInternalId("abc12345"));
        assertFalse(SignalAddress.validGroupInternalId(null));
        assertFalse(SignalAddress.validGroupInternalId("short"));
        assertFalse(SignalAddress.validGroupInternalId("has space in it"));
    }

    @Test
    void maskKeepsOnlyTheLastFourCharacters() {
        assertEquals("***", SignalAddress.mask(null));
        assertEquals("***", SignalAddress.mask("abc"));
        assertEquals("***4567", SignalAddress.mask("+15551234567"));
    }
}
