package com.agenticai.core.chat;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;

/**
 * The {@link ToolExecutionExceptionProcessor} every chat model's tool loop uses (wired in {@link
 * ResilientToolCallingManagerFactory}).
 *
 * <p><b>Why not Spring AI's default.</b> {@code DefaultToolExecutionExceptionProcessor} hands the
 * model {@code e.getMessage()} verbatim. A tool's exception message is the single most likely place
 * for a credential to leak into a prompt: an HTTP client error quoting the URL it called ({@code
 * ?api_key=...}), a connection failure echoing the {@code Authorization: Bearer ...} header, a JDBC
 * error with the DSN in it. Whatever the processor returns is sent to the LLM provider as a tool
 * result on the very next request, and it is also persisted into the conversation history. This
 * processor redacts credential-shaped substrings, caps the length, and wraps the result as a JSON
 * object (Gemini's Spring AI integration parses every tool result as a JSON object on replay and
 * kills the turn on anything else - see {@link UnavailableToolCallback#call}).
 *
 * <p><b>Which exceptions still propagate.</b> Two families are deliberate stop conditions rather
 * than tool failures and must keep ending the turn the way their callers expect: the platform's
 * per-turn step limit and guardrail violations ({@link #PROPAGATED_EXCEPTION_TYPES}; matched by
 * class name because {@code core/chat} cannot depend on {@code core/guardrails} - that edge would
 * cycle). An {@link Error} (OOM, StackOverflow) is a system fault, never a tool result, and is
 * rethrown too.
 *
 * <p><b>Note on what reaches this class at all.</b> Spring AI 1.1.7's {@code
 * DefaultToolCallingManager#executeToolCall} catches ONLY {@link ToolExecutionException} around
 * {@code ToolCallback#call}; any other {@code RuntimeException} escapes the manager and kills the
 * turn. {@link ArgumentSanitizingToolCallingManager} closes that gap by wrapping every callback so
 * a plain {@code RuntimeException} is re-thrown as a {@code ToolExecutionException} (unless it is
 * one of the propagated types), which is how it ends up here instead of aborting the stream.
 */
public class RedactingToolExecutionExceptionProcessor implements ToolExecutionExceptionProcessor {

    private static final Logger log =
            LoggerFactory.getLogger(RedactingToolExecutionExceptionProcessor.class);

    /** Longest error detail handed back to the model - enough to act on, not a stack dump. */
    static final int MAX_DETAIL_CHARS = 500;

    /**
     * Fully-qualified names of exceptions that are deliberate turn-level stop conditions, not tool
     * failures. Checked against the thrown type and every supertype, so subclasses propagate too.
     */
    static final Set<String> PROPAGATED_EXCEPTION_TYPES =
            Set.of(
                    "com.agenticai.core.guardrails.ToolStepLimitExceededException",
                    "com.agenticai.core.guardrails.GuardrailViolationException");

    // Beyond ProviderErrorTranslator's query-param/Bearer patterns: the other
    // credential shapes an exception message realistically carries.
    private static final Pattern AUTH_HEADER_PATTERN =
            Pattern.compile(
                    "(?i)((?:authorization|x-api-key|api-key|x-goog-api-key)\\s*[:=]\\s*)(?:basic\\s+|bearer\\s+)?[^\\s,;\"']{6,}");
    // Possessive quantifiers: `:`/`@` are excluded from each run, so a match can never need to
    // backtrack across the run boundary - keeps the scan linear even on hostile input (CodeQL
    // polynomial-redos), and the caller caps at MAX_DETAIL_CHARS before these patterns run.
    private static final Pattern URL_USERINFO_PATTERN =
            Pattern.compile("(?i)([a-z][a-z0-9+.-]*+://)[^/\\s:@]++:[^/\\s@]++@");
    private static final Pattern KEY_LIKE_TOKEN_PATTERN =
            Pattern.compile(
                    "\\b(?:sk-[A-Za-z0-9_-]{16,}|sk_(?:live|test)_[A-Za-z0-9]{16,}|AIza[0-9A-Za-z_-]{30,}"
                            + "|gh[pousr]_[A-Za-z0-9]{30,}|xox[abpr]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16})\\b");
    private static final Pattern JWT_PATTERN =
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b");
    private static final Pattern JSON_SECRET_FIELD_PATTERN =
            Pattern.compile(
                    "(?i)(\"(?:api[_-]?key|access[_-]?token|refresh[_-]?token|secret|password|client[_-]?secret)\"\\s*:\\s*\")[^\"]+");

    private static final ObjectMapper JSON = JsonMappers.plain();

    @Override
    public String process(ToolExecutionException exception) {
        Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
        if (cause instanceof Error error) {
            throw error;
        }
        if (isPropagated(cause)) {
            throw (RuntimeException) cause;
        }
        String toolName =
                exception.getToolDefinition() != null
                        ? exception.getToolDefinition().name()
                        : "unknown";
        String detail = redact(describe(cause));
        log.warn("Tool '{}' failed; returning a redacted error to the model: {}", toolName, detail);
        return toModelResult(toolName, detail);
    }

    /**
     * True when {@code error} (or any of its supertypes) is a deliberate turn-level stop condition.
     */
    public static boolean isPropagated(Throwable error) {
        if (!(error instanceof RuntimeException)) {
            return false;
        }
        for (Class<?> type = error.getClass();
                type != null && type != Object.class;
                type = type.getSuperclass()) {
            if (PROPAGATED_EXCEPTION_TYPES.contains(type.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Credential-shaped substrings replaced with {@code ***}, then capped at {@link
     * #MAX_DETAIL_CHARS}. Public so the agents-side wrapper that turns a failed tool call into a
     * friendly result ({@code TenantContextPropagatingToolCallback}) applies exactly the same
     * scrub.
     */
    public static String redact(String text) {
        if (text == null) {
            return null;
        }
        // Bound the input before the regex pass: exception text is model-visible user content and
        // can be arbitrarily long; the patterns below are linear/possessive but a bounded scan is
        // also just cheaper. Anything past the cap is dropped anyway - secrets can't live there.
        String capped =
                text.length() > MAX_DETAIL_CHARS * 8
                        ? text.substring(0, MAX_DETAIL_CHARS * 8)
                        : text;
        String redacted = ProviderErrorTranslator.redactSecrets(capped);
        redacted = AUTH_HEADER_PATTERN.matcher(redacted).replaceAll("$1***");
        redacted = URL_USERINFO_PATTERN.matcher(redacted).replaceAll("$1***@");
        redacted = KEY_LIKE_TOKEN_PATTERN.matcher(redacted).replaceAll("***");
        redacted = JWT_PATTERN.matcher(redacted).replaceAll("***");
        redacted = JSON_SECRET_FIELD_PATTERN.matcher(redacted).replaceAll("$1***");
        if (redacted.length() > MAX_DETAIL_CHARS) {
            redacted = redacted.substring(0, MAX_DETAIL_CHARS) + "...";
        }
        return redacted;
    }

    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static String toModelResult(String toolName, String detail) {
        String instruction =
                "The tool '"
                        + toolName
                        + "' failed: "
                        + detail
                        + ". Explain this to the user in plain "
                        + "language and try a different approach or different arguments rather than retrying the exact same call.";
        try {
            return JSON.writeValueAsString(Map.of("error", instruction));
        } catch (Exception e) {
            return "{\"error\":\"The tool '" + toolName.replace("\"", "") + "' failed.\"}";
        }
    }
}
