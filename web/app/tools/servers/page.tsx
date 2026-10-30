"use client";

import { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import {
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  Clock,
  HelpCircle,
  KeyRound,
  Lock,
  Loader2,
  Power,
  PowerOff,
  RefreshCw,
  ShieldCheck,
  Wrench,
} from "lucide-react";
import { api } from "../../lib/api";
import type { McpToolPinStatus } from "../../lib/api/mcpToolPins";
import { useTranslations } from "../../lib/i18n";
import { Skeleton } from "../../components/ui/skeleton";
import { Input } from "../../components/ui/input";
import AppShell from "../../components/AppShell";
import PageHeader from "../../components/ax/PageHeader";
import { usePageReady } from "../../components/RouteTransition";
import { Button } from "../../components/ui/button";
import { Badge } from "../../components/ui/badge";
import { Card, CardContent } from "../../components/ui/card";
import { Alert, AlertDescription, AlertTitle } from "../../components/ui/alert";
import PaginationControls from "../../components/PaginationControls";
import ToolPinReview from "../../components/McpPanel/ToolPinReview";
import { ToolPinBadge, ToolPinSummary, useToolPinStatuses } from "../../components/McpPanel/ToolPinStatus";
import { usePagination } from "../../lib/usePagination";

/**
 * "All enabled MCP servers" - a flat follow-through list of every MCP server
 * enabled for this tenant (MCP-UX task, 2026-07-26, product owner priority
 * #4), one level down from the two-pane grid+inspector on /tools. That
 * screen is built for discovery/browsing (catalog, registry search, one
 * server's full detail); this one is built for "which of my servers need
 * attention right now" - a plain list with Test + Trust-all-actions +
 * Enable/Disable per row and a page-level bulk Trust, without the
 * grid/inspector chrome.
 *
 * "Trust all actions" reuses api.acknowledgeMcpServer(id) - the SAME
 * server-level ACKNOWLEDGED flow McpPanel's AcknowledgeControl already
 * exercises for one server at a time. This codebase has no per-tool
 * trust/approval concept anywhere (only a server-level usageStatus gate), so
 * "trust all actions of this server" is read here as the existing
 * server-level acknowledge, not a new granular permission model - flagging
 * that reading explicitly since the request was ambiguous on this point.
 *
 * Rework (2026-07-26, direct product-owner feedback: "I don't see the
 * disabled button in the enabled MCP" + "use right fonts, redesign with
 * proper colours, alert messages, proper direction informing consumer where
 * is the notification"):
 *   1. Enable/Disable toggle added per server - reuses api.toggleMcpServer,
 *      the SAME PATCH-based call McpPanel's own toggle already uses (server
 *      stays selectable/testable either way; disabling here is otherwise
 *      identical to flipping it off from the /tools grid).
 *   2. Migrated off the legacy ax-tl-* classes/ax-green/red/amber vars onto
 *      the app's current ShadCN primitives (Card/Badge/Alert/Button) and
 *      Tailwind tokens - matches PageHeader and every other recently-touched
 *      screen instead of inventing new colors.
 *   3. Connection errors and self-heal suggested fixes (McpServerRegistration
 *      .suggestedFix*, exposed directly on the list response - see
 *      McpServerRegistrationResponse) render as Alert components INSIDE the
 *      affected server's own card, not as ad hoc colored text or a
 *      disconnected page-level banner - the point being it should be
 *      unambiguous which server a given alert is about.
 */

const PHASE_LABELS: Record<string, string> = {
  SPAWN: "Spawning the server process",
  HTTP: "HTTP connection",
  HANDSHAKE: "MCP handshake",
  INIT_TIMEOUT: "Initialization timed out",
  OK: "Connected",
};

// Badge color classes sourced from the same --state-success/--state-warning
// tokens ui/alert.tsx's success/warning variants (and McpPanel/helpers.ts's
// TONE_SUCCESS/TONE_WARNING) already use - a status badge and an alert about
// the same state should always read as the same color, not two different
// greens/ambers invented independently. Token-driven rather than dark:
// utilities: this console's dark mode flips via [data-mode="dark"], not the
// OS-driven `dark:` variant, so `dark:text-*` classes never actually
// triggered under this app's own theme switch.
const STATUS_SUCCESS =
  "border-[color-mix(in_srgb,var(--state-success)_32%,transparent)] " +
  "bg-[color-mix(in_srgb,var(--state-success)_12%,transparent)] text-[var(--state-success)]";
const STATUS_WARNING =
  "border-[color-mix(in_srgb,var(--state-warning)_32%,transparent)] " +
  "bg-[color-mix(in_srgb,var(--state-warning)_12%,transparent)] text-[var(--state-warning)]";

function usageStatusOf(server: any): string | null {
  return typeof server?.usageStatus === "string" && server.usageStatus ? server.usageStatus : null;
}

// MCP-STDIO-SANDBOXING (2026-08-05) - the platform-staff review gate a
// self-service custom/registry-browsed STDIO registration must clear before
// it's ever allowed to connect (see core's McpReviewStatus javadoc). null =
// the running core predates the field.
function reviewStatusOf(server: any): string | null {
  return typeof server?.reviewStatus === "string" && server.reviewStatus ? server.reviewStatus : null;
}

// "Needs attention" = the same set the AppShell nav badge counts (see
// AppShell.tsx's mcpAttentionCount) - an enabled server that's UNTESTED,
// FAILED, or (for a dynamic server on an older core with no usageStatus
// field at all) reporting a live connection ERROR.
function needsAttention(server: any): boolean {
  const status = usageStatusOf(server);
  if (status === "UNTESTED" || status === "FAILED") return true;
  if (server.dynamic && server.connectionStatus === "ERROR") return true;
  return false;
}

function statusBadge(server: any): { label: string; variant?: "destructive" | "secondary"; className?: string } {
  // Decrypt-failure and review-gate states both override the plain
  // connection/usage read below - a corrupted-credentials or pending/
  // rejected-review server needs its own distinct badge, not a misleading
  // "Not verified"/"Failed" that reads like an ordinary connection hiccup.
  if (server.credentialsCorrupted) return { label: "Credentials unreadable", variant: "destructive" };
  const review = reviewStatusOf(server);
  if (review === "PENDING")
    return {
      label: "Awaiting review",
      className: "border-[var(--info-border)] bg-[var(--info-bg)] text-[var(--info-text)]",
    };
  if (review === "REJECTED") return { label: "Review rejected", variant: "destructive" };
  const status = usageStatusOf(server);
  if (status === "READY") return { label: "Ready", className: STATUS_SUCCESS };
  if (status === "ACKNOWLEDGED") return { label: "Trusted", className: STATUS_SUCCESS };
  if (status === "FAILED") return { label: "Failed", variant: "destructive" };
  if (status === "UNTESTED") return { label: "Not verified", className: STATUS_WARNING };
  if (server.dynamic) {
    if (server.connectionStatus === "CONNECTED") return { label: "Connected", className: STATUS_SUCCESS };
    if (server.connectionStatus === "ERROR") return { label: "Error", variant: "destructive" };
    if (server.connectionStatus === "CONNECTING") return { label: "Connecting…", className: STATUS_WARNING };
  }
  return { label: "No live status", variant: "secondary" };
}

// Row-scoped test result - a lighter variant of McpPanel's
// TestConnectionSection (same api.testMcpServer contract, same
// {unsupported:true} 404 handling), not a full reuse of that component since
// it's tied to McpPanel's own inspector layout/props.
function ServerRow({
  server,
  onTest,
  onTrust,
  onToggle,
  onApplyFix,
  testBusy,
  trustBusy,
  toggleBusy,
  fixBusy,
  pin,
  pinLoading,
  pinFailed,
}: {
  server: any;
  pin?: McpToolPinStatus;
  pinLoading?: boolean;
  pinFailed?: boolean;
  onTest: (server: any) => Promise<any>;
  onTrust: (server: any) => void;
  onToggle: (server: any) => void;
  onApplyFix: (server: any, fix: any) => void;
  testBusy: boolean;
  trustBusy: boolean;
  toggleBusy: boolean;
  fixBusy: boolean;
}) {
    const tHC = useTranslations("hcToolsServersPage");
  const [result, setResult] = useState<any>(null);

  async function runTest() {
    setResult(null);
    const r = await onTest(server);
    setResult(r);
  }

  const attention = needsAttention(server);
  const badge = statusBadge(server);
  // MCP-STDIO-SANDBOXING - DynamicMcpConnectionManager refuses connect/test
  // for a PENDING/REJECTED registration before any other guard runs (see
  // McpReviewStatus's javadoc), regardless of server.dynamic - so Test/Trust
  // would just surface that refusal here too.
  const reviewBlocked = server.reviewStatus === "PENDING" || server.reviewStatus === "REJECTED";
  // Static/built-in registrations (server.dynamic === false, e.g. puppeteer,
  // filesystem, memory - wired once in application-mcp.yml, shared by the
  // whole deployment) have no per-tenant McpDynamicConfig, so POST .../test
  // always answers "no dynamic connection config - nothing to test" -
  // DynamicMcpConnectionManager.test() line ~798. McpPanel's inspector
  // already knows this (it only renders TestConnectionSection when
  // server.dynamic) but this page's Test button rendered unconditionally -
  // fixing it here to match.
  const testable = server.dynamic && !reviewBlocked;

  // A just-run test's own detail/hint/suggestedFix supersedes whatever's
  // persisted on the registration for the same server - once a fresh result
  // exists this render, the persisted connectionError/suggestedFix (which
  // could be from a stale, already-fixed failure) steps aside for it.
  const persistedFix = !result && server.suggestedFix?.command ? server.suggestedFix : null;
  const persistedError =
    !result && server.connectionStatus === "ERROR" && (server.connectionError || server.connectionHint);
  const activeFix = result ? result.suggestedFix : persistedFix;
  const fixArgsText = Array.isArray(activeFix?.args) ? activeFix.args.join(" ") : "";

  return (
    <Card>
      <CardContent className="flex flex-col gap-3">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <span className="truncate font-semibold text-foreground">{server.name}</span>
              <Badge variant={badge.variant} className={badge.className}>
                {badge.label}
              </Badge>
              {server.dynamic && <ToolPinBadge status={pin} loading={pinLoading} failed={pinFailed} />}
            </div>
            <div className="mt-1 text-xs text-muted-foreground">
              {server.type}
              {server.dynamic ? ` · ${server.connectionStatus}` : " · Built-in"}
            </div>
            {server.description && <p className="mt-1.5 text-sm text-muted-foreground">{server.description}</p>}
          </div>
          <div className="flex shrink-0 flex-wrap gap-2">
            {testable ? (
              <Button variant="outline" size="sm" disabled={testBusy} onClick={runTest}>
                {testBusy ? (
                  <Loader2 size={13} className="animate-spin" aria-hidden="true" />
                ) : (
                  <RefreshCw size={13} aria-hidden="true" />
                )}
                {testBusy ? "Testing…" : "Test"}
              </Button>
            ) : (
              <Badge
                variant="secondary"
                className="h-8 gap-1 px-2.5"
                title={tHC("s16")}
              >
                {tHC("s15")}
              </Badge>
            )}
            {/* Trusting a server that's blocked on unreadable credentials or
             * a pending/rejected review wouldn't do anything - the review
             * gate and the decrypt failure both run ahead of usageStatus
             * gating, so ACKNOWLEDGED alone can't unblock either case. */}
            {attention && !reviewBlocked && !server.credentialsCorrupted && (
              <Button variant="outline" size="sm" disabled={trustBusy} onClick={() => onTrust(server)}>
                <ShieldCheck size={13} aria-hidden="true" />
                {trustBusy ? "Trusting…" : "Trust all actions"}
              </Button>
            )}
            {/* The disable/enable affordance the product owner reported
             * missing - "I don't see the disabled button in the enabled
             * MCP." Same PATCH /api/mcp/registry/{id} call McpPanel's own
             * toggle already uses (api.toggleMcpServer), so behavior (incl.
             * disconnecting a live dynamic connection) matches exactly. */}
            <Button
              variant={server.enabled ? "outline" : "default"}
              size="sm"
              disabled={toggleBusy}
              onClick={() => onToggle(server)}
              aria-label={server.enabled ? `Disable ${server.name}` : `Enable ${server.name}`}
            >
              {toggleBusy ? (
                <Loader2 size={13} className="animate-spin" aria-hidden="true" />
              ) : server.enabled ? (
                <PowerOff size={13} aria-hidden="true" />
              ) : (
                <Power size={13} aria-hidden="true" />
              )}
              {toggleBusy ? "Working…" : server.enabled ? "Disable" : "Enable"}
            </Button>
          </div>
        </div>

        {result && (
          <Alert variant={result.unsupported ? "default" : result.ok ? "success" : "destructive"}>
            {result.unsupported ? (
              <AlertDescription>{tHC("s14")}</AlertDescription>
            ) : (
              <>
                {result.ok ? <CheckCircle2 aria-hidden="true" /> : <AlertTriangle aria-hidden="true" />}
                <AlertTitle>
                  {server.name}: {PHASE_LABELS[result.phase] || result.phase || "Test finished"}
                  {typeof result.toolCount === "number"
                    ? ` · ${result.toolCount} tool${result.toolCount === 1 ? "" : "s"} discovered`
                    : ""}
                </AlertTitle>
                {(result.detail || result.error) && (
                  <AlertDescription>{result.detail || result.error}</AlertDescription>
                )}
                {result.hint && <AlertDescription className="font-medium">{result.hint}</AlertDescription>}
              </>
            )}
          </Alert>
        )}

        {/* Decrypt-failure visibility - distinct from "never configured":
         * the credentials ARE stored, they just can't be read anymore. */}
        {server.credentialsCorrupted && (
          <Alert variant="warning">
            <KeyRound aria-hidden="true" />
            <AlertTitle>{server.name}{tHC("s13")}</AlertTitle>
            <AlertDescription>{tHC("s12")}</AlertDescription>
          </Alert>
        )}

        {/* MCP-STDIO-SANDBOXING review-state - PENDING is expected/normal for
         * a new self-service STDIO server, not an error, so it gets the
         * neutral "info" tone rather than destructive/warning. */}
        {server.reviewStatus === "PENDING" && (
          <Alert variant="info">
            <Clock aria-hidden="true" />
            <AlertTitle>{server.name}{tHC("s11")}</AlertTitle>
            <AlertDescription>
              {tHC("s10")}
            </AlertDescription>
          </Alert>
        )}
        {server.reviewStatus === "REJECTED" && (
          <Alert variant="destructive">
            <AlertTriangle aria-hidden="true" />
            <AlertTitle>{server.name}{tHC("s9")}</AlertTitle>
            <AlertDescription>
              {server.reviewRejectionReason ||
                "No reason was given. Edit the server's command/args and re-register to request another review."}
            </AlertDescription>
          </Alert>
        )}

        {/* Persisted connection error - surfaced without needing a fresh
         * Test click, since the registration already carries it (see
         * McpServerRegistrationResponse.connectionError/connectionHint).
         * Rendered as a proper alert INSIDE this server's card, not floating
         * colored text, so it's unambiguous which server it's about. */}
        {persistedError && (
          <Alert variant="destructive">
            <AlertTriangle aria-hidden="true" />
            <AlertTitle>{server.name}{tHC("s8")}</AlertTitle>
            <AlertDescription>
              {server.connectionHint && <p className="font-medium">{server.connectionHint}</p>}
              {server.connectionError && <p>{server.connectionError}</p>}
            </AlertDescription>
          </Alert>
        )}

        {/* MCP self-heal suggested fix (McpServerRegistration.suggestedFix*,
         * migration V135) - rendered as an actionable warning alert rather
         * than a plain ERROR label, with a one-click Apply that re-registers
         * the server with the corrected command (POST /api/mcp/registry is
         * an upsert-by-name - the same path McpPanel's own applySuggestedFix
         * uses; there's no dedicated apply-fix endpoint yet). */}
        {activeFix?.command && (
          <Alert variant="warning">
            <Wrench aria-hidden="true" />
            <AlertTitle>{tHC("s7")} {server.name}</AlertTitle>
            <AlertDescription>
              {activeFix.reason && <p>{activeFix.reason}</p>}
              <code className="block w-full break-words rounded bg-black/5 px-2 py-1 font-mono text-xs dark:bg-white/10">
                {activeFix.command}
                {fixArgsText ? ` ${fixArgsText}` : ""}
              </code>
              <Button size="sm" className="mt-1 w-fit" disabled={fixBusy} onClick={() => onApplyFix(server, activeFix)}>
                {fixBusy ? "Applying fix…" : "Apply suggested fix"}
              </Button>
              <p className="text-xs text-muted-foreground">
                {tHC("s6")}
              </p>
            </AlertDescription>
          </Alert>
        )}
      </CardContent>
    </Card>
  );
}

export default function McpServersPage() {
    const tHC = useTranslations("hcToolsServersPage");
  const [servers, setServers] = useState<any[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [filter, setFilter] = useState("");
  const tx = useTranslations("channelsUsageMcp");
  const [testBusyId, setTestBusyId] = useState<string | null>(null);
  const [trustBusyId, setTrustBusyId] = useState<string | null>(null);
  const [toggleBusyId, setToggleBusyId] = useState<string | null>(null);
  const [fixBusyId, setFixBusyId] = useState<string | null>(null);
  const [bulkBusy, setBulkBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const refresh = useCallback(() => {
    api
      .listMcpServers()
      .then((list) => {
        setServers(list);
        setLoadFailed(false);
      })
      .catch(() => setLoadFailed(true))
      .finally(() => setLoaded(true));
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);
  usePageReady(loaded);

  const needle = filter.trim().toLowerCase();
  const enabledServers = servers.filter(
    (s) => s.enabled && (!needle || `${s.name} ${s.description ?? ""} ${s.type ?? ""}`.toLowerCase().includes(needle)),
  );
  const attentionServers = enabledServers.filter(needsAttention);
  const serversPagination = usePagination(enabledServers);
  const pinIds = serversPagination.pageItems.filter((s) => s.dynamic).map((s) => s.id);
  const pins = useToolPinStatuses(pinIds, servers);

  async function handleTest(server: any) {
    setTestBusyId(server.id);
    setError(null);
    try {
      const r = await api.testMcpServer(server.id);
      if (!r?.unsupported) refresh();
      return r;
    } catch (err: any) {
      const message = err?.message || "Connection test failed.";
      setError(message);
      return { ok: false, error: message };
    } finally {
      setTestBusyId(null);
    }
  }

  async function handleTrust(server: any) {
    setTrustBusyId(server.id);
    setError(null);
    setSuccess(null);
    try {
      const r = await api.acknowledgeMcpServer(server.id);
      if (r?.unsupported) {
        setError("Trusting a server isn't available on this deployment yet.");
        return;
      }
      setSuccess(`${server.name} marked as trusted — agents can use it now.`);
      refresh();
    } catch (err: any) {
      setError(err?.message || "Couldn't mark the server as trusted.");
    } finally {
      setTrustBusyId(null);
    }
  }

  async function handleTrustAll() {
    if (attentionServers.length === 0 || bulkBusy) return;
    setBulkBusy(true);
    setError(null);
    setSuccess(null);
    try {
      const results = await Promise.allSettled(attentionServers.map((s) => api.acknowledgeMcpServer(s.id)));
      const okCount = results.filter((r) => r.status === "fulfilled" && !(r.value as any)?.unsupported).length;
      const failedCount = results.length - okCount;
      if (okCount > 0) setSuccess(`Trusted ${okCount} server${okCount === 1 ? "" : "s"}.`);
      if (failedCount > 0) {
        setError(
          `${failedCount} server${failedCount === 1 ? "" : "s"} couldn't be trusted — try again or check individually.`
        );
      }
      refresh();
    } finally {
      setBulkBusy(false);
    }
  }

  // Reuses api.toggleMcpServer - the exact PATCH /api/mcp/registry/{id}
  // {enabled} call McpPanel's grid toggle already exercises (which, on the
  // backend, also disconnects a live dynamic connection when disabling - see
  // McpRegistryController#toggle). This is the fix for "I don't see the
  // disabled button in the enabled MCP": previously this page had Test and
  // Trust only, no way to turn a server back off without leaving for /tools.
  async function handleToggle(server: any) {
    if (toggleBusyId === server.id) return;
    setToggleBusyId(server.id);
    setError(null);
    setSuccess(null);
    const nextEnabled = !server.enabled;
    try {
      await api.toggleMcpServer(server.id, nextEnabled);
      setSuccess(`${server.name} ${nextEnabled ? "enabled" : "disabled"}.`);
      refresh();
    } catch (err: any) {
      setError(err?.message || `Couldn't ${nextEnabled ? "enable" : "disable"} the server.`);
    } finally {
      setToggleBusyId(null);
    }
  }

  // Applies a self-heal suggested fix by re-registering the server with the
  // corrected command/args (POST /api/mcp/registry upserts by name - see
  // McpRegistryController#register). Mirrors McpPanel.applySuggestedFix's
  // payload shape exactly; there's no dedicated "apply fix" endpoint yet, so
  // this goes through the same update path the manual edit flow uses.
  async function handleApplyFix(server: any, fix: any) {
    if (!fix?.command?.trim() || fixBusyId) return;
    setFixBusyId(server.id);
    setError(null);
    setSuccess(null);
    try {
      const payload: any = {
        name: server.name,
        type: server.type,
        description: server.description,
        enabled: true,
        command: fix.command,
        args: Array.isArray(fix.args) ? fix.args : [],
      };
      if (server.url) payload.url = server.url;
      await api.registerMcpServer(payload);
      setSuccess(`Applied the suggested fix for ${server.name} — reconnecting now.`);
      refresh();
    } catch (err: any) {
      setError(err?.message || "Couldn't apply the suggested fix.");
    } finally {
      setFixBusyId(null);
    }
  }

  return (
    <AppShell eyebrow="Tools & MCP">
      <PageHeader
        title={tHC("s5")}
        subtitle="Every MCP server enabled for this workspace in one place — test a connection, trust a server's actions, or disable it, without opening the full catalog."
        actions={
          <div className="flex items-center gap-2">
            <Button variant="ghost" size="sm" asChild>
              <Link href="/help/docs#tools" target="_blank" rel="noopener noreferrer">
                <HelpCircle size={13} aria-hidden="true" /> {tHC("s4")}
              </Link>
            </Button>
            <Button variant="outline" size="sm" asChild>
              <Link href="/tools/credentials">
                <Lock size={13} aria-hidden="true" /> {tHC("s3")}
              </Link>
            </Button>
            <Button variant="outline" size="sm" asChild>
              <Link href="/tools">
                <ArrowLeft size={13} aria-hidden="true" /> {tHC("s2")}
              </Link>
            </Button>
            <Button size="sm" disabled={bulkBusy || attentionServers.length === 0} onClick={handleTrustAll}>
              <ShieldCheck size={14} aria-hidden="true" />
              {bulkBusy
                ? "Trusting…"
                : `Trust all${attentionServers.length > 0 ? ` (${attentionServers.length})` : ""}`}
            </Button>
          </div>
        }
      />

      <ToolPinReview onChanged={refresh} />
      <ToolPinSummary statuses={pins.statuses} />

      {error && (
        <Alert variant="destructive" className="mb-4">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{tHC("s1")}</AlertTitle>
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {success && (
        <Alert variant="success" className="mb-4">
          <CheckCircle2 aria-hidden="true" />
          <AlertTitle>{success}</AlertTitle>
        </Alert>
      )}

      {!loaded && (
        <div className="flex flex-col gap-3" data-testid="mcp-servers-skeleton">
          <Skeleton className="h-24 w-full rounded-xl" />
          <Skeleton className="h-24 w-full rounded-xl" />
        </div>
      )}

      {loaded && loadFailed && (
        <Alert variant="destructive" className="mb-4">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{tx("serversLoadErrorTitle")}</AlertTitle>
          <AlertDescription>
            <p>{tx("serversLoadErrorBody")}</p>
            <Button variant="outline" size="sm" className="mt-2" onClick={refresh}>
              {tx("retry")}
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {loaded && !loadFailed && servers.length > 10 && (
        <div className="mb-3 max-w-sm">
          <Input
            type="search"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
            placeholder={tx("serversFilterPlaceholder")}
            aria-label={tx("serversFilterPlaceholder")}
          />
        </div>
      )}

      {loaded && !loadFailed && enabledServers.length === 0 ? (
        <Card>
          <CardContent className="py-8 text-center text-sm text-muted-foreground">
            {needle ? tx("serversFilterNoMatch") : "No enabled MCP servers yet — enable one from the Tools page first."}
          </CardContent>
        </Card>
      ) : loaded && !loadFailed ? (
        <div className="flex flex-col gap-3">
          {serversPagination.pageItems.map((server) => (
            <ServerRow
              key={server.id}
              server={server}
              onTest={handleTest}
              onTrust={handleTrust}
              onToggle={handleToggle}
              onApplyFix={handleApplyFix}
              testBusy={testBusyId === server.id}
              trustBusy={trustBusyId === server.id}
              toggleBusy={toggleBusyId === server.id}
              fixBusy={fixBusyId === server.id}
              pin={pins.statuses[server.id]}
              pinLoading={pins.loading}
              pinFailed={pins.failed[server.id]}
            />
          ))}
          <PaginationControls
            page={serversPagination.page}
            totalPages={serversPagination.totalPages}
            totalItems={serversPagination.totalItems}
            pageSize={serversPagination.pageSize}
            onPageChange={serversPagination.setPage}
            onPageSizeChange={serversPagination.setPageSize}
            itemLabel="servers"
          />
        </div>
      ) : null}
    </AppShell>
  );
}
