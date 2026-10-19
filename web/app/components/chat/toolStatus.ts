import type { Tier1ToolCallEvent } from "../../lib/edge/tier1";

/** What this panel knows about the two tool surfaces. Separate variants
 * rather than two nullable numbers: "you are not signed in" and "the surface
 * did not answer" are different sentences, and collapsing them is how the
 * silence this replaces happened in the first place. */
export type ToolState =
  /** Local tools exist, but no on-device model is cached — so none of them can
   * actually run yet. Counting them without saying that would advertise a
   * capability this browser does not currently have. */
  | { kind: "needs-model"; local: number }
  /** Signed out: the ordinary free state, and the one where "sign in" is the
   * right next step. */
  | { kind: "local-only"; local: number }
  /** Signed IN, but this surface has no agent selected. Telling this person to
   * sign in would be wrong, which is why the two are separate variants rather
   * than one "not entitled". */
  | { kind: "no-agent"; local: number }
  | { kind: "both"; local: number; server: number }
  | { kind: "server-unavailable"; local: number };

/**
 * One short, readable rendering of what the model actually asked a tool for.
 * Objects become `key: value` pairs (the shape every edge tool's inputSchema
 * produces); anything else becomes its JSON. Truncated because this sits on a
 * single status row — AgentActivityTrace is where the full arguments belong.
 * Never throws: a status line must not be able to fail a turn.
 */
export function summarizeToolArgs(args: unknown): string | null {
  if (args == null) return null;
  try {
    let text: string;
    if (typeof args === "object" && !Array.isArray(args)) {
      const pairs = Object.entries(args as Record<string, unknown>).map(
        ([k, v]) => `${k}: ${typeof v === "string" ? v : JSON.stringify(v)}`
      );
      if (pairs.length === 0) return null;
      text = pairs.join(", ");
    } else {
      text = JSON.stringify(args);
    }
    text = (text || "").replace(/\s+/g, " ").trim();
    if (!text) return null;
    return text.length > 90 ? `${text.slice(0, 89)}…` : text;
  } catch {
    return null;
  }
}

/**
 * "Which tool, what was it asked, and where did it run" for the round in
 * flight — the three things a name alone cannot tell anyone.
 *
 * Every optional field of Tier1ToolCallEvent (`arguments`, `round`, `source`)
 * is treated as genuinely optional, because it is: that interface marks all
 * three optional so events built outside tier1.ts — AgentActivityTrace's tests
 * construct bare {name, phase} ones — stay valid. An absent field is simply
 * not rendered; nothing is substituted for it.
 *
 * `round` is zero-based (tier1.ts's runTurn loops `let round = 0`), so it is
 * shown from the SECOND round on, where it stops being noise and starts
 * answering "why is this still going".
 *
 * `source === "local"` is compared to the literal rather than tested for
 * truthiness: an event that doesn't say where it ran must not be labelled as
 * having stayed on the device. tier1.ts assigns "server" only on the branch
 * that actually POSTs to the execute endpoint, so "local" here is the honest
 * "nothing left this device".
 */
export function describeToolCall(
  event: Tier1ToolCallEvent,
  t: (key: string, values?: Record<string, unknown>) => string
): string {
  const tool = event?.name || "tool";
  const round = typeof event?.round === "number" && event.round > 0 ? event.round + 1 : null;
  const label = round == null ? tool : t("toolRound", { tool, round });
  const args = summarizeToolArgs(event?.arguments);
  const local = event?.source === "local";
  if (args) {
    return local
      ? t("toolRunningLocalWithArgs", { tool: label, args })
      : t("toolRunningWithArgs", { tool: label, args });
  }
  return local ? t("toolRunningLocal", { tool: label }) : t("toolRunning", { tool: label });
}
