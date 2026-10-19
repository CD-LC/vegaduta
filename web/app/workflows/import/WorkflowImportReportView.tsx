"use client";

import { AlertTriangle, Ban, CheckCircle2, Info, type LucideIcon } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import type { ImportReport } from "../../lib/api/agentImport";
import { groupReport, needsAttention, nonEmptySections, type ReportSectionId } from "../../agents/import/importReport";

// The conversion report for a workflow import: what carried over, what needs the
// person's hand, and what has no equivalent. The wording of each item comes from
// the server and is shown as-is (display text only); only the section headings
// are translated. Same structure as the agent importer's report, deliberately -
// the pure grouping helpers are shared, the strings live in this page's own
// namespace.

const SECTION_META: Record<ReportSectionId, { icon: LucideIcon; tone: string; titleKey: string }> = {
  converted: { icon: CheckCircle2, tone: "text-[var(--state-success)]", titleKey: "sectionConverted" },
  needsManual: { icon: AlertTriangle, tone: "text-[var(--state-warning)]", titleKey: "sectionNeedsManual" },
  unsupported: { icon: Ban, tone: "text-muted-foreground", titleKey: "sectionUnsupported" },
};

export default function WorkflowImportReportView({ report }: { report: ImportReport }) {
  const t = useTranslations("workflowPortability");
  const sections = nonEmptySections(groupReport(report));
  const attention = needsAttention(report);

  return (
    <div className="space-y-4" data-testid="workflow-import-report">
      {!attention && (
        <p className="flex items-center gap-2 text-sm text-muted-foreground">
          <CheckCircle2 className="size-4 shrink-0 text-[var(--state-success)]" aria-hidden="true" />
          {t("nothingToFix")}
        </p>
      )}

      {sections.map((section) => {
        const meta = SECTION_META[section.id];
        const Icon = meta.icon;
        const headingId = `workflow-import-report-${section.id}`;
        return (
          <section
            key={section.id}
            aria-labelledby={headingId}
            data-testid={`workflow-import-report-${section.id}`}
            className="rounded-lg border border-border bg-card/40"
          >
            <h3 id={headingId} className="flex items-center gap-2 px-4 py-3 text-sm font-semibold text-foreground">
              <Icon className={`size-4 shrink-0 ${meta.tone}`} aria-hidden="true" />
              {t(meta.titleKey, { count: section.items.length })}
            </h3>
            <ul className="divide-y divide-border border-t border-border">
              {section.items.map((item) => (
                <li key={item.key} className="space-y-1 px-4 py-3 text-sm">
                  <p className="break-words font-medium text-foreground">{item.title}</p>
                  {item.body && <p className="break-words text-muted-foreground">{item.body}</p>}
                  {item.hint && <p className="break-words text-foreground/90">{item.hint}</p>}
                </li>
              ))}
            </ul>
          </section>
        );
      })}

      {report.warnings.length > 0 && (
        <section
          aria-labelledby="workflow-import-report-warnings"
          data-testid="workflow-import-report-warnings"
          className="rounded-lg border border-border bg-card/40"
        >
          <h3
            id="workflow-import-report-warnings"
            className="flex items-center gap-2 px-4 py-3 text-sm font-semibold text-foreground"
          >
            <Info className="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
            {t("sectionWarnings")}
          </h3>
          <ul className="space-y-1 border-t border-border px-4 py-3 text-sm text-muted-foreground">
            {report.warnings.map((warning, i) => (
              <li key={i} className="break-words">
                {warning}
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
