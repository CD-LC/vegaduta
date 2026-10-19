import type {
  DetectedPortabilitySource,
  PortabilityEnvelope,
  PortabilityFidelity,
  PortabilityFidelityLevel,
  PortabilityPlaceholder,
  PortabilitySource,
} from "../../lib/api/workflowPortability";

// Pure helpers for the "Import workflow" page: guess which format the pasted or
// dropped file is (so the hint can say so), size-check it, build the request
// envelope and group the placeholders the server lists. No React, no network,
// and the file text is never logged or stored here.
//
// Detection is a CLIENT-SIDE HINT only. The server re-detects (and refuses a
// source/shape mismatch), so a wrong or missing guess here costs a 400 at worst
// and "auto" is always safe. When the shape is ambiguous this returns null.

/** Client-side ceiling for the pasted/uploaded file (matches the server default, 2 MiB). */
export const MAX_WORKFLOW_IMPORT_BYTES = 2 * 1024 * 1024;

/** Workflow names are sanitised to 100 chars server-side; stop the input there too. */
export const MAX_WORKFLOW_NAME_LENGTH = 100;

export const PORTABILITY_SOURCES: readonly PortabilitySource[] = ["auto", "vegaduta", "n8n", "dify"];

/** Placeholder kinds in the order the page lists them; anything else sorts last. */
export const PLACEHOLDER_KIND_ORDER = ["agent", "kb", "workflow", "group", "device", "destination", "credential", "secret"];

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function stripBom(text: string): string {
  return (text || "").replace(/^\uFEFF/, "");
}

/** UTF-8 byte length without relying on TextEncoder being present in every runtime. */
export function utf8Bytes(text: string): number {
  let bytes = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c < 0x80) bytes += 1;
    else if (c < 0x800) bytes += 2;
    else if (c >= 0xd800 && c <= 0xdbff && i + 1 < text.length && text.charCodeAt(i + 1) >= 0xdc00 && text.charCodeAt(i + 1) <= 0xdfff) {
      bytes += 4;
      i++;
    } else bytes += 3;
  }
  return bytes;
}

export function exceedsWorkflowImportLimit(text: string, maxBytes: number = MAX_WORKFLOW_IMPORT_BYTES): boolean {
  // Cheap pre-checks: a UTF-8 string is at least text.length bytes and at most 3x.
  if (text.length > maxBytes) return true;
  if (text.length * 3 <= maxBytes) return false;
  return utf8Bytes(text) > maxBytes;
}

function detectFromObject(root: Record<string, unknown>): DetectedPortabilitySource | null {
  if (root.format === "vegaduta.workflow") return "vegaduta";
  const workflow = root.workflow;
  if (isPlainObject(workflow) && isPlainObject(workflow.graph)) return "dify";
  if (root.kind === "app" && isPlainObject(root.app)) return "dify";
  if (Array.isArray(root.nodes) && isPlainObject(root.connections)) return "n8n";
  if ("schemaVersion" in root && Array.isArray(root.nodes)) return "vegaduta";
  return null;
}

/**
 * Best-guess source for the text of a workflow file; null when it is not clearly one of the three.
 * JSON is parsed (an n8n CLI export is an ARRAY of workflows - the first is looked at); anything else is checked for
 * the two top-level keys a Dify app DSL always has, without parsing YAML.
 */
export function detectWorkflowFormat(text: string): DetectedPortabilitySource | null {
  const trimmed = stripBom(text).trim();
  if (!trimmed) return null;
  if (trimmed[0] === "{" || trimmed[0] === "[") {
    try {
      const parsed: unknown = JSON.parse(trimmed);
      const root = Array.isArray(parsed) ? parsed[0] : parsed;
      return isPlainObject(root) ? detectFromObject(root) : null;
    } catch {
      return null;
    }
  }
  const hasApp = /^app:/m.test(trimmed) || /^kind:\s*app\b/m.test(trimmed);
  const hasWorkflow = /^workflow:/m.test(trimmed);
  return hasApp && hasWorkflow ? "dify" : null;
}

/**
 * Builds the request envelope both routes accept. The file goes as TEXT, exactly as given - the server reads JSON and
 * YAML alike. `name` is sent as overrides.name only when non-blank; `mappings` only with entries that have a value.
 */
