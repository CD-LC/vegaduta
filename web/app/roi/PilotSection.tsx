"use client";

// Pilot kit on the Cost & ROI page: the tenant types their pre-pilot numbers for one agent, then opens the one-page
// pilot report (HTML / PDF) for the selected date range. Every field here is tenant-entered and labelled so; the
// platform never fills one in. Saving needs MANAGE_BILLING; opening the report needs VIEW_USAGE (the page's gate).
import { useEffect, useState, type FormEvent } from "react";
import { Button } from "../components/ui/button";
import { Card } from "../components/ui/card";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Select } from "../components/ui/select";
import { EmptyState, Section } from "../components/ax/page-kit";
import { JOB_TYPES, roiPilotApi, type BaselineDto, type ReportFormat } from "../lib/api/roiPilot";
import type { RoiRange } from "../lib/api/roi";
import { useTranslations } from "../lib/i18n";
import { parseAmount } from "./roiModel";

export interface PilotAgent {
  id: string;
  name: string;
}

const message = (err: unknown) => (err instanceof Error && err.message ? err.message : "error");
const text = (v: string | number | null | undefined) => (v === null || v === undefined ? "" : String(v));

interface Draft {
  job: string;
  period: string;
  volume: string;
  minutes: string;
  missedPct: string;
  value: string;
  currency: string;
}

export function draftFrom(b: BaselineDto | null): Draft {
  const missed = b?.missedRate === null || b?.missedRate === undefined ? "" : String(Math.round(Number(b.missedRate) * 10000) / 100);
  return {
    job: b?.jobType ?? "",
    period: b?.baselinePeriod ?? "",
    volume: text(b?.monthlyVolume),
    minutes: text(b?.avgHandlingMinutes),
    missedPct: missed,
    value: text(b?.avgValuePerConversion),
    currency: b?.valueCurrency ?? "",
  };
}

