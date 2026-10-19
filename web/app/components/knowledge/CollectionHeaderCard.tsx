"use client";

import Link from "next/link";
import { Plus } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Card } from "../ui/card";
import { Tooltip, TooltipContent, TooltipTrigger } from "../ui/tooltip";
import { initialsOf, type KnowledgeCollectionSummary } from "./knowledgeFormat";

/** Collection title, real file/chunk counts, and the agents it is exposed to. */
export default function CollectionHeaderCard({
  selectedCollection,
  exposedAgents,
}: {
  selectedCollection: KnowledgeCollectionSummary;
  exposedAgents: any[];
}) {
  const t = useTranslations("knowledgePanel");
  return (
    <Card className="gap-0 p-5">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="min-w-0 space-y-1">
          <h2 className="text-lg font-semibold tracking-tight text-foreground">{selectedCollection.name}</h2>
          <div className="text-sm text-muted-foreground">
            {selectedCollection.isDefault && (
              <span className="text-[var(--state-success)]">{t("defaultBadgeLabel")} · </span>
            )}
            {/* Real fileCount/chunkCount from KnowledgeCollectionResponse - see
             * KnowledgeDocumentService.fileCount/chunkCount for the exact queries
             * (migration V45, task #541). */}
            {t("collectionMetaLine", {
              fileCount: selectedCollection.fileCount,
              filePlural: selectedCollection.fileCount === 1 ? "" : "s",
              chunkCount: selectedCollection.chunkCount,
              chunkPlural: selectedCollection.chunkCount === 1 ? "" : "s",
              model: selectedCollection.embeddingModel,
              date: new Date(selectedCollection.updatedAt).toLocaleString(),
            })}
          </div>
          {selectedCollection.description && (
            <p className="text-sm text-muted-foreground">{selectedCollection.description}</p>
          )}
        </div>
        <div className="space-y-1.5">
          <div className="text-xs font-semibold uppercase tracking-wider text-muted-foreground">
            {t("exposedToAgentsLabel")}
          </div>
          <div className="flex items-center -space-x-1.5">
            {exposedAgents.slice(0, 4).map((a) => (
              <span
                key={a.id}
                className="flex size-7 items-center justify-center rounded-full border border-card bg-primary/15 text-[11px] font-semibold text-primary"
                title={a.name}
              >
                {initialsOf(a.name)}
              </span>
            ))}
            {exposedAgents.length > 4 && (
              <span className="flex size-7 items-center justify-center rounded-full border border-card bg-secondary text-[11px] font-semibold text-muted-foreground">
                +{exposedAgents.length - 4}
              </span>
            )}
            <Tooltip>
              <TooltipTrigger asChild>
                <Link
                  href="/agents"
                  className="flex size-7 items-center justify-center rounded-full border border-dashed border-border bg-card text-muted-foreground transition-colors hover:border-primary/40 hover:text-foreground"
                  aria-label={t("manageAgentAccessTooltip")}
                >
                  <Plus aria-hidden="true" size={13} />
                </Link>
              </TooltipTrigger>
              <TooltipContent>{t("manageAgentAccessTooltip")}</TooltipContent>
            </Tooltip>
          </div>
        </div>
      </div>
    </Card>
  );
}
