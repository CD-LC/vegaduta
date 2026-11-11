"use client";

import { Select } from "../../components/ui/select";
import { useCallback, useEffect, useMemo, useState } from "react";
import {
  ALL_CHANNELS,
  DEFAULT_RETENTION_DAYS,
  MAX_RETENTION_DAYS,
  privacyApi,
  retentionAllows,
  type RetentionDataClass,
  type RetentionRuleDto,
} from "../../lib/api/privacy";
import { useTranslations } from "../../lib/i18n";
import { usePagination } from "../../lib/usePagination";
import PaginationControls from "../../components/PaginationControls";
import { EmptyState, Section } from "../../components/ax/page-kit";
import { Alert, AlertDescription } from "../../components/ui/alert";
import { Badge } from "../../components/ui/badge";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Skeleton } from "../../components/ui/skeleton";
import { Switch } from "../../components/ui/switch";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "../../components/ui/table";
import { offeredChannels, LoadError, errText, fmtTime } from "./shared";

const CLASSES: RetentionDataClass[] = ["TRANSCRIPT", "RECORDING", "EXTRACTED_FIELDS"];

export default function RetentionTab() {
  const t = useTranslations("privacySso");
  const [rules, setRules] = useState<RetentionRuleDto[] | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [channel, setChannel] = useState(ALL_CHANNELS);
  const [dataClass, setDataClass] = useState<RetentionDataClass>("TRANSCRIPT");
  const [enabled, setEnabled] = useState(true);
  const [days, setDays] = useState(String(DEFAULT_RETENTION_DAYS));
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [saved, setSaved] = useState(false);

  const load = useCallback(() => {
    setLoadError(null);
    setRules(null);
    privacyApi
      .listRetention()
      .then(setRules)
      .catch((e: unknown) => setLoadError(errText(e)));
  }, []);
  useEffect(load, [load]);

  const pg = usePagination(useMemo(() => rules ?? [], [rules]));

  const edit = (r: RetentionRuleDto) => {
    setChannel(r.channel);
    setDataClass(r.dataClass);
    setEnabled(r.enabled);
    setDays(String(r.retentionDays));
    setSaved(false);
    setFormError(null);
  };

  const onSave = async () => {
    setSaved(false);
    setWarnings([]);
    if (!retentionAllows(dataClass, channel)) {
      setFormError(t("errRetentionChannel", { dataClass: t(`class_${dataClass}`), channel }));
      return;
    }
    const n = Number(days);
    if (!Number.isInteger(n) || n < 1 || n > MAX_RETENTION_DAYS) {
      setFormError(t("errRetentionDays", { max: MAX_RETENTION_DAYS }));
      return;
    }
    setFormError(null);
    setSaving(true);
    try {
      const res = await privacyApi.putRetention({ channel, dataClass, enabled, retentionDays: n });
      setWarnings(res.warnings ?? []);
      setSaved(true);
      load();
    } catch (e) {
      setFormError(errText(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Section title={t("retentionTitle")} description={t("retentionIntro")}>
      {loadError ? (
        <LoadError message={loadError} onRetry={load} />
      ) : rules === null ? (
        <Skeleton className="h-32 w-full" aria-label={t("loading")} />
      ) : rules.length === 0 ? (
        <EmptyState title={t("retentionEmptyTitle")} description={t("retentionEmptyBody")} />
      ) : (
        <div className="space-y-3">
          <div className="overflow-x-auto rounded-lg border border-border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t("dataClass")}</TableHead>
                  <TableHead>{t("channel")}</TableHead>
                  <TableHead>{t("status")}</TableHead>
                  <TableHead>{t("retentionDays")}</TableHead>
                  <TableHead>{t("updated")}</TableHead>
                  <TableHead>
                    <span className="sr-only">{t("actions")}</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {pg.pageItems.map((r) => (
                  <TableRow key={`${r.dataClass}:${r.channel}`}>
                    <TableCell>{t(`class_${r.dataClass}`)}</TableCell>
                    <TableCell>{r.channel === ALL_CHANNELS ? t("tenantDefault") : r.channel}</TableCell>
                    <TableCell>
                      <Badge variant={r.enabled ? "default" : "secondary"}>{r.enabled ? t("on") : t("off")}</Badge>
                    </TableCell>
                    <TableCell className="tabular-nums">{r.retentionDays}</TableCell>
                    <TableCell className="text-muted-foreground">{fmtTime(r.updatedAt)}</TableCell>
                    <TableCell className="text-end">
                      <Button type="button" variant="ghost" size="sm" onClick={() => edit(r)}>
                        {t("edit")}
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <PaginationControls
            page={pg.page}
            totalPages={pg.totalPages}
            totalItems={pg.totalItems}
            pageSize={pg.pageSize}
            onPageChange={pg.setPage}
            onPageSizeChange={pg.setPageSize}
            itemLabel={t("rulesLabel")}
          />
        </div>
      )}

      <form
        noValidate
        className="space-y-4 rounded-lg border border-border p-4"
        onSubmit={(e) => {
          e.preventDefault();
          void onSave();
        }}
      >
        <h3 className="text-sm font-medium">{t("retentionFormTitle")}</h3>
        <div className="grid gap-3 sm:grid-cols-3">
          <div className="grid gap-1.5">
            <Label htmlFor="ret-class">{t("dataClass")}</Label>
            <Select
              id="ret-class"
              value={dataClass}
              onValueChange={(v) => setDataClass(v as RetentionDataClass)}
              options={CLASSES.map((c) => ({ value: c, label: t(`class_${c}`) }))}
            />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="ret-channel">{t("channel")}</Label>
            <Select
              id="ret-channel"
              value={channel}
              onValueChange={setChannel}
              options={[
                { value: ALL_CHANNELS, label: t("tenantDefault") },
                ...offeredChannels().map((c) => ({ value: c, label: c })),
              ]}
            />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="ret-days">{t("retentionDays")}</Label>
            <Input
              id="ret-days"
              type="number"
              inputMode="numeric"
              min={1}
              max={MAX_RETENTION_DAYS}
              value={days}
              onChange={(e) => setDays(e.target.value)}
            />
          </div>
        </div>
        <p className="text-xs text-muted-foreground">{t("retentionRulesHint", { max: MAX_RETENTION_DAYS })}</p>
        <div className="flex items-center gap-3">
          <Switch id="ret-enabled" checked={enabled} onCheckedChange={setEnabled} aria-label={t("retentionEnabled")} />
          <Label htmlFor="ret-enabled">{t("retentionEnabled")}</Label>
        </div>
        {formError && (
          <Alert variant="destructive">
            <AlertDescription>{formError}</AlertDescription>
          </Alert>
        )}
        {saved && (
          <Alert variant="success">
            <AlertDescription>{t("retentionSaved")}</AlertDescription>
          </Alert>
        )}
        {warnings.map((w) => (
          <Alert key={w} variant="warning">
            <AlertDescription>{w}</AlertDescription>
          </Alert>
        ))}
        <Button type="submit" disabled={saving}>
          {saving ? t("saving") : t("saveRetention")}
        </Button>
      </form>
    </Section>
  );
}
