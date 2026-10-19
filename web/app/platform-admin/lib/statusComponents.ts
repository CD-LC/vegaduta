// SystemStatusResponse (GET /api/platform/health, /api/system/status) carries a
// fixed set of components SystemStatusPanel renders by name. Core grows that
// set over time (Temporal, code-intel, MinIO, Langfuse, Academy...), so any
// OTHER top-level key whose value looks like a component ({ status: "..." })
// is rendered generically instead of being silently dropped.

const KNOWN_KEYS = new Set([
  "overallStatus",
  "database",
  "redis",
  "kafka",
  "mcp",
  "jvm",
  "llm",
  "sidecar",
  "whatsappQr",
  "timestamp",
  "generatedAt",
  "checkedAt",
]);

export interface ExtraComponent {
  key: string;
  label: string;
  status: string;
  detail: string;
}

/** "codeIntel" -> "Code intel", "minio" -> "Minio", "langfuse_ui" -> "Langfuse ui". */
export function humanizeKey(key: string): string {
  const spaced = key
    .replace(/[_-]+/g, " ")
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .trim()
    .toLowerCase();
  return spaced ? spaced[0].toUpperCase() + spaced.slice(1) : key;
}

export function extraStatusComponents(status: any): ExtraComponent[] {
  if (!status || typeof status !== "object") return [];
  const out: ExtraComponent[] = [];
  for (const [key, value] of Object.entries(status)) {
    if (KNOWN_KEYS.has(key)) continue;
    if (!value || typeof value !== "object" || Array.isArray(value)) continue;
    const st = (value as any).status;
    if (typeof st !== "string" || !st) continue;
    const detailRaw = (value as any).detail ?? (value as any).message;
    out.push({
      key,
      label: typeof (value as any).name === "string" && (value as any).name ? (value as any).name : humanizeKey(key),
      status: st,
      detail: typeof detailRaw === "string" && detailRaw ? detailRaw : st === "NOT_CONFIGURED" ? "not configured" : st,
    });
  }
  return out.sort((a, b) => a.label.localeCompare(b.label));
}
