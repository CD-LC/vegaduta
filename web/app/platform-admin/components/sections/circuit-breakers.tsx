"use client";

import { useCallback, useEffect, useState } from "react";
import { CircuitBoard, RefreshCw } from "lucide-react";
import { rateControlApi } from "../../lib/adminApi/rate-control";
import { toast } from "../../lib/toastBus";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Alert, AlertDescription } from "../ui/alert";
import { Skeleton } from "../ui/skeleton";
import { SectionCard, InfoNote } from "./common";
import { useConfirm } from "../admin/ConfirmDialog";
import { useTranslations } from "../../../lib/i18n";

// Tool circuit breaker (ToolCircuitBreakerService): per (tenant, tool) it trips
// after N consecutive failures and short-circuits that tool for a cooldown.
// State is read live from Redis. The one control is Reset, which deletes the
// open marker - effective on the tool's very next call (no cache), and safe:
// a still-broken tool simply trips again. Thresholds are boot-time config, so
// they are shown read-only rather than as editable fields.
export function ToolCircuitBreakersSection({ delay }: { delay?: number }) {
  const tHC = useTranslations("hcPlatformadminComponentsSectionsCircuitbreakers");
  const askConfirm = useConfirm();
  const [data, setData] = useState<any>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setData(await rateControlApi.getToolCircuitBreakers());
      setError(null);
    } catch (err: any) {
      setData(null);
      setError(err?.message || "Could not load circuit-breaker state.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function reset(tenantId: string, tool: string) {
    if (
      !(await askConfirm(
        `Reset the circuit breaker for "${tool}" (tenant ${tenantId})? Calls to it resume immediately.`
      ))
    )
      return;
    const id = `${tenantId}:${tool}`;
    setBusy(id);
    try {
      await rateControlApi.resetToolCircuitBreaker(tenantId, tool);
      toast.success("Breaker reset - the tool is callable again on its next call.");
      await load();
    } catch {
      // toJson already toasted the reason.
    } finally {
      setBusy(null);
    }
  }

  const cfg = data?.config;
  const entries: any[] = data?.entries || [];

  return (
    <SectionCard
      title={tHC("s8")}
      subtitle="Live state per tenant and tool - open circuits and failure streaks in progress"
      icon={CircuitBoard}
      delay={delay}
      actions={
        <Button size="sm" variant="outline" onClick={() => void load()} disabled={loading}>
          <RefreshCw size={13} className={loading ? "animate-spin" : ""} /> Refresh
        </Button>
      }
    >
      {error && (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      {loading && !data && <Skeleton className="h-16 w-full" />}
      {cfg && (
        <p className="text-xs text-muted-foreground">
          {tHC("s7")} <b>{cfg.failureThreshold}</b> {tHC("s6")} <b>{cfg.windowMinutes} min</b>
          {tHC("s5")} <b>{cfg.cooldownMinutes} min</b>
          {tHC("s4")}
        </p>
      )}
      {data && entries.length === 0 && <p className="text-sm text-muted-foreground">{tHC("s3")}</p>}
      {entries.map((e) => {
        const id = `${e.tenantId}:${e.tool}`;
        return (
          <div
            key={id}
            className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border p-3"
          >
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2 text-sm font-semibold">
                <code className="font-mono">{e.tool}</code>
                {e.open ? <Badge variant="destructive">Open</Badge> : <Badge variant="secondary">Failing</Badge>}
              </div>
              <p className="text-xs text-muted-foreground">
                tenant <code>{e.tenantId}</code>
                {e.open
                  ? ` - blocked for another ${Math.max(0, Math.round((e.cooldownSecondsRemaining || 0) / 60))} min`
                  : ` - ${e.consecutiveFailures} consecutive failure${e.consecutiveFailures === 1 ? "" : "s"} so far`}
              </p>
              {e.open && e.lastError && (
                <p className="mt-1 truncate text-xs text-muted-foreground" title={e.lastError}>
                  {tHC("s2")} <code>{e.lastError}</code>
                </p>
              )}
            </div>
            <Button size="sm" variant="outline" disabled={busy === id} onClick={() => void reset(e.tenantId, e.tool)}>
              {busy === id ? "Resetting…" : "Reset"}
            </Button>
          </div>
        );
      })}
      {data?.truncated && <InfoNote>{tHC("s1")}</InfoNote>}
    </SectionCard>
  );
}
