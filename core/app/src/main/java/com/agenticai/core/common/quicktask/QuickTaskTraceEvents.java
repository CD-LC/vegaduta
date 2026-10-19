package com.agenticai.core.common.quicktask;

import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogService;
import com.agenticai.core.security.TenantContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;

/**
 * The live-trace half of {@link QuickTaskService#runStream}: serialises SSE events onto the run's
 * sink and tails {@link ToolCallLogService} for tool calls not yet shown. Holds no thread hops of
 * its own - the service schedules the polls and owns the async boundaries. Created and owned by
 * {@link QuickTaskService}; not a bean.
 */
final class QuickTaskTraceEvents {

    private static final Logger log = LoggerFactory.getLogger(QuickTaskTraceEvents.class);

    private final ToolCallLogService toolCallLogService;
    private final ObjectMapper objectMapper;

    QuickTaskTraceEvents(ToolCallLogService toolCallLogService, ObjectMapper objectMapper) {
        this.toolCallLogService = toolCallLogService;
        this.objectMapper = objectMapper;
    }

    /**
     * Re-applies the calling thread's captured {@link TenantContext} (may be {@code null} if the
     * "security" profile is off, in which case this is a no-op) before reading {@link
     * ToolCallLogService}, then emits a {@code tool_call} event for every row not already emitted.
     * Failures are logged and swallowed - a missed poll tick should never kill the whole stream,
     * the next tick just picks up whatever it missed.
     */
    void pollNewToolCalls(
            TenantContext capturedContext,
            UUID agentId,
            String sessionId,
            Set<UUID> seenToolCallIds,
            Sinks.Many<String> sink) {
        Optional<TenantContext> previous = TenantContext.current();
        if (capturedContext != null) {
            TenantContext.set(capturedContext);
        }
        try {
            for (ToolCallLog toolCall :
                    toolCallLogService.findByAgentIdAndSessionId(agentId, sessionId)) {
                if (seenToolCallIds.add(toolCall.getId())) {
                    long durationMs =
                            toolCall.getCompletedAt().toEpochMilli()
                                    - toolCall.getStartedAt().toEpochMilli();
                    emit(
                            sink,
                            event(
                                    "tool_call",
                                    "toolName",
                                    toolCall.getToolName(),
                                    "toolInput",
                                    toolCall.getToolInput(),
                                    "toolOutput",
                                    toolCall.getToolOutput(),
                                    "errorMessage",
                                    toolCall.getErrorMessage(),
                                    "durationMs",
                                    durationMs));
                }
            }
        } catch (RuntimeException e) {
            log.warn("Quick Task trace poll failed for session {}", sessionId, e);
        } finally {
            previous.ifPresentOrElse(TenantContext::set, TenantContext::clear);
        }
    }

    static Map<String, Object> event(String type, Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", type);
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    void emit(Sinks.Many<String> sink, Map<String, Object> event) {
        try {
            sink.tryEmitNext(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize Quick Task trace event", e);
        }
    }
}
