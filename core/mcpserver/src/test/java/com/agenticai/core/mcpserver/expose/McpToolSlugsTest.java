package com.agenticai.core.mcpserver.expose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Tool names must be legal for strict MCP clients, stable once allocated, and never collide. */
class McpToolSlugsTest {

    private static final Pattern LEGAL = Pattern.compile("[a-z0-9_]{1,64}");

    @Test
    void slugifyFoldsAccentsAndCollapsesSeparators() {
        assertEquals("support_bot_v2", McpToolSlugs.slugify("  Support Bot -- v2!  "));
        assertEquals("cafe_agent", McpToolSlugs.slugify("Café Agent"));
    }

    @Test
    void slugifyOfNothingUsableIsUnnamed() {
        assertEquals("unnamed", McpToolSlugs.slugify(null));
        assertEquals("unnamed", McpToolSlugs.slugify("   "));
        assertEquals("unnamed", McpToolSlugs.slugify("!!!"));
        assertEquals("unnamed", McpToolSlugs.slugify("你好"));
    }

    @Test
    void slugifyIsCappedAndNeverEndsWithAnUnderscore() {
        String slug = McpToolSlugs.slugify("a".repeat(39) + " tail that is cut off");
        assertTrue(slug.length() <= McpToolSlugs.MAX_SLUG_LENGTH);
        assertFalse(slug.endsWith("_"));
    }

    @Test
    void allocateUsesThePrefixOfTheKind() {
        assertEquals("agent_helper", McpToolSlugs.allocate(McpExposureKind.AGENT, "Helper", UUID.randomUUID(), Set.of()));
        assertEquals("workflow_helper", McpToolSlugs.allocate(McpExposureKind.WORKFLOW, "Helper", UUID.randomUUID(), Set.of()));
    }

    @Test
    void sameNameTwiceGetsADistinctStableSuffix() {
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < 5; i++) {
            taken.add(McpToolSlugs.allocate(McpExposureKind.AGENT, "Helper", UUID.randomUUID(), taken));
        }
        assertEquals(Set.of("agent_helper", "agent_helper_2", "agent_helper_3", "agent_helper_4", "agent_helper_5"), taken);
    }

    @Test
    void anAgentAndAWorkflowWithTheSameNameDoNotCollide() {
        Set<String> taken = new HashSet<>();
        taken.add(McpToolSlugs.allocate(McpExposureKind.AGENT, "Report", UUID.randomUUID(), taken));
        taken.add(McpToolSlugs.allocate(McpExposureKind.WORKFLOW, "Report", UUID.randomUUID(), taken));
        assertEquals(Set.of("agent_report", "workflow_report"), taken);
    }

    @Test
    void allocationIsDeterministicGivenTheSameTakenSet() {
        UUID id = UUID.randomUUID();
        Set<String> taken = Set.of("agent_x", "agent_x_2");
        assertEquals("agent_x_3", McpToolSlugs.allocate(McpExposureKind.AGENT, "x", id, taken));
        assertEquals("agent_x_3", McpToolSlugs.allocate(McpExposureKind.AGENT, "x", id, taken));
    }

    @Test
    void exhaustedNumericSuffixesFallBackToTheResourceIdAndStayLegalAndShort() {
        Set<String> taken = new HashSet<>();
        taken.add("workflow_" + "a".repeat(40));
        for (int i = 2; i <= 999; i++) {
            taken.add("workflow_" + "a".repeat(40) + "_" + i);
        }
        UUID id = UUID.fromString("12345678-1234-1234-1234-123456789abc");
        String name = McpToolSlugs.allocate(McpExposureKind.WORKFLOW, "a".repeat(80), id, taken);
        assertTrue(name.endsWith("_12345678"), name);
        assertTrue(LEGAL.matcher(name).matches(), name);
        assertTrue(name.length() <= McpToolSlugs.MAX_TOOL_NAME_LENGTH, name);
        assertFalse(taken.contains(name));
    }

    @Test
    void everyAllocatedNameIsLegalForStrictClients() {
        for (String raw : new String[] {"", "Weird / Name (final) #1", "x".repeat(200), "Über-Agent"}) {
            for (McpExposureKind kind : McpExposureKind.values()) {
                String name = McpToolSlugs.allocate(kind, raw, UUID.randomUUID(), Set.of());
                assertTrue(LEGAL.matcher(name).matches(), name);
                assertTrue(name.length() <= McpToolSlugs.MAX_TOOL_NAME_LENGTH, name);
                assertTrue(McpToolSlugs.isDynamicToolName(name));
            }
        }
    }

    @Test
    void staticToolNamesAreNeverTreatedAsDynamic() {
        for (String staticName : new String[] {
            "list_my_agents", "call_agent", "list_my_workflows", "call_workflow", "search_knowledge",
            "start_agent_task", "get_task", "cancel_task"}) {
            assertFalse(McpToolSlugs.isDynamicToolName(staticName), staticName);
        }
        assertFalse(McpToolSlugs.isDynamicToolName(null));
    }
}
