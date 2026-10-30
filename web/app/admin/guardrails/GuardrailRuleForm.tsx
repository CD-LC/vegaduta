"use client";

// The create / edit form for one workspace guardrail rule, with the live
// "Try it" preview. State lives in the guardrails page (page.tsx).

import { Loader2, X } from "lucide-react";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../../components/ui/button";
import { Input } from "../../components/ui/input";
import { Label } from "../../components/ui/label";
import { Textarea } from "../../components/ui/textarea";
import { MAX_DESCRIPTION, MAX_NAME, MAX_PATTERN, type RuleDraft } from "./guardrailRules";

/** What the current draft would do to the sample text (null when there is nothing to preview). */
export type RulePreview = { matched: boolean; result: string; count: number } | null;

export function GuardrailRuleForm({
  draft,
  onDraftChange,
  editingId,
  busy,
  canAuthor,
  patternError,
  sample,
  onSampleChange,
  preview,
  onClose,
  onSave,
}: {
  draft: RuleDraft;
  onDraftChange: (draft: RuleDraft) => void;
  editingId: string | null;
  busy: boolean;
  canAuthor: boolean;
  patternError: string | null;
  sample: string;
  onSampleChange: (sample: string) => void;
  preview: RulePreview;
  onClose: () => void;
  onSave: () => void;
}) {
    const tHC = useTranslations("hcAdminGuardrailsGuardrailRuleForm");
  const tg = useTranslations("f2GapsAdmin");
  return (
    <div className="flex flex-col gap-4 rounded-lg border border-primary/30 bg-primary/5 p-4">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-medium">{editingId ? "Edit rule" : "New rule"}</h3>
        <Button type="button" size="sm" variant="outline" onClick={onClose} disabled={busy}>
          <X className="size-3.5" aria-hidden="true" /> Cancel
        </Button>
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="rule-name">Name</Label>
        <Input
          id="rule-name"
          value={draft.name}
          maxLength={MAX_NAME}
          placeholder={tHC("s11")}
          disabled={busy}
          onChange={(e) => onDraftChange({ ...draft, name: e.target.value })}
        />
        <p className="text-xs text-muted-foreground">
          {tHC("s10")}
        </p>
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="rule-description">{tHC("s9")}</Label>
        <Input
          id="rule-description"
          value={draft.description}
          maxLength={MAX_DESCRIPTION}
          placeholder={tHC("s8")}
          disabled={busy}
          onChange={(e) => onDraftChange({ ...draft, description: e.target.value })}
        />
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="rule-pattern">Pattern</Label>
        <Textarea
          id="rule-pattern"
          value={draft.pattern}
          maxLength={MAX_PATTERN}
          rows={2}
          spellCheck={false}
          className="font-mono text-xs"
          placeholder="project\s+bluejay"
          disabled={busy}
          onChange={(e) => onDraftChange({ ...draft, pattern: e.target.value })}
          aria-invalid={!!patternError}
          aria-describedby={patternError ? "rule-pattern-error" : undefined}
        />
        {patternError ? (
          <p id="rule-pattern-error" className="text-xs text-destructive">{patternError}</p>
        ) : (
          <p className="text-xs text-muted-foreground">
            {tHC("s7")}
          </p>
        )}
      </div>

      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">{tHC("s6")}</legend>
        <label className="flex cursor-pointer items-start gap-2 text-sm">
          <input
            type="radio"
            name="rule-action"
            className="mt-1"
            checked={draft.action === "BLOCK"}
            disabled={busy}
            onChange={() => onDraftChange({ ...draft, action: "BLOCK" })}
          />
          <span>
            <span className="font-medium">Block</span>
            <span className="block text-xs text-muted-foreground">
              {tHC("s5")}
            </span>
          </span>
        </label>
        <label className="flex cursor-pointer items-start gap-2 text-sm">
          <input
            type="radio"
            name="rule-action"
            className="mt-1"
            checked={draft.action === "REDACT"}
            disabled={busy}
            onChange={() => onDraftChange({ ...draft, action: "REDACT" })}
          />
          <span>
            <span className="font-medium">{tg("redact")}</span>
            <span className="block text-xs text-muted-foreground">
              {tHC("s4")} <code>[REDACTED]</code>{tHC("s3")}
            </span>
          </span>
        </label>
      </fieldset>

      <label className="flex cursor-pointer items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={draft.enabled}
          disabled={busy}
          onChange={(e) => onDraftChange({ ...draft, enabled: e.target.checked })}
        />
        Active
      </label>

      <div className="flex flex-col gap-1.5 rounded-md border border-border bg-card p-3">
        <Label htmlFor="rule-sample">Try it</Label>
        <Textarea
          id="rule-sample"
          value={sample}
          rows={2}
          spellCheck={false}
          className="text-xs"
          placeholder={tHC("s2")}
          onChange={(e) => onSampleChange(e.target.value)}
        />
        {preview && (
          <div className="text-xs">
            {preview.matched ? (
              <>
                <p className="text-foreground">
                  Matches {preview.count} time{preview.count === 1 ? "" : "s"} —{" "}
                  {draft.action === "BLOCK" ? "this text would be withheld entirely." : "it would become:"}
                </p>
                {draft.action === "REDACT" && (
                  <pre className="mt-1 max-h-32 overflow-auto whitespace-pre-wrap rounded bg-muted px-2 py-1 font-mono">
                    {preview.result}
                  </pre>
                )}
              </>
            ) : (
              <p className="text-muted-foreground">{tHC("s1")}</p>
            )}
          </div>
        )}
      </div>

      <div>
        <Button type="button" size="sm" disabled={busy || !canAuthor} onClick={onSave}>
          {busy && <Loader2 className="size-4 animate-spin" aria-hidden="true" />}
          {editingId ? "Save changes" : "Create rule"}
        </Button>
      </div>
    </div>
  );
}
