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
          placeholder="Internal project codenames"
          disabled={busy}
          onChange={(e) => onDraftChange({ ...draft, name: e.target.value })}
        />
        <p className="text-xs text-muted-foreground">
          Shown to the agent when this rule stops something, so name it after what it protects.
        </p>
      </div>

      <div className="flex flex-col gap-1.5">
        <Label htmlFor="rule-description">Description (optional)</Label>
        <Input
          id="rule-description"
          value={draft.description}
          maxLength={MAX_DESCRIPTION}
          placeholder="Why this exists, for whoever reads it next"
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
            A regular expression, matched case-insensitively. Checked here as you type by your browser; the
            server checks it again with Java&apos;s regex engine, which differs on exotic syntax.
          </p>
        )}
      </div>

      <fieldset className="flex flex-col gap-2">
        <legend className="mb-1 text-sm font-medium">What happens on a match</legend>
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
              The whole result is withheld. The agent is told a rule stopped it, by name, and carries on with
              the rest of the turn.
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
              Only the matched text is replaced with <code>[REDACTED]</code>. The rest still reaches the
              model, which is usually what you want for something like a key or an internal id.
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
          placeholder="Paste some text to see whether this rule would catch it"
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
              <p className="text-muted-foreground">No match — this text would pass through untouched.</p>
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
