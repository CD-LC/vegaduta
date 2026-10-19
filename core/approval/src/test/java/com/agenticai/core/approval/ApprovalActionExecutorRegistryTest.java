package com.agenticai.core.approval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The two-phase lookup contract {@link ApprovalActionExecutorRegistry#find}
 * added for the agent-tool replay executor: exact {@code actionType()} match
 * always wins, {@link ApprovalActionExecutor#supports} is only ever a
 * fallback, and the startup uniqueness check on declared action types is
 * unchanged. Small hand-rolled stubs rather than Mockito - the interface's
 * default {@code supports} implementation is itself part of what's under
 * test, and a mock would silently replace it.
 */
class ApprovalActionExecutorRegistryTest {

    private static final class FixedExecutor implements ApprovalActionExecutor {
        private final String actionType;

        FixedExecutor(String actionType) {
            this.actionType = actionType;
        }

        @Override
        public String actionType() {
            return actionType;
        }

        @Override
        public void execute(ApprovalRequest request) {
        }
    }

    private static final class PrefixExecutor implements ApprovalActionExecutor {
        @Override
        public String actionType() {
            return "mcp_tool.";
        }

        @Override
        public boolean supports(String actionType) {
            return actionType != null && actionType.startsWith("mcp_tool.") && actionType.length() > "mcp_tool.".length();
        }

        @Override
        public void execute(ApprovalRequest request) {
        }
    }

    @Test
    void exactActionTypeMatchStillResolves() {
        FixedExecutor channelSend = new FixedExecutor("channel.whatsapp.send");
        ApprovalActionExecutorRegistry registry = new ApprovalActionExecutorRegistry(List.of(channelSend));

        assertEquals(Optional.of(channelSend), registry.find("channel.whatsapp.send"));
        assertTrue(registry.find("channel.telegram.send").isEmpty());
    }

    @Test
    void defaultSupportsIsExactEqualityOnly() {
        // A fixed executor must never accidentally claim a family - the
        // default supports() is equality with actionType(), nothing looser.
        ApprovalActionExecutorRegistry registry =
            new ApprovalActionExecutorRegistry(List.of(new FixedExecutor("mcp_tool.")));

        assertTrue(registry.find("mcp_tool.delete_file").isEmpty());
    }

    @Test
    void supportsFallbackResolvesDynamicFamilyActionTypes() {
        PrefixExecutor family = new PrefixExecutor();
        ApprovalActionExecutorRegistry registry = new ApprovalActionExecutorRegistry(List.of(family));

        assertEquals(Optional.of(family), registry.find("mcp_tool.delete_file"));
        // The bare prefix is the registry key, not a real recorded action
        // type - but an exact-key lookup for it still resolves via phase 1.
        assertEquals(Optional.of(family), registry.find("mcp_tool."));
        assertTrue(registry.find("channel.whatsapp.send").isEmpty());
    }

    @Test
    void exactMatchAlwaysBeatsAFamilyMatch() {
        // If a dedicated executor for one specific tool is ever registered,
        // it takes that tool over from the family executor - see find()'s
        // javadoc.
        PrefixExecutor family = new PrefixExecutor();
        FixedExecutor dedicated = new FixedExecutor("mcp_tool.delete_file");
        ApprovalActionExecutorRegistry registry = new ApprovalActionExecutorRegistry(List.of(family, dedicated));

        assertEquals(Optional.of(dedicated), registry.find("mcp_tool.delete_file"));
        assertEquals(Optional.of(family), registry.find("mcp_tool.other_tool"));
    }

    @Test
    void duplicateDeclaredActionTypesRemainAStartupError() {
        assertThrows(IllegalStateException.class, () -> new ApprovalActionExecutorRegistry(
            List.of(new FixedExecutor("channel.whatsapp.send"), new FixedExecutor("channel.whatsapp.send"))));
    }
}
