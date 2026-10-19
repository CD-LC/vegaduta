"use client";

import { useCallback, useEffect, useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Loader2, PlugZap, ShieldAlert } from "lucide-react";
import { api } from "../lib/api";
import type { McpOAuthProvider, McpOAuthProvidersResponse } from "../lib/api/mcpOauth";
import {
  connectedStatusKey,
  disconnectNoticeKey,
  noticeForReturn,
  notConfiguredReasonKey,
  orderProviders,
  parseOAuthReturn,
  providerCardState,
  scopeLabels,
  stripOAuthReturnParams,
} from "../lib/mcpOAuthProviders";
import { REMOTE_OAUTH_PROVIDER } from "../lib/mcpRemoteOAuth";
import { useTranslations } from "../lib/i18n";
import { Alert, AlertDescription } from "./ui/alert";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "./ui/alert-dialog";
import { Badge } from "./ui/badge";
import { Button } from "./ui/button";
import { Card, CardContent } from "./ui/card";

// Same semantic status-badge classes GoogleIntegrationPanel uses, so "connected"
// keeps its meaning in every accent palette and in light/dark.
const OK_BADGE = "border-[var(--success-border)] bg-[var(--success-bg)] text-[var(--success-text)]";

/**
 * One-click "Connect" grid for the OAuth providers in core/mcp's
 * OAuthProviderRegistry (Google Sheets/Drive/Gmail/Calendar, GitHub, Slack,
 * Notion, Linear, HubSpot, Microsoft 365, Asana, Zoom). Driven entirely by
 * GET /api/mcp/oauth/providers: a provider the platform admin has not set up is
 * shown as exactly that ("Not configured by your admin", with the reason) and
 * gets NO Connect button - never a button that cannot work. Connecting leaves
 * for the provider's consent screen and returns through the backend callback to
 * /tools?mcp_oauth=..., which this panel reads, announces and strips.
 */
