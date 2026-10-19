"use client";

import { Input } from "../../../components/ui/input";

export interface RunOptionValues {
  judgeModel: string;
  strictJudge: boolean;
  repeats: string;
  budget: string;
}

/** Run options (V343) - applied to the NEXT triggered run. */
export default function RunOptionsPanel({
  values,
  onChange,
}: {
  values: RunOptionValues;
  onChange: (patch: Partial<RunOptionValues>) => void;
}) {
  return (
    <div className="space-y-3 rounded-md border border-border p-3">
      <h3 className="text-sm font-semibold text-foreground">Run options</h3>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <div className="space-y-1">
          <label className="text-xs font-medium text-muted-foreground" htmlFor="eval-run-judge-model">
            Judge model (optional)
          </label>
          <Input
            id="eval-run-judge-model"
            value={values.judgeModel}
            onChange={(e) => onChange({ judgeModel: e.target.value })}
            placeholder="e.g. gpt-4o-mini — blank = default"
          />
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium text-muted-foreground" htmlFor="eval-run-repeats">
            Repeats per case (1–5)
          </label>
          <Input
            id="eval-run-repeats"
            value={values.repeats}
            onChange={(e) => onChange({ repeats: e.target.value })}
            placeholder="1"
          />
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium text-muted-foreground" htmlFor="eval-run-budget">
            Budget (USD, default 5)
          </label>
          <Input
            id="eval-run-budget"
            value={values.budget}
            onChange={(e) => onChange({ budget: e.target.value })}
            placeholder="5"
          />
        </div>
        <label className="flex items-center gap-2 self-end pb-1 text-sm text-foreground">
          <input
            type="checkbox"
            checked={values.strictJudge}
            onChange={(e) => onChange({ strictJudge: e.target.checked })}
          />
          Strict judge
          <span className="text-xs text-muted-foreground">(refuse a judge identical to the agent model)</span>
        </label>
      </div>
      <p className="text-xs text-muted-foreground">
        Repeats replay each case k times and require a strict majority to pass — the non-determinism guard. The run
        cancels once observed spend exceeds the budget, and a cancelled run never satisfies a promotion gate.
      </p>
    </div>
  );
}
