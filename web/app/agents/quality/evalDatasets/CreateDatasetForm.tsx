"use client";

import { Button } from "../../../components/ui/button";
import { Card, CardContent } from "../../../components/ui/card";
import { Input } from "../../../components/ui/input";
import { Textarea } from "../../../components/ui/textarea";
import DatasetModeControls from "./DatasetModeControls";

export interface CreateDatasetDraft {
  name: string;
  description: string;
  threshold: string;
  /** true pins the full pipeline, false pins quick (text only), null pins nothing: the workspace default. */
  toolExec: boolean | null;
  /** The eval sandbox approves tool calls that would wait for a person (only when tools run). */
  autoApprove: boolean;
}

/** The "New dataset" card. State is owned by the section; this is presentational. */
export default function CreateDatasetForm({
  agentId,
  refusal = null,
  draft,
  onChange,
  busy,
  onCancel,
  onCreate,
}: {
  agentId: string | null;
  /** The server's sentence when it refused auto-approval for this workspace. */
  refusal?: string | null;
  draft: CreateDatasetDraft;
  onChange: (patch: Partial<CreateDatasetDraft>) => void;
  busy: boolean;
  onCancel: () => void;
  onCreate: () => void;
}) {
  return (
    <Card>
      <CardContent className="space-y-3 pt-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="space-y-1">
            <label className="text-xs font-medium text-muted-foreground" htmlFor="eval-dataset-new-name">
              Name
            </label>
            <Input
              id="eval-dataset-new-name"
              value={draft.name}
              onChange={(e) => onChange({ name: e.target.value })}
              placeholder="e.g. Billing FAQ regression set"
            />
          </div>
          <div className="space-y-1">
            <label className="text-xs font-medium text-muted-foreground" htmlFor="eval-dataset-new-threshold">
              Pass threshold (0–1)
            </label>
            <Input
              id="eval-dataset-new-threshold"
              value={draft.threshold}
              onChange={(e) => onChange({ threshold: e.target.value })}
              placeholder="0.7"
            />
          </div>
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium text-muted-foreground" htmlFor="eval-dataset-new-description">
            Description
          </label>
          <Textarea
            id="eval-dataset-new-description"
            rows={2}
            value={draft.description}
            onChange={(e) => onChange({ description: e.target.value })}
            placeholder="What this dataset protects against regressing"
          />
        </div>
        <DatasetModeControls
          agentId={agentId}
          refusal={refusal}
          disabled={busy}
          value={{ toolExecutionEnabled: draft.toolExec, autoApproveTools: draft.autoApprove }}
          onChange={(next) => onChange({ toolExec: next.toolExecutionEnabled, autoApprove: next.autoApproveTools })}
        />
        <div className="flex justify-end gap-2">
          <Button variant="outline" size="sm" onClick={() => onCancel()}>
            Cancel
          </Button>
          <Button size="sm" onClick={onCreate} disabled={busy || !draft.name.trim()}>
            Create dataset
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
