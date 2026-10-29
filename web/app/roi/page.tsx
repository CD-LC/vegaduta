"use client";

// Cost and ROI per agent: what each agent cost, how its conversations ended, and - only if the workspace has
// typed in what a resolution is worth - an ROI figure "based on your assumptions".
//
// Data: /api/tenant/roi/** (lib/api/roi.ts). Reading needs VIEW_USAGE; entering prices and value assumptions
// needs MANAGE_BILLING, so those controls are hidden without it. All the "what may a number look like" rules
// (null is a dash, never 0; costs say "estimated"; ROI says whose assumptions) live in roiModel.ts.
import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from "react";
import { Download, Gauge, Pencil } from "lucide-react";
import AppShell from "../components/AppShell";
import DemoWatermark from "./DemoWatermark";
import PilotSection from "./PilotSection";
import { useAuth } from "../components/AuthProvider";
import { usePageReady } from "../components/RouteTransition";
import PageHeader from "../components/ax/PageHeader";
import { Section } from "../components/ax/page-kit";
import { Badge } from "../components/ui/badge";
import { Button } from "../components/ui/button";
import { Card } from "../components/ui/card";
import { Input } from "../components/ui/input";
import { Label } from "../components/ui/label";
import { Skeleton } from "../components/ui/skeleton";
import { Switch } from "../components/ui/switch";
import AgentTable from "./AgentTable";
import { FamilyLinks, PricesPager, usePricePaging } from "./RoiFamily";
import {
  roiApi,
  type AgentRoiResponseDto,
  type AgentRoiRowDto,
  type BudgetDto,
  type Money,
  type PriceDto,
  type PriceListDto,
  type UnpricedModelDto,
} from "../lib/api/roi";
import { useTranslations } from "../lib/i18n";
import {
  DASH,
  budgetFill,
  budgetTone,
  formatCount,
  formatRoiPercent,
  formatUsd,
  parseAmount,
  presetRange,
  validateRange,
} from "./roiModel";

const PRESETS = [7, 30, 90] as const;
const TONE_COLOR: Record<string, string> = {
  ok: "var(--state-ok)",
  warn: "var(--state-warn)",
  over: "var(--state-error)",
  none: "var(--muted-foreground)",
};

type Loaded = {
  key: string;
  table: AgentRoiResponseDto | null;
  budget: BudgetDto | null;
  prices: PriceListDto | null;
  error: string | null;
};

const message = (err: unknown) => (err instanceof Error && err.message ? err.message : "error");

