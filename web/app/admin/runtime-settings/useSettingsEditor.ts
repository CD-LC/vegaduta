"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { SettingCatalogEntry, SettingsClient } from "../../lib/api/settings";
import { isDirty, needsReason, parseDraft, type Draft } from "../../components/settings/settingsModel";
import type { RiskyChange } from "../../components/settings/RiskyChangeDialog";
import { buildRiskyChange } from "../../components/settings/riskyChange";
import {
  useSettingsWriter,
  type EffectiveState,
  type SettingChange,
  type WriterState,
} from "../../components/settings/useSettings";
import type { SettingsText } from "../../components/settings/useSettingsText";

// The edit state of the workspace settings screen: drafts per field, the save flow (risky changes wait for a typed
// reason), reset, and the bulk operations the page adds on top (apply an import, reset a group). It mirrors what
// SettingsPanel does for one scope, but is a hook so the page can own the layout, filters and extra actions.

// What the risky-change dialog is asking about: a save (resolves with the keys that were saved) or the reset of
// high-risk / security settings (resolves with the typed reason, or null when it was cancelled).
type PendingDialog =
  | { kind: "write"; changes: SettingChange[]; risky: RiskyChange[]; resolve: (saved: string[]) => void }
  | { kind: "reset"; risky: RiskyChange[]; resolve: (reason: string | null) => void };

export interface SettingsEditor {
  writer: WriterState;
  drafts: Record<string, Draft>;
  hasUnsaved: boolean;
  message: string;
  riskyDialog: { open: boolean; risky: RiskyChange[] };
  onDraftChange: (key: string, draft: Draft | undefined) => void;
  onSave: (keys: string[]) => Promise<void>;
  onDiscard: (keys: string[]) => void;
  onReset: (key: string) => Promise<boolean>;
  confirmRisky: (reason: string) => Promise<void>;
  cancelRisky: () => void;
  /** Write these values; risky ones ask for a reason first. Resolves with the keys that were saved ([] if cancelled). */
  requestCommit: (changes: SettingChange[]) => Promise<string[]>;
  /**
   * Reset several overrides one after another; resolves with how many worked and how many did not. High-risk and
   * security keys need a typed reason: one prompt covers all of them, and cancelling it leaves them (counted in
   * `cancelled`, not as failures) while the others are still reset.
   */
  resetMany: (keys: string[]) => Promise<{ done: number; failed: number; cancelled: number }>;
  setMessage: (message: string) => void;
}

