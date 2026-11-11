package com.agenticai.core.eventbus.webhook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.agenticai.core.eventbus.WorkflowEventTypes;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link WebhookEventCatalog} is the documented subset of event types the API advertises for
 * subscription pickers. Two invariants keep it honest: no two rows share an event type (a duplicate
 * would render twice in the picker) and every row carries a non-empty description.
 */
class WebhookEventCatalogTest {

    @Test
    void everyEntryHasADistinctEventTypeAndADescription() {
        Set<String> seen = new HashSet<>();
        for (WebhookEventCatalog.Entry entry : WebhookEventCatalog.ENTRIES) {
            assertTrue(seen.add(entry.eventType()), "duplicate event type: " + entry.eventType());
            assertFalse(
                    entry.description() == null || entry.description().isBlank(),
                    "blank description for " + entry.eventType());
        }
        assertEquals(WebhookEventCatalog.ENTRIES.size(), seen.size());
    }

    @Test
    void theChannelAndAgentConstantsAppearInTheCatalog() {
        Set<String> types = new HashSet<>();
        for (WebhookEventCatalog.Entry entry : WebhookEventCatalog.ENTRIES) {
            types.add(entry.eventType());
        }

        assertTrue(types.contains(WorkflowEventTypes.CHANNEL_WHATSAPP_MESSAGE_RECEIVED));
        assertTrue(types.contains(WorkflowEventTypes.CHANNEL_TEAMS_MESSAGE_RECEIVED));
        assertTrue(types.contains(WorkflowEventTypes.AGENT_MESSAGE_COMPLETED));
        // The synthetic type the Test button enqueues must be subscribable/discoverable too.
        assertTrue(types.contains("webhook.test"));
    }

    @Test
    void anEntryExposesItsRecordAccessors() {
        WebhookEventCatalog.Entry entry = WebhookEventCatalog.ENTRIES.get(0);

        assertEquals(entry.eventType(), WorkflowEventTypes.CHANNEL_WHATSAPP_MESSAGE_RECEIVED);
        assertFalse(entry.description().isEmpty());
    }
}
