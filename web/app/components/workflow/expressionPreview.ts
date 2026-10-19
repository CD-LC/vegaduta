// Live-preview resolution for ExpressionField (pure, no React). The field asks
// the server to evaluate its template against pinned data first and falls back
// to the last run's data; this module decides what the line under the field
// says. Every network failure degrades to "unavailable" so a field never stops
// working as plain text because core is older or down.
import type { EvaluateRequest, EvaluateResponse, ExpressionDataSource, ExpressionValueType } from "../../lib/api/workflowExpressions";

type Fetched = { ok: true; data: EvaluateResponse } | { ok: false; unsupported: boolean; data?: undefined };

export type PreviewState =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "unavailable" }
  | { kind: "nodata" }
  | {
      kind: "ok";
      rendered: string;
      type: ExpressionValueType;
      value: unknown;
      source: Exclude<ExpressionDataSource, "supplied">;
      warnings: string[];
    }
  | { kind: "error"; message: string; start: number; end: number };

export type Evaluate = (req: EvaluateRequest) => Promise<Fetched>;

const okBody = (r: Fetched): EvaluateResponse | null => (r.ok && r.data && typeof r.data === "object" ? r.data : null);

/**
 * Pinned data first, last-run data second. `needsData` = the template reads
 * node data at all; a pure `{{ 1 + 2 }}` previews without any sample.
 */
export async function resolvePreview(
  evaluate: Evaluate,
  template: string,
  nodeId: string,
  needsData: boolean,
): Promise<PreviewState> {
  const good = (b: EvaluateResponse | null) => !!b && !b.error && b.ok !== false && (!needsData || (b.sources?.length ?? 0) > 0);
  const toOk = (b: EvaluateResponse, source: "pinned" | "lastRun"): PreviewState => ({
    kind: "ok",
    rendered: typeof b.rendered === "string" ? b.rendered : "",
    type: b.type,
    value: b.value,
    source,
    warnings: Array.isArray(b.warnings) ? b.warnings : [],
  });

  const pinned = await evaluate({ template, nodeId, dataSource: "pinned" });
  const pb = okBody(pinned);
  if (pb && good(pb)) return toOk(pb, "pinned");

  const last = await evaluate({ template, nodeId, dataSource: "lastRun" });
  const lb = okBody(last);
  if (lb && good(lb)) return toOk(lb, "lastRun");

  if (!pb && !lb) return { kind: "unavailable" };

  // Report the error from whichever side actually had data; a syntax error shows on both.
  const withErr = [pb, lb].filter((b): b is EvaluateResponse => !!b && !!b.error);
  if (withErr.length) {
    const pick = withErr.find((b) => (b.sources?.length ?? 0) > 0) ?? withErr[0];
    const e = pick.error!;
    return { kind: "error", message: e.message, start: e.start, end: e.end };
  }
  return { kind: "nodata" };
}

/** Compact one-line form for a rendered value (objects arrive as JSON text). */
export function previewText(rendered: string, max = 240): string {
  const flat = rendered.replace(/\s+/g, " ").trim();
  return flat.length > max ? `${flat.slice(0, max)}…` : flat;
}
