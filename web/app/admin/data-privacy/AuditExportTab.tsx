"use client";

import { Select } from "../../components/ui/select";
import { useState } from "react";
import { MAX_AUDIT_RANGE_DAYS, privacyApi, type AuditFormat } from "../../lib/api/privacy";
import { useTranslations } from "../../lib/i18n";
import { Section } from "../../components/ax/page-kit";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { errText } from "./shared";

const DAY_MS = 86_400_000;

/** [from, to) in UTC: `to` is the day AFTER the chosen last day, so the last day is included. */
export function auditRange(fromDay: string, toDay: string): { from: string; to: string } | "order" | "range" | "missing" {
  if (!fromDay || !toDay) return "missing";
  const from = new Date(`${fromDay}T00:00:00Z`);
  const to = new Date(new Date(`${toDay}T00:00:00Z`).getTime() + DAY_MS);
  if (Number.isNaN(from.getTime()) || Number.isNaN(to.getTime())) return "missing";
  if (from >= to) return "order";
  if ((to.getTime() - from.getTime()) / DAY_MS > MAX_AUDIT_RANGE_DAYS) return "range";
  return { from: from.toISOString(), to: to.toISOString() };
}

export default function AuditExportTab() {
  const t = useTranslations("privacySso");
  const [fromDay, setFromDay] = useState("");
  const [toDay, setToDay] = useState("");
  const [format, setFormat] = useState<AuditFormat>("csv");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const run = async () => {
    setDone(false);
    const r = auditRange(fromDay, toDay);
    if (typeof r === "string") {
      setError(t(r === "missing" ? "errAuditMissing" : r === "order" ? "errAuditOrder" : "errAuditRange", { days: MAX_AUDIT_RANGE_DAYS }));
      return;
    }
    setError(null);
    setBusy(true);
    try {
      await privacyApi.downloadAuditExport(r.from, r.to, format);
      setDone(true);
    } catch (e) {
      setError(errText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Section title={t("auditTitle")} description={t("auditIntro", { days: MAX_AUDIT_RANGE_DAYS })}>
      <form
        className="space-y-4"
        onSubmit={(e) => {
          e.preventDefault();
          void run();
        }}
      >
        <div className="grid gap-3 sm:grid-cols-3">
          <div className="grid gap-1.5">
            <Label htmlFor="audit-from">{t("auditFrom")}</Label>
            <Input id="audit-from" type="date" value={fromDay} onChange={(e) => setFromDay(e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="audit-to">{t("auditTo")}</Label>
            <Input id="audit-to" type="date" value={toDay} onChange={(e) => setToDay(e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="audit-format">{t("auditFormat")}</Label>
            <Select
              id="audit-format"
              value={format}
              onValueChange={(v) => setFormat(v as AuditFormat)}
              options={[
                { value: "csv", label: "CSV" },
                { value: "json", label: "JSON" },
              ]}
            />
          </div>
        </div>
        <p className="text-xs text-muted-foreground">{t("auditHint")}</p>
        {error && (
          <Alert variant="destructive">
            <AlertDescription>{error}</AlertDescription>
          </Alert>
        )}
        {done && (
          <Alert variant="success">
            <AlertDescription>{t("auditDone")}</AlertDescription>
          </Alert>
        )}
        <Button type="submit" disabled={busy}>
          {busy ? t("auditPreparing") : t("auditDownload")}
        </Button>
      </form>
    </Section>
  );
}
