package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import com.agenticai.core.security.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

/**
 * Turns a {@link ChatClientRequest} into a redacted, capped {@link LlmRequestSnapshot}
 * and files it in {@link LlmRequestSnapshotStore}. Called from {@link LlmMetricsAdvisor}.
 * Fail-open: an inspector must never break a chat turn.
 */
final class LlmRequestSnapshotter {

    private static final Logger log = LoggerFactory.getLogger(LlmRequestSnapshotter.class);

    private static final int MAX_MESSAGE_CHARS = 40_000;
    private static final int MAX_TOTAL_CHARS = 300_000;
    private static final int MAX_SCHEMA_CHARS = 4_000;
    private static final int MAX_TOOLS = 200;

    /** Same secret shapes the workflow trace redactor strips, kept local because core/chat sits below core/agents. */
    private static final Pattern SECRET = Pattern.compile(
        "(?i)(sk-[a-z0-9_\\-]{16,}|AKIA[0-9A-Z]{16}|AIza[0-9A-Za-z_\\-]{30,}|gh[pousr]_[A-Za-z0-9]{30,}"
            + "|xox[abprs]-[A-Za-z0-9\\-]{10,}|eyJ[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}"
            + "|bearer\\s+[A-Za-z0-9._\\-]{16,})"
    );
    private static final Pattern KEY_VALUE = Pattern.compile(
        "(?i)((?:api[_-]?key|secret|password|token|authorization)\"?\\s*[:=]\\s*\"?)([^\\s\",}]{6,})"
    );

    private LlmRequestSnapshotter() {}

    /** Records the snapshot and returns its id (null when nothing was stored), so usage can be attached to exactly this call. */
    static String capture(ChatClientRequest request, ProviderName provider, String fallbackModel, boolean streaming) {
        try {
            Map<String, Object> ctx = request.context();
            String sessionId = str(ctx.get(LlmMetricsAdvisor.SESSION_ID_PARAM));
            if (sessionId == null) {
                return null;
            }
            String tenantId = TenantContext.current().map(TenantContext::getTenantId).orElse(null);
            LlmRequestSnapshot snapshot = build(request, provider, fallbackModel, streaming, sessionId);
            LlmRequestSnapshotStore.INSTANCE.add(tenantId, snapshot);
            return snapshot.id();
        } catch (RuntimeException e) {
            log.debug("LLM request snapshot skipped: {}", e.toString());
            return null;
        }
    }