export function buildPortabilityEnvelope(
  source: PortabilitySource,
  text: string,
  name?: string,
  mappings?: Record<string, string>
): PortabilityEnvelope {
  const envelope: PortabilityEnvelope = {
    source: PORTABILITY_SOURCES.includes(source) ? source : "auto",
    payload: stripBom(text),
  };
  const trimmed = (name || "").trim().slice(0, MAX_WORKFLOW_NAME_LENGTH);
  if (trimmed) envelope.overrides = { name: trimmed };
  const chosen = cleanMappings(mappings);
  if (Object.keys(chosen).length > 0) envelope.mappings = chosen;
  return envelope;
}

/** Drops blank and non-string entries so an unselected dropdown never reaches the server as an empty id. */
export function cleanMappings(mappings: Record<string, string> | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(mappings || {})) {
    if (typeof value === "string" && value.trim()) out[key] = value.trim();
  }
  return out;
}

export function sameMappings(a: Record<string, string> | undefined, b: Record<string, string> | undefined): boolean {
  const left = cleanMappings(a);
  const right = cleanMappings(b);
  const keys = Object.keys(left);
  if (keys.length !== Object.keys(right).length) return false;
  return keys.every((key) => left[key] === right[key]);
}

export type PlaceholderGroup = { kind: string; items: PortabilityPlaceholder[] };

/** Placeholders grouped by kind, in display order; empty groups are left out. */
export function groupPlaceholders(placeholders: PortabilityPlaceholder[]): PlaceholderGroup[] {
  const byKind = new Map<string, PortabilityPlaceholder[]>();
  for (const p of placeholders || []) {
    const list = byKind.get(p.kind) || [];
    list.push(p);
    byKind.set(p.kind, list);
  }
  const rank = (kind: string) => {
    const index = PLACEHOLDER_KIND_ORDER.indexOf(kind);
    return index === -1 ? PLACEHOLDER_KIND_ORDER.length : index;
  };
  return [...byKind.entries()]
    .sort((a, b) => rank(a[0]) - rank(b[0]) || a[0].localeCompare(b[0]))
    .map(([kind, items]) => ({ kind, items }));
}

/** Placeholders the person can point at an existing resource. */
export function mappablePlaceholders(placeholders: PortabilityPlaceholder[]): PortabilityPlaceholder[] {
  return (placeholders || []).filter((p) => p.mappable);
}

/** How many mappable placeholders still have nothing chosen (neither by the server's echo nor by the current picks). */
export function unmappedCount(placeholders: PortabilityPlaceholder[], picks: Record<string, string>): number {
  return mappablePlaceholders(placeholders).filter((p) => !(picks[p.key] || "").trim()).length;
}

/** Which existing-resource list a placeholder kind is chosen from: agents, knowledge bases or workflows; null otherwise. */
export function pickerSourceFor(kind: string): "agents" | "knowledge" | "workflows" | null {
  if (kind === "agent") return "agents";
  if (kind === "kb") return "knowledge";
  if (kind === "workflow") return "workflows";
  return null;
}

/** Share of the source's steps that mapped (exact or needing review), 0-100; annotations are not counted. */
export function fidelityPercent(f: PortabilityFidelity): number {
  const denominator = Math.max(1, f.sourceNodes - f.skipped);
  return Math.max(0, Math.min(100, Math.round(((f.exact + f.needsReview) / denominator) * 100)));
}

/** Badge variant for a fidelity level (design-system variants only; no colours here). */
export function fidelityBadgeVariant(level: PortabilityFidelityLevel): "default" | "secondary" | "outline" {
  if (level === "high") return "default";
  if (level === "partial") return "secondary";
  return "outline";
}

/** The suggested name when the file has none: the file's own name without its extension chain. */
export function nameFromFileName(fileName: string): string {
  return (fileName || "")
    .replace(/\.vegaduta-workflow\.json$/i, "")
    .replace(/\.(json|ya?ml)$/i, "")
    .replace(/[-_]+/g, " ")
    .trim()
    .slice(0, MAX_WORKFLOW_NAME_LENGTH);
}

/** True when the file name looks like something the page can read (JSON or YAML, or a mime type that says so). */
export function looksLikeWorkflowFile(fileName: string, mimeType: string): boolean {
  return /\.(json|ya?ml)$/i.test(fileName || "") || /json|yaml|yml|text\/plain/i.test(mimeType || "");
}
