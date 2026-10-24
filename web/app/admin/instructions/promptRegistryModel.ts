// Pure helpers of the prompt registry console: which stored override an editor works on, what text it starts
// from, and how a result is described. Kept out of the components so they can be tested without rendering.
import type {
  PromptOverrideView,
  PromptScopePath,
  PromptSource,
  PromptView,
  ResolvedPrompt,
} from "../../lib/api/promptRegistry";

export interface EditTarget {
  scope: PromptScopePath;
  /** Set for the agent scope. */
  agentId: string | null;
}

const SCOPE_ENUM: Record<PromptScopePath, "PLATFORM" | "TENANT" | "AGENT"> = {
  platform: "PLATFORM",
  tenant: "TENANT",
  agent: "AGENT",
};

/** The stored override at the target scope (the default-locale one when a key has several locales). */
export function overrideAt(view: PromptView, target: EditTarget): PromptOverrideView | null {
  const wanted = SCOPE_ENUM[target.scope];
  const rows = view.overrides.filter(
    (o) => o.scope === wanted && (target.scope !== "agent" || o.scopeId === target.agentId)
  );
  if (rows.length === 0) return null;
  return rows.find((o) => o.locale === (view.effective.locale ?? "en")) ?? rows.find((o) => o.locale === "en") ?? rows[0];
}

/**
 * The wording in force just BELOW the target scope: what the key would say if the override there were removed.
 * Agent looks at tenant, platform, then the shipped text; tenant at platform then the shipped text; platform at the
 * shipped text. A switched-off row below counts as "no text" (null), because that is what the model would get.
 */
export function wordingBelow(view: PromptView, target: EditTarget): { text: string | null; source: PromptSource } {
  const order: PromptScopePath[] = ["agent", "tenant", "platform"];
  for (const scope of order.slice(order.indexOf(target.scope) + 1)) {
    const row = overrideAt(view, { scope, agentId: null });
    if (row) return { text: row.enabled ? row.text : null, source: SCOPE_ENUM[scope] };
  }
  return { text: view.defaultText, source: "DEFAULT" };
}

/** What the editor's textarea starts with. */
export function startingText(view: PromptView, target: EditTarget): string {
  const own = overrideAt(view, target);
  if (own?.text) return own.text;
  return wordingBelow(view, target).text ?? view.defaultText ?? "";
}

export function startingEnabled(view: PromptView, target: EditTarget): boolean {
  return overrideAt(view, target)?.enabled ?? true;
}

/** True when the key's effective value is not the shipped one. */
export function isCustomised(effective: ResolvedPrompt): boolean {
  return effective.source !== "DEFAULT" && effective.source !== "FALLBACK";
}

export const RISK_CLASSES = ["STEERS_EVERY_TURN", "SECONDARY_CALL", "USER_VISIBLE", "TOOL_DESCRIPTION"] as const;

/** Case-insensitive match on the key, title and description. */
export function matchesQuery(view: PromptView, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  const d = view.definition;
  return d.key.toLowerCase().includes(q) || d.title.toLowerCase().includes(q) || (d.description ?? "").toLowerCase().includes(q);
}
