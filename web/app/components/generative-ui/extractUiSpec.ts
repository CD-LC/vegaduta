import { UI_SPEC_VERSION, validateUiSpec, type UiSpec } from "./schema";

/**
 * Extracts a generative-UI spec from agent output.
 *
 * Two channels, one contract (see schema.ts):
 *  1. A fenced ```json_ui block inside ordinary chat text - the path that
 *     works over the existing SSE step stream with zero server changes. The
 *     block is stripped from the returned `text` so the chat transcript shows
 *     prose + a rendered card, never raw JSON.
 *  2. An AG-UI `CUSTOM` event whose `name` is `ui_spec` - the structured path
 *     for HttpAgent-style consumers; `extractUiSpecFromCustomEvent` maps the
 *     event's `value` onto the same validator.
 */

export type ExtractedUiBlock = {
  spec: UiSpec;
  /** Validation warnings (stripped props, unsupported nodes) - spec still rendered. */
  errors: string[];
  /** Chat text with the fenced block removed. */
  text: string;
};

const FENCE_RE = /```(?:json_ui|a2ui|ui_spec)\s*\n([\s\S]*?)```/;

export function extractUiSpec(text: string): ExtractedUiBlock | null {
  const m = FENCE_RE.exec(text);
  if (!m) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(m[1]);
  } catch {
    return null; // malformed JSON - leave the block in the text, untouched
  }
  const { spec, errors } = validateUiSpec(parsed);
  if (!spec) return null;
  return { spec, errors, text: (text.slice(0, m.index) + text.slice(m.index + m[0].length)).trim() };
}

/** AG-UI `CUSTOM` event shape (flat record - {type:"CUSTOM", name, value}). */
export function extractUiSpecFromCustomEvent(event: {
  type?: string;
  name?: string;
  value?: unknown;
}): ExtractedUiBlock | null {
  if (event?.type !== "CUSTOM" || event.name !== "ui_spec" || event.value == null) return null;
  const { spec, errors } = validateUiSpec(event.value);
  return spec ? { spec, errors, text: "" } : null;
}

/** Convenience for prompt-side docs: the header line an agent should emit. */
export const UI_SPEC_FENCE_LANG = "json_ui";
export const UI_SPEC_CONTRACT_VERSION = UI_SPEC_VERSION;
