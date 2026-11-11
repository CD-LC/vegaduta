// Parser for POST /api/agents/{id}/chat SSE bodies (data-only frames; see
// tests/e2e/helpers/prod-api.ts parseChatSse, whose marker constants this mirrors).
// If the backend rotates the markers, update them here and in that helper together.

export const STEP_EVENT_MARKER = "@@AGENT_STEP_EVENT_4d81ba@@";
export const STREAM_ERROR_MARKER = "@@AGENT_CHAT_STREAM_ERROR_9f2b1c@@";

/**
 * @returns {{text: string, stepEvents: object[], streamErrors: string[]}}
 * Tool calls are the step events with `type: "tool_call"` ({id, tool, args}).
 */
export function parseChatSse(body) {
  const parts = [];
  const stepEvents = [];
  const streamErrors = [];
  for (const block of String(body).split(/\r?\n\r?\n/)) {
    const data = block
      .split(/\r?\n/)
      .filter((l) => l.startsWith("data:"))
      .map((l) => l.slice(5)); // a space after the colon is payload
    if (data.length === 0) continue;
    const payload = data.join("\n");
    if (payload.startsWith(STEP_EVENT_MARKER)) {
      try {
        stepEvents.push(JSON.parse(payload.slice(STEP_EVENT_MARKER.length)));
      } catch {
        stepEvents.push({ type: "unparsed" });
      }
    } else if (payload.startsWith(STREAM_ERROR_MARKER)) {
      streamErrors.push(payload.slice(STREAM_ERROR_MARKER.length).trim());
    } else parts.push(payload);
  }
  return { text: parts.join(""), stepEvents, streamErrors };
}

/** Tool calls from step events, in order: [{tool, args}]. `args` is whatever the platform emitted (redacted). */
export function toolCallsFromSteps(stepEvents) {
  return stepEvents.filter((e) => e && e.type === "tool_call" && e.tool).map((e) => ({ tool: String(e.tool), args: e.args ?? {} }));
}