export default function PilotSection({ agents, range, mayEdit }: { agents: PilotAgent[]; range: RoiRange; mayEdit: boolean }) {
  const t = useTranslations("roiPilot");
  const [agentId, setAgentId] = useState(agents[0]?.id ?? "");
  const [draft, setDraft] = useState<Draft>(draftFrom(null));
  const [loadedFor, setLoadedFor] = useState("");
  const [stored, setStored] = useState<BaselineDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [saving, setSaving] = useState(false);
  const [reportError, setReportError] = useState(false);

  useEffect(() => {
    if (!agentId) return undefined;
    let cancelled = false;
    // Never carry the previous agent's numbers into this agent's form: a failed load must leave it empty and unsaveable.
    setStored(null);
    setDraft(draftFrom(null));
    setLoadedFor("");
    setError(null);
    setSaved(false);
    roiPilotApi
      .getBaseline(agentId)
      .then((b) => {
        if (cancelled) return;
        setStored(b);
        setDraft(draftFrom(b));
        setLoadedFor(agentId);
      })
      .catch((err) => {
        if (!cancelled) setError(message(err));
      });
    return () => {
      cancelled = true;
    };
  }, [agentId]);

  if (agents.length === 0) {
    return (
      <Section title={t("title")} description={t("desc")}>
        <EmptyState title={t("noAgents")} />
      </Section>
    );
  }

  const set = (k: keyof Draft) => (e: { target: { value: string } }) => {
    setSaved(false);
    setDraft((d) => ({ ...d, [k]: e.target.value }));
  };

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const volume = parseAmount(draft.volume);
    const minutes = parseAmount(draft.minutes);
    const missed = parseAmount(draft.missedPct);
    const value = parseAmount(draft.value);
    if ([volume, minutes, missed, value].includes(undefined) || (missed !== null && (missed as number) > 100)) return setError(t("invalid"));
    if (volume !== null && !Number.isInteger(volume)) return setError(t("invalid"));
    // The server stores 2 decimals of minutes, 2 of a percentage and 4 of a value; reject more instead of rounding.
    const within = (n: number | null | undefined, places: number) => n == null || Math.abs(Math.round(n * 10 ** places) / 10 ** places - n) < 1e-9;
    if (!within(minutes as number | null, 2) || !within(missed as number | null, 2) || !within(value as number | null, 4)) return setError(t("tooManyDecimals"));
    if (value !== null && !/^[A-Za-z]{3}$/.test(draft.currency.trim())) return setError(t("currencyRequired"));
    setSaving(true);
    setError(null);
    try {
      const b = await roiPilotApi.setBaseline(agentId, {
        jobType: draft.job || null,
        baselinePeriod: draft.period.trim() || null,
        monthlyVolume: volume as number | null,
        avgHandlingMinutes: minutes as number | null,
        missedRate: missed === null ? null : (missed as number) / 100,
        avgValuePerConversion: value as number | null,
        valueCurrency: value === null ? null : draft.currency.trim().toUpperCase(),
      });
      setStored(b);
      setDraft(draftFrom(b));
      setSaved(true);
    } catch (err) {
      setError(message(err));
    } finally {
      setSaving(false);
    }
  };

  const open = async (format: ReportFormat) => {
    setReportError(false);
    try {
      await roiPilotApi.openReport(agentId, format, range);
    } catch {
      setReportError(true);
    }
  };

  const ready = loadedFor === agentId;
  return (
    <Section title={t("title")} description={t("desc")}>
      <Card className="gap-4 p-5" data-testid="pilot-section">
        <div className="space-y-1">
          <Label htmlFor="pilot-agent">{t("agent")}</Label>
          <Select
            id="pilot-agent"
            className="max-w-sm"
            value={agentId}
            onValueChange={setAgentId}
            options={agents.map((a) => ({ value: a.id, label: a.name }))}
          />
        </div>
        <form onSubmit={(e) => void submit(e)} className="space-y-4" aria-label={t("formLabel")} aria-busy={!ready}>
          <p className="text-sm text-muted-foreground">{t("formHelp")}</p>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            <div className="space-y-1">
              <Label htmlFor="pilot-job">{t("job")}</Label>
              <Select
                id="pilot-job"
                value={draft.job}
                onValueChange={(v) => set("job")({ target: { value: v } })}
                disabled={!mayEdit || !ready}
                options={[{ value: "", label: t("jobNone") }, ...JOB_TYPES.map((j) => ({ value: j, label: t(`job_${j}`) }))]}
              />
            </div>
            <div className="space-y-1"><Label htmlFor="pilot-period">{t("period")}</Label><Input id="pilot-period" maxLength={80} value={draft.period} onChange={set("period")} disabled={!mayEdit || !ready} placeholder={t("periodHint")} /></div>
            <div className="space-y-1"><Label htmlFor="pilot-volume">{t("volume")}</Label><Input id="pilot-volume" inputMode="numeric" value={draft.volume} onChange={set("volume")} disabled={!mayEdit || !ready} /></div>
            <div className="space-y-1"><Label htmlFor="pilot-minutes">{t("minutes")}</Label><Input id="pilot-minutes" inputMode="decimal" value={draft.minutes} onChange={set("minutes")} disabled={!mayEdit || !ready} /></div>
            <div className="space-y-1"><Label htmlFor="pilot-missed">{t("missed")}</Label><Input id="pilot-missed" inputMode="decimal" value={draft.missedPct} onChange={set("missedPct")} disabled={!mayEdit || !ready} /></div>
            <div className="grid grid-cols-[1fr_5rem] gap-2">
              <div className="space-y-1"><Label htmlFor="pilot-value">{t("value")}</Label><Input id="pilot-value" inputMode="decimal" value={draft.value} onChange={set("value")} disabled={!mayEdit || !ready} /></div>
              <div className="space-y-1"><Label htmlFor="pilot-currency">{t("currency")}</Label><Input id="pilot-currency" maxLength={3} value={draft.currency} onChange={set("currency")} disabled={!mayEdit || !ready} placeholder="INR" /></div>
            </div>
          </div>
          {!mayEdit && <p className="text-sm text-muted-foreground" data-testid="pilot-read-only">{t("readOnly")}</p>}
          {stored?.enteredBy && <p className="text-xs text-muted-foreground" data-testid="pilot-entered-by">{t("enteredBy", { who: stored.enteredBy })}</p>}
          {error && <p role="alert" className="text-sm" style={{ color: "var(--state-error)" }}>{error}</p>}
          {saved && <p role="status" className="text-sm">{t("saved")}</p>}
          <div className="flex flex-wrap gap-2">
            {mayEdit && <Button type="submit" size="sm" disabled={saving || !ready}>{t("save")}</Button>}
            <Button type="button" variant="outline" size="sm" onClick={() => void open("html")}>{t("openReport")}</Button>
            <Button type="button" variant="outline" size="sm" onClick={() => void open("pdf")}>{t("downloadPdf")}</Button>
          </div>
          {reportError && <p role="alert" className="text-sm" style={{ color: "var(--state-error)" }}>{t("reportError")}</p>}
          <p className="text-xs text-muted-foreground">{t("reportNote")}</p>
        </form>
      </Card>
    </Section>
  );
}
