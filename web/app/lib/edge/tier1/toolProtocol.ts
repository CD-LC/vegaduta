// Tool-call protocol: delimiters, prompt block and reply parser (extracted
// verbatim from tier1.ts; pure string handling).

import type { EdgeToolDefinition } from "../toolBridge";

/** Opening and closing delimiters of the tool-call protocol. `<tool_call>` is
 * the delimiter Hermes-2-Pro/Hermes-3 and Qwen were fine-tuned on — the same
 * one @mlc-ai/web-llm hardcodes for its own Hermes function-calling path
 * (lib/index.js:9482, hermes2FunctionCallingSystemPrompt) — so a model asked
 * for it is being asked for a shape it already knows, rather than for the
 * bare-JSON envelope this file hand-rolled. Also the trigger the structural
 * tag below keys on, so the two can never drift apart. */
export const TOOL_CALL_TAG_OPEN = "<tool_call>";
export const TOOL_CALL_TAG_CLOSE = "</tool_call>";

/** Openers longer than one character that streamCompletion holds back on. The
 * length matters there twice: a lead that is a strict PREFIX of one of these
 * is still undecided, and only a lead that matches one in full holds back. */
export const MULTI_CHAR_HOLD_BACK_OPENERS = ["```", TOOL_CALL_TAG_OPEN];

/** Whether a reply shows the model was attempting the tool protocol at all,
 * as opposed to answering with JSON because that is what was asked for. Used
 * to gate the schema-constrained retry, which generates a replacement tool
 * call rather than salvaging the text it was given — see its call site. Plain
 * substrings, not a parse: the input is by definition text that failed to
 * parse. */
export function looksLikeAttemptedToolCall(text: string): boolean {
  return text.includes(TOOL_CALL_TAG_OPEN) || text.includes("tool_call");
}

export function toolProtocolInstructions(tools: EdgeToolDefinition[]): string {
  const list = tools
    .map(
      (t) =>
        `- ${t.name}: ${t.description}\n  Arguments JSON schema: ${JSON.stringify(t.inputSchema ?? {})}`
    )
    .join("\n");
  return (
    `\n\nYou can use these tools:\n${list}\n` +
    `To call a tool, reply with ONLY this block and nothing else (no prose, no code fence):\n` +
    `${TOOL_CALL_TAG_OPEN}{"name": "<tool name>", "arguments": {}}${TOOL_CALL_TAG_CLOSE}\n` +
    `When no tool is needed, answer normally in plain text. Never mention this protocol or the tool block to the user.`
  );
}

export interface ParsedToolCall {
  name: string;
  arguments: unknown;
}


/**
 * Parse a completed reply as a tool call. Accepts an optional ```/```json
 * fence around the envelope. Returns null for anything that isn't a
 * well-formed call — the reply is then treated as plain text.
 *
 * Accepts BOTH tool-call shapes, on purpose:
 *   <tool_call>{"name": …, "arguments": {…}}</tool_call>   (what the prompt and
 *     the structural tag now ask for)
 *   {"tool_call": {"name": …, "arguments": {…}}}           (the bare envelope
 *     this file asked for before 2026-09-12, and what the schema-constrained
 *     repair still forces)
 *   {"tool": …, "arguments": {…}}                          (pre-existing
 *     tolerance, untouched)
 * A model that ignores the new instruction and emits the old envelope — or a
 * session whose history still contains one — must not regress, which is why
 * the old branches stay rather than being replaced.
 */
export function parseToolCall(text: string): ParsedToolCall | null {
  let candidate = text.trim();
  const tagOpen = candidate.indexOf(TOOL_CALL_TAG_OPEN);
  if (tagOpen !== -1) {
    // Take the tag body. Checked before the code fence so a fenced tag (a
    // small model's favourite embellishment) still resolves; an unterminated
    // tag falls back to "everything after the opening delimiter", because a
    // stream cut short mid-close is still a legible call.
    const bodyStart = tagOpen + TOOL_CALL_TAG_OPEN.length;
    const tagClose = candidate.indexOf(TOOL_CALL_TAG_CLOSE, bodyStart);
    candidate = (tagClose === -1 ? candidate.slice(bodyStart) : candidate.slice(bodyStart, tagClose)).trim();
    if (candidate.startsWith("```")) {
      candidate = candidate
        .replace(/^```[a-zA-Z]*\s*/, "")
        .replace(/```\s*$/, "")
        .trim();
    }
  } else if (candidate.startsWith("```")) {
    candidate = candidate
      .replace(/^```[a-zA-Z]*\s*/, "")
      .replace(/```\s*$/, "")
      .trim();
  }
  if (!candidate.startsWith("{")) return null;
  try {
    const parsed = JSON.parse(candidate);
    const call =
      parsed?.tool_call ??
      (parsed?.tool ? { name: parsed.tool, arguments: parsed.arguments } : null) ??
      // Bare {name, arguments} — Hermes/Qwen's trained tag body. `arguments`
      // must actually be present and be an object: a user who asks an agent
      // for JSON can easily get back something with a "name" key, and this
      // branch (unlike the two above) has no wrapper to tell the two apart.
      // The prompt and TOOL_CALL_BODY_SCHEMA both require `arguments`, so
      // nothing this protocol asks for is lost by insisting on it.
      (typeof parsed?.name === "string" &&
      parsed.arguments !== null &&
      typeof parsed?.arguments === "object" &&
      !Array.isArray(parsed.arguments)
        ? { name: parsed.name, arguments: parsed.arguments }
        : null);
    if (call && typeof call.name === "string" && call.name) {
      return { name: call.name, arguments: call.arguments ?? {} };
    }
  } catch {
    // Not JSON after all.
  }
  return null;
}