export default function RoiPage() {
  const t = useTranslations("roi");
  const { isAdmin, hasPermission } = useAuth();
  const mayView = !!isAdmin || hasPermission("VIEW_USAGE");
  const mayEdit = !!isAdmin || hasPermission("MANAGE_BILLING");

  const [preset, setPreset] = useState<number | null>(30);
  const initial = presetRange(30);
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [includeAssumed, setIncludeAssumed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [data, setData] = useState<Loaded>({ key: "", table: null, budget: null, prices: null, error: null });
  const [editing, setEditing] = useState<AgentRoiRowDto | null>(null);
  const [exportError, setExportError] = useState(false);

  const rangeError = validateRange(from, to);
  const key = `${from}:${to}:${includeAssumed}:${attempt}`;

  useEffect(() => {
    if (!mayView || rangeError) return undefined;
    let cancelled = false;
    void (async () => {
      try {
        const [table, budget, prices] = await Promise.all([
          roiApi.getAgents({ from, to, includeAssumed }),
          roiApi.getBudget(),
          roiApi.getPrices({ from, to }),
        ]);
        if (!cancelled) setData({ key, table, budget, prices, error: null });
      } catch (err) {
        if (!cancelled) setData({ key, table: null, budget: null, prices: null, error: message(err) });
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [mayView, rangeError, from, to, includeAssumed, key]);

  const loading = mayView && !rangeError && data.key !== key;
  usePageReady(!mayView || !!rangeError || data.key !== "");
  const reload = useCallback(() => setAttempt((n) => n + 1), []);

  const applyPreset = (days: number) => {
    const r = presetRange(days);
    setPreset(days);
    setFrom(r.from);
    setTo(r.to);
  };

  const exportCsv = async () => {
    setExportError(false);
    try {
      await roiApi.downloadAgentsCsv({ from, to, includeAssumed });
    } catch {
      setExportError(true);
    }
  };

  const controls = (
    <div className="flex flex-wrap items-end gap-3" data-testid="roi-filters">
      <div role="group" aria-label={t("periodLabel")} className="flex flex-wrap items-center gap-1.5">
        {PRESETS.map((days) => (
          <Button
            key={days}
            type="button"
            size="sm"
            variant={preset === days ? "default" : "outline"}
            aria-pressed={preset === days}
            onClick={() => applyPreset(days)}
          >
            {t(`period${days}`)}
          </Button>
        ))}
      </div>
      <div className="flex items-end gap-2">
        <div className="space-y-1">
          <Label htmlFor="roi-from">{t("from")}</Label>
          <Input
            id="roi-from"
            type="date"
            value={from}
            max={to}
            onChange={(e) => {
              setPreset(null);
              setFrom(e.target.value);
            }}
            className="w-40"
          />
        </div>
        <div className="space-y-1">
          <Label htmlFor="roi-to">{t("to")}</Label>
          <Input
            id="roi-to"
            type="date"
            value={to}
            min={from}
            onChange={(e) => {
              setPreset(null);
              setTo(e.target.value);
            }}
            className="w-40"
          />
        </div>
      </div>
      <div className="flex items-center gap-2">
        <Switch
          id="roi-assumed"
          checked={includeAssumed}
          onCheckedChange={setIncludeAssumed}
          aria-label={t("includeAssumed")}
        />
        <Label htmlFor="roi-assumed" className="text-sm">
          {t("includeAssumed")}
        </Label>
      </div>
      <Button
        type="button"
        variant="outline"
        size="sm"
        onClick={() => void exportCsv()}
        disabled={!data.table}
        data-testid="roi-export"
      >
        <Download className="me-1.5 h-4 w-4" aria-hidden="true" />
        {t("exportCsv")}
      </Button>
    </div>
  );

  let body: ReactNode;
  if (!mayView) {
    body = (
      <p
        data-testid="roi-no-access"
        className="rounded-xl border border-border bg-card/40 px-4 py-3 text-sm text-muted-foreground"
      >
        {t("noAccess")}
      </p>
    );
  } else if (rangeError) {
    body = (
      <p role="alert" data-testid="roi-range-error" className="text-sm" style={{ color: "var(--state-error)" }}>
        {t(rangeError)}
      </p>
    );
  } else if (loading) {
    body = (
      <div aria-busy="true" data-testid="roi-loading" className="space-y-4">
        <Skeleton className="h-24 w-full rounded-xl" />
        <Skeleton className="h-64 w-full rounded-xl" />
      </div>
    );
  } else if (data.error || !data.table) {
    body = (
      <div
        role="alert"
        className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-border px-4 py-3 text-sm"
        style={{ color: "var(--state-error)" }}
      >
        <span className="min-w-0">{t("loadError")}</span>
        <Button type="button" variant="outline" size="sm" onClick={reload}>
          {t("retry")}
        </Button>
      </div>
    );
  } else {
    body = (
      <div className="min-w-0 space-y-8">
        {data.budget && <BudgetCard budget={data.budget} t={t} />}
        <Section title={t("tableTitle")} description={t("tableDesc", { from: data.table.from, to: data.table.to })}>
          {exportError && (
            <p role="alert" className="text-sm" style={{ color: "var(--state-error)" }}>
              {t("exportError")}
            </p>
          )}
          <AgentTable table={data.table} t={t} mayEdit={mayEdit} onEdit={setEditing} />
          {editing && (
            <AssumptionEditor
              row={editing}
              t={t}
              onClose={() => setEditing(null)}
              onSaved={() => {
                setEditing(null);
                reload();
              }}
            />
          )}
        </Section>
        {data.prices && <PricesSection prices={data.prices} t={t} mayEdit={mayEdit} onChanged={reload} />}
        <PilotSection
          agents={data.table.agents.map((a) => ({ id: a.agentId, name: a.agentName }))}
          range={{ from, to, includeAssumed }}
          mayEdit={mayEdit}
        />
      </div>
    );
  }

  return (
    <AppShell eyebrow={t("eyebrow")}>
      {mayView && <DemoWatermark />}
      <PageHeader
        icon={Gauge}
        title={t("pageTitle")}
        subtitle={t("pageSubtitle")}
        actions={mayView ? controls : undefined}
      />
      {mayView && <FamilyLinks />}
      {body}
    </AppShell>
  );
}

type T = (key: string, values?: Record<string, string | number>) => string;

function BudgetCard({ budget, t }: { budget: BudgetDto; t: T }) {
  const tone = budgetTone(budget.percentUsed, budget.capConfigured);
  const fill = budgetFill(budget.percentUsed);
  return (
    <Card className="gap-3 p-5" data-testid="roi-budget">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-base font-semibold">{t("budgetTitle")}</h2>
        {budget.capConfigured && (
          <Badge variant="outline" data-testid="roi-budget-percent">
            {formatRoiPercent(budget.percentUsed)}
          </Badge>
        )}
      </div>
      {budget.capConfigured ? (
        <>
          <div
            role="progressbar"
            aria-label={t("budgetTitle")}
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={Math.round(fill)}
            className="h-3 w-full overflow-hidden rounded-full bg-muted"
          >
            <div
              className="h-full rounded-full"
              style={{ width: `${fill}%`, background: TONE_COLOR[tone] }}
              data-testid="roi-budget-fill"
              data-tone={tone}
            />
          </div>
          <p className="text-sm">
            {t("budgetLine", {
              spent: formatUsd(budget.spentUsd),
              cap: formatUsd(budget.capUsd),
              month: budget.monthStart.slice(0, 7),
            })}
          </p>
          <p className="text-xs text-muted-foreground">
            {t("budgetAlerts", {
              fired: budget.alertsFired.length
                ? budget.alertsFired.map((n) => `${n}%`).join(", ")
                : t("budgetNoAlerts"),
            })}
          </p>
        </>
      ) : (
        <p className="text-sm text-muted-foreground" data-testid="roi-budget-none">
          {t("budgetNoCap")}
        </p>
      )}
      <p className="text-xs text-muted-foreground">{t("budgetBasis")}</p>
    </Card>
  );
}

function AssumptionEditor({
  row,
  t,
  onClose,
  onSaved,
}: {
  row: AgentRoiRowDto;
  t: T;
  onClose: () => void;
  onSaved: () => void;
}) {
  // Starts from what is stored - never from a default. A blank field means "no assumption".
  const [value, setValue] = useState(row.valuePerResolution === null ? "" : String(row.valuePerResolution));
  const [currency, setCurrency] = useState(row.valueCurrency ?? "");
  const [minutes, setMinutes] = useState(
    row.minutesSavedPerResolution === null ? "" : String(row.minutesSavedPerResolution)
  );
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const v = parseAmount(value);
    const m = parseAmount(minutes);
    if (v === undefined || m === undefined) return setError(t("assumptionInvalid"));
    if (v !== null && !/^[A-Za-z]{3}$/.test(currency.trim())) return setError(t("assumptionCurrency"));
    setSaving(true);
    setError(null);
    try {
      await roiApi.setAssumption(row.agentId, {
        valuePerResolution: v,
        valueCurrency: v === null ? null : currency.trim().toUpperCase(),
        minutesSavedPerResolution: m,
      });
      onSaved();
    } catch (err) {
      setError(message(err));
      setSaving(false);
    }
  };

  return (
    <Card className="gap-4 p-5" data-testid="roi-assumption-editor">
      <form
        onSubmit={(e) => void submit(e)}
        className="space-y-4"
        aria-label={t("assumptionTitle", { agent: row.agentName })}
      >
        <div>
          <h3 className="text-base font-semibold">{t("assumptionTitle", { agent: row.agentName })}</h3>
          <p className="text-sm text-muted-foreground">{t("assumptionHelp")}</p>
        </div>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
          <div className="space-y-1">
            <Label htmlFor="roi-value">{t("assumptionValue")}</Label>
            <Input
              id="roi-value"
              inputMode="decimal"
              value={value}
              onChange={(e) => setValue(e.target.value)}
              placeholder={DASH}
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor="roi-currency">{t("assumptionCurrencyLabel")}</Label>
            <Input
              id="roi-currency"
              maxLength={3}
              value={currency}
              onChange={(e) => setCurrency(e.target.value)}
              placeholder="USD"
            />
          </div>
          <div className="space-y-1">
            <Label htmlFor="roi-minutes">{t("assumptionMinutes")}</Label>
            <Input
              id="roi-minutes"
              inputMode="decimal"
              value={minutes}
              onChange={(e) => setMinutes(e.target.value)}
              placeholder={DASH}
            />
          </div>
        </div>
        {error && (
          <p role="alert" className="text-sm" style={{ color: "var(--state-error)" }}>
            {error}
          </p>
        )}
        <div className="flex flex-wrap gap-2">
          <Button type="submit" size="sm" disabled={saving}>
            {t("save")}
          </Button>
          <Button type="button" variant="outline" size="sm" onClick={onClose}>
            {t("cancel")}
          </Button>
        </div>
      </form>
    </Card>
  );
}

function PricesSection({
  prices,
  t,
  mayEdit,
  onChanged,
}: {
  prices: PriceListDto;
  t: T;
  mayEdit: boolean;
  onChanged: () => void;
}) {
  const [draft, setDraft] = useState<PriceDraft | null>(null);
  const pager = usePricePaging(prices.prices);
  return (
    <Section title={t("pricesTitle")} description={t("pricesDesc")}>
      {prices.unpricedModels.length === 0 ? (
        <p className="text-sm text-muted-foreground" data-testid="roi-prices-all-priced">
          {t("pricesAllPriced")}
        </p>
      ) : (
        <Card className="gap-3 p-5" data-testid="roi-unpriced">
          <p className="text-sm">{t("unpricedIntro")}</p>
          <ul className="divide-y divide-border text-sm">
            {prices.unpricedModels.map((m) => (
              <li
                key={`${m.providerName}/${m.model}`}
                className="flex flex-wrap items-center justify-between gap-2 py-2"
              >
                <span className="min-w-0">
                  <span className="font-medium">{m.model}</span>{" "}
                  <span className="text-muted-foreground">{m.providerName}</span>
                  <span className="ms-2 text-xs text-muted-foreground">
                    {t("unpricedCalls", { calls: formatCount(m.calls) })}
                  </span>
                </span>
                {mayEdit && (
                  <Button type="button" variant="outline" size="sm" onClick={() => setDraft(draftFromUnpriced(m))}>
                    {t("setPrice")}
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </Card>
      )}
      {mayEdit && draft && (
        <PriceForm
          model={draft}
          t={t}
          onClose={() => setDraft(null)}
          onSaved={() => {
            setDraft(null);
            onChanged();
          }}
        />
      )}
      {prices.prices.length > 0 && (
        <ul className="space-y-1 text-sm" data-testid="roi-price-list">
          {pager.pageItems.map((p) => (
            <li key={p.id} className="flex flex-wrap items-center gap-2">
              <span>
                <span className="font-medium">{p.modelPrefix}</span>{" "}
                <span className="text-muted-foreground">{p.providerName}</span>{" "}
                {t("priceLine", {
                  input: formatUsd(p.inputPerMillion, 4),
                  output: formatUsd(p.outputPerMillion, 4),
                  date: p.effectiveFrom,
                })}
              </span>
              {mayEdit && (
                <Button
                  type="button"
                  variant="ghost"
                  size="sm"
                  onClick={() => setDraft(draftFromPrice(p))}
                  aria-label={t("editPriceFor", { model: p.modelPrefix })}
                >
                  <Pencil className="h-4 w-4" aria-hidden="true" />
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}
      {prices.prices.length > 0 && <PricesPager pager={pager} />}
    </Section>
  );
}

/** What the price form starts from: a blank form for an unpriced model, or the stored rates of the version being replaced. */
interface PriceDraft {
  providerName: string;
  modelPrefix: string;
  input: string;
  output: string;
  cacheWrite: string;
  cacheRead: string;
}

const asText = (v: Money): string => (v === null ? "" : String(v));

function draftFromUnpriced(m: UnpricedModelDto): PriceDraft {
  return { providerName: m.providerName, modelPrefix: m.model, input: "", output: "", cacheWrite: "", cacheRead: "" };
}

function draftFromPrice(p: PriceDto): PriceDraft {
  return {
    providerName: p.providerName,
    modelPrefix: p.modelPrefix,
    input: asText(p.inputPerMillion),
    output: asText(p.outputPerMillion),
    cacheWrite: asText(p.cacheWritePerMillion),
    cacheRead: asText(p.cacheReadPerMillion),
  };
}

function PriceForm({
  model,
  t,
  onClose,
  onSaved,
}: {
  model: PriceDraft;
  t: T;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [prefix, setPrefix] = useState(model.modelPrefix);
  const [input, setInput] = useState(model.input);
  const [output, setOutput] = useState(model.output);
  const [cacheWrite, setCacheWrite] = useState(model.cacheWrite);
  const [cacheRead, setCacheRead] = useState(model.cacheRead);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const [i, o, cw, cr] = [parseAmount(input), parseAmount(output), parseAmount(cacheWrite), parseAmount(cacheRead)];
    if ([i, o, cw, cr].includes(undefined) || !prefix.trim()) return setError(t("priceInvalid"));
    setSaving(true);
    setError(null);
    try {
      await roiApi.setPrice({
        providerName: model.providerName,
        modelPrefix: prefix.trim(),
        inputPerMillion: i as number | null,
        outputPerMillion: o as number | null,
        cacheWritePerMillion: cw as number | null,
        cacheReadPerMillion: cr as number | null,
      });
      onSaved();
    } catch (err) {
      setError(message(err));
      setSaving(false);
    }
  };

  return (
    <Card className="gap-4 p-5" data-testid="roi-price-form">
      <form
        onSubmit={(e) => void submit(e)}
        className="space-y-4"
        aria-label={t("priceFormTitle", { model: model.modelPrefix })}
      >
        <div>
          <h3 className="text-base font-semibold">{t("priceFormTitle", { model: model.modelPrefix })}</h3>
          <p className="text-sm text-muted-foreground">{t("priceFormHelp")}</p>
        </div>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <div className="space-y-1">
            <Label htmlFor="roi-prefix">{t("pricePrefix")}</Label>
            <Input id="roi-prefix" value={prefix} onChange={(e) => setPrefix(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="roi-in">{t("priceInput")}</Label>
            <Input id="roi-in" inputMode="decimal" value={input} onChange={(e) => setInput(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="roi-out">{t("priceOutput")}</Label>
            <Input id="roi-out" inputMode="decimal" value={output} onChange={(e) => setOutput(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="roi-cw">{t("priceCacheWrite")}</Label>
            <Input id="roi-cw" inputMode="decimal" value={cacheWrite} onChange={(e) => setCacheWrite(e.target.value)} />
          </div>
          <div className="space-y-1">
            <Label htmlFor="roi-cr">{t("priceCacheRead")}</Label>
            <Input id="roi-cr" inputMode="decimal" value={cacheRead} onChange={(e) => setCacheRead(e.target.value)} />
          </div>
        </div>
        {error && (
          <p role="alert" className="text-sm" style={{ color: "var(--state-error)" }}>
            {error}
          </p>
        )}
        <div className="flex flex-wrap gap-2">
          <Button type="submit" size="sm" disabled={saving}>
            {t("save")}
          </Button>
          <Button type="button" variant="outline" size="sm" onClick={onClose}>
            {t("cancel")}
          </Button>
        </div>
      </form>
    </Card>
  );
}
