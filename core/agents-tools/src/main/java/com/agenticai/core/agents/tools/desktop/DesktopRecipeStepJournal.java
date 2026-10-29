package com.agenticai.core.agents.tools.desktop;

import com.agenticai.core.agents.tools.ToolCallLog;
import com.agenticai.core.agents.tools.ToolCallLogRepository;
import com.agenticai.core.approval.ToolCallScope;
import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Writes one {@code tool_call_log} row per replayed recipe step so the run journal, the Issues
 * portal and the learning loops see what each step did - without it a {@code run_desktop_recipe}
 * call produces a single row and a failed run gives no hint which step failed.
 *
 * <p>Journaling needs the calling turn's {@link ToolCallScope}: the row belongs to the turn's
 * agent and session. When no scope is open (a REST-driven run, a sweeper, a bare harness) the
 * steps run unjournaled, exactly as before.
 *
 * <p>Step fields that can carry resolved {@code {{secret.*}}} values ({@code text}, {@code key},
 * {@code keys}, {@code password}, {@code value}) are never journaled; the row keeps the sidecar
 * path, the step's other fields and the reply.
 */
final class DesktopRecipeStepJournal {

    /** The {@code toolName} every step row is written under, suffixed with the sidecar path. */
    static final String STEP_TOOL_PREFIX = "desktop_recipe_step";

    private static final ObjectMapper MAPPER = JsonMappers.lenient();
    private static final int MAX_JOURNAL_CHARS = 4_000;
    private static final Set<String> SECRET_STEP_FIELDS =
            Set.of("text", "key", "keys", "password", "value");

    private DesktopRecipeStepJournal() {}

    /**
     * The step executor that journals each call. The tool scope is read per post (not at wrap
     * time): the executor can be built before {@code ToolCallScope.open} and the calls still run
     * inside it. With no journal, or no open scope on the posting thread, the call is unjournaled.
     */
    static DesktopRecipeReplayer.Executor wrap(
            DesktopRecipeReplayer.Executor raw, ToolCallLogRepository journal) {
        if (raw == null || journal == null) {
            return raw;
        }
        return (path, body) -> {
            ToolCallScope.Current scope = ToolCallScope.current().orElse(null);
            if (scope == null || scope.agentId() == null || scope.sessionId() == null) {
                return raw.post(path, body);
            }
            Instant started = Instant.now();
            String input = journalableInput(path, body);
            try {
                JsonNode reply = raw.post(path, body);
                journal.save(
                        new ToolCallLog(
                                scope.agentId(),
                                scope.sessionId(),
                                STEP_TOOL_PREFIX + path,
                                input,
                                reply == null ? null : clip(reply.toString()),
                                null,
                                started,
                                Instant.now()));
                return reply;
            } catch (IOException | RuntimeException e) {
                journal.save(
                        new ToolCallLog(
                                scope.agentId(),
                                scope.sessionId(),
                                STEP_TOOL_PREFIX + path,
                                input,
                                null,
                                e.getClass().getSimpleName() + ": " + e.getMessage(),
                                started,
                                Instant.now()));
                throw e;
            }
        };
    }

    /** The step body with any field that can carry resolved secret values removed. */
    static String journalableInput(String path, Map<String, Object> body) {
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("path", path);
        if (body != null) {
            body.forEach(
                    (k, v) -> {
                        if (!SECRET_STEP_FIELDS.contains(k)) {
                            shown.put(k, v);
                        }
                    });
        }
        try {
            return clip(MAPPER.writeValueAsString(shown));
        } catch (Exception e) {
            return "{\"path\":\"" + path + "\"}";
        }
    }

    private static String clip(String s) {
        return s != null && s.length() > MAX_JOURNAL_CHARS ? s.substring(0, MAX_JOURNAL_CHARS) : s;
    }
}
