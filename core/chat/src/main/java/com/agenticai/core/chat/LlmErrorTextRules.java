package com.agenticai.core.chat;

import com.agenticai.core.providers.ProviderName;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The error-text layer of {@link LlmCallDiagnosisService}'s deterministic rules: patterns matched against a
 * failed call's error message plus exception type, in rough order of specificity. Pure and zero-cost; the
 * model-identity rules (retired model, catalog membership) and the AI fallback stay in the service.
 */
final class LlmErrorTextRules {

    // ---- deterministic error-text patterns --------------------------------
    // All matched case-insensitively against errorMessage + exceptionType.
    // Word-boundary anchors around bare status codes so "1401ms" or a token
    // count never false-positives as an HTTP status.
    private static final Pattern UNSUPPORTED_PARAM_PATTERN =
        Pattern.compile("presence_penalty|frequency_penalty", Pattern.CASE_INSENSITIVE);
    private static final Pattern STOP_PARAM_PATTERN =
        Pattern.compile("\\bstop\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern UNSUPPORTED_HINT_PATTERN =
        Pattern.compile("not supported|unsupported|is not allowed|invalid argument", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTH_PATTERN = Pattern.compile(
        "\\b401\\b|\\b403\\b|invalid api key|incorrect api key|invalid x-api-key|api key not valid"
            + "|authentication|unauthorized|forbidden|permission denied",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern RATE_LIMIT_PATTERN =
        Pattern.compile("\\b429\\b|rate[ _-]?limit|too many requests", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTA_PATTERN =
        Pattern.compile("quota|billing|insufficient", Pattern.CASE_INSENSITIVE);
    private static final Pattern MALFORMED_PATTERN = Pattern.compile(
        "must have at least one content|invalid request|malformed|\\b400\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern OUTAGE_PATTERN = Pattern.compile(
        "\\b50[0-4]\\b|overloaded|internal server error|service unavailable|bad gateway|server error",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMEOUT_PATTERN =
        Pattern.compile("timed[ _-]?out|timeout", Pattern.CASE_INSENSITIVE);
    // Spring AI's MCP tool-callback machinery wraps a failed tool invocation
    // as "Error calling tool: <TextContent[...]>" verbatim (confirmed live,
    // 2026-08-09: a Playwright MCP call failing with a missing-Chromium
    // error surfaced this way and got mis-diagnosed as PROVIDER_OUTAGE by
    // the AI fallback below, since this failure class has NOTHING to do
    // with the LLM provider and none of the LLM-oriented categories above
    // fit it). Checked BEFORE every other pattern - a tool-execution failure
    // is a categorically different problem from an LLM API error, and this
    // signature is specific/unambiguous enough that it should never fall
    // through to the AI fallback's guesswork, regardless of what incidental
    // words (an HTTP status, "timeout", "unauthorized"...) the underlying
    // tool's own error text happens to also contain.
    private static final Pattern TOOL_EXECUTION_ERROR_PATTERN =
        Pattern.compile("error calling tool|mcp (server|tool) error|tool execution failed", Pattern.CASE_INSENSITIVE);

    private LlmErrorTextRules() {
    }

    /**
     * The diagnosis for {@code text} (the row's non-blank combined error text), or null when no pattern
     * recognizes it - the AI fallback's cue. QUOTA is deliberately checked BEFORE RATE_LIMIT: several vendors
     * (OpenAI's insufficient_quota being the canonical case) report an exhausted balance as HTTP 429, and
     * "add credit" is the actionable fix there, not "slow down."
     */
    static LlmCallDiagnosisService.DiagnosisResult classify(String text, LlmCallLog row) {
        ProviderName provider = row.getProviderName();
        String model = row.getModel();
        if (TOOL_EXECUTION_ERROR_PATTERN.matcher(text).find()) {
            return new LlmCallDiagnosisService.DiagnosisResult(
                LlmCallDiagnosisService.CATEGORY_TOOL_EXECUTION_ERROR,
                "A tool this agent tried to use failed - not an AI provider problem",
                "The AI model itself responded normally; it's a tool call (an MCP server or built-in tool) "
                    + "made during this turn that failed. Retrying the same message will very likely hit the "
                    + "identical failure again, since this is almost always a missing dependency or "
                    + "misconfiguration on the tool's side, not a transient hiccup.",
                "Check the failing tool/MCP server's setup on the Tools screen (its connection status and "
                    + "any logged error detail) rather than retrying - if it names a missing binary or "
                    + "dependency, that needs an admin/infra fix before this tool will work.",
                List.of(),
                LlmCallDiagnosisService.SOURCE_RULES);
        }
        if (looksLikeUnsupportedParam(text, provider, model)) {
            return new LlmCallDiagnosisService.DiagnosisResult(
                LlmCallDiagnosisService.CATEGORY_UNSUPPORTED_PARAM,
                "The request used a parameter this model does not support",
                "Some models - Grok and reasoning models in particular - reject standard sampling "
                    + "parameters such as presence_penalty, frequency_penalty, or stop sequences. The "
                    + "provider refused the whole request because one of those was sent.",
                "Remove or reset the unsupported parameter in the agent's model settings, or switch to a "
                    + "model that accepts it.",
                List.of(),
                LlmCallDiagnosisService.SOURCE_RULES);
        }
        if (AUTH_PATTERN.matcher(text).find()) {
            return new LlmCallDiagnosisService.DiagnosisResult(
                LlmCallDiagnosisService.CATEGORY_AUTH,
                "The provider rejected this account's credentials",
                "The API key was missing, invalid, expired, or doesn't have permission to use this model - "
                    + "the provider refused the call before running it.",
                "Re-check the API key on the Providers screen: re-paste it from the provider's console and "
                    + "confirm it has access to this model.",
                List.of(),
                LlmCallDiagnosisService.SOURCE_RULES);
        }
        if (QUOTA_PATTERN.matcher(text).find()) {
            return new LlmCallDiagnosisService.DiagnosisResult(
                LlmCallDiagnosisService.CATEGORY_QUOTA_BILLING,
                "The provider account is out of quota or credit",
                "The provider accepted the credentials but refused the call because the account's spending "
                    + "limit, prepaid credit, or plan quota is exhausted.",
                "Check the provider account's billing page - add credit or raise the limit, then retry.",
                List.of(),
                LlmCallDiagnosisService.SOURCE_RULES);
        }
        if (RATE_LIMIT_PATTERN.matcher(text).find()) {
            return new LlmCallDiagnosisService.DiagnosisResult(
                LlmCallDiagnosisService.CATEGORY_RATE_LIMIT,
                "The provider rate-limited this call",
                "Too many requests reached the provider in a short window, so it turned this one away. "
                    + "This is temporary - nothing about the agent's configuration is wrong.",
                "Retry after a short wait; if it happens often, spread the traffic out or request a higher "
                    + "rate limit from the provider.",
                List.of(),
                LlmCallDiagnosisService.SOURCE_RULES);
        }
        if (MALFORMED_PATTERN.matcher(text).find()) {
            return new LlmCallDiagnosisService.DiagnosisResult(
                LlmCallDiagnosisService.CATEGORY_MALFORMED_REQUEST,
                "The provider rejected the request as invalid",
                "The request's shape was wrong for this provider - an empty message list, a field the API "
                    + "doesn't accept, or content the model can't take.",
                "Retry once; if it keeps failing, review recent changes to the agent's prompt or settings - "
                    + "an empty message or an invalid field is the usual cause.",
                List.of(),
                LlmCallDiagnosisService.SOURCE_RULES);
        }
        if (OUTAGE_PATTERN.matcher(text).find()) {
            return new LlmCallDiagnosisService.DiagnosisResult(
                LlmCallDiagnosisService.CATEGORY_PROVIDER_OUTAGE,
                "The provider had a server-side problem",
                "The provider's own service errored or was overloaded while handling this call - the "
                    + "failure is on their side, not in this agent's configuration.",
                "Retry after a short wait; if it persists, check the provider's status page.",
                List.of(),
                LlmCallDiagnosisService.SOURCE_RULES);
        }
        if (TIMEOUT_PATTERN.matcher(text).find()) {
            return new LlmCallDiagnosisService.DiagnosisResult(
                LlmCallDiagnosisService.CATEGORY_TIMEOUT,
                "The call timed out after " + row.getLatencyMs() + " ms",
                "The provider didn't finish responding within the allowed time - usually a long prompt, a "
                    + "slow model, or a transient network problem.",
                "Retry; for long prompts, shorten the input or switch to a faster model.",
                List.of(),
                LlmCallDiagnosisService.SOURCE_RULES);
        }
        return null;
    }

    /**
     * presence_penalty/frequency_penalty anywhere is conclusive on its own;
     * the bare word "stop" is far too common in prose, so it only counts
     * alongside an unsupported-parameter hint or a Grok/reasoning/Meta
     * context (the model families known to reject it - Meta Model API
     * returns HTTP 400 for any request carrying {@code stop}, per
     * dev.meta.ai 2026-09-17; see {@code MetaAgentChatClientFactory}).
     */
    private static boolean looksLikeUnsupportedParam(String text, ProviderName provider, String model) {
        if (UNSUPPORTED_PARAM_PATTERN.matcher(text).find()) {
            return true;
        }
        if (!STOP_PARAM_PATTERN.matcher(text).find()) {
            return false;
        }
        if (UNSUPPORTED_HINT_PATTERN.matcher(text).find()) {
            return true;
        }
        String lowerModel = model == null ? "" : model.toLowerCase(Locale.ROOT);
        return provider == ProviderName.GROK || provider == ProviderName.META
            || lowerModel.contains("grok") || lowerModel.contains("muse-spark") || lowerModel.contains("reason");
    }
}
