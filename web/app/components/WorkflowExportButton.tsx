"use client";

import { useState } from "react";
import { Download, Loader2 } from "lucide-react";
import { api } from "../lib/api";
import { triggerBrowserDownload } from "../lib/api/core";
import { toast } from "../lib/toastBus";
import { useTranslations } from "../lib/i18n";
import { Button } from "./ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "./ui/tooltip";

// Self-contained "Export workflow" action for a row in the workflows list. Downloads the workflow as the VegaDuta
// workflow DSL (JSON) - tenant ids, destinations and typed-in credentials are already replaced by named
// placeholders server-side (core/workflow WorkflowDslExporter), so the file is safe to keep or share.
//
// A 404 means export is switched off for this workspace or this core predates it: said once, quietly, as an
// info toast - never an error toast, nothing is broken.
export default function WorkflowExportButton({ workflowId, workflowName }: { workflowId: string; workflowName?: string }) {
  const t = useTranslations("workflowPortability");
  const [busy, setBusy] = useState(false);

  const run = async () => {
    if (busy) return;
    setBusy(true);
    try {
      const file = await api.exportWorkflowDsl(workflowId);
      triggerBrowserDownload(file.blob, file.filename);
      toast.success(t("exportDone", { name: file.filename }));
    } catch (err: any) {
      if (err?.status === 404) toast.info(t("exportUnavailable"));
      else toast.error(t("exportFailed"));
    } finally {
      setBusy(false);
    }
  };

  const label = workflowName ? t("exportNamed", { name: workflowName }) : t("exportWorkflow");
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <Button
          variant="ghost"
          size="icon"
          className="size-8"
          onClick={run}
          disabled={busy}
          aria-label={label}
          data-testid="workflow-export-button"
        >
          {busy ? <Loader2 size={15} className="animate-spin" aria-hidden="true" /> : <Download size={15} aria-hidden="true" />}
        </Button>
      </TooltipTrigger>
      <TooltipContent>{t("exportWorkflow")}</TooltipContent>
    </Tooltip>
  );
}
