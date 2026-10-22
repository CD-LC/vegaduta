"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, API_BASE } from "../lib/api";
import { useTranslations } from "../lib/i18n";
import {
  buildClaudeDesktopConfig,
  buildCurlExample,
  buildCursorConfig,
  mcpEndpointUrl,
  normalizeMcpExposure,
  type McpExposure,
  type McpExposureKind,
} from "../lib/mcpExposure";
import { isPersonalEdition } from "../lib/edition";
import { Button } from "./ui/button";
import { Switch } from "./ui/switch";

/**
 * "Expose as MCP tool" switch plus copyable connection instructions, mounted on an agent's
 * settings and on the workflow page. Talks to core/mcpserver's /api/mcpserver/exposure.
 *
 * Self-hiding like AgentRunBudgetField: an older core (404) or a resource the caller cannot see
 * renders nothing - an absent optional control must not look like an error. The snippets never
 * contain a real key; the admin pastes their own from Developer API access.
 */
export default function McpExposureField(props: McpExposureFieldProps) {
  // core does not run the MCP server on a personal install (MCP_SERVER_EXPOSURE edition feature) and the
  // /admin/mcp-servers page the instructions point at is not served, so there is nothing to switch on.
  // Not mounting also skips the exposure lookup. The edition is fixed for the page's life, so the hook
  // order inside McpExposureFieldInner never changes between renders.
  if (isPersonalEdition()) return null;
  return <McpExposureFieldInner {...props} />;
}

interface McpExposureFieldProps {
  kind: McpExposureKind;
  resourceId: string | null | undefined;
  /** Read-only (e.g. the agent is edit-locked). */
  locked?: boolean;
}

function McpExposureFieldInner({ kind, resourceId, locked = false }: McpExposureFieldProps) {
  const t = useTranslations("mcpExposure");
  // Keyed by what it was fetched for, so switching resources never shows the previous one's state.
  const [loaded, setLoaded] = useState<{ key: string; state: McpExposure } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  const key = resourceId ? `${kind}:${resourceId}` : "";

  useEffect(() => {
    setError(null);
    if (!resourceId) {
      setLoaded(null);
      return undefined;
    }
    let cancelled = false;
    const requestedKey = `${kind}:${resourceId}`;
    api
      .getMcpExposure(kind, resourceId)
      .then((raw: unknown) => {
        if (cancelled) return;
        const state = normalizeMcpExposure(raw);
        setLoaded(state ? { key: requestedKey, state } : null);
      })
      .catch(() => {
        if (!cancelled) setLoaded(null);
      });
    return () => {
      cancelled = true;
    };
  }, [kind, resourceId]);

  const state = loaded && loaded.key === key ? loaded.state : null;
  if (!resourceId || !state) return null;

  async function toggle(next: boolean) {
    if (!resourceId || busy || locked) return;
    setBusy(true);
    setError(null);
    try {
      const updated = normalizeMcpExposure(await api.setMcpExposure(kind, resourceId, next));
      if (updated) setLoaded({ key, state: updated });
    } catch (err: any) {
      setError(err?.message || t("saveFailed"));
    } finally {
      setBusy(false);
    }
  }

  async function copy(id: string, text: string) {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(id);
      setTimeout(() => setCopied((current) => (current === id ? null : current)), 1500);
    } catch {
      /* clipboard unavailable: the text is still selectable on screen */
    }
  }

  const url = mcpEndpointUrl(API_BASE, state.mcpPath);
  const snippets: { id: string; title: string; text: string }[] = [
    { id: "cursor", title: t("cursorTitle"), text: buildCursorConfig(url) },
    { id: "claude", title: t("claudeTitle"), text: buildClaudeDesktopConfig(url) },
    { id: "curl", title: t("curlTitle"), text: buildCurlExample(url) },
  ];

  return (
    <div className="mb-4 rounded-md border border-border p-3" data-testid="mcp-exposure">
      <div className="flex items-center justify-between gap-3">
        <label htmlFor={`mcp-exposure-${key}`} className="text-sm font-medium text-foreground">
          {t("label")}
        </label>
        <Switch
          id={`mcp-exposure-${key}`}
          aria-label={t("label")}
          checked={state.exposed}
          disabled={busy || locked}
          onCheckedChange={(next) => void toggle(next)}
        />
      </div>
      <p className="mt-1 text-xs text-muted-foreground">{kind === "agents" ? t("hintAgent") : t("hintWorkflow")}</p>
      {error && (
        <p role="alert" className="mt-1 text-xs text-destructive">
          {error}
        </p>
      )}
      {state.exposed && (
        <div className="mt-3 space-y-2 text-xs text-muted-foreground">
          {state.toolName && <p className="font-mono text-foreground">{t("toolName", { name: state.toolName })}</p>}
          {!state.featureEnabled && <p>{t("featureOff")}</p>}
          <p className="font-medium text-foreground">{t("howTitle")}</p>
          <p>
            {t("step1", { kind: kind === "agents" ? t("kindAgent") : t("kindWorkflow") })}{" "}
            <Link href="/admin/mcp-servers" className="underline">
              /admin/mcp-servers
            </Link>
          </p>
          <p>{t("step2", { url })}</p>
          {snippets.map((snippet) => (
            <div key={snippet.id}>
              <div className="mb-1 flex items-center justify-between">
                <span>{snippet.title}</span>
                <Button type="button" variant="outline" size="sm" onClick={() => void copy(snippet.id, snippet.text)}>
                  {copied === snippet.id ? t("copied") : t("copy")}
                </Button>
              </div>
              <pre className="overflow-x-auto rounded bg-muted p-2 text-[11px] text-foreground">{snippet.text}</pre>
            </div>
          ))}
          <p>{t("approvalNote")}</p>
          {kind === "workflows" && <p>{t("workflowNote")}</p>}
        </div>
      )}
    </div>
  );
}
