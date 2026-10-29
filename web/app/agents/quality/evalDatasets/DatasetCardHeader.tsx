"use client";

// Header row of one eval dataset card: expand toggle, case count / pass bar /
// tools badges, and the Run, run-options, JSONL export and delete actions.

import { Braces, ChevronDown, ChevronRight, Play, Settings2, Trash2 } from "lucide-react";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "../../../components/ui/alert-dialog";
import { Badge } from "../../../components/ui/badge";
import { Button, buttonVariants } from "../../../components/ui/button";
import type { EvalDatasetSummary } from "../evalDatasetsApi";
import { pct } from "./evalBadges";
import { effectiveModeOf } from "./datasetMode";
import { useTranslations } from "../../../lib/i18n";

export function DatasetCardHeader({
  dataset,
  expanded,
  busy,
  onToggleExpand,
  onRun,
  onToggleRunOptions,
  onExportJsonl,
  onDelete,
}: {
  dataset: EvalDatasetSummary;
  expanded: boolean;
  busy: boolean;
  onToggleExpand: () => void;
  onRun: () => void;
  onToggleRunOptions: () => void;
  onExportJsonl: () => void;
  onDelete: () => void;
}) {
  const t = useTranslations("evalControls");
  // What a run of this dataset does right now when nobody names a mode: its pin, else the workspace default.
  const mode = effectiveModeOf(dataset);
  const pinned = dataset.toolExecutionEnabled != null;
  return (
    <div className="flex flex-wrap items-center gap-3">
      <button type="button" className="flex items-center gap-2 bg-transparent p-0 text-start" onClick={onToggleExpand}>
        {expanded ? <ChevronDown size={16} aria-hidden="true" /> : <ChevronRight size={16} aria-hidden="true" />}
        <span className="font-medium text-foreground">{dataset.name}</span>
      </button>
      <Badge variant="secondary">
        {dataset.caseCount} case{dataset.caseCount === 1 ? "" : "s"}
      </Badge>
      <Badge variant="outline">bar {pct(dataset.passThreshold)}</Badge>
      <Badge
        variant={mode === "FULL" ? "accent" : "outline"}
        title={t(pinned ? "datasetModePinnedTitle" : "datasetModeDefaultTitle")}
      >
        {t(mode === "FULL" ? "badgeFull" : "badgeQuick")}
      </Badge>
      {mode === "FULL" && dataset.autoApproveTools && (
        <Badge variant="outline" title="Tool calls that would wait for a person are approved by the eval sandbox">
          auto-approve
        </Badge>
      )}
      <div className="ms-auto flex items-center gap-2">
        <Button size="sm" onClick={onRun} disabled={busy || dataset.caseCount === 0}>
          <Play size={14} aria-hidden="true" /> Run
        </Button>
        <Button
          variant="outline"
          size="sm"
          onClick={onToggleRunOptions}
          title="Judge model, repeats and budget for the next run"
        >
          <Settings2 size={14} aria-hidden="true" />
        </Button>
        <Button
          variant="outline"
          size="sm"
          onClick={onExportJsonl}
          disabled={dataset.caseCount === 0}
          title="Download the dataset's live cases as JSONL (optimizer input)"
          aria-label={`Export dataset ${dataset.name} as JSONL`}
        >
          <Braces size={14} aria-hidden="true" />
        </Button>
        <AlertDialog>
          <AlertDialogTrigger asChild>
            <Button variant="outline" size="sm" disabled={busy} aria-label={`Delete dataset ${dataset.name}`}>
              <Trash2 size={14} aria-hidden="true" />
            </Button>
          </AlertDialogTrigger>
          <AlertDialogContent>
            <AlertDialogHeader>
              <AlertDialogTitle>Delete dataset &quot;{dataset.name}&quot;?</AlertDialogTitle>
              <AlertDialogDescription>
                This permanently deletes the dataset and all of its cases. This cannot be undone.
              </AlertDialogDescription>
            </AlertDialogHeader>
            <AlertDialogFooter>
              <AlertDialogCancel>Cancel</AlertDialogCancel>
              <AlertDialogAction
                className={buttonVariants({ variant: "destructive" })}
                onClick={onDelete}
                disabled={busy}
              >
                Delete
              </AlertDialogAction>
            </AlertDialogFooter>
          </AlertDialogContent>
        </AlertDialog>
      </div>
    </div>
  );
}
