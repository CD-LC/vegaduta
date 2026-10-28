"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, API_BASE } from "../../lib/api";
import type { A2aConsolePolicy } from "../../lib/api/a2a";
import { useTranslations } from "../../lib/i18n";
import { isPersonalEdition } from "../../lib/edition";
import { Button } from "../ui/button";

/**
 * Per-agent A2A exposure panel: the JSON-RPC / REST endpoints a peer agent calls to reach THIS
 * agent, the signed agent-card URL, and links into the A2A activity console and directory
 * publishing (core/agents-interop a2a, /api/agents/{id}/a2a[/rest]).
 *
 * Self-hiding like McpExposureField: a personal install (no A2A_DIRECTORY feature) or an older
 * core whose /api/a2a/console/policy 404s renders nothing - an absent optional panel must not
 * look like an error. Read-only by design: per-agent A2A availability follows RUNNER access on
 * the agent and the operator's app.a2a.* settings; the mutating knobs live at
 * /admin/agent-trust (directory publication), not here.
 */
export default function A2aEndpointPanel({ agentId }: { agentId: string | null | undefined }) {
  if (isPersonalEdition()) return null;
  return <A2aEndpointPanelInner agentId={agentId} />;
}

function A2aEndpointPanelInner({ agentId }: { agentId: string | null | undefined }) {
  const t = useTranslations("a2aPanel");
  const [policy, setPolicy] = useState<A2aConsolePolicy | null | undefined>(undefined);
  const [copied, setCopied] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api
      .a2aPolicy()
      .then((p: A2aConsolePolicy | null) => {
        if (!cancelled) setPolicy(p);
      })
      .catch(() => {
        if (!cancelled) setPolicy(null);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (!agentId || !policy || !policy.server.enabled) return null;

  const agentPath = (template: string) => `${API_BASE}${template.replace("{agentId}", agentId)}`;
  const jsonRpcUrl = agentPath(policy.server.agentJsonRpcPath);
  const restUrl = agentPath(policy.server.agentRestPath);
  const cardUrl = agentPath(policy.server.agentCardPath);

  async function copy(id: string, text: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(id);
      setTimeout(() => setCopied((current) => (current === id ? null : current)), 1500);
    } catch {
      /* clipboard unavailable: the text is still selectable on screen */
    }
  }

  const rows: { id: string; label: string; value: string }[] = [
    { id: "jsonrpc", label: t("jsonRpc"), value: `POST ${jsonRpcUrl}` },
    { id: "rest", label: t("restBinding"), value: `${restUrl}/message:send` },
    { id: "card", label: t("agentCard"), value: `GET ${cardUrl}` },
  ];

  return (
    <div className="mb-4 rounded-md border border-border p-3" data-testid="a2a-endpoints">
      <p className="text-sm font-medium text-foreground">{t("label")}</p>
      <p className="mt-1 text-xs text-muted-foreground">{t("hint")}</p>
      <div className="mt-3 space-y-2">
        {rows.map((row) => (
          <div key={row.id}>
            <div className="mb-1 flex items-center justify-between text-xs">
              <span className="text-muted-foreground">{row.label}</span>
              <Button type="button" variant="outline" size="sm" onClick={() => void copy(row.id, row.value)}>
                {copied === row.id ? t("copied") : t("copy")}
              </Button>
            </div>
            <pre className="overflow-x-auto rounded bg-muted p-2 text-[11px] text-foreground">{row.value}</pre>
          </div>
        ))}
      </div>
      <p className="mt-3 text-xs text-muted-foreground">
        {t("manageNote")}{" "}
        <Link href={`/admin/a2a?agent=${encodeURIComponent(agentId)}`} className="underline">
          /admin/a2a
        </Link>{" "}
        ·{" "}
        <Link href="/admin/agent-trust" className="underline">
          /admin/agent-trust
        </Link>
      </p>
    </div>
  );
}
