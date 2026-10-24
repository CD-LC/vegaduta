"use client";

import { useId, useMemo, useState } from "react";
import { AlertTriangle, CheckCircle2, Info, XCircle } from "lucide-react";
import { Badge } from "../ui/badge";
import { Input } from "../ui/input";
import { Label } from "../ui/label";
import { Switch } from "../ui/switch";
import { Button } from "../ui/button";
import { useLocale, useTranslations } from "../../lib/i18n";
import type { TurnJournalEvent } from "../../lib/api/turns";
import {
  NO_FILTER,
  TIMELINE_CATEGORIES,
  buildTimeline,
  categoryCounts,
  filterTimeline,
  formatCostMinor,
  formatMs,
  formatStamp,
  type Severity,
  type TimelineCategory,
} from "./timelineModel";

const TYPE_VARIANT: Record<string, "default" | "secondary" | "outline" | "accent" | "destructive"> = {
  INTAKE: "secondary",
  GUARD: "outline",
  MODEL_CALL: "accent",
  TOOL_CALL: "accent",
  APPROVAL: "outline",
  VERIFY: "outline",
  COMMIT: "default",
};

const CATEGORY_LABEL: Record<TimelineCategory, string> = {
  intake: "tlCatIntake",
  model: "tlCatModel",
  tool: "tlCatTool",
  approval: "tlCatApproval",
  continuation: "tlCatContinuation",
  retry: "tlCatRetry",
  guardrail: "tlCatGuardrail",
  cache: "tlCatCache",
  verify: "tlCatVerify",
  finish: "tlCatFinish",
};

const SEVERITY: Record<Severity, { icon: typeof Info; border: string; text: string; labelKey: string }> = {
  ok: { icon: CheckCircle2, border: "border-border", text: "text-muted-foreground", labelKey: "tlSevOk" },
  info: { icon: Info, border: "border-border", text: "text-muted-foreground", labelKey: "tlSevInfo" },
  warn: { icon: AlertTriangle, border: "border-amber-500/60", text: "text-amber-600 dark:text-amber-400", labelKey: "tlSevWarn" },
  error: { icon: XCircle, border: "border-destructive", text: "text-destructive", labelKey: "tlSevError" },
};

function shortHash(h: string): string {
  return h ? `${h.slice(0, 10)}...${h.slice(-6)}` : "";
}

/**
 * The turn's events in order, in plain language: what happened (model call, tool call, approval, continuation,
 * retry or failover with its reason, guardrail action, cache hit, check, finish reason), whether it was a problem,
 * and how long it took, with filters. The journal stores hashes and statuses, never arguments or results, so the
 * hashes sit in a details block under each event.
 */
