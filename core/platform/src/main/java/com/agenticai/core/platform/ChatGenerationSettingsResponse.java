package com.agenticai.core.platform;

/**
 * Response for {@code GET/PUT /api/platform/chat-generation-settings} - the
 * admin surface for the platform-wide chat GENERATION knobs (2026-08-12),
 * grouped as one typed object the same way {@code
 * PerUserBudgetService.BudgetConfig} groups the dollar-budget keys rather
 * than exposing raw {@code platform_setting} strings.
 *
 * <p>All three are {@code platform_setting}-backed, so a change is live on
 * the next chat turn with no redeploy (each consumer reads through its own
 * short time-boxed cache):
 * <ul>
 *   <li>{@code geminiReasoningEffort} ({@code
 *       platform.hosted-llm.gemini-reasoning-effort}, one of {@code
 *       none|low|medium|high}, default {@code low}) - bounds the silent
 *       thinking-token spend of the hosted pool's Gemini 2.5+/3 rows.
 *       Thinking tokens are billed as OUTPUT at output rates and drawn from
 *       the same maxOutputTokens budget the reply needs; before this knob
 *       every hosted turn paid for unbounded thinking. Consumed by {@code
 *       core.agents.FreeTierChatClientService}; {@code
 *       core.chat.HostedLlmChatClientFactory} applies it per-model (never
 *       forwarded to non-Gemini providers).</li>
 *   <li>{@code historyMaxTurns} ({@code platform.chat.history-max-turns},
 *       default 40) / {@code historyMaxChars} ({@code
 *       platform.chat.history-max-chars}, default 32000) - the
 *       conversation-history window {@code SessionMemoryService} replays
 *       into each prompt. Written here; consumed by that service (its
 *       wiring is a separate change - this endpoint is only the admin
 *       read/write surface).</li>
 * </ul>
 *
 * @param geminiReasoningEffort effective thinking effort ({@code none|low|medium|high}).
 * @param historyMaxTurns       max prior conversation turns replayed per prompt.
 * @param historyMaxChars       max total characters of replayed history per prompt.
 */
public record ChatGenerationSettingsResponse(
    String geminiReasoningEffort,
    int historyMaxTurns,
    int historyMaxChars
) {
}