export function useSettingsEditor(opts: {
  client: SettingsClient;
  entries: SettingCatalogEntry[];
  effective: EffectiveState;
  text: SettingsText;
}): SettingsEditor {
  const { client, entries, effective, text } = opts;
  const { ui, label } = text;
  const writer = useSettingsWriter({ client, scope: "tenant", entries, effective });
  const { clearError } = writer;

  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [dialog, setDialog] = useState<PendingDialog | null>(null);
  const [message, setMessage] = useState("");
  const draftsRef = useRef(drafts);
  draftsRef.current = drafts;
  const live = useRef({ entries, effective, text });
  live.current = { entries, effective, text };

  // Closing the tab with unsaved edits asks first (the browser shows its own generic prompt).
  const hasUnsaved = entries.some((e) =>
    isDirty(e, drafts[e.key], effective.values[e.key] ? effective.values[e.key].value : e.default)
  );
  useEffect(() => {
    if (!hasUnsaved) return undefined;
    const warn = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = "";
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [hasUnsaved]);

  const focusField = (key: string) => {
    setTimeout(() => {
      document
        .querySelector<HTMLElement>(
          `[data-testid="setting-${key}"] input, [data-testid="setting-${key}"] textarea, [data-testid="setting-${key}"] [role="switch"], [data-testid="setting-${key}"] [role="combobox"]`
        )
        ?.focus();
    }, 0);
  };

  const onDraftChange = useCallback(
    (key: string, draft: Draft | undefined) => {
      setDrafts((cur) => {
        const next = { ...cur };
        if (draft) next[key] = draft;
        else delete next[key];
        return next;
      });
      setMessage("");
      clearError(key);
    },
    [clearError]
  );

  const runCommit = useCallback(
    async (changes: SettingChange[]): Promise<string[]> => {
      const saved = await writer.commit(changes);
      setDrafts((cur) => {
        const next = { ...cur };
        for (const k of saved) delete next[k];
        return next;
      });
      const failed = changes.filter((c) => !saved.includes(c.key));
      setMessage(failed.length ? ui("saveFailed", { count: failed.length }) : ui("saved", { count: saved.length }));
      if (failed.length) focusField(failed[0].key);
      return saved;
    },
    [writer, ui]
  );

  const requestCommit = useCallback(
    (changes: SettingChange[]): Promise<string[]> => {
      const risky: RiskyChange[] = [];
      for (const c of changes) {
        const entry = live.current.entries.find((e) => e.key === c.key);
        if (!entry || !needsReason(entry)) continue;
        const current = live.current.effective.values[c.key];
        risky.push(buildRiskyChange(live.current.text, entry, current ? current.value : entry.default, c.value));
      }
      if (!changes.length) return Promise.resolve([]);
      if (!risky.length) return runCommit(changes);
      return new Promise<string[]>((resolve) => setDialog({ kind: "write", changes, risky, resolve }));
    },
    [runCommit]
  );

  const onSave = useCallback(
    async (saveKeys: string[]) => {
      const changes: SettingChange[] = [];
      for (const key of saveKeys) {
        const entry = live.current.entries.find((e) => e.key === key);
        const draft = draftsRef.current[key];
        if (!entry || !draft) continue;
        const parsed = parseDraft(entry, draft);
        if (parsed.ok) changes.push({ key, value: parsed.value });
      }
      await requestCommit(changes);
    },
    [requestCommit]
  );

  const confirmRisky = useCallback(
    async (reason: string) => {
      if (!dialog) return;
      if (dialog.kind === "reset") {
        dialog.resolve(reason);
        setDialog(null);
        return;
      }
      const riskyKeys = new Set(dialog.risky.map((r) => r.key));
      const withReason = dialog.changes.map((c) => (riskyKeys.has(c.key) ? { ...c, reason } : c));
      const saved = await runCommit(withReason);
      dialog.resolve(saved);
      setDialog(null);
    },
    [dialog, runCommit]
  );

  const cancelRisky = useCallback(() => {
    if (dialog?.kind === "reset") dialog.resolve(null);
    else dialog?.resolve([]);
    setDialog(null);
  }, [dialog]);

  const onDiscard = useCallback(
    (discardKeys: string[]) => {
      setDrafts((cur) => {
        const next = { ...cur };
        for (const k of discardKeys) delete next[k];
        return next;
      });
      discardKeys.forEach(clearError);
      setMessage("");
    },
    [clearError]
  );

  // The typed reason for resetting these (high-risk or security) settings, or null when the prompt is cancelled.
  const askResetReason = useCallback((keys: string[]): Promise<string | null> => {
    const risky: RiskyChange[] = [];
    for (const key of keys) {
      const entry = live.current.entries.find((e) => e.key === key);
      if (!entry) continue;
      const current = live.current.effective.values[key];
      // A workspace override falls back to the platform value (the catalog default).
      risky.push(buildRiskyChange(live.current.text, entry, current ? current.value : entry.default, entry.default, "reset"));
    }
    return new Promise<string | null>((resolve) => setDialog({ kind: "reset", risky, resolve }));
  }, []);

  const needsReasonKey = useCallback((key: string) => {
    const entry = live.current.entries.find((e) => e.key === key);
    return entry != null && needsReason(entry);
  }, []);

  const onReset = useCallback(
    async (key: string): Promise<boolean> => {
      let reason: string | undefined;
      if (needsReasonKey(key)) {
        const typed = await askResetReason([key]);
        if (typed == null) return false;
        reason = typed;
      }
      setDrafts((cur) => {
        if (!(key in cur)) return cur;
        const next = { ...cur };
        delete next[key];
        return next;
      });
      const ok = await writer.reset(key, reason);
      const entry = live.current.entries.find((e) => e.key === key);
      setMessage(ok ? ui("resetDone", { label: entry ? label(entry) : key }) : ui("resetFailed"));
      return ok;
    },
    [writer, ui, label, askResetReason, needsReasonKey]
  );

  const resetMany = useCallback(
    async (keys: string[]) => {
      let done = 0;
      let failed = 0;
      let cancelled = 0;
      const riskyKeys = new Set(keys.filter(needsReasonKey));
      let reason: string | undefined;
      if (riskyKeys.size) {
        const typed = await askResetReason(keys.filter((k) => riskyKeys.has(k)));
        if (typed == null) cancelled = riskyKeys.size;
        else reason = typed;
      }
      // One after another: each reset refreshes the effective values, and a failure should not hide behind a burst.
      for (const key of keys) {
        if (riskyKeys.has(key) && reason === undefined) continue; // the reason prompt was cancelled
        setDrafts((cur) => {
          if (!(key in cur)) return cur;
          const next = { ...cur };
          delete next[key];
          return next;
        });
        if (await writer.reset(key, riskyKeys.has(key) ? reason : undefined)) done += 1;
        else failed += 1;
      }
      return { done, failed, cancelled };
    },
    [writer, askResetReason, needsReasonKey]
  );

  return {
    writer,
    drafts,
    hasUnsaved,
    message,
    riskyDialog: { open: dialog != null, risky: dialog?.risky ?? [] },
    onDraftChange,
    onSave,
    onDiscard,
    onReset,
    confirmRisky,
    cancelRisky,
    requestCommit,
    resetMany,
    setMessage,
  };
}
