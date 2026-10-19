"use client";

import { Button } from "../../../components/ui/button";
import { Card, CardContent } from "../../../components/ui/card";
import { Input } from "../../../components/ui/input";
import { Textarea } from "../../../components/ui/textarea";

export interface CreateDatasetDraft {
  name: string;
  description: string;
  threshold: string;
  toolExec: boolean;
}

/** The "New dataset" card. State is owned by the section; this is presentational. */
export default function CreateDatasetForm({
  draft,
  onChange,
  busy,
  onCancel,
  onCreate,
}: {
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
        <label className="flex items-center gap-2 text-sm text-foreground">
          <input type="checkbox" checked={draft.toolExec} onChange={(e) => onChange({ toolExec: e.target.checked })} />
          Execute tools during replays
          <span className="text-xs text-muted-foreground">
            (off = safe tool-less replay; on = real tool side effects, required for expected-tool checks)
          </span>
        </label>
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
