"use client";

import { useState } from "react";
import { Wrench, Loader2, CheckCircle2, AlertTriangle, ShieldCheck, RefreshCw } from "lucide-react";
import { api } from "../../lib/api";
import { Button } from "../ui/button";
import { Alert, AlertTitle, AlertDescription } from "../ui/alert";
import { PHASE_LABELS, splitConnectionError, usageStatusOf } from "./helpers";
import { HintBox } from "./parts";
import { useTranslations } from "../../lib/i18n";

/* eslint-disable @typescript-eslint/no-explicit-any */
// ---------------------------------------------------------------------------
// Enable/diagnose UX (MCP-UX task, 2026-07-25). Strings in this block are
// deliberately inline English rather than en.json keys: en.json is a fragile
// mid-migration file that must not be bulk-edited (see MIGRATION-NOTES.md /
// the ~230-key migration gap) - these join that queue instead of risking it.
//
// Everything here is defensive against an OLDER core: the phased-test
// endpoint, the acknowledge endpoint, and the usageStatus field are all being
// built in parallel by the core agent. A 404 (endpoint) or missing field
// (DTO) hides the corresponding affordance instead of erroring, so this UI
// ships safely ahead of the backend.
// ---------------------------------------------------------------------------

// Whether the running core supports the new endpoints - null until first
// probed, then remembered for the rest of the page load (module scope, so a
// card switch or panel remount doesn't re-offer a button the core already
// 404'd on).
let testEndpointSupported: boolean | null = null;
let ackEndpointSupported: boolean | null = null;
// Enriched last-error rendering: hint (if any) emphasized on its own line,
// raw multi-line detail collapsed behind a disclosure so the panel stops
// reading like a stack-trace dump. Short single-line details still render
// directly - a disclosure hiding twelve words would just be friction.
export function ConnectionErrorBlock({ server, label }: { server: any; label: string }) {
    const tHC = useTranslations("hcComponentsMcpPanelConnectionParts");
  const { detail, hint } = splitConnectionError(server);
  if (!detail && !hint) return null;
  const shortDetail = detail && detail.length <= 110 && !detail.includes("\n");
  return (
    <div className="mt-3 space-y-2">
      <div className="text-[0.7rem] font-semibold uppercase tracking-wide text-muted-foreground">{label}</div>
      {hint && <HintBox hint={hint} />}
      {detail && shortDetail && <p className="text-sm text-destructive">{detail}</p>}
      {detail && !shortDetail && (
        <details className="text-xs">
          <summary className="cursor-pointer text-muted-foreground">{tHC("hcComponentsMcpPanelConnectionParts.s4")}</summary>
          {/* Real code-block surface (background + border), not just red text
           * floating on the panel background - the thing the product owner
           * flagged as "reads like a raw stack trace" was this block having
           * no visual container of its own to separate it from prose. */}
          <pre className="mt-2 whitespace-pre-wrap break-words rounded-lg border border-destructive/25 bg-destructive/10 px-3 py-2 font-mono text-xs leading-relaxed text-destructive">
            {detail}
          </pre>
        </details>
      )}
    </div>
  );
}

// suggestedFix support (MCP-UX follow-up, 2026-07-26). A separate backend
// session is concurrently adding an optional `suggestedFix: {command, args,
// reason}` alongside a failed connection's error - read defensively from
// either plausible location (server.suggestedFix on the registration, or
// result.suggestedFix on a fresh testMcpServer() response) since the exact
// shape wasn't finalized when this shipped. Renders nothing when absent, so
// this degrades cleanly against an older core / a core that never lands the
// field on this exact response shape.
export function SuggestedFixBlock({ fix, onApply, busy }: { fix: any; onApply: any; busy: boolean }) {
    const tHC = useTranslations("hcComponentsMcpPanelConnectionParts");
  if (!fix || typeof fix.command !== "string" || !fix.command.trim()) return null;
  const argsText = Array.isArray(fix.args) ? fix.args.join(" ") : "";
  return (
    <Alert variant="warning" className="mt-3">
      <Wrench aria-hidden="true" />
      <AlertTitle>{tHC("hcComponentsMcpPanelConnectionParts.s3")}</AlertTitle>
      <AlertDescription>
        {fix.reason && <p>{fix.reason}</p>}
        <code className="block w-full break-words rounded bg-black/5 px-2 py-1 font-mono text-xs dark:bg-white/10">
          {fix.command}
          {argsText ? ` ${argsText}` : ""}
        </code>
        <Button type="button" size="sm" className="mt-1 w-fit" disabled={busy} onClick={onApply}>
          {busy ? "Applying fix…" : "Apply suggested fix"}
        </Button>
        <p className="text-xs text-muted-foreground">
          {tHC("hcComponentsMcpPanelConnectionParts.s2")}
        </p>
      </AlertDescription>
    </Alert>
  );
}

