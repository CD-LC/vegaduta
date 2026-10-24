"use client";

import { useCallback, useEffect, useMemo, useState, type MouseEvent } from "react";
import { AlertTriangle, BellRing, HeartPulse, Loader2, ShieldCheck } from "lucide-react";
import { apiFetch, jsonBody, toJsonQuietOn } from "../lib/api/core";
import { useTranslations } from "../lib/i18n";
import { useAuth } from "./AuthProvider";
import { Button } from "./ui/button";
import { Badge } from "./ui/badge";
import { Input } from "./ui/input";
import { Label } from "./ui/label";
import { Switch } from "./ui/switch";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "./ui/card";
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
import PaginationControls from "./PaginationControls";
import { usePagination } from "../lib/usePagination";
import { cn } from "../lib/utils";

// Mobile Sentinels - console-side controls for the on-device health &
// wellbeing monitors in the Android/iOS apps. Wire contract:
// docs/plans/MOBILE-SENTINELS-CONTRACT.md (v1, 2026-09-29).
//
// Privacy posture mirrors the contract: the admin aggregate carries counts
// only (never titles/summaries/user ids), and "My synced events" is strictly
// the signed-in user's own data. Every sentinel endpoint may be absent on a
// server that predates the feature, so 403/404/405/501 on the first policy
// read collapses the whole panel into a quiet "not available yet" state
// rather than an error wall (toJsonQuietOn keeps those off the global toast).

const SENTINEL_KINDS = [
  "HEART_RATE",
  "RESTING_HEART_RATE",
  "HRV",
  "SPO2",
  "STEPS",
  "SLEEP",
  "BLOOD_PRESSURE",
  "BLOOD_GLUCOSE",
  "BODY_TEMPERATURE",
  "WEIGHT",
  "RESPIRATORY_RATE",
  "SYMPTOM",
  "MOOD",
  "MEDICATION",
  "DEVICE",
] as const;

const SEVERITIES = ["INFO", "WATCH", "ALERT", "CRITICAL"] as const;
type Severity = (typeof SEVERITIES)[number];

// Semantic state tokens (globals.css --state-*), rendered via Badge's
// "state" variant. CRITICAL uses the solid destructive badge so it never
// reads as just another tinted pill.
const SEVERITY_STATE: Record<Exclude<Severity, "CRITICAL">, string> = {
  INFO: "var(--state-idle)",
  WATCH: "var(--state-warning)",
  ALERT: "var(--state-error)",
};

const QUIET_STATUSES = [403, 404, 405, 501];
const quietJson = toJsonQuietOn(QUIET_STATUSES);

// "My synced events" is fetched in one shot with this hard cap; the pager
// slices it client-side and flags the cap via PaginationControls' `capped`.
const EVENTS_LIMIT = 50;

const RETENTION_MIN = 1;
const RETENTION_MAX = 3650;

type SentinelPolicy = {
  enabled: boolean;
  syncEnabled: boolean;
  allowRawReadings: boolean;
  allowedKinds: string[];
  caregiverEscalationEnabled: boolean;
  retentionDays: number;
  updatedBy?: string | null;
  updatedAt?: string | null;
};

type SentinelSummary = {
  windowDays?: number;
  activeUsers?: number;
  events?: number;
  bySeverity?: Partial<Record<Severity, number>>;
  escalations?: number;
};

type SentinelEvent = {
  id?: string | number;
  clientEventId?: string;
  sentinelId?: string;
  sentinelName?: string;
  severity?: string;
  title?: string;
  summary?: string;
  kinds?: string[];
  occurredAt?: string;
  receivedAt?: string;
  escalatedAt?: string | null;
  escalatedTo?: string | null;
};

const POLICY_TOGGLES = [
  { field: "enabled", labelKey: "policyEnabledLabel", hintKey: "policyEnabledHint" },
  { field: "syncEnabled", labelKey: "policySyncLabel", hintKey: "policySyncHint" },
  { field: "allowRawReadings", labelKey: "policyRawLabel", hintKey: "policyRawHint" },
  { field: "caregiverEscalationEnabled", labelKey: "policyCaregiverLabel", hintKey: "policyCaregiverHint" },
] as const;

