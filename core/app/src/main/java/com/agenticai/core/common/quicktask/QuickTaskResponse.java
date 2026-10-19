package com.agenticai.core.common.quicktask;

/**
 * @param resultText   The agent's full final answer - shown on-screen too, not just delivered by channel, so the person doesn't have to go check their phone to know it worked.
 * @param deliveryNote The dispatch service's own short human-readable line (e.g. "Sent WhatsApp message to +1... via Twilio.").
 * @param sessionId    The real session id {@link QuickTaskService#run} generated for this run - the same id every tool call made during it was logged against (see {@code ToolCallLog}). Lets a caller that used the
 *     blocking {@code POST /api/quick-task} endpoint (rather than {@code /api/quick-task/stream}) still fetch the same trace afterward via {@code GET /api/agents/{agentId}/tool-calls/session/{sessionId}}.
 * @param agentId      Which agent actually ran this - paired with {@code sessionId} above for that same lookup.
 */
public record QuickTaskResponse(String resultText, QuickTaskChannel channel, String deliveryNote, String sessionId, java.util.UUID agentId) {
}
