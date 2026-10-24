package com.agenticai.core.agents.turn;

import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the conversation says about a short follow-up ("yes", "continue", "try again"), which carries no
 * words of its own to select tools or a model by (see {@link FollowUpProperties}).
 *
 * <ul>
 *   <li>{@link #stickyTools}: the tools this conversation already used, most recent first, read from the
 *       session's tool-call log. They are kept on offer, so the tool set stays the same through a task
 *       instead of collapsing to the few tools that happen to match the word "continue" (which also
 *       changes the request prefix a provider's prompt cache is keyed on).
 *   <li>{@link #keepsPrimaryModel}: whether cost routing must leave the turn on the agent's own model
 *       because it is the middle of a tool task.
 * </ul>
 *
 * <p>The tool-call log is read only for a message that is a follow-up, so an ordinary turn pays nothing.
 */
public final class FollowUpTools {

    private static final Logger log = LoggerFactory.getLogger(FollowUpTools.class);

    private final ToolCallLogService toolCallLog;
    private final FollowUpProperties properties;

    public FollowUpTools(ToolCallLogService toolCallLog, FollowUpProperties properties) {
        this.toolCallLog = toolCallLog;
        this.properties = properties == null ? new FollowUpProperties() : properties;
    }

    /**
     * The names of the tools {@code sessionId} used within the configured window, most recently used
     * first and at most {@code max-sticky-tools}; empty unless {@code message} is a short follow-up.
     * Never throws: a log that cannot be read leaves the turn with the tools its message selects.
     */
    public Set<String> stickyTools(UUID agentId, String sessionId, String message) {
        return stickyTools(agentId, sessionId, message, this::platformPolicy);
    }

    /**
     * Whether tools stay on offer for a follow-up, how far back the log is read, and how many tools are kept
     * ({@code maxTools}; negative = the platform's {@code app.chat.follow-up.max-sticky-tools}).
     */
    public record StickyPolicy(boolean enabled, Duration window, int maxTools) {
        /** A policy that leaves the number of tools to the platform. */
        public StickyPolicy(boolean enabled, Duration window) {
            this(enabled, window, -1);
        }
    }

    /**
     * The policy of {@code app.chat.follow-up.*}: what applies unless a tenant or an agent set their own. Its {@code
     * maxTools} is negative: the platform's number is read where the tools are listed.
     */
    public StickyPolicy platformPolicy() {
        return new StickyPolicy(properties.isStickyToolsEnabled(), properties.getStickyToolsWindow());
    }

    /** Whether cost routing leaves a follow-up in a tool conversation on the agent's own model, as the platform has it. */
    public boolean platformKeepsPrimaryModel() {
        return properties.isKeepPrimaryModel();
    }

    /**
     * As {@link #stickyTools(UUID, String, String)} under the policy {@code policy} gives (the tenant's and the agent's
     * {@code chat.followUp.stickyTools*} settings). The supplier is asked only for a message that is a short follow-up,
     * so an ordinary turn pays nothing.
     */
    public Set<String> stickyTools(
            UUID agentId, String sessionId, String message, Supplier<StickyPolicy> policy) {
        if (toolCallLog == null || agentId == null || sessionId == null || !properties.isShortFollowUp(message)) {
            return Set.of();
        }
        StickyPolicy sticky = policy.get();
        int maxTools = sticky.maxTools() >= 0 ? sticky.maxTools() : properties.getMaxStickyTools();
        if (!sticky.enabled() || maxTools <= 0) {
            return Set.of();
        }
        try {
            List<ToolCallLog> rows =
                    toolCallLog.findSince(agentId, sessionId, Instant.now().minus(sticky.window()));
            Set<String> names = new LinkedHashSet<>();
            for (int i = rows.size() - 1; i >= 0 && names.size() < maxTools; i--) {
                String name = rows.get(i).getToolName();
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
            return names;
        } catch (RuntimeException e) {
            log.warn(
                    "Could not read which tools session '{}' already used; this follow-up is offered the tools its"
                            + " message selects",
                    sessionId,
                    e);
            return Set.of();
        }
    }

    /**
     * True when {@code message} is a short follow-up to a conversation that already used tools
     * ({@code stickyTools} is what {@link #stickyTools} returned for it): the economy model is then not
     * chosen for the turn, whatever the message looks like on its own.
     */
    public boolean keepsPrimaryModel(String message, Set<String> stickyTools) {
        return keepsPrimaryModel(message, stickyTools, properties.isKeepPrimaryModel());
    }

    /** As {@link #keepsPrimaryModel(String, Set)} with {@code enabled} the tenant's or the agent's {@code chat.followUp.keepPrimaryModel}. */
    public boolean keepsPrimaryModel(String message, Set<String> stickyTools, boolean enabled) {
        return enabled
                && stickyTools != null
                && !stickyTools.isEmpty()
                && properties.isShortFollowUp(message);
    }
}