export default function TurnTimeline({ events, brokenAtSeq }: { events: TurnJournalEvent[]; brokenAtSeq: number | null }) {
  const t = useTranslations("turnInspector");
  const locale = useLocale();
  const uid = useId();
  const entries = useMemo(() => buildTimeline(events), [events]);
  const counts = useMemo(() => categoryCounts(entries), [entries]);
  const [categories, setCategories] = useState<ReadonlySet<TimelineCategory>>(NO_FILTER.categories);
  const [problemsOnly, setProblemsOnly] = useState(false);
  const [text, setText] = useState("");
  const filter = { categories, problemsOnly, text };
  const shown = useMemo(() => filterTimeline(entries, filter), [entries, categories, problemsOnly, text]); // eslint-disable-line react-hooks/exhaustive-deps
  const filtering = categories.size > 0 || problemsOnly || text.trim() !== "";

  const toggle = (c: TimelineCategory) =>
    setCategories((prev) => {
      const next = new Set(prev);
      if (next.has(c)) next.delete(c);
      else next.add(c);
      return next;
    });
  const clear = () => {
    setCategories(new Set());
    setProblemsOnly(false);
    setText("");
  };

  return (
    <div className="space-y-4">
      <section aria-label={t("tlFilterTitle")} className="space-y-3 rounded-lg border border-border bg-card p-3">
        <div className="flex flex-wrap items-center gap-2" role="group" aria-label={t("tlFilterCategories")}>
          {TIMELINE_CATEGORIES.filter((c) => counts[c] > 0).map((c) => (
            <Button
              key={c}
              type="button"
              size="sm"
              variant={categories.has(c) ? "default" : "outline"}
              aria-pressed={categories.has(c)}
              onClick={() => toggle(c)}
            >
              {t(CATEGORY_LABEL[c])} <span className="tabular-nums opacity-80">{counts[c]}</span>
            </Button>
          ))}
        </div>
        <div className="flex flex-wrap items-end gap-4">
          <div className="min-w-48 flex-1 space-y-1.5">
            <Label htmlFor={`${uid}-search`}>{t("tlSearch")}</Label>
            <Input
              id={`${uid}-search`}
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder={t("tlSearchPlaceholder")}
              spellCheck={false}
              autoComplete="off"
            />
          </div>
          <div className="flex items-center gap-2 pb-2">
            <Switch id={`${uid}-problems`} checked={problemsOnly} onCheckedChange={setProblemsOnly} aria-label={t("tlProblemsOnly")} />
            <Label htmlFor={`${uid}-problems`}>{t("tlProblemsOnly")}</Label>
          </div>
          {filtering && (
            <Button type="button" size="sm" variant="ghost" onClick={clear}>
              {t("tlClearFilters")}
            </Button>
          )}
        </div>
        <p role="status" aria-live="polite" className="text-xs text-muted-foreground">
          {t("tlShowing", { shown: shown.length, total: entries.length })}
        </p>
      </section>

      {shown.length === 0 ? (
        <p className="rounded-lg border border-dashed border-border p-4 text-sm text-muted-foreground">{t("tlNoMatch")}</p>
      ) : (
        <ol className="space-y-3" aria-label={t("timelineTitle")}>
          {shown.map((entry) => {
            const e = entry.event;
            const broken = brokenAtSeq !== null && brokenAtSeq >= 0 && e.seq >= brokenAtSeq;
            const sev = SEVERITY[entry.severity];
            const SevIcon = sev.icon;
            return (
              <li
                key={`${e.seq}-${e.hash}`}
                aria-label={t("eventLabel", { seq: e.seq, type: String(e.type) })}
                className={`rounded-lg border bg-card p-3 ${broken ? "border-destructive" : sev.border}`}
              >
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-xs tabular-nums text-muted-foreground">#{e.seq}</span>
                  <Badge variant={TYPE_VARIANT[String(e.type)] ?? "outline"}>{String(e.type)}</Badge>
                  <span className="text-xs text-muted-foreground">{t(CATEGORY_LABEL[entry.category])}</span>
                  <span className={`inline-flex items-center gap-1 text-xs ${sev.text}`}>
                    <SevIcon size={12} aria-hidden="true" />
                    <span className="sr-only">{t(sev.labelKey)}</span>
                  </span>
                  <span className="text-xs text-muted-foreground">{formatStamp(entry.event.createdAt, locale)}</span>
                </div>
                <p className="mt-2 break-words text-sm text-foreground">{t(entry.titleKey, entry.titleValues)}</p>
                {entry.reasonKey && <p className="mt-1 text-xs text-muted-foreground">{t(entry.reasonKey)}</p>}
                <dl className="mt-2 flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
                  {entry.offsetMs !== null && (
                    <div>
                      <dt className="inline">{t("tlAtStart")}: </dt>
                      <dd className="inline tabular-nums">+{formatMs(entry.offsetMs)}</dd>
                    </div>
                  )}
                  {entry.gapMs !== null && (
                    <div>
                      <dt className="inline">{t("tlGap")}: </dt>
                      <dd className="inline tabular-nums">{formatMs(entry.gapMs)}</dd>
                    </div>
                  )}
                  {entry.durationMs !== null && (
                    <div>
                      <dt className="inline">{t("tlDuration")}: </dt>
                      <dd className="inline tabular-nums">{formatMs(entry.durationMs)}</dd>
                    </div>
                  )}
                  {(entry.tokensIn !== null || entry.tokensOut !== null) && (
                    <div>
                      <dt className="inline">{t("tlTokens")}: </dt>
                      <dd className="inline tabular-nums">
                        {entry.tokensIn ?? "-"} / {entry.tokensOut ?? "-"}
                      </dd>
                    </div>
                  )}
                  {entry.fields.runTools && (
                    <div data-testid="tl-run-tools">
                      <dt className="inline">{t("tlRunTools")}: </dt>
                      <dd className="inline break-all font-mono">
                        {entry.fields.runTools === "none" ? t("tlRunToolsNone") : entry.fields.runTools.replaceAll(",", ", ")}
                      </dd>
                    </div>
                  )}
                  {entry.costMinor !== null && (
                    <div>
                      <dt className="inline">{t("tlCost")}: </dt>
                      <dd className="inline tabular-nums">{formatCostMinor(entry.costMinor, locale, entry.fields.currency)}</dd>
                    </div>
                  )}
                </dl>
                <details className="mt-2 text-xs text-muted-foreground">
                  <summary className="cursor-pointer select-none">{t("tlDetails")}</summary>
                  <p className="mt-1 break-words font-mono text-foreground">{e.summary || "-"}</p>
                  <dl className="mt-2 grid gap-x-4 gap-y-1 sm:grid-cols-2">
                    <div>
                      <dt className="inline">{e.type === "TOOL_CALL" ? t("tlArgsHash") : t("payloadHash")}: </dt>
                      <dd className="inline font-mono" title={e.payloadHash}>
                        {shortHash(e.payloadHash)}
                      </dd>
                    </div>
                    {entry.fields.resultSha && (
                      <div>
                        <dt className="inline">{t("tlResultDigest")}: </dt>
                        <dd className="inline font-mono">{entry.fields.resultSha}</dd>
                      </div>
                    )}
                    <div>
                      <dt className="inline">{t("prevHash")}: </dt>
                      <dd className="inline font-mono" title={e.prevHash}>
                        {shortHash(e.prevHash)}
                      </dd>
                    </div>
                    <div>
                      <dt className="inline">{t("eventHash")}: </dt>
                      <dd className="inline font-mono" title={e.hash}>
                        {shortHash(e.hash)}
                      </dd>
                    </div>
                  </dl>
                </details>
              </li>
            );
          })}
        </ol>
      )}
      <p className="text-xs text-muted-foreground">{t("tlContentNote")}</p>
    </div>
  );
}
