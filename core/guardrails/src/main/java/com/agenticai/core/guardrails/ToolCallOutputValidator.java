package com.agenticai.core.guardrails;

import com.agenticai.core.json.JsonMappers;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 2026 Reliability Gap Analysis (task #815), Gap 1 - "no deterministic,
 * 100%-of-outputs schema/format validation layer on tool calls." Runs on
 * every tool call's raw output before it's handed back to the model,
 * distinct from and complementary to {@link GuardrailService#sanitizeToolOutput}
 * (which screens for unsafe *content*, not structural validity):
 *
 * <ol>
 *   <li>Length bound - catches a runaway/looping tool response before it
 *       blows the context budget, same ceiling {@code PatternBasedGuardrailService}
 *       already truncates to (kept as its own constant here rather than a
 *       cross-class reference, since that field is private and this check is
 *       "reject and let the model retry," not "truncate and continue" - a
 *       different response to the same threshold).</li>
 *   <li>JSON well-formedness - if a tool's output *looks* like JSON (starts
 *       with {@code {} or {@code [}), it must actually parse. A tool that
 *       emits truncated/malformed JSON is a strong signal something broke
 *       server-side (a timeout mid-stream, an upstream API error dropped
 *       into the payload, etc.) - better to tell the model plainly than let
 *       it try to reason over garbage.</li>
 * </ol>
 *
 * <p>Deliberately has zero LLM calls in it - the entire point (per the 2026
 * research this gap analysis is based on) is a cheap, deterministic check
 * that runs on every single tool call at effectively zero latency/cost,
 * distinct from the sampled LLM-as-judge pass (Gap 2) reserved for
 * catching subtler "technically valid but wrong" failures.
 */
public final class ToolCallOutputValidator {

    /**
     * Matches {@code PatternBasedGuardrailService.MAX_TOOL_OUTPUT_CHARS} -
     * same ceiling, different response (see class javadoc).
     */
    static final int MAX_OUTPUT_CHARS = 20_000;

    /**
     * Prefix {@link com.agenticai.core.guardrails.GuardedToolCallback} puts
     * on the synthetic error message it returns to the model on a failed
     * validation, so {@code LoggingToolCallback} can detect the failure
     * (to flag {@code ToolCallLog.validationFailed}) and strip the marker
     * before it's persisted/re-shown - the model and any human reading the
     * log should see a clean sentence, never this internal tag.
     */
    public static final String VALIDATION_FAILURE_PREFIX = "[TOOL_OUTPUT_VALIDATION_FAILED] ";

    private static final ObjectMapper MAPPER = JsonMappers.plain();

    private ToolCallOutputValidator() {
    }

    public static ValidationResult validate(String toolName, String rawOutput) {
        if (rawOutput == null) {
            return ValidationResult.ok();
        }
        if (rawOutput.length() > MAX_OUTPUT_CHARS) {
            return ValidationResult.invalid(
                "output was " + rawOutput.length() + " characters, over the " + MAX_OUTPUT_CHARS + "-character limit"
            );
        }
        String trimmed = rawOutput.trim();
        boolean looksLikeJson = trimmed.startsWith("{") || trimmed.startsWith("[");
        if (looksLikeJson) {
            try {
                MAPPER.readTree(trimmed);
            } catch (Exception e) {
                return ValidationResult.invalid(
                    "output looked like JSON (started with '" + trimmed.charAt(0) + "') but failed to parse - "
                        + "the tool ('" + toolName + "') likely returned a truncated or malformed response"
                );
            }
        }
        return ValidationResult.ok();
    }

    public record ValidationResult(boolean valid, String reason) {
        // Named "ok", not "valid" - a static factory method named the same as
        // the record's own "valid" component would collide with the compiler-
        // generated accessor valid() (same erasure, incompatible return types:
        // ValidationResult vs boolean), which is exactly what broke the build.
        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String reason) {
            return new ValidationResult(false, reason);
        }
    }
}
