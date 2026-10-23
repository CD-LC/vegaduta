"use client";

import { useId } from "react";
import type { EffectiveSetting, SettingCatalogEntry, SettingRole, SettingScope, SettingsFieldError } from "../../lib/api/settings";
import { Button } from "../ui/button";
import SettingField from "./SettingField";
import { editability, isDirty, parseDraft, type Draft } from "./settingsModel";
import { useSettingsText } from "./useSettingsText";

export interface SettingsGroupProps {
  /** Catalog group id; the heading is its localized name. */
  group: string;
  entries: SettingCatalogEntry[];
  scope: SettingScope;
  values: Record<string, EffectiveSetting>;
  inherited: Record<string, EffectiveSetting>;
  drafts: Record<string, Draft>;
  errors: Record<string, SettingsFieldError>;
  pending: ReadonlySet<string>;
  role?: SettingRole;
  readOnly?: boolean;
  onDraftChange: (key: string, draft: Draft | undefined) => void;
  /** Save these keys (every dirty, valid one in the group). */
  onSave: (keys: string[]) => void | Promise<void>;
  onDiscard: (keys: string[]) => void;
  onReset: (key: string) => Promise<boolean> | boolean | void;
  onOpenHistory: (key: string) => void;
}

/**
 * A titled set of SettingFields with one save/discard bar for the whole group. The bar appears as soon as
 * any field is dirty, announces how many changes are waiting, and Save stays disabled while any draft is invalid
 * (focus jumps to the first invalid field when the user presses it anyway, e.g. with the keyboard).
 */
export default function SettingsGroup({
  group,
  entries,
  scope,
  values,
  inherited,
  drafts,
  errors,
  pending,
  role,
  readOnly,
  onDraftChange,
  onSave,
  onDiscard,
  onReset,
  onOpenHistory,
}: SettingsGroupProps) {
  const { ui, groupLabel } = useSettingsText();
  const headingId = useId();
  const dirtyEntries = entries.filter((e) => isDirty(e, drafts[e.key], values[e.key] ? values[e.key].value : e.default));
  const dirtyKeys = dirtyEntries.map((e) => e.key);
  const invalidKeys = dirtyEntries.filter((e) => !parseDraft(e, drafts[e.key]).ok).map((e) => e.key);
  const saving = dirtyKeys.some((k) => pending.has(k));

  return (
    <section aria-labelledby={headingId} data-testid={`settings-group-${group}`} className="space-y-3">
      <h3 id={headingId} className="text-sm font-semibold uppercase tracking-wide text-muted-foreground">
        {groupLabel(group)}
      </h3>
      <div className="grid gap-3 lg:grid-cols-2">
        {entries.map((entry) => (
          <SettingField
            key={entry.key}
            entry={entry}
            effective={values[entry.key]}
            inheritedValue={scope === "agent" ? inherited[entry.key]?.value : undefined}
            scope={scope}
            draft={drafts[entry.key]}
            onDraftChange={onDraftChange}
            onReset={onReset}
            onOpenHistory={onOpenHistory}
            error={errors[entry.key]}
            pending={pending.has(entry.key)}
            blocked={editability(entry, values[entry.key], { scope, role, readOnly })}
          />
        ))}
      </div>

      {/* Always mounted so the count is announced when it changes; the buttons only exist while there is work. */}
      <div
        className={dirtyKeys.length ? "sticky bottom-2 z-10 flex flex-wrap items-center gap-2 rounded-lg border border-primary/40 bg-card p-3 shadow-md" : "sr-only"}
        data-testid={`settings-savebar-${group}`}
      >
        <p role="status" aria-live="polite" className="mr-auto text-sm">
          {dirtyKeys.length
            ? `${ui("savebar.count", { count: dirtyKeys.length })}${invalidKeys.length ? ` ${ui("savebar.invalid", { count: invalidKeys.length })}` : ""}`
            : ""}
        </p>
        {dirtyKeys.length ? (
          <>
            <Button type="button" variant="outline" size="sm" disabled={saving} onClick={() => onDiscard(dirtyKeys)}>
              {ui("savebar.discard")}
            </Button>
            <Button
              type="button"
              size="sm"
              disabled={saving}
              aria-disabled={invalidKeys.length > 0 || undefined}
              onClick={() => {
                if (invalidKeys.length) {
                  // aria-disabled (not disabled) so keyboard users can still land here and be taken to the problem.
                  const first = document.querySelector<HTMLElement>(`[data-testid="setting-${invalidKeys[0]}"] [aria-invalid="true"]`);
                  first?.focus();
                  return;
                }
                void onSave(dirtyKeys);
              }}
            >
              {saving ? ui("savebar.saving") : ui("savebar.save")}
            </Button>
          </>
        ) : null}
      </div>
    </section>
  );
}
