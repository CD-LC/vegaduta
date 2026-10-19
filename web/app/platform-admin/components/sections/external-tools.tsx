"use client";

import { useEffect, useState } from "react";
import { ExternalLink, LineChart, Gauge, Radar, Workflow } from "lucide-react";
import { SectionCard } from "./common";
import { auth } from "../../lib/auth";
import { safeHref } from "../../lib/safeLink";

// SEC-054 (2026-09-30): the URLs are no longer read from window.__ENV (which
// exposed internal infra hostnames to anonymous visitors). They are fetched
// after sign-in from /api/observability-links, which is gated server-side by
// verifyPlatformAdmin - see app/lib/observabilityLinks.ts.

// Diagnostics & Observability §10 "Phase A" - deep-link buttons to the external
// monitoring tools, each with a live reachability dot. URLs come from the
// server environment (GRAFANA_URL / PROMETHEUS_URL / LANGFUSE_URL /
// TEMPORAL_UI_URL) via /api/observability-links. There are deliberately NO
// localhost fallbacks: a tool whose URL is unset is shown as "Not configured
// for this environment" with no link, rather than a button that points an
// operator's browser at their own machine.
//
// Reachability note: these tools live on other origins, so the browser can't
// read their HTTP status (CORS). We probe with a no-cors fetch + timeout - a
// resolved request means the host answered (dot = up), a rejected one means the
// connection failed (dot = down), and a timeout stays "unknown". It's a coarse
// "is it reachable from your browser" signal, not a real health check; §10
// "Phase B" (SSO-embedded Grafana panels / Langfuse traces) is a follow-up.

type Reach = "checking" | "up" | "down" | "unknown";

interface ExternalToolDef {
  key: string;
  label: string;
  envName: string;
  use: string;
}

const TOOLS = [
  {
    key: "grafana",
    label: "Grafana",
    envName: "GRAFANA_URL",
    use: "Dashboards; also the UI for Loki logs + Prometheus metrics",
  },
  {
    key: "prometheus",
    label: "Prometheus",
    envName: "PROMETHEUS_URL",
    use: "Raw metrics / PromQL",
  },
  {
    key: "langfuse",
    label: "Langfuse",
    envName: "LANGFUSE_URL",
    use: "LLM tracing - the Traces panel on /observability reads it through core",
  },
  {
    key: "temporal",
    label: "Temporal UI",
    envName: "TEMPORAL_UI_URL",
    use: "Durable workflow executions",
  },
] as const satisfies readonly ExternalToolDef[];

type ToolKey = (typeof TOOLS)[number]["key"];
type Links = Partial<Record<ToolKey, string>>;

// Fetches the admin-only link map. Errors surface as a message, never as a
// fallback URL - an unverified caller must not learn the hostnames.
function useObservabilityLinks(): { links: Links | null; error: string } {
  const [links, setLinks] = useState<Links | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const token = await auth.getToken();
        const res = await fetch("/platform-admin/api/observability-links", {
          headers: token ? { Authorization: `Bearer ${token}` } : {},
          cache: "no-store",
        });
        const body = await res.json().catch((): null => null);
        if (cancelled) return;
        if (!res.ok) {
          setError(body?.error || `Could not load tool links (${res.status}).`);
          return;
        }
        setLinks(body || {});
      } catch {
        if (!cancelled) setError("Could not load tool links.");
      }
    })().catch(() => {});
    return () => {
      cancelled = true;
    };
  }, []);
  return { links, error };
}

const ICONS: Record<string, any> = { grafana: LineChart, prometheus: Gauge, langfuse: Radar, temporal: Workflow };

const DOT_CLASS: Record<Reach, string> = {
  checking: "bg-muted-foreground/50 animate-pulse",
  up: "bg-[var(--state-success)]",
  down: "bg-destructive",
  unknown: "bg-[var(--warning)]",
};

const REACH_LABEL: Record<Reach, string> = {
  checking: "checking…",
  up: "reachable",
  down: "unreachable",
  unknown: "unknown",
};

function useReachable(url: string): Reach {
  const [state, setState] = useState<Reach>("checking");
  useEffect(() => {
    if (!url) {
      setState("unknown");
      return undefined;
    }
    let cancelled = false;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 4000);
    fetch(url, { mode: "no-cors", signal: controller.signal })
      .then(() => !cancelled && setState("up"))
      .catch((e) => {
        if (cancelled) return;
        // AbortError => timed out, inconclusive; anything else => connection failed.
        setState(e?.name === "AbortError" ? "unknown" : "down");
      })
      .finally(() => clearTimeout(timer));
    return () => {
      cancelled = true;
      clearTimeout(timer);
      controller.abort();
    };
  }, [url]);
  return state;
}

function ToolButton({ tool, url }: { tool: ExternalToolDef; url: string }) {
  const reach = useReachable(url);
  const Icon = ICONS[tool.key] || ExternalLink;
  return (
    <a
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      className="flex flex-1 basis-56 flex-col gap-1.5 rounded-lg border border-border bg-secondary/30 p-4 transition-colors hover:border-primary/40 hover:bg-accent"
    >
      <div className="flex items-center gap-2">
        <Icon size={16} className="text-primary" />
        <span className="text-sm font-semibold text-foreground">{tool.label}</span>
        <span
          className={`ml-auto size-2 rounded-full ${DOT_CLASS[reach]}`}
          title={`Reachability: ${REACH_LABEL[reach]}`}
          aria-hidden="true"
        />
        <ExternalLink size={13} className="text-muted-foreground" />
      </div>
      <span className="text-xs text-muted-foreground">{tool.use}</span>
      <span className="break-all font-mono text-[10.5px] text-muted-foreground">
        {url} · {REACH_LABEL[reach]}
      </span>
    </a>
  );
}

function UnconfiguredTool({ tool }: { tool: ExternalToolDef }) {
  const Icon = ICONS[tool.key] || ExternalLink;
  return (
    <div
      className="flex flex-1 basis-56 flex-col gap-1.5 rounded-lg border border-dashed border-border p-4"
      data-testid={`external-tool-unconfigured-${tool.key}`}
    >
      <div className="flex items-center gap-2">
        <Icon size={16} className="text-muted-foreground" />
        <span className="text-sm font-semibold text-muted-foreground">{tool.label}</span>
      </div>
      <span className="text-xs text-muted-foreground">Not configured for this environment.</span>
      <span className="font-mono text-[10.5px] text-muted-foreground">
        Set {tool.envName} in the server environment to link it.
      </span>
    </div>
  );
}

export function ExternalToolsSection({ delay }: { delay?: number }) {
  const { links, error } = useObservabilityLinks();
  return (
    <SectionCard
      title="External monitoring tools"
      subtitle="Deep-link out to the full observability stack. Live reachability dot per tool; URLs come from the server environment (GRAFANA_URL / PROMETHEUS_URL / LANGFUSE_URL / TEMPORAL_UI_URL)."
      icon={ExternalLink}
      delay={delay}
    >
      {error && <p className="mb-3 text-xs text-destructive">{error}</p>}
      <div className="flex flex-wrap gap-3">
        {TOOLS.map((tool) => {
          const url = safeHref(links?.[tool.key]);
          return url ? (
            <ToolButton key={tool.key} tool={tool} url={url} />
          ) : (
            <UnconfiguredTool key={tool.key} tool={tool} />
          );
        })}
      </div>
    </SectionCard>
  );
}