type ApiError = { message?: string; status?: number; isBackendUnreachable?: boolean };
const asApiError = (e: unknown): ApiError => (e && typeof e === "object" ? (e as ApiError) : {});

// eslint-disable-next-line @typescript-eslint/no-explicit-any -- wire JSON, shape checked field by field
function normalizePolicy(raw: any): SentinelPolicy {
  return {
    enabled: raw?.enabled ?? true,
    syncEnabled: !!raw?.syncEnabled,
    allowRawReadings: !!raw?.allowRawReadings,
    allowedKinds: Array.isArray(raw?.allowedKinds) ? raw.allowedKinds : [...SENTINEL_KINDS],
    caregiverEscalationEnabled: !!raw?.caregiverEscalationEnabled,
    retentionDays: typeof raw?.retentionDays === "number" ? raw.retentionDays : 30,
    updatedBy: raw?.updatedBy ?? null,
    updatedAt: raw?.updatedAt ?? null,
  };
}

function formatTime(iso?: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
}

function SeverityBadge({ severity, label }: { severity: string; label: string }) {
  if (severity === "CRITICAL") {
    return (
      <Badge variant="destructive" className="uppercase tracking-wide">
        {label}
      </Badge>
    );
  }
  const state = SEVERITY_STATE[severity as keyof typeof SEVERITY_STATE] || "var(--state-idle)";
  return (
    <Badge variant="state" state={state} className="uppercase tracking-wide">
      {label}
    </Badge>
  );
}

function StatTile({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="rounded-xl border border-border bg-muted/40 p-3">
      <div className="font-mono text-[10px] uppercase tracking-wide text-muted-foreground">{label}</div>
      <div className="mt-1 text-xl font-semibold tabular-nums text-foreground">{value}</div>
    </div>
  );
}

