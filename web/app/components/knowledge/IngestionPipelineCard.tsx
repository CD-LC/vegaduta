"use client";

import { Braces, Database, FileText, Loader2, Upload } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { cn } from "../../lib/utils";
import { Card } from "../ui/card";
import { formatBytes } from "./knowledgeFormat";

export interface PipelineStats {
  totalFiles: number;
  totalBytes: number;
  queuedOrUploaded: number;
  extracting: number;
  embedding: number;
  ready: number;
  failed: number;
  active: boolean;
}

/** Real 4-stage view (upload, extract, embed, index) over the collection's document statuses. */
export default function IngestionPipelineCard({
  pipelineStats,
  embeddingModel,
  uploading,
}: {
  pipelineStats: PipelineStats;
  embeddingModel?: string;
  uploading: boolean;
}) {
  const t = useTranslations("knowledgePanel");
  return (
    <Card
      className={cn("gap-0 p-5 transition-shadow", (uploading || pipelineStats.active) && "ring-1 ring-primary/40")}
    >
      <div className="mb-4 text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        {t("ingestionPipelineLabel")}
      </div>
      <div className="flex flex-wrap items-stretch gap-2">
        {[
          {
            glyph: <Upload size={14} aria-hidden="true" />,
            busy: false,
            name: t("uploadStageName"),
            sub: t("uploadStageSub", {
              count: pipelineStats.totalFiles,
              plural: pipelineStats.totalFiles === 1 ? "" : "s",
              bytes: formatBytes(pipelineStats.totalBytes),
            }),
            connectorActive: pipelineStats.queuedOrUploaded > 0 || pipelineStats.extracting > 0,
          },
          {
            glyph:
              pipelineStats.extracting > 0 ? (
                <Loader2 aria-hidden="true" size={15} className="animate-spin" />
              ) : (
                <FileText size={14} aria-hidden="true" />
              ),
            busy: pipelineStats.extracting > 0,
            name: t("extractStageName"),
            sub:
              pipelineStats.extracting > 0
                ? t("extractInProgress", { count: pipelineStats.extracting })
                : t("textExtractionLabel"),
            connectorActive: pipelineStats.extracting > 0 || pipelineStats.embedding > 0,
          },
          {
            glyph:
              pipelineStats.embedding > 0 ? (
                <Loader2 aria-hidden="true" size={15} className="animate-spin" />
              ) : (
                <Braces size={14} aria-hidden="true" />
              ),
            busy: pipelineStats.embedding > 0,
            name: t("embedStageName"),
            sub: `${embeddingModel}${pipelineStats.embedding > 0 ? t("embedInProgressSuffix", { count: pipelineStats.embedding }) : ""}`,
            connectorActive: pipelineStats.embedding > 0,
          },
          {
            glyph: <Database size={14} aria-hidden="true" />,
            busy: false,
            name: t("indexStageName"),
            sub: t("indexedStageSub", { count: pipelineStats.ready }),
            connectorActive: null,
          },
        ].map((stage, idx) => (
          <div key={idx} className="flex basis-[calc(50%-0.25rem)] items-start gap-2 sm:basis-0 sm:flex-1">
            <div className="min-w-0 flex-1 text-center">
              <div
                className={cn(
                  "mx-auto flex size-8 items-center justify-center rounded-lg text-sm",
                  stage.busy ? "bg-primary/10 text-primary" : "bg-secondary text-muted-foreground"
                )}
              >
                {stage.glyph}
              </div>
              <div className="mt-1.5 text-xs font-medium text-foreground">{stage.name}</div>
              <div className="mt-0.5 truncate text-xs text-muted-foreground">{stage.sub}</div>
            </div>
            {stage.connectorActive !== null && (
              <span
                className={cn(
                  "mt-4 hidden h-px w-full max-w-6 flex-1 self-start sm:block",
                  stage.connectorActive ? "bg-primary" : "bg-border"
                )}
              />
            )}
          </div>
        ))}
      </div>
      {pipelineStats.failed > 0 && (
        <div className="mt-3 text-xs text-[var(--danger-text)]">
          {t("pipelineFailedLine", {
            count: pipelineStats.failed,
            plural: pipelineStats.failed === 1 ? "" : "s",
          })}
        </div>
      )}
    </Card>
  );
}
