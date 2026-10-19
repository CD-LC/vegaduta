"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Loader2, ShieldCheck } from "lucide-react";
import { api } from "../../lib/api";
import type { McpRemoteOAuthState } from "../../lib/api/mcpOauth";
import { isConnectableUrl, isInsecureUrl, viewForState } from "../../lib/mcpRemoteOAuth";
import { useTranslations } from "../../lib/i18n";
import { Alert, AlertDescription } from "../ui/alert";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";

// Same semantic status-badge classes GoogleIntegrationPanel/McpOAuthPanel use, so states keep their meaning in every
// accent palette and in light/dark.
const OK_BADGE = "border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]";

const DEBOUNCE_MS = 600;

/**
 * "Connect with OAuth" for the remote server URL typed in the add-server dialog (spec-discovered OAuth: the server
 * itself says how to sign in - no per-service setup). Four honest states, from one POST /api/mcp/oauth/remote/status:
 * the server needs no sign-in / needs sign-in / is connected / needs authorizing again.
 *
 * Renders NOTHING unless the deployment turned the feature on (GET /api/mcp/oauth/remote -> enabled), so a flag-off
 * deployment's dialog is unchanged. Connecting first saves the server (the parent's `ensureRegistered`, so the
 * backend callback has a registration to attach the token to), then leaves for the authorization server's consent
 * screen and returns through the backend callback to /tools?mcp_oauth=...&provider=remote, which the tools page reads.
 */
export default function McpRemoteOAuthSection({
  url,
  ensureRegistered,
  onChanged,
}: {
  url: string;
  ensureRegistered: () => Promise<boolean>;
  onChanged?: () => void;
}) {
  const t = useTranslations("mcpRemoteOAuth");
  const [enabled, setEnabled] = useState(false);
  const [state, setState] = useState<McpRemoteOAuthState | "checking" | null>(null);
  const [busy, setBusy] = useState<"connect" | "disconnect" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const seq = useRef(0);

  useEffect(() => {
    let cancelled = false;
    api
      .getMcpRemoteOAuthCapabilities()
      .then((c) => {
        if (!cancelled) setEnabled(Boolean(c?.enabled));
      })
      .catch(() => {
        // 404 / network: the feature is not available - stay hidden.
        if (!cancelled) setEnabled(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const trimmed = url.trim();
  const connectable = isConnectableUrl(trimmed);

  const check = useCallback(async (target: string) => {
    const mine = ++seq.current;
    setState("checking");
    try {
      const status = await api.getMcpRemoteOAuthStatus(target);
      if (mine === seq.current) setState(status?.state ?? "unknown");
    } catch {
      if (mine === seq.current) setState("unknown");
    }
  }, []);

  // Probe after the user stops typing; a stale answer for a previous URL is dropped by the sequence guard.
  useEffect(() => {
    if (!enabled) return undefined;
    if (!connectable) {
      seq.current += 1;
      setState(null);
      return undefined;
    }
    setError(null);
    const timer = setTimeout(() => void check(trimmed), DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [enabled, connectable, trimmed, check]);

  if (!enabled) return null;

  async function connect() {
    setError(null);
    setBusy("connect");
    try {
      // The parent shows its own reason (e.g. "name required") and resolves false.
      if (!(await ensureRegistered())) {
        setBusy(null);
        return;
      }
      const { url: consentUrl } = await api.startMcpRemoteOAuth(trimmed);
      // Leaves the app for the authorization server's consent screen (full-page navigation, like the other connects).
      window.location.href = consentUrl;
    } catch (err) {
      setError((err as Error)?.message || t("startError"));
      setBusy(null);
    }
  }

  async function disconnect() {
    setError(null);
    setBusy("disconnect");
    try {
      await api.disconnectMcpRemoteOAuth(trimmed);
      await check(trimmed);
      onChanged?.();
    } catch (err) {
      setError((err as Error)?.message || t("disconnectError"));
    } finally {
      setBusy(null);
    }
  }

  if (isInsecureUrl(trimmed)) {
    return (
      <p className="text-xs text-muted-foreground" data-testid="mcp-remote-oauth-insecure">
        {t("httpsOnly")}
      </p>
    );
  }
  if (!connectable || !state) return null;

  const view = viewForState(state);
  const toneClass =
    view.tone === "warning"
      ? "text-[var(--warning-text,inherit)]"
      : view.tone === "info"
        ? "text-foreground"
        : "text-muted-foreground";

  return (
    <div className="space-y-2 rounded-lg border border-border p-3" data-testid="mcp-remote-oauth" data-state={state}>
      <div className="flex flex-wrap items-center gap-2">
        <span className="flex items-center gap-1.5 text-sm font-medium text-foreground">
          <ShieldCheck size={14} aria-hidden="true" />
          {t("heading")}
        </span>
        {state === "connected" && <Badge className={OK_BADGE}>{t("badgeConnected")}</Badge>}
        {state === "needs_reauth" && <Badge variant="destructive">{t("badgeNeedsReauth")}</Badge>}
        {state === "checking" && (
          <Loader2 size={13} className="animate-spin text-muted-foreground" aria-label={t("checking")} />
        )}
      </div>
      <p className={`text-xs ${toneClass}`} role="status">
        {t(view.messageKey)}
      </p>
      <div className="flex flex-wrap gap-2">
        {(view.action === "connect" || view.action === "reauthorize") && (
          <Button type="button" size="sm" onClick={() => void connect()} disabled={busy !== null}>
            {busy === "connect" && <Loader2 size={12} className="mr-1 animate-spin" aria-hidden="true" />}
            {busy === "connect"
              ? t("connecting")
              : view.action === "reauthorize"
                ? t("reauthorizeButton")
                : t("connectButton")}
          </Button>
        )}
        {view.action === "disconnect" && (
          <Button type="button" size="sm" variant="outline" onClick={() => void disconnect()} disabled={busy !== null}>
            {busy === "disconnect" ? t("disconnecting") : t("disconnectButton")}
          </Button>
        )}
        {view.action === "retry" && (
          <Button type="button" size="sm" variant="outline" onClick={() => void check(trimmed)}>
            {t("retryButton")}
          </Button>
        )}
      </div>
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
    </div>
  );
}
