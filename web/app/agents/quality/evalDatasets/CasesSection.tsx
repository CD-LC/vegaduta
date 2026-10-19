"use client";

import { Plus, Trash2 } from "lucide-react";
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
import { Input } from "../../../components/ui/input";
import { Textarea } from "../../../components/ui/textarea";
import PaginationControls from "../../../components/PaginationControls";
import type { EvalCase, EvalDatasetDetail, EvalDatasetSummary } from "../evalDatasetsApi";
import TrajectoryExpectedEditor from "../TrajectoryExpectedEditor";
import type { PageState } from "./pageState";

export interface CaseDraft {
  message: string;
  expected: string;
  tools: string;
}

/** Cases list + add-case form for one expanded dataset. */
export default function CasesSection({
  agentId,
  dataset,
  detail,
  pagination: casesPagination,
  draft,
  onDraftChange,
  busy,
  onAddCase,
  onDeleteCase,
}: {
  agentId: string | null;
  dataset: EvalDatasetSummary;
  detail: EvalDatasetDetail | null;
  pagination: PageState<EvalCase>;
  draft: CaseDraft;
  onDraftChange: (patch: Partial<CaseDraft>) => void;
  busy: boolean;
  onAddCase: () => void;
  onDeleteCase: (caseId: string) => void;
}) {
  return (
    <div className="space-y-2">
      <h3 className="text-sm font-semibold text-foreground">Cases</h3>
      {detail && detail.cases.length === 0 && (
        <p className="text-sm text-muted-foreground">No cases yet — add the first one below.</p>
      )}
      {casesPagination.pageItems.map((c) => (
        <div key={c.id} className="flex items-start gap-3 rounded-md border border-border p-3">
          <div className="min-w-0 flex-1 space-y-1">
            <p className="text-sm text-foreground">{c.userMessage}</p>
            {c.expectedOutput && (
              <p className="text-xs text-muted-foreground">
                <span className="font-medium">Expected:</span> {c.expectedOutput}
              </p>
            )}
            {c.expectedTools.length > 0 && (
              <div className="flex flex-wrap gap-1">
                {c.expectedTools.map((t) => (
                  <Badge key={t} variant="outline">
                    {t}
                  </Badge>
                ))}
              </div>
            )}
            {agentId && (
              <TrajectoryExpectedEditor
                agentId={agentId}
                datasetId={dataset.id}
                caseId={c.id}
                toolExecutionEnabled={dataset.toolExecutionEnabled}
              />
            )}
          </div>
          <AlertDialog>
            <AlertDialogTrigger asChild>
              <Button variant="outline" size="sm" className="shrink-0" aria-label="Delete case">
                <Trash2 size={13} aria-hidden="true" />
              </Button>
            </AlertDialogTrigger>
            <AlertDialogContent>
              <AlertDialogHeader>
                <AlertDialogTitle>Delete this case?</AlertDialogTitle>
                <AlertDialogDescription>
                  This permanently deletes the eval case. This cannot be undone.
                </AlertDialogDescription>
              </AlertDialogHeader>
              <AlertDialogFooter>
                <AlertDialogCancel>Cancel</AlertDialogCancel>
                <AlertDialogAction
                  className={buttonVariants({ variant: "destructive" })}
                  onClick={() => onDeleteCase(c.id)}
                >
                  Delete
                </AlertDialogAction>
              </AlertDialogFooter>
            </AlertDialogContent>
          </AlertDialog>
        </div>
      ))}
      <PaginationControls
        page={casesPagination.page}
        totalPages={casesPagination.totalPages}
        totalItems={casesPagination.totalItems}
        pageSize={casesPagination.pageSize}
        onPageChange={casesPagination.setPage}
        onPageSizeChange={casesPagination.setPageSize}
        itemLabel="cases"
      />

      <div className="space-y-2 rounded-md border border-dashed border-border p-3">
        <Textarea
          rows={2}
          value={draft.message}
          onChange={(e) => onDraftChange({ message: e.target.value })}
          placeholder="User message to replay (required)"
        />
        <Textarea
          rows={2}
          value={draft.expected}
          onChange={(e) => onDraftChange({ expected: e.target.value })}
          placeholder="Expected answer (optional — given to the accuracy judge as ground truth)"
        />
        <Input
          value={draft.tools}
          onChange={(e) => onDraftChange({ tools: e.target.value })}
          placeholder="Expected tools, comma-separated (optional; needs 'Execute tools' on)"
        />
        <div className="flex justify-end">
          <Button size="sm" onClick={onAddCase} disabled={busy || !draft.message.trim()}>
            <Plus size={14} aria-hidden="true" /> Add case
          </Button>
        </div>
      </div>
    </div>
  );
}
