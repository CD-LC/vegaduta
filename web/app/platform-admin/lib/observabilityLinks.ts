// SEC-054 (2026-09-30): observability deep links are SERVER-ONLY config.
//
// GRAFANA_URL / PROMETHEUS_URL / LANGFUSE_URL / TEMPORAL_UI_URL used to be
// in lib/env.ts's PUBLIC_ENV_KEYS, which serialised them into window.__ENV on
// every page - including the pre-auth shell an anonymous visitor gets. They
// map internal infrastructure, so they now leave the server only through
// app/api/observability-links (platform-admin gated via verifyPlatformAdmin).
//
// Dynamic process.env lookups on purpose: a literal `process.env.GRAFANA_URL`
// would be inlined at build time by next.config.js's `env` block; this reads
// the running container's env.

export const OBSERVABILITY_ENV = {
  grafana: { key: "GRAFANA_URL", devDefault: "http://localhost:3003" },
  prometheus: { key: "PROMETHEUS_URL", devDefault: "http://localhost:9090" },
  langfuse: { key: "LANGFUSE_URL", devDefault: "http://localhost:3002" },
  temporal: { key: "TEMPORAL_UI_URL", devDefault: "http://localhost:8088" },
} as const;

export type ObservabilityTool = keyof typeof OBSERVABILITY_ENV;
export type ObservabilityLinks = Record<ObservabilityTool, string>;

export function observabilityLinks(env: Record<string, string | undefined> = process.env): ObservabilityLinks {
  const out = {} as ObservabilityLinks;
  for (const [tool, { key, devDefault }] of Object.entries(OBSERVABILITY_ENV)) {
    const v = env[key];
    out[tool as ObservabilityTool] = typeof v === "string" && v !== "" ? v : devDefault;
  }
  return out;
}