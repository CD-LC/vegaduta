// Turns the JSON patch core returns from `tune` into plain-language lines the
// user can read before anything is applied ("agent Refunds: reasoning depth
// CAREFUL -> RIGOROUS"). Structured output, not strings: the component maps
// scope/field to translated words, while names and values (which are the
// user's own content) pass through untouched.

import { labelOfRef } from "./logic";
import type { Brief, JsonPatchOp } from "./types";

export type DiffScope = "agent" | "workflow" | "step" | "trust" | "brief" | "other";

/** Field words the component can translate (`brief.diff.field.<key>`). */
export const DIFF_FIELDS: ReadonlySet<string> = new Set([
  "name",
  "description",
  "systemPrompt",
  "reasoningDepth",
  "tuningMode",
  "temperature",
  "topP",
  "maxTokens",
  "toolAllowList",
  "knowledgeRefs",
  "procedureRefs",
  "guardrails",
  "edgeRoutingPolicy",
  "shareMemoryWithTenant",
  "sampleInput",
  "triggers",
  "rung",
  "irreversible",
  "humanMustDecide",
  "title",
  "goal",
  "locale",
  "evalSet",
  "settings",
  "label",
]);

export interface DiffLine {
  id: string;
  op: "changed" | "added" | "removed";
  scope: DiffScope;
  /** Agent / workflow / step name, or null when the scope has none. */
  subject: string | null;
  /** A DIFF_FIELDS key, or null when the path is not one we can name. */
  field: string | null;
  /** Raw path, shown only when `field` is null. */
  path: string;
  /** Short one-line renderings of the old / new value or the added / removed list item. */
  from: string | null;
  to: string | null;
  /** The whole agent / workflow / step is added or removed, not one of its fields. */
  entity: boolean;
  /** Full old / new text, present when either was truncated (system prompts). */
  full: { from: string; to: string } | null;
}

const MAX_INLINE = 60;

const decode = (seg: string) => seg.replace(/~1/g, "/").replace(/~0/g, "~");

function show(v: unknown): string {
  if (v == null) return "";
  if (typeof v === "string") return v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  if (Array.isArray(v)) return v.map(show).join(", ");
  return JSON.stringify(v);
}

function clip(s: string): string {
  const one = s.replace(/\s+/g, " ").trim();
  return one.length > MAX_INLINE ? `${one.slice(0, MAX_INLINE - 1)}...` : one;
}

function walk(root: unknown, segs: readonly string[]): unknown {
  let cur: any = root;
  for (const s of segs) {
    if (cur == null) return undefined;
    cur = cur[s === "-" ? (Array.isArray(cur) ? cur.length - 1 : s) : s];
  }
  return cur;
}

const opKind = (op: JsonPatchOp["op"]): DiffLine["op"] => (op === "add" || op === "copy" ? "added" : op === "remove" ? "removed" : "changed");

function line(base: Pick<DiffLine, "id" | "op" | "scope" | "subject" | "path">, field: string | null, from: unknown, to: unknown, entity = false): DiffLine {
  const f = show(from);
  const t = show(to);
  const long = f.replace(/\s+/g, " ").length > MAX_INLINE || t.replace(/\s+/g, " ").length > MAX_INLINE;
  return {
    ...base,
    entity,
    field: field && DIFF_FIELDS.has(field) ? field : null,
    from: from === undefined ? null : clip(f),
    to: to === undefined ? null : clip(t),
    full: long ? { from: f, to: t } : null,
  };
}

/**
 * One line per operation. `before` resolves what a path used to hold and the
 * name of the thing it sits under; `after` (the proposed brief) resolves names
 * for things that only exist after the patch.
 */
export function describePatch(diff: readonly JsonPatchOp[], before: Brief, after?: Brief | null): DiffLine[] {
  return diff.map((p, i) => {
    const segs = p.path.split("/").slice(1).map(decode);
    const id = `${i}:${p.op}:${p.path}`;
    const kind = opKind(p.op);
    const oldValue = kind === "added" ? undefined : walk(before, segs);
    const newValue = p.op === "remove" ? undefined : p.value;
    const named = (root: Brief | null | undefined, ref: string) => (root ? labelOfRef(root, ref) : ref);
    const [top, idx, second, third] = segs;

    if (top === "agents" && idx != null) {
      const at = idx === "-" ? -1 : Number(idx);
      const agent = (before.agents[at] ?? after?.agents[at < 0 ? after.agents.length - 1 : at]) as Brief["agents"][number] | undefined;
      const subject = agent?.name ?? (p.value as { name?: string } | undefined)?.name ?? null;
      const base = { id, op: kind, scope: "agent" as const, subject, path: p.path };
      if (second == null) return line(base, null, undefined, subject, true);
      if (second === "generation" && third) return line(base, third, oldValue, newValue);
      // A list item (tool ref, knowledge ref...) shows as the item itself.
      const isListItem = third != null && /^(\d+|-)$/.test(third);
      if (isListItem) {
        const item = kind === "removed" ? oldValue : p.value;
        return line(base, second, undefined, typeof item === "string" ? named(after ?? before, item) : item);
      }
      return line(base, second, oldValue, newValue);
    }

    if (top === "workflows" && idx != null) {
      const at = Number(idx);
      const wf = before.workflows[at] ?? after?.workflows[at];
      const base = { id, op: kind, scope: "workflow" as const, subject: wf?.name ?? null, path: p.path };
      if (second === "graph" && third === "nodes" && segs[4] != null) {
        const nodeAt = segs[4] === "-" ? -1 : Number(segs[4]);
        const node = before.workflows[at]?.graph.nodes[nodeAt] ?? (p.value as any);
        const stepName = node?.data?.label ?? node?.id ?? null;
        const stepBase = { ...base, scope: "step" as const, subject: stepName ? `${wf?.name ?? ""} / ${stepName}`.replace(/^ \/ /, "") : wf?.name ?? null };
        if (segs.length === 5) return line(stepBase, null, undefined, stepName, true);
        const leaf = segs[segs.length - 1];
        return line(stepBase, leaf === "label" ? "label" : "settings", oldValue, newValue);
      }
      if (second === "graph") return line(base, null, oldValue, newValue);
      if (second == null) return line(base, null, undefined, wf?.name ?? (p.value as { name?: string } | undefined)?.name ?? null, true);
      return line(base, second, oldValue, newValue);
    }

    if (top === "trust") {
      const base = { id, op: kind, scope: "trust" as const, subject: null as string | null, path: p.path };
      const item = second === "irreversible" || second === "humanMustDecide" ? (third != null ? (kind === "removed" ? oldValue : p.value) : undefined) : undefined;
      if (item !== undefined) return line(base, second, undefined, typeof item === "string" ? named(after ?? before, item) : item);
      return line(base, second ?? null, oldValue, newValue);
    }

    const base = { id, op: kind, scope: (top === "title" || top === "goal" || top === "locale" || top === "evalSet" ? "brief" : "other") as DiffScope, subject: null as string | null, path: p.path };
    return line(base, top ?? null, oldValue, newValue);
  });
}
