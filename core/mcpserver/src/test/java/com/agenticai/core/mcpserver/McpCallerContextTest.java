package com.agenticai.core.mcpserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link McpCallerContext} is the per-request capability boundary every
 * tool callback in this module consults - {@code isAgentExposed}/{@code
 * isWorkflowExposed} returning a wrong {@code true} IS the cross-grant
 * authorization bug, so the deny-by-default membership semantics (empty set
 * means nothing, null id means no, another grant's ids mean no) get pinned
 * down here independently of any one tool's parsing logic.
 */
class McpCallerContextTest {

    @AfterEach
    void tearDown() {
        McpCallerContext.clear();
    }

    @Test
    void currentIsEmptyUntilSet() {
        assertTrue(McpCallerContext.current().isEmpty());
    }

    @Test
    void setExposesExactlyWhatWasGranted() {
        UUID grantId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        UUID workflowId = UUID.randomUUID();
        UUID collectionId = UUID.randomUUID();
        McpCallerContext.set(grantId, Set.of(agentId), Set.of(workflowId), Set.of(collectionId));

        McpCallerContext context = McpCallerContext.current().orElseThrow();
        assertEquals(grantId, context.grantId());
        assertTrue(context.isAgentExposed(agentId));
        assertTrue(context.isWorkflowExposed(workflowId));
        assertEquals(Set.of(collectionId), context.exposedKnowledgeCollectionIds());
    }

    @Test
    void idsOutsideTheGrantAreNeverExposed() {
        // The scoping contract in one line: holding a valid grant for agent
        // A/workflow A must authorize nothing else - including another
        // tenant's ids, which are just "ids not in this grant's set" from
        // this class's point of view.
        McpCallerContext.set(UUID.randomUUID(), Set.of(UUID.randomUUID()), Set.of(UUID.randomUUID()), Set.of());

        McpCallerContext context = McpCallerContext.current().orElseThrow();
        assertFalse(context.isAgentExposed(UUID.randomUUID()));
        assertFalse(context.isWorkflowExposed(UUID.randomUUID()));
    }

    @Test
    void emptySetsExposeNothing() {
        // Deny-by-default: an admin who granted no agents granted NO agents -
        // empty must never be interpreted as "everything" anywhere downstream.
        McpCallerContext.set(UUID.randomUUID(), Set.of(), Set.of(), Set.of());

        McpCallerContext context = McpCallerContext.current().orElseThrow();
        assertFalse(context.isAgentExposed(UUID.randomUUID()));
        assertFalse(context.isWorkflowExposed(UUID.randomUUID()));
        assertTrue(context.exposedKnowledgeCollectionIds().isEmpty());
    }

    @Test
    void nullIdsAreRejectedNotNpe() {
        McpCallerContext.set(UUID.randomUUID(), Set.of(UUID.randomUUID()), Set.of(), Set.of());

        McpCallerContext context = McpCallerContext.current().orElseThrow();
        assertFalse(context.isAgentExposed(null));
        assertFalse(context.isWorkflowExposed(null));
    }

    @Test
    void clearRemovesTheContext() {
        McpCallerContext.set(UUID.randomUUID(), Set.of(), Set.of(), Set.of());
        McpCallerContext.clear();
        assertTrue(McpCallerContext.current().isEmpty());
    }

    @Test
    void aLaterSetFullyReplacesTheEarlierGrant() {
        // The filter re-uses the same thread across requests (servlet thread
        // pool) - a stale earlier grant bleeding through would be a
        // cross-caller privilege leak, which is why the filter clears in a
        // finally block; this asserts set() itself never merges.
        UUID firstAgent = UUID.randomUUID();
        McpCallerContext.set(UUID.randomUUID(), Set.of(firstAgent), Set.of(), Set.of());
        UUID secondGrant = UUID.randomUUID();
        McpCallerContext.set(secondGrant, Set.of(), Set.of(), Set.of());

        McpCallerContext context = McpCallerContext.current().orElseThrow();
        assertEquals(secondGrant, context.grantId());
        assertFalse(context.isAgentExposed(firstAgent));
    }
}