export default function SentinelsPanel() {
  const t = useTranslations("sentinelsPanel");
  const { isAdmin, isPlatformAdmin } = useAuth() as { isAdmin?: boolean; isPlatformAdmin?: boolean };
  const canAdmin = !!(isAdmin || isPlatformAdmin);

  // "loading" -> "ready" | "unavailable" (endpoint missing / not deployed) |
  // "offline" (network failure). Only the first policy read decides this.
  const [availability, setAvailability] = useState<"loading" | "ready" | "unavailable" | "offline">("loading");
  const [policy, setPolicy] = useState<SentinelPolicy | null>(null);
  const [draft, setDraft] = useState<SentinelPolicy | null>(null);
  const [savingPolicy, setSavingPolicy] = useState(false);
  const [policyError, setPolicyError] = useState<string | null>(null);
  const [policySaved, setPolicySaved] = useState(false);

  const [summary, setSummary] = useState<SentinelSummary | null>(null);
  const [summaryUnavailable, setSummaryUnavailable] = useState(false);

  const [events, setEvents] = useState<SentinelEvent[]>([]);
  const [eventsLoaded, setEventsLoaded] = useState(false);
  const [eventsError, setEventsError] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Record<string, boolean>>({});
  // Client-side paging over the synced-events list - the whole (capped)
  // array is already in memory, so this just slices it for display.
  const eventsPg = usePagination(events);

  const [confirmErase, setConfirmErase] = useState(false);
  const [erasing, setErasing] = useState(false);
  const [eraseResult, setEraseResult] = useState<string | null>(null);

  const loadEvents = useCallback(async () => {
    setEventsError(null);
    try {
      const body = await apiFetch(`/api/mobile/sentinels/events?limit=${EVENTS_LIMIT}`).then(
        toJsonQuietOn<{ events?: SentinelEvent[] } | null>(QUIET_STATUSES)
      );
      setEvents(Array.isArray(body?.events) ? body.events : []);
    } catch (e: unknown) {
      const err = asApiError(e);
      if (QUIET_STATUSES.includes(err.status ?? 0)) setEvents([]);
      else setEventsError(err.message || t("eventsLoadError"));
    } finally {
      setEventsLoaded(true);
    }
  }, [t]);

  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const raw = await apiFetch(`/api/mobile/sentinels/policy`).then(quietJson);
        if (cancelled) return;
        const p = normalizePolicy(raw);
        setPolicy(p);
        setDraft(p);
        setAvailability("ready");
      } catch (e: unknown) {
        const err = asApiError(e);
        if (cancelled) return;
        setAvailability(err.isBackendUnreachable ? "offline" : "unavailable");
        return;
      }
      void loadEvents();
      if (canAdmin) {
        try {
          const s = await apiFetch(`/api/mobile/sentinels/admin/summary`).then(quietJson);
          if (!cancelled) setSummary(s || {});
        } catch {
          if (!cancelled) setSummaryUnavailable(true);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [canAdmin, loadEvents]);

  const dirty = useMemo(() => {
    if (!policy || !draft) return false;
    const sameKinds =
      policy.allowedKinds.length === draft.allowedKinds.length &&
      policy.allowedKinds.every((k) => draft.allowedKinds.includes(k));
    return (
      !sameKinds ||
      policy.enabled !== draft.enabled ||
      policy.syncEnabled !== draft.syncEnabled ||
      policy.allowRawReadings !== draft.allowRawReadings ||
      policy.caregiverEscalationEnabled !== draft.caregiverEscalationEnabled ||
      policy.retentionDays !== draft.retentionDays
    );
  }, [policy, draft]);

  const retentionValid =
    !!draft &&
    Number.isInteger(draft.retentionDays) &&
    draft.retentionDays >= RETENTION_MIN &&
    draft.retentionDays <= RETENTION_MAX;

  const updateDraft = (patch: Partial<SentinelPolicy>) => {
    setPolicySaved(false);
    setDraft((d) => (d ? { ...d, ...patch } : d));
  };

  const toggleKind = (kind: string) => {
    if (!draft) return;
    const has = draft.allowedKinds.includes(kind);
    updateDraft({
      allowedKinds: has
        ? draft.allowedKinds.filter((k) => k !== kind)
        : SENTINEL_KINDS.filter((k) => k === kind || draft.allowedKinds.includes(k)),
    });
  };

  const handleSavePolicy = async () => {
    if (!draft || !retentionValid) return;
    setSavingPolicy(true);
    setPolicyError(null);
    setPolicySaved(false);
    try {
      const body = {
        enabled: draft.enabled,
        syncEnabled: draft.syncEnabled,
        allowRawReadings: draft.allowRawReadings,
        allowedKinds: draft.allowedKinds,
        caregiverEscalationEnabled: draft.caregiverEscalationEnabled,
        retentionDays: draft.retentionDays,
      };
      const saved = await apiFetch(`/api/mobile/sentinels/policy`, { method: "PUT", ...jsonBody(body) }).then(
        quietJson
      );
      const p = normalizePolicy(saved || body);
      setPolicy(p);
      setDraft(p);
      setPolicySaved(true);
    } catch (e: unknown) {
      const err = asApiError(e);
      setPolicyError(err.status === 403 ? t("policyForbidden") : err.message || t("policySaveError"));
    } finally {
      setSavingPolicy(false);
    }
  };

  const handleErase = async () => {
    setErasing(true);
    setEraseResult(null);
    try {
      const body = await apiFetch(`/api/mobile/sentinels/events`, { method: "DELETE" }).then(
        toJsonQuietOn<{ deleted?: number } | null>(QUIET_STATUSES)
      );
      setEraseResult(t("eraseDone", { count: typeof body?.deleted === "number" ? body.deleted : 0 }));
      setEvents([]);
      setConfirmErase(false);
    } catch (e: unknown) {
      const err = asApiError(e);
      setEraseResult(err.message || t("eraseError"));
      setConfirmErase(false);
    } finally {
      setErasing(false);
    }
  };

  const kindLabel = (kind: string) => t(`kinds.${kind}`);
  const severityLabel = (sev: string) =>
    (SEVERITIES as readonly string[]).includes(sev) ? t(`severity.${sev}`) : sev || "-";

  const readOnly = !canAdmin;
  const showDataLeavesWarning = !!draft && (draft.allowRawReadings || draft.caregiverEscalationEnabled);

  return (
    <section aria-labelledby="sentinels-heading" className="mt-6 space-y-4 border-t border-border pt-6">
      <div className="space-y-1.5">
        <h3 id="sentinels-heading" className="flex items-center gap-2 text-base font-semibold text-foreground">
          <HeartPulse className="size-4 text-primary" aria-hidden="true" />
          {t("heading")}
        </h3>
        <p className="text-sm leading-relaxed text-muted-foreground">{t("intro")}</p>
        <p className="flex items-center gap-1.5 text-xs font-medium text-muted-foreground">
          <ShieldCheck className="size-3.5" aria-hidden="true" />
          {t("notMedicalDevice")}
        </p>
      </div>

      {availability === "loading" && (
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          {t("loading")}
        </div>
      )}

      {(availability === "unavailable" || availability === "offline") && (
        <div className="rounded-xl border border-dashed border-border bg-muted/30 p-4 text-sm text-muted-foreground">
          {availability === "offline" ? t("offline") : t("unavailable")}
        </div>
      )}

      {availability === "ready" && draft && (
        <>
          {/* Policy */}
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">{t("policyTitle")}</CardTitle>
              <CardDescription>{readOnly ? t("policyReadOnlyHint") : t("policyAdminHint")}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-4">
              <div className="space-y-3">
                {POLICY_TOGGLES.map(({ field, labelKey, hintKey }) => {
                  const id = `sentinel-policy-${field}`;
                  return (
                    <div key={field} className="flex items-start justify-between gap-4">
                      <div className="min-w-0">
                        <Label htmlFor={id} className="text-sm font-medium text-foreground">
                          {t(labelKey)}
                        </Label>
                        <p className="text-xs text-muted-foreground">{t(hintKey)}</p>
                      </div>
                      <Switch
                        id={id}
                        aria-label={t(labelKey)}
                        checked={draft[field]}
                        disabled={readOnly || savingPolicy}
                        onCheckedChange={(v) => updateDraft({ [field]: v } as Partial<SentinelPolicy>)}
                        className="mt-0.5"
                      />
                    </div>
                  );
                })}
              </div>

              {showDataLeavesWarning && (
                <Alert>
                  <AlertTriangle className="size-4" aria-hidden="true" />
                  <AlertDescription>{t("dataLeavesWarning")}</AlertDescription>
                </Alert>
              )}

              <div className="space-y-2">
                <div className="text-sm font-medium text-foreground">{t("allowedKindsLabel")}</div>
                <p className="text-xs text-muted-foreground">{t("allowedKindsHint")}</p>
                <div className="flex flex-wrap gap-1.5" role="group" aria-label={t("allowedKindsLabel")}>
                  {SENTINEL_KINDS.map((kind) => {
                    const on = draft.allowedKinds.includes(kind);
                    return (
                      <button
                        key={kind}
                        type="button"
                        aria-pressed={on}
                        disabled={readOnly || savingPolicy}
                        onClick={() => toggleKind(kind)}
                        className={cn(
                          "rounded-full border px-2.5 py-0.5 text-xs font-medium transition-colors",
                          "focus-visible:outline-none focus-visible:ring-[3px] focus-visible:ring-ring/40",
                          "disabled:cursor-not-allowed",
                          on
                            ? "border-primary/40 bg-primary/10 text-foreground"
                            : "border-border bg-transparent text-muted-foreground",
                          !readOnly && !on && "hover:border-primary/30 hover:text-foreground"
                        )}
                      >
                        {kindLabel(kind)}
                      </button>
                    );
                  })}
                </div>
              </div>

              <div className="space-y-1.5">
                <Label htmlFor="sentinel-policy-retention" className="text-sm font-medium text-foreground">
                  {t("retentionLabel")}
                </Label>
                <Input
                  id="sentinel-policy-retention"
                  type="number"
                  inputMode="numeric"
                  min={RETENTION_MIN}
                  max={RETENTION_MAX}
                  step={1}
                  className="w-32"
                  disabled={readOnly || savingPolicy}
                  value={Number.isFinite(draft.retentionDays) ? String(draft.retentionDays) : ""}
                  onChange={(e) => updateDraft({ retentionDays: e.target.value === "" ? NaN : Number(e.target.value) })}
                  aria-invalid={!retentionValid}
                />
                <p className={cn("text-xs", retentionValid ? "text-muted-foreground" : "text-destructive")}>
                  {t("retentionHint", { min: RETENTION_MIN, max: RETENTION_MAX })}
                </p>
              </div>

              {policy?.updatedAt && (
                <p className="text-xs text-muted-foreground">
                  {t("lastUpdated", { time: formatTime(policy.updatedAt) })}
                </p>
              )}

              {!readOnly && (
                <div className="flex flex-wrap items-center gap-2">
                  <Button
                    type="button"
                    size="sm"
                    disabled={!dirty || !retentionValid || savingPolicy}
                    onClick={handleSavePolicy}
                  >
                    {savingPolicy && <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />}
                    {t("saveButton")}
                  </Button>
                  {dirty && (
                    <Button
                      type="button"
                      size="sm"
                      variant="ghost"
                      disabled={savingPolicy}
                      onClick={() => {
                        setDraft(policy);
                        setPolicyError(null);
                      }}
                    >
                      {t("resetButton")}
                    </Button>
                  )}
                  {policySaved && !dirty && <span className="text-xs text-muted-foreground">{t("savedNotice")}</span>}
                </div>
              )}
              {policyError && <p className="text-xs text-destructive">{policyError}</p>}
            </CardContent>
          </Card>

          {/* Admin aggregate - counts only */}
          {canAdmin && (
            <Card>
              <CardHeader>
                <CardTitle className="text-sm">{t("summaryTitle", { days: summary?.windowDays ?? 7 })}</CardTitle>
                <CardDescription>{t("summaryPrivacyNote")}</CardDescription>
              </CardHeader>
              <CardContent>
                {summaryUnavailable ? (
                  <p className="text-sm text-muted-foreground">{t("summaryUnavailable")}</p>
                ) : !summary ? (
                  <div className="flex items-center gap-2 text-sm text-muted-foreground">
                    <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                    {t("loading")}
                  </div>
                ) : (
                  <div className="space-y-3">
                    <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
                      <StatTile label={t("statActiveUsers")} value={summary.activeUsers ?? 0} />
                      <StatTile label={t("statEvents")} value={summary.events ?? 0} />
                      <StatTile label={t("statEscalations")} value={summary.escalations ?? 0} />
                    </div>
                    <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
                      {SEVERITIES.map((sev) => (
                        <div
                          key={sev}
                          className="flex items-center justify-between gap-2 rounded-xl border border-border p-2.5"
                        >
                          <SeverityBadge severity={sev} label={severityLabel(sev)} />
                          <span className="text-sm font-semibold tabular-nums text-foreground">
                            {summary.bySeverity?.[sev] ?? 0}
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </CardContent>
            </Card>
          )}

          {/* My synced events */}
          <Card>
            <CardHeader>
              <CardTitle className="text-sm">{t("eventsTitle")}</CardTitle>
              <CardDescription>{t("eventsHint")}</CardDescription>
            </CardHeader>
            <CardContent className="space-y-3">
              {!eventsLoaded && (
                <div className="flex items-center gap-2 text-sm text-muted-foreground">
                  <Loader2 className="size-4 animate-spin" aria-hidden="true" />
                  {t("loading")}
                </div>
              )}
              {eventsError && (
                <div className="flex flex-wrap items-center gap-2 text-sm text-destructive">
                  {eventsError}
                  <Button type="button" size="sm" variant="outline" onClick={() => void loadEvents()}>
                    {t("retryButton")}
                  </Button>
                </div>
              )}
              {eventsLoaded && !eventsError && events.length === 0 && (
                <p className="text-sm text-muted-foreground">{t("eventsEmpty")}</p>
              )}
              {eventsPg.pageItems.map((ev, i) => {
                const key = String(ev.id ?? ev.clientEventId ?? i);
                const open = !!expanded[key];
                const summaryText = ev.summary || "";
                const long = summaryText.length > 160;
                return (
                  <div key={key} className="rounded-xl border border-border bg-muted/40 p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <SeverityBadge severity={ev.severity || ""} label={severityLabel(ev.severity || "")} />
                      {ev.escalatedAt && (
                        <Badge variant="outline" className="gap-1" title={ev.escalatedTo || undefined}>
                          <BellRing aria-hidden="true" />
                          {t("escalatedBadge")}
                        </Badge>
                      )}
                      <span className="ml-auto text-xs text-muted-foreground">
                        {formatTime(ev.occurredAt || ev.receivedAt)}
                      </span>
                    </div>
                    <div className="mt-1.5 text-sm font-semibold text-foreground">{ev.title || t("untitledEvent")}</div>
                    {ev.sentinelName && <div className="text-xs text-muted-foreground">{ev.sentinelName}</div>}
                    {summaryText && (
                      <div className="mt-1.5">
                        <p
                          className={cn(
                            "whitespace-pre-line break-words text-sm leading-relaxed text-muted-foreground",
                            long && !open && "line-clamp-2"
                          )}
                        >
                          {summaryText}
                        </p>
                        {long && (
                          <button
                            type="button"
                            className="mt-1 text-xs font-medium text-primary hover:underline"
                            aria-expanded={open}
                            onClick={() => setExpanded((m) => ({ ...m, [key]: !open }))}
                          >
                            {open ? t("showLess") : t("showMore")}
                          </button>
                        )}
                      </div>
                    )}
                  </div>
                );
              })}

              <PaginationControls
                page={eventsPg.page}
                totalPages={eventsPg.totalPages}
                totalItems={eventsPg.totalItems}
                pageSize={eventsPg.pageSize}
                onPageChange={eventsPg.setPage}
                onPageSizeChange={eventsPg.setPageSize}
                capped={EVENTS_LIMIT}
              />

              <div className="flex flex-wrap items-center gap-2 border-t border-border pt-3">
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  className="text-destructive"
                  disabled={erasing || !eventsLoaded}
                  onClick={() => setConfirmErase(true)}
                >
                  {t("eraseButton")}
                </Button>
                {eraseResult && <span className="text-xs text-muted-foreground">{eraseResult}</span>}
              </div>
            </CardContent>
          </Card>

          <AlertDialog open={confirmErase} onOpenChange={(open: boolean) => !erasing && setConfirmErase(open)}>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>{t("eraseConfirmTitle")}</AlertDialogTitle>
                <AlertDialogDescription>{t("eraseConfirmBody")}</AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel disabled={erasing}>{t("eraseCancel")}</AlertDialogCancel>
                <AlertDialogAction
                  className="bg-destructive text-destructive-foreground hover:bg-destructive/90"
                  disabled={erasing}
                  onClick={(e: MouseEvent<HTMLButtonElement>) => {
                    e.preventDefault();
                    void handleErase();
                  }}
                >
                  {erasing && <Loader2 className="size-3.5 animate-spin" aria-hidden="true" />}
                  {t("eraseConfirmButton")}
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </>
      )}
    </section>
  );
}
