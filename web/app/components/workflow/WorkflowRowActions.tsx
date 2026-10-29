"use client";

import { Check, Copy, Link2, Loader2, Pencil, Trash2 } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "../ui/tooltip";
import ShareAsTemplateButton from "../templates/ShareAsTemplateButton";
import { isPersonalEdition } from "../../lib/edition";
import WorkflowExportButton from "../WorkflowExportButton";

/**
 * The actions cell of one workflow row: edit, duplicate, copy link, share as
 * template, export (when portability is on) and delete (upgrade-locked on FREE).
 */
export default function WorkflowRowActions({
  wf,
  portable,
  isFreePlan,
  atLimit,
  duplicating,
  copied,
  deleting,
  onEdit,
  onDuplicate,
  onCopyLink,
  onDelete,
}: {
  wf: { id: string; name: string };
  portable: boolean;
  isFreePlan: boolean;
  atLimit: boolean;
  duplicating: boolean;
  copied: boolean;
  deleting: boolean;
  onEdit: () => void;
  onDuplicate: () => void;
  onCopyLink: () => void;
  onDelete: () => void;
}) {
    const tHC = useTranslations("hcComponentsWorkflowWorkflowRowActions");
  const t = useTranslations("workflowDesignerPanel");
  const deleteButton = (
    <Button
      variant="ghost"
      size="icon"
      className="size-8 text-muted-foreground hover:text-destructive"
      onClick={onDelete}
      disabled={deleting}
      upgradeLocked={isFreePlan}
      upgradeReason="Deleting workflows is a paid-plan feature."
      upgradeLabel={t("upgradeYourPlan")}
      aria-label={t("deleteWorkflow")}
    >
      <Trash2 aria-hidden="true" size={15} />
    </Button>
  );
  return (
    <div className="flex items-center justify-end gap-1">
      <Tooltip>
        <TooltipTrigger asChild>
          <Button variant="ghost" size="icon" className="size-8" onClick={onEdit} aria-label={t("editWorkflow")}>
            <Pencil aria-hidden="true" size={15} />
          </Button>
        </TooltipTrigger>
        <TooltipContent>{t("editWorkflow")}</TooltipContent>
      </Tooltip>
      <Tooltip>
        <TooltipTrigger asChild>
          <Button
            variant="ghost"
            size="icon"
            className="size-8"
            disabled={atLimit || duplicating}
            onClick={onDuplicate}
            aria-label={tHC("hcComponentsWorkflowWorkflowRowActions.s2")}
          >
            {duplicating ? (
              <Loader2 aria-hidden="true" size={15} className="animate-spin" />
            ) : (
              <Copy aria-hidden="true" size={15} />
            )}
          </Button>
        </TooltipTrigger>
        <TooltipContent>{atLimit ? "Workflow limit reached" : "Duplicate as a new draft"}</TooltipContent>
      </Tooltip>
      <Tooltip>
        <TooltipTrigger asChild>
          <Button variant="ghost" size="icon" className="size-8" onClick={onCopyLink} aria-label={tHC("hcComponentsWorkflowWorkflowRowActions.s1")}>
            {copied ? <Check aria-hidden="true" size={15} /> : <Link2 aria-hidden="true" size={15} />}
          </Button>
        </TooltipTrigger>
        <TooltipContent>{copied ? "Link copied" : "Copy link"}</TooltipContent>
      </Tooltip>
      {!isPersonalEdition() && (
        <ShareAsTemplateButton
          sourceId={wf.id}
          sourceName={wf.name}
          sourceKind="WORKFLOW"
          variant="ghost"
          size="icon"
          className="size-8"
        />
      )}
      {portable && <WorkflowExportButton workflowId={wf.id} workflowName={wf.name} />}
      {/* On FREE the Button renders its own upgrade tooltip
          (upgradeLocked), so only the unlocked button gets
          the plain "Delete workflow" one. */}
      {isFreePlan ? (
        deleteButton
      ) : (
        <Tooltip>
          <TooltipTrigger asChild>{deleteButton}</TooltipTrigger>
          <TooltipContent>{t("deleteWorkflow")}</TooltipContent>
        </Tooltip>
      )}
    </div>
  );
}
