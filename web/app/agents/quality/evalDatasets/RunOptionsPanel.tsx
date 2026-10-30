"use client";

import { Input } from "../../../components/ui/input";
import { Select } from "../../../components/ui/select";
import { useTranslations } from "../../../lib/i18n";

export interface RunOptionValues {
  judgeModel: string;
  strictJudge: boolean;
  repeats: string;
  budget: string;
  /** "" = the dataset's pinned mode, else the workspace default; FULL or QUICK = this run only. */
  mode: "" | "FULL" | "QUICK";
}

/** Run options (V343) - applied to the NEXT triggered run. */
export default function RunOptionsPanel({
  values,
  onChange,
}: {
  values: RunOptionValues;
  onChange: (patch: Partial<RunOptionValues>) => void;
}) {
    const tHC = useTranslations("hcAgentsQualityEvalDatasetsRunOptionsPanel");
  const t = useTranslations("evalControls");
  return (
    <div className="space-y-3 rounded-md border border-border p-3">
      <h3 className="text-sm font-semibold text-foreground">{tHC("s8")}</h3>
      <div className="space-y-1">
        <label className="text-xs font-medium text-muted-foreground" id="eval-run-mode-label">
          {t("runModeLabel")}
        </label>
        <Select
          aria-labelledby="eval-run-mode-label"
          value={values.mode}
          onValueChange={(next) => onChange({ mode: next === "FULL" || next === "QUICK" ? next : "" })}
          options={[
            { value: "", label: t("runModeInherit") },
            { value: "FULL", label: t("modeNameFull") },
            { value: "QUICK", label: t("modeNameQuick") },
          ]}
        />
        <p className="text-xs text-muted-foreground">{t("runModeHelp")}</p>
      </div>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <div className="space-y-1">
          <label className="text-xs font-medium text-muted-foreground" htmlFor="eval-run-judge-model">
            {tHC("s7")}
          </label>
          <Input
            id="eval-run-judge-model"
            value={values.judgeModel}
            onChange={(e) => onChange({ judgeModel: e.target.value })}
            placeholder={tHC("s6")}
          />
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium text-muted-foreground" htmlFor="eval-run-repeats">
            {tHC("s5")}
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
            {tHC("s4")}
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
          {tHC("s3")}
          <span className="text-xs text-muted-foreground">{tHC("s2")}</span>
        </label>
      </div>
      <p className="text-xs text-muted-foreground">
        {tHC("s1")}
      </p>
    </div>
  );
}
