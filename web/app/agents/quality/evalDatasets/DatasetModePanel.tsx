"use client";

import { useEffect, useState } from "react";
import { Button } from "../../../components/ui/button";
import { useTranslations } from "../../../lib/i18n";
import type { EvalDatasetSummary } from "../evalDatasetsApi";
import DatasetModeControls from "./DatasetModeControls";
import { autoApproveRefusal, effectiveModeOf, normalizeMode, sameMode, type DatasetModeValue } from "./datasetMode";

/**
 * The replay-mode editor of one existing dataset: shows the saved mode, lets the user change it, and saves it
 * explicitly (a mode change alters what the next run measures, so it is never applied by a stray click). A refused
 * auto-approval keeps the saved mode, explains why, and leaves the switch off.
 */
export default function DatasetModePanel({
  agentId,
  dataset,
  save,
}: {
  agentId: string | null;
  dataset: EvalDatasetSummary;
  /** Persists the mode; rejects with the server's error. The parent refreshes the dataset afterwards. */
  save: (next: DatasetModeValue) => Promise<void>;
}) {
  const t = useTranslations("evalControls");
  const saved: DatasetModeValue = normalizeMode({
    toolExecutionEnabled: dataset.toolExecutionEnabled ?? null,
    autoApproveTools: dataset.autoApproveTools === true,
  });
  const [draft, setDraft] = useState<DatasetModeValue>(saved);
  const [busy, setBusy] = useState(false);
  const [refusal, setRefusal] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  // A refresh that changes the saved mode (another tab, a rollback) resets the draft to it.
  useEffect(() => {
    setDraft(saved);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dataset.id, saved.toolExecutionEnabled, saved.autoApproveTools]);

  const dirty = !sameMode(draft, saved);

  const submit = async () => {
    setBusy(true);
    setError(null);
    setDone(false);
    try {
      await save(normalizeMode(draft));
      setRefusal(null);
      setDone(true);
    } catch (e) {
      const refused = autoApproveRefusal(e);
      if (refused) {
        setRefusal(refused);
        // Roll the draft back to what is saved, with auto-approval off.
        setDraft({ toolExecutionEnabled: draft.toolExecutionEnabled, autoApproveTools: false });
      } else {
        setError((e as { message?: string } | null)?.message || t("saveFailed"));
      }
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-3 rounded-md border border-border p-3">
      <DatasetModeControls
        agentId={agentId}
        value={draft}
        refusal={refusal}
        workspaceDefault={dataset.toolExecutionEnabled == null ? effectiveModeOf(dataset) : null}
        disabled={busy}
        onChange={(next) => {
          setDone(false);
          setDraft(next);
        }}
      />
      <div className="flex flex-wrap items-center gap-3">
        <Button type="button" size="sm" disabled={!dirty || busy} onClick={() => void submit()}>
          {busy ? t("saving") : t("saveMode")}
        </Button>
        {dirty && (
          <Button type="button" size="sm" variant="ghost" disabled={busy} onClick={() => setDraft(saved)}>
            {t("discard")}
          </Button>
        )}
        <span role="status" aria-live="polite" className="text-xs text-muted-foreground">
          {done && !dirty ? t("saved") : dirty ? t("unsavedChanges") : ""}
        </span>
      </div>
      {error && (
        <p role="alert" className="text-xs text-destructive">
          {error}
        </p>
      )}
    </div>
  );
}