    private static LlmRequestSnapshot build(
        ChatClientRequest request, ProviderName provider, String fallbackModel, boolean streaming, String sessionId
    ) {
        Map<String, Object> ctx = request.context();
        ChatOptions opts = request.prompt().getOptions();

        Map<String, Object> options = new LinkedHashMap<>();
        String model = fallbackModel;
        if (opts != null) {
            if (opts.getModel() != null) {
                model = opts.getModel();
            }
            put(options, "temperature", opts.getTemperature());
            put(options, "maxTokens", opts.getMaxTokens());
            put(options, "topP", opts.getTopP());
            put(options, "topK", opts.getTopK());
            put(options, "frequencyPenalty", opts.getFrequencyPenalty());
            put(options, "presencePenalty", opts.getPresencePenalty());
            if (opts.getStopSequences() != null && !opts.getStopSequences().isEmpty()) {
                options.put("stopSequences", opts.getStopSequences());
            }
        }

        int total = 0;
        int fullTotal = 0;
        boolean truncated = false;
        List<LlmRequestSnapshot.Message> messages = new ArrayList<>();
        for (Message m : request.prompt().getInstructions()) {
            String text = m.getText() == null ? "" : m.getText();
            int chars = text.length();
            int payload = chars + callPayloadChars(m);
            fullTotal += payload;
            String shown = redact(text);
            if (shown.length() > MAX_MESSAGE_CHARS || total + shown.length() > MAX_TOTAL_CHARS) {
                int room = Math.max(0, Math.min(MAX_MESSAGE_CHARS, MAX_TOTAL_CHARS - total));
                shown = shown.substring(0, Math.min(shown.length(), room)) + "\n… [truncated for display]";
                truncated = true;
            }
            total += shown.length();
            List<String> toolCalls = List.of();
            int media = 0;
            if (m instanceof AssistantMessage a && a.hasToolCalls()) {
                toolCalls = a.getToolCalls().stream()
                    .map(tc -> tc.name() + "(" + cap(redact(tc.arguments()), 600) + ")").toList();
            } else if (m instanceof ToolResponseMessage t) {
                toolCalls = t.getResponses().stream()
                    .map(r -> r.name() + " → " + cap(redact(r.responseData()), 600)).toList();
            } else if (m instanceof UserMessage u && u.getMedia() != null) {
                media = u.getMedia().size();
            }
            messages.add(new LlmRequestSnapshot.Message(role(m), shown, chars, toolCalls, media, payload));
        }

        List<LlmRequestSnapshot.Tool> tools = new ArrayList<>();
        int schemaTotal = 0;
        if (opts instanceof ToolCallingChatOptions tco && tco.getToolCallbacks() != null) {
            for (ToolCallback cb : tco.getToolCallbacks()) {
                if (tools.size() >= MAX_TOOLS) {
                    truncated = true;
                    break;
                }
                var def = cb.getToolDefinition();
                int full = def.inputSchema() == null ? 0 : def.inputSchema().length();
                schemaTotal += full;
                tools.add(new LlmRequestSnapshot.Tool(def.name(), def.description(), cap(def.inputSchema(), MAX_SCHEMA_CHARS), full));
            }
        }
        int[] prefix = prefixSize(request);

        Map<String, String> context = new LinkedHashMap<>();
        ctx.forEach((k, v) -> {
            if (k.startsWith("llmCallLog.") && v != null) {
                context.put(k.substring("llmCallLog.".length()), String.valueOf(v));
            }
        });

        return new LlmRequestSnapshot(
            UUID.randomUUID().toString(), Instant.now(), str(ctx.get(LlmMetricsAdvisor.AGENT_ID_PARAM)), sessionId,
            provider == null ? null : provider.name(), model, streaming, options, messages, tools, context, total, truncated,
            schemaTotal, prefix[0], prefix[1], null, fullTotal
        );
    }

    private static int callPayloadChars(Message m) {
        int n = 0;
        if (m instanceof AssistantMessage a && a.hasToolCalls()) {
            for (var tc : a.getToolCalls()) {
                n += len(tc.name()) + len(tc.arguments());
            }
        } else if (m instanceof ToolResponseMessage t) {
            for (var r : t.getResponses()) {
                n += len(r.name()) + len(r.responseData());
            }
        }
        return n;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    /**
     * Fixed per-turn prefix: system messages plus the full size of every tool definition.
     * Returns {chars, chars/4 token estimate}. Never throws.
     */
    static int[] prefixSize(ChatClientRequest request) {
        try {
            long chars = 0;
            for (Message m : request.prompt().getInstructions()) {
                if (m.getMessageType() == org.springframework.ai.chat.messages.MessageType.SYSTEM) {
                    chars += len(m.getText());
                }
            }
            if (request.prompt().getOptions() instanceof ToolCallingChatOptions tco && tco.getToolCallbacks() != null) {
                for (ToolCallback cb : tco.getToolCallbacks()) {
                    var def = cb.getToolDefinition();
                    chars += len(def.name()) + len(def.description()) + len(def.inputSchema());
                }
            }
            int c = (int) Math.min(Integer.MAX_VALUE, chars);
            return new int[] {c, c / 4};
        } catch (RuntimeException e) {
            return new int[] {0, 0};
        }
    }

    private static String role(Message m) {
        return switch (m.getMessageType()) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }

    static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String out = SECRET.matcher(text).replaceAll("[REDACTED]");
        return KEY_VALUE.matcher(out).replaceAll("$1[REDACTED]");
    }

    private static String cap(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max) + "…");
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
