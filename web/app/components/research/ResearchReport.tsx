"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { AlertTriangle, Clock, RotateCcw, Sparkles } from "lucide-react";
import RichText from "../RichText";
import { Card } from "../ui/card";
import { Alert, AlertDescription, AlertTitle } from "../ui/alert";
import { Badge } from "../ui/badge";
import { useTranslations } from "../../lib/i18n";
import ExportMenu from "./ExportMenu";
import ReportBody from "./ReportBody";
import SourcesPanel from "./SourcesPanel";
import { setResearchPrefill } from "./prefill";
import { deriveFollowUps, groupSources, readingMinutes, splitReport } from "./reportStructure";
import { RS } from "./researchStrings";
import { parseResearchResult, questionFromGoal, verdictTone } from "./researchRun";

// Print / "Save as PDF": only the report is drawn, in plain black on white.
const PRINT_CSS = `@media print {
  body * { visibility: hidden !important; }
  #research-print-root, #research-print-root * { visibility: visible !important; color: black !important; background: white !important; box-shadow: none !important; }
  #research-print-root { position: absolute; left: 0; top: 0; width: 100%; }
  .research-no-print { display: none !important; }
}`;

const PARTIAL_CLASS = "border-[color-mix(in_srgb,var(--state-warning)_40%,transparent)] text-[var(--state-warning)]";

/**
 * A finished run's report.
 *
 * Structured result (resultJson): the markdown is rendered section by section
 * (so the table of contents can target real headings) after citeLinks turns
 * each [S1] marker into a link to that source, followed by the questions left
 * unanswered, follow-up questions, and a Sources panel with the quoted
 * passages and what the citation check said about each.
 *
 * No structured result (the model missed the schema, or the job was started
 * some other way): whatever text the run produced is shown as it is.
 *
 * Everything here is model output: links are scheme-checked (http/https
 * only), nothing is rendered as HTML, and no remote image is fetched.
 */
export default function ResearchReport({ job, noToolCalls = false }: { job: any; noToolCalls?: boolean }) {
  const t = useTranslations("researchPage");
  const rawResult = job?.resultJson;
  const result = useMemo(() => parseResearchResult(rawResult), [rawResult]);
  const [jump, setJump] = useState<{ id: string; n: number } | null>(null);

  const outline = useMemo(() => (result ? splitReport(result.reportMarkdown, result.title) : null), [result]);
  const groups = useMemo(() => groupSources(result?.sources), [result]);
  const question = questionFromGoal(job?.goal);
  const followUps = useMemo(
    () => (result && outline ? deriveFollowUps(outline, result.unanswered, question) : []),
    [result, outline, question]
  );

  if (!result || !outline) {
    if (!job?.resultText) return null;
    return (
      <Card className="gap-3 p-5" data-testid="research-report-plain">
        <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{t("reportHeading")}</p>
        <p className="text-xs text-muted-foreground">{t("plainResultNote")}</p>
        <RichText text={job.resultText} />
      </Card>
    );
  }

  const minutes = readingMinutes(result.reportMarkdown);
  const failedChecks = groups.reduce(
    (n, g) => n + g.quotes.filter((q) => verdictTone(q.verdict) === "unsupported").length,
    0
  );

  return (
    <div className="space-y-5" data-testid="research-report">
      <style>{PRINT_CSS}</style>
      {(result.sources.length === 0 || noToolCalls) && (
        // A run that cites nothing, or whose trace holds no tool call at all, never searched: the model answered
        // from memory (or filled the JSON schema with placeholder sources) instead of calling its search tool, so
        // the text below is unverified and must not look like a finished report.
        <Alert variant="warning" data-testid="research-no-sources">
          <AlertTriangle aria-hidden="true" />
          <AlertTitle>{t("noSourcesWarnTitle")}</AlertTitle>
          <AlertDescription>
            <p>{t("noSourcesWarnBody")}</p>
            {job?.agentId && (
              <Link
                href={`/agents?agentId=${encodeURIComponent(String(job.agentId))}`}
                className="mt-2 inline-block text-sm font-medium underline underline-offset-2"
              >
                {t("noSourcesWarnAction")}
              </Link>
            )}
          </AlertDescription>
        </Alert>
      )}
      <div id="research-print-root" className="space-y-5">
        <Card className="gap-3 p-5">
          <div className="flex flex-wrap items-center gap-2">
            <h2 className="text-lg font-semibold text-foreground">{result.title || t("reportHeading")}</h2>
            {result.partial && (
              <Badge variant="outline" className={PARTIAL_CLASS} data-testid="research-partial">
                {t("partialBadge")}
              </Badge>
            )}
          </div>
          <p className="flex items-center gap-1.5 text-xs text-muted-foreground">
            <Clock className="size-3.5" aria-hidden="true" /> {RS.readingTime(minutes)}
          </p>
          {result.partial && <p className="text-xs text-muted-foreground">{t("partialNote")}</p>}
          {failedChecks > 0 && (
            <p className="text-xs text-destructive" data-testid="research-failed-checks">
              {failedChecks === 1
                ? "1 quote failed the citation check. Look for it in the sources below."
                : `${failedChecks} quotes failed the citation check. Look for them in the sources below.`}
            </p>
          )}
          <ReportBody
            outline={outline}
            sources={result.sources}
            groups={groups}
            onCite={(id) => setJump((prev) => ({ id, n: (prev?.n ?? 0) + 1 }))}
          />
          <div className="research-no-print border-t border-border pt-3">
            <ExportMenu result={result} />
          </div>
        </Card>

        {!!result.unanswered?.length && (
          <Card className="gap-2 p-5" data-testid="research-unanswered">
            <p className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
              {t("unansweredHeading")}
            </p>
            <p className="text-xs text-muted-foreground">{t("unansweredNote")}</p>
            <ul className="ml-5 list-disc space-y-1 text-sm text-foreground">
              {result.unanswered.map((u, i) => (
                <li key={i}>{u}</li>
              ))}
            </ul>
          </Card>
        )}

        <SourcesPanel groups={groups} sourceCount={result.sources.length} jump={jump} />
      </div>

      {(followUps.length > 0 || question) && (
        <Card className="research-no-print gap-3 p-5" data-testid="research-followups">
          <p className="flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-muted-foreground">
            <Sparkles className="size-3.5" aria-hidden="true" /> {RS.followUpsHeading}
          </p>
          <p className="text-xs text-muted-foreground">{RS.followUpsNote}</p>
          <ul className="flex flex-wrap gap-2">
            {question && (
              <li>
                <Link
                  href="/research"
                  onClick={() => setResearchPrefill(question)}
                  className="inline-flex items-center gap-1.5 rounded-full border border-primary/40 bg-primary/5 px-3 py-1.5 text-sm text-foreground hover:bg-primary/10"
                >
                  <RotateCcw className="size-3.5" aria-hidden="true" /> {RS.refine}
                </Link>
              </li>
            )}
            {followUps.map((f) => (
              <li key={`${f.kind}-${f.label}`}>
                <Link
                  href="/research"
                  onClick={() => setResearchPrefill(f.prompt)}
                  className="inline-flex max-w-full rounded-full border border-border px-3 py-1.5 text-left text-sm text-foreground hover:bg-muted [overflow-wrap:anywhere]"
                >
                  {f.label}
                </Link>
              </li>
            ))}
          </ul>
        </Card>
      )}
    </div>
  );
}
