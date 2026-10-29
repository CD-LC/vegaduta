package com.agenticai.core.omnichannel.contact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.agenticai.core.omnichannel.session.ChannelType;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link ChannelContact} (and {@link ContactGroup}): the known-sender directory entities. */
class ChannelContactTest {

    private static final UUID GROUP_ID = UUID.randomUUID();
    private static final UUID AGENT_ID = UUID.randomUUID();

    @Test
    void constructorDerivesTheDigitProjectionFromTheIdentifier() {
        ChannelContact c =
                new ChannelContact(
                        ChannelType.WHATSAPP,
                        "+1 (555) 123-4567",
                        "Asha",
                        GROUP_ID,
                        AGENT_ID,
                        "CSV");

        assertNull(c.getId());
        assertEquals(ChannelType.WHATSAPP, c.getChannel());
        assertEquals("+1 (555) 123-4567", c.getIdentifier());
        assertEquals("15551234567", c.getIdentifierDigits());
        assertEquals("Asha", c.getDisplayName());
        assertEquals(GROUP_ID, c.getGroupId());
        assertEquals(AGENT_ID, c.getAgentId());
        assertEquals("CSV", c.getSource());
        assertNull(c.getSessionId());
        assertNull(c.getLastSeenAt());
        assertNotNull(c.getCreatedAt());
    }

    @Test
    void digitsOfKeepsOnlyDigitsAndMapsDigitlessInputToNull() {
        assertNull(ChannelContact.digitsOf(null));
        assertNull(ChannelContact.digitsOf("someone@example.com"));
        assertNull(ChannelContact.digitsOf(""));
        assertEquals("15551234567", ChannelContact.digitsOf("+1-555-123-4567"));
        assertEquals("12345", ChannelContact.digitsOf("12345@lid"));
    }

    @Test
    void updateReplacesEditableFields() {
        ChannelContact c =
                new ChannelContact(ChannelType.TELEGRAM, "42", "Old", null, null, "ADMIN");
        UUID newGroup = UUID.randomUUID();
        UUID newAgent = UUID.randomUUID();

        c.update("New Name", newGroup, newAgent);

        assertEquals("New Name", c.getDisplayName());
        assertEquals(newGroup, c.getGroupId());
        assertEquals(newAgent, c.getAgentId());
    }

    @Test
    void linkSessionStampsTheSessionAndTheSightingInstant() {
        ChannelContact c =
                new ChannelContact(ChannelType.TELEGRAM, "42", "Asha", null, null, "ADMIN");
        UUID sessionId = UUID.randomUUID();

        c.linkSession(sessionId);

        assertEquals(sessionId, c.getSessionId());
        assertNotNull(c.getLastSeenAt());
    }

    @Test
    void contactGroupStoresAndUpdatesItsFields() {
        ContactGroup group = new ContactGroup("VIP", "Important people", AGENT_ID);
        assertNull(group.getId());
        assertEquals("VIP", group.getName());
        assertEquals("Important people", group.getDescription());
        assertEquals(AGENT_ID, group.getAgentId());
        assertNotNull(group.getCreatedAt());

        UUID newAgent = UUID.randomUUID();
        group.update("Renamed", "New desc", newAgent);
        assertEquals("Renamed", group.getName());
        assertEquals("New desc", group.getDescription());
        assertEquals(newAgent, group.getAgentId());
    }
}