export default function McpOAuthPanel({ onChanged }: { onChanged?: () => void }) {
  const t = useTranslations("mcpOAuth");
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();

  const [data, setData] = useState<McpOAuthProvidersResponse | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);
  // A banner is either literal text (an API error) or an i18n key + the provider it is about, resolved at render so
  // the provider's display name is used once the list has loaded (the callback return only carries the id).
  const [notice, setNotice] = useState<{
    variant: "success" | "destructive";
    key?: string;
    providerId?: string;
    text?: string;
  } | null>(null);
  const [disconnectTarget, setDisconnectTarget] = useState<McpOAuthProvider | null>(null);

  const refresh = useCallback(async () => {
    try {
      setData(await api.listMcpOAuthProviders());
      setLoadFailed(false);
    } catch {
      setLoadFailed(true);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // Result of the backend callback redirect: announce it once, then strip the params so a reload does not repeat it.
  useEffect(() => {
    const ret = parseOAuthReturn(searchParams);
    // A return from the generic "Connect with OAuth" flow (provider=remote) belongs to the add-server dialog's banner on
    // the tools page, not to this grid - leave it for that consumer.
    if (!ret || ret.provider === REMOTE_OAUTH_PROVIDER) return;
    const n = noticeForReturn(ret);
    setNotice({ variant: n.variant, key: n.key, providerId: ret.provider });
    if (ret.result === "connected") {
      void refresh();
      onChanged?.();
    }
    router.replace(stripOAuthReturnParams(pathname || "/tools", searchParams.toString()));
  }, [searchParams, router, pathname, refresh, onChanged]);

  async function connect(p: McpOAuthProvider) {
    setNotice(null);
    setBusyId(p.id);
    try {
      const { url } = await api.startMcpOAuth(p.id);
      // Leaves the app for the provider's consent screen (full-page navigation, like the Google/Atlassian connects).
      window.location.href = url;
    } catch (err) {
      setNotice({ variant: "destructive", text: (err as Error)?.message || t("startError", { name: p.name }) });
      setBusyId(null);
    }
  }

  async function disconnect(p: McpOAuthProvider) {
    setBusyId(p.id);
    try {
      const res = await api.disconnectMcpOAuth(p.id);
      setNotice({
        variant: "success",
        key: disconnectNoticeKey(res?.providerRevocation, Boolean(res?.disconnected)),
        providerId: p.id,
      });
      setDisconnectTarget(null);
      await refresh();
      onChanged?.();
    } catch (err) {
      setNotice({ variant: "destructive", text: (err as Error)?.message || t("disconnectError", { name: p.name }) });
    } finally {
      setBusyId(null);
    }
  }

  const providers = data ? orderProviders(data.providers) : [];
  const nameFor = (id?: string) => data?.providers.find((p) => p.id === id)?.name || id || "";
  const noticeText = notice
    ? (notice.text ?? t(notice.key || "noticeExchangeFailed", { name: nameFor(notice.providerId) }))
    : "";

  return (
    <div className="space-y-5">
      <div className="space-y-1">
        <h2 className="text-base font-semibold text-foreground">{t("heading")}</h2>
        <p className="max-w-2xl text-sm text-muted-foreground">{t("intro")}</p>
      </div>

      {notice && (
        <Alert variant={notice.variant}>
          <AlertDescription>{noticeText}</AlertDescription>
        </Alert>
      )}

      {loadFailed && (
        <Alert variant="destructive">
          <ShieldAlert aria-hidden="true" />
          <AlertDescription>
            <p>{t("loadError")}</p>
            <Button variant="outline" size="sm" className="mt-2" onClick={() => void refresh()}>
              {t("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {!data && !loadFailed && (
        <p className="text-sm text-muted-foreground" role="status">
          {t("loading")}
        </p>
      )}

      {data && !data.enabled && (
        <Alert>
          <AlertDescription>
            <p className="font-medium">{t("featureOffTitle")}</p>
            <p>{t("featureOffBody")}</p>
          </AlertDescription>
        </Alert>
      )}

      {data && (
        <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3" data-testid="mcp-oauth-grid">
          {providers.map((p) => {
            const state = providerCardState(p);
            const busy = busyId === p.id;
            const scopes = scopeLabels(p.scopes);
            return (
              <Card key={p.id} data-testid={`mcp-oauth-card-${p.id}`} data-state={state}>
                <CardContent className="flex h-full flex-col gap-3">
                  <div className="flex items-start justify-between gap-2">
                    <div className="flex min-w-0 items-center gap-2">
                      <PlugZap size={16} className="shrink-0 text-muted-foreground" aria-hidden="true" />
                      <span className="truncate text-sm font-semibold text-foreground">{p.name}</span>
                    </div>
                    {state === "connected" && (
                      <Badge variant="outline" className={p.registersMcpServer ? OK_BADGE : undefined}>
                        {t(connectedStatusKey(p))}
                      </Badge>
                    )}
                    {state === "available" && <Badge variant="outline">{t("statusAvailable")}</Badge>}
                    {state === "not_configured" && (
                      <Badge variant="outline" className="text-muted-foreground">
                        {t("statusNotConfigured")}
                      </Badge>
                    )}
                  </div>

                  {state === "not_configured" ? (
                    <div className="space-y-2 text-xs text-muted-foreground">
                      <p>{t(notConfiguredReasonKey(p.notConfiguredReason))}</p>
                      {p.redirectUri && (
                        <p style={{ overflowWrap: "anywhere" }}>
                          <span className="font-medium">{t("adminRedirectLabel", { name: p.name })}</span>{" "}
                          <code className="font-mono">{p.redirectUri}</code>
                        </p>
                      )}
                    </div>
                  ) : (
                    <div className="space-y-2 text-xs text-muted-foreground">
                      <div>
                        <span className="font-medium">{t("scopesLabel")}</span>{" "}
                        {scopes.length > 0 ? scopes.join(", ") : t("scopesNone")}
                      </div>
                      <p>{t(p.registersMcpServer ? "registersServerHint" : "noAgentToolHint", { name: p.name })}</p>
                    </div>
                  )}

                  <div className="mt-auto flex pt-1">
                    {state === "available" && (
                      <Button type="button" size="sm" disabled={busy} onClick={() => void connect(p)}>
                        {busy && <Loader2 aria-hidden="true" size={12} className="animate-spin" />}
                        {busy ? t("connectingEllipsis") : t("connectButton")}
                      </Button>
                    )}
                    {state === "connected" && (
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={busy}
                        onClick={() => setDisconnectTarget(p)}
                      >
                        {t("disconnectButton")}
                      </Button>
                    )}
                  </div>
                </CardContent>
              </Card>
            );
          })}
        </div>
      )}

      <AlertDialog open={Boolean(disconnectTarget)} onOpenChange={(open) => !open && setDisconnectTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>{t("disconnectTitle", { name: disconnectTarget?.name || "" })}</AlertDialogTitle>
            <AlertDialogDescription>
              {t("disconnectBody", { name: disconnectTarget?.name || "" })}
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel disabled={busyId !== null}>{t("disconnectCancel")}</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
              disabled={busyId !== null}
              onClick={(e) => {
                e.preventDefault();
                if (disconnectTarget) void disconnect(disconnectTarget);
              }}
            >
              {busyId !== null ? t("disconnectingEllipsis") : t("disconnectButton")}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