// "Test connection" - runs the phased diagnostic against a dynamic
// registration and shows ✓/✗ + which phase it reached + detail + hint.
// If the endpoint 404s (older core) the button removes itself for the rest
// of the page load and the panel falls back to the pre-existing reconnect
// button alone.
export function TestConnectionSection({
  server,
  onRefresh,
  onApplySuggestedFix,
  fixBusy,
}: {
  server: any;
  onRefresh: any;
  onApplySuggestedFix?: any;
  fixBusy?: boolean;
}) {
  const [testing, setTesting] = useState(false);
  const [result, setResult] = useState<any>(null);
  const [testError, setTestError] = useState<string | null>(null);
  const [supported, setSupported] = useState<boolean | null>(testEndpointSupported);

  if (supported === false) return null;

  async function runTest() {
    if (testing) return;
    setTesting(true);
    setResult(null);
    setTestError(null);
    try {
      const r = await api.testMcpServer(server.id);
      if (r?.unsupported) {
        testEndpointSupported = false;
        setSupported(false);
        return;
      }
      testEndpointSupported = true;
      setSupported(true);
      setResult(r);
      // The server persisted the same outcome onto the registration
      // (status/last-error/usageStatus) - refresh so the panel agrees.
      onRefresh();
    } catch (err: any) {
      testEndpointSupported = true; // reached the endpoint; the test itself failed
      setTestError(err?.message || "Connection test failed.");
    } finally {
      setTesting(false);
    }
  }

  const toolCountSuffix =
    result && typeof result.toolCount === "number" && result.toolCount !== null
      ? ` · ${result.toolCount} tool${result.toolCount === 1 ? "" : "s"} discovered`
      : "";

  return (
    <div className="mt-3">
      <Button type="button" variant="outline" size="sm" className="w-full" disabled={testing} onClick={runTest}>
        {testing ? (
          <Loader2 size={13} className="animate-spin" aria-hidden="true" />
        ) : (
          <RefreshCw size={13} aria-hidden="true" />
        )}
        {testing ? "Testing connection…" : "Test connection"}
      </Button>
      {result && (
        <div className="mt-3 space-y-3">
          <Alert variant={result.ok ? "success" : "destructive"}>
            {result.ok ? <CheckCircle2 aria-hidden="true" /> : <AlertTriangle aria-hidden="true" />}
            <AlertTitle>
              {PHASE_LABELS[result.phase] || result.phase || "Test finished"}
              {toolCountSuffix}
            </AlertTitle>
            {result.detail && <AlertDescription>{result.detail}</AlertDescription>}
            {result.hint && <AlertDescription className="font-medium">{result.hint}</AlertDescription>}
          </Alert>
          {onApplySuggestedFix && (
            <SuggestedFixBlock
              fix={result.suggestedFix}
              onApply={() => onApplySuggestedFix(server, result.suggestedFix)}
              busy={Boolean(fixBusy)}
            />
          )}
        </div>
      )}
      {testError && (
        <Alert variant="destructive" className="mt-3">
          <AlertTriangle aria-hidden="true" />
          <AlertDescription>{testError}</AlertDescription>
        </Alert>
      )}
    </div>
  );
}

// "Use anyway (I trust this server)" - a deliberate override for an
// UNTESTED/FAILED server, only rendered when the running core actually
// gates agents on usageStatus (field present) and only until a 404 proves
// the acknowledge endpoint isn't there yet.
export function AcknowledgeControl({ server, onRefresh }: { server: any; onRefresh: any }) {
    const tHC = useTranslations("hcComponentsMcpPanelConnectionParts");
  const [busy, setBusy] = useState(false);
  const [ackError, setAckError] = useState<string | null>(null);
  const [supported, setSupported] = useState<boolean | null>(ackEndpointSupported);
  const status = usageStatusOf(server);

  if (supported === false || !status || (status !== "UNTESTED" && status !== "FAILED")) return null;

  async function acknowledge() {
    if (busy) return;
    setBusy(true);
    setAckError(null);
    try {
      const r = await api.acknowledgeMcpServer(server.id);
      if (r?.unsupported) {
        ackEndpointSupported = false;
        setSupported(false);
        return;
      }
      ackEndpointSupported = true;
      onRefresh();
    } catch (err: any) {
      ackEndpointSupported = true;
      setAckError(err?.message || "Couldn't mark the server as trusted.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="mt-3">
      <Button
        type="button"
        variant="outline"
        size="sm"
        className="w-full text-muted-foreground"
        disabled={busy}
        onClick={acknowledge}
      >
        <ShieldCheck size={13} aria-hidden="true" />
        {busy ? "Marking as trusted…" : "Use anyway (I trust this server)"}
      </Button>
      <p className="mt-1.5 text-xs text-muted-foreground">
        {tHC("hcComponentsMcpPanelConnectionParts.s1")}
      </p>
      {ackError && <p className="mt-1.5 text-xs text-destructive">{ackError}</p>}
    </div>
  );
}
