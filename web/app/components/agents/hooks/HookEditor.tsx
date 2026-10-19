// The add/edit form for one agent hook: id, point, action, the matcher (tool points and ON_ERROR), the
// action-specific fields and a live plain-language preview. Split out of AgentHooksCard.

import { Button } from "../../ui/button";
import { Input } from "../../ui/input";
import { Select } from "../../ui/select";
import {
  ACTIONS_AT,
  HOOK_POINTS,
  SENSITIVITIES,
  TRUST_TIERS,
  describeDraft,
  hasErrors,
  hasMatcher,
  validateDraft,
  type DraftErrors,
  type HookAction,
  type HookDraft,
  type HookPoint,
  type Translate,
} from "../agentHooks";
import FieldError from "./FieldError";
import HookActionFields from "./HookActionFields";

export default function HookEditor({
  t,
  draft,
  otherIds,
  webhookHosts,
  onChange,
  onDone,
  onCancel,
}: {
  t: Translate;
  draft: HookDraft;
  otherIds: string[];
  webhookHosts: string[];
  onChange: (draft: HookDraft) => void;
  onDone: () => void;
  onCancel: () => void;
}) {
  const errors: DraftErrors = validateDraft(draft, otherIds);
  const set = (patch: Partial<HookDraft>) => onChange({ ...draft, ...patch });
  const actions = ACTIONS_AT[draft.point];

  function setPoint(point: HookPoint) {
    const action: HookAction = ACTIONS_AT[point].includes(draft.action) ? draft.action : ACTIONS_AT[point][0];
    // A matcher only exists at the tool points and ON_ERROR; drop it when moving away so nothing stale is sent.
    const cleared = hasMatcher(point) ? {} : { tool: "", trust: "", sensitivity: "", irreversible: "" as const };
    set({ point, action, ...cleared });
  }

  const showsAfterModelNote =
    draft.point === "AFTER_MODEL" && (draft.action === "redact" || draft.action === "block" || draft.action === "webhook");

  return (
    <div className="mt-4 rounded-md border border-border bg-secondary/20 p-3" data-testid="agent-hook-editor">
      <h4 className="text-xs font-semibold text-foreground">{t("editorTitle")}</h4>
      <div className="mt-2 grid grid-cols-[minmax(0,1fr)] gap-3 sm:grid-cols-[repeat(2,minmax(0,1fr))]">
        <div>
          <label htmlFor="agent-hook-id" className="mb-1 block text-xs text-muted-foreground">
            {t("fieldId")}
          </label>
          <Input
            id="agent-hook-id"
            data-testid="agent-hook-id"
            value={draft.id}
            autoComplete="off"
            aria-invalid={errors.id ? true : undefined}
            aria-describedby="agent-hook-id-error"
            onChange={(e) => set({ id: e.target.value })}
          />
          <p className="mt-1 text-xs text-muted-foreground">{t("fieldIdHint")}</p>
          <FieldError t={t} error={errors.id} id="agent-hook-id-error" />
        </div>
        <div>
          <label htmlFor="agent-hook-point" className="mb-1 block text-xs text-muted-foreground">
            {t("fieldPoint")}
          </label>
          <Select
            id="agent-hook-point"
            value={draft.point}
            onValueChange={(v) => setPoint(v as HookPoint)}
            options={HOOK_POINTS.map((p) => ({ value: p, label: t(`when.${p}`) }))}
          />
        </div>
        <div>
          <label htmlFor="agent-hook-action" className="mb-1 block text-xs text-muted-foreground">
            {t("fieldAction")}
          </label>
          <Select
            id="agent-hook-action"
            value={draft.action}
            onValueChange={(v) => set({ action: v as HookAction })}
            options={actions.map((a) => ({ value: a, label: t(`actionLabel.${a}`) }))}
          />
        </div>
      </div>

      {hasMatcher(draft.point) && (
        <div className="mt-3 grid grid-cols-[minmax(0,1fr)] gap-3 sm:grid-cols-[repeat(2,minmax(0,1fr))]" data-testid="agent-hook-matcher">
          <div>
            <label htmlFor="agent-hook-tool" className="mb-1 block text-xs text-muted-foreground">
              {t("fieldTool")}
            </label>
            <Input
              id="agent-hook-tool"
              data-testid="agent-hook-tool"
              value={draft.tool}
              placeholder={t("fieldToolPlaceholder")}
              autoComplete="off"
              aria-invalid={errors.tool ? true : undefined}
              aria-describedby="agent-hook-tool-error"
              onChange={(e) => set({ tool: e.target.value })}
            />
            <FieldError t={t} error={errors.tool} id="agent-hook-tool-error" />
          </div>
          <div>
            <label htmlFor="agent-hook-trust" className="mb-1 block text-xs text-muted-foreground">
              {t("fieldTrust")}
            </label>
            <Select
              id="agent-hook-trust"
              value={draft.trust}
              onValueChange={(v) => set({ trust: v })}
              options={[{ value: "", label: t("any") }, ...TRUST_TIERS.map((v) => ({ value: v, label: t(`trustLabel.${v}`) }))]}
            />
          </div>
          <div>
            <label htmlFor="agent-hook-sensitivity" className="mb-1 block text-xs text-muted-foreground">
              {t("fieldSensitivity")}
            </label>
            <Select
              id="agent-hook-sensitivity"
              value={draft.sensitivity}
              onValueChange={(v) => set({ sensitivity: v })}
              options={[
                { value: "", label: t("any") },
                ...SENSITIVITIES.map((v) => ({ value: v, label: t(`sensitivityLabel.${v}`) })),
              ]}
            />
          </div>
          <div>
            <label htmlFor="agent-hook-irreversible" className="mb-1 block text-xs text-muted-foreground">
              {t("fieldIrreversible")}
            </label>
            <Select
              id="agent-hook-irreversible"
              value={draft.irreversible}
              onValueChange={(v) => set({ irreversible: v as HookDraft["irreversible"] })}
              options={[
                { value: "", label: t("any") },
                { value: "true", label: t("irreversibleYes") },
                { value: "false", label: t("irreversibleNo") },
              ]}
            />
          </div>
        </div>
      )}

      <HookActionFields t={t} draft={draft} errors={errors} webhookHosts={webhookHosts} set={set} />

      {showsAfterModelNote && <p className="mt-3 text-xs text-muted-foreground">{t("afterModelNote")}</p>}

      <div className="mt-3 rounded-md border border-border bg-card px-3 py-2">
        <p className="text-xs font-medium text-foreground">{t("preview")}</p>
        <p className="mt-0.5 text-xs text-muted-foreground" data-testid="agent-hooks-preview">
          {describeDraft(draft, t)}
        </p>
      </div>

      <div className="mt-3 flex gap-2">
        <Button type="button" size="sm" data-testid="agent-hook-done" disabled={hasErrors(errors)} onClick={onDone}>
          {t("done")}
        </Button>
        <Button type="button" size="sm" variant="ghost" onClick={onCancel}>
          {t("cancel")}
        </Button>
      </div>
    </div>
  );
}
