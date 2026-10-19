"use client";

import { Play } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Badge } from "../ui/badge";
import { Button } from "../ui/button";
import { Label } from "../ui/label";
import { Textarea } from "../ui/textarea";
import { prettyJson, type TestResult } from "./model";

/** The "try it" block of the tool form. Runs the version STORED on the server. */
export default function ToolTestPanel({
  saved,
  sampleInput,
  onSampleInputChange,
  testing,
  testResult,
  onRun,
}: {
  saved: boolean;
  sampleInput: string;
  onSampleInputChange: (value: string) => void;
  testing: boolean;
  testResult: TestResult | null;
  onRun: () => void;
}) {
  const t = useTranslations("customCodeTools");
  const editing = saved;
  return (
    <div className="rounded-lg border border-border bg-secondary/30 p-4">
      <div className="mb-2 font-mono text-xs font-semibold uppercase tracking-wider text-muted-foreground">
        {t("testSectionLabel")}
      </div>
      {!editing ? (
        <p className="text-sm text-muted-foreground">{t("testSaveFirst")}</p>
      ) : (
        <>
          <Label htmlFor="cct-sample" className="mb-1.5">
            {t("sampleInputLabel")}
          </Label>
          <Textarea
            id="cct-sample"
            value={sampleInput}
            onChange={(e) => onSampleInputChange(e.target.value)}
            rows={3}
            className="bg-background font-mono text-xs"
          />
          <Button type="button" variant="outline" size="sm" className="mt-2" onClick={onRun} disabled={testing}>
            <Play aria-hidden="true" size={13} /> {testing ? t("runningEllipsis") : t("runTestButton")}
          </Button>

          {testResult && (
            <div className="mt-3 space-y-2">
              <div className="flex items-center gap-2">
                <Badge variant="state" state={testResult.ok ? "var(--state-success)" : "var(--state-error)"}>
                  {testResult.ok ? t("testOkLabel") : t("testFailedLabel")}
                </Badge>
                <span className="text-xs text-muted-foreground tabular-nums">
                  {t("testDurationLabel", { ms: testResult.durationMs ?? 0 })}
                </span>
              </div>

              {testResult.ok && (
                <div>
                  <div className="mb-1 text-xs font-medium text-muted-foreground">{t("testResultLabel")}</div>
                  <pre className="max-h-56 overflow-auto whitespace-pre-wrap break-words rounded-md bg-background p-3 font-mono text-xs text-foreground">
                    {prettyJson(testResult.result)}
                  </pre>
                </div>
              )}

              {/* stdout is shown for BOTH outcomes on purpose: a
                    print()/System.out trace is usually the only clue
                    for a run that failed halfway. */}
              {testResult.stdout && (
                <div>
                  <div className="mb-1 text-xs font-medium text-muted-foreground">{t("testStdoutLabel")}</div>
                  <pre className="max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-md bg-background p-3 font-mono text-xs text-muted-foreground">
                    {testResult.stdout}
                  </pre>
                </div>
              )}

              {!testResult.ok && (
                <div>
                  <div className="mb-1 text-xs font-medium text-[var(--danger-text)]">
                    {t("testErrorLabel")}
                    {testResult.errorType ? ` · ${testResult.errorType}` : ""}
                  </div>
                  <pre className="max-h-40 overflow-auto whitespace-pre-wrap break-words rounded-md border border-[var(--danger-border)] bg-[var(--danger-bg)] p-3 font-mono text-xs text-[var(--danger-text)]">
                    {testResult.error || t("testFailedFallback")}
                  </pre>
                </div>
              )}
            </div>
          )}
        </>
      )}
    </div>
  );
}
