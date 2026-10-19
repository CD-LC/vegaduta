"use client";

import type { SkillDetail } from "../../lib/api";
import { useTranslations } from "../../lib/i18n";
import { Button } from "../ui/button";
import { Card, CardContent } from "../ui/card";
import { Textarea } from "../ui/textarea";
import { SkillLintPanel, SkillPromptPreview, SkillTemplatePicker } from "./SkillAuthoringAids";

/**
 * The SKILL.md editor for a new or existing custom skill: template picker
 * (new skills only), the text, live lint and prompt preview, Save/Cancel.
 */
export default function SkillEditorForm({
  detail,
  draft,
  onDraft,
  existingNames,
  busy,
  onSave,
  onCancel,
}: {
  detail: SkillDetail | null;
  draft: string;
  onDraft: (draft: string) => void;
  existingNames: string[];
  busy: boolean;
  onSave: () => void;
  onCancel: () => void;
}) {
  const t = useTranslations("skillsPage");
  return (
    <Card>
      <CardContent className="space-y-3 p-5">
        <h2 className="text-base font-semibold text-foreground">
          {detail ? t("editHeading", { name: detail.name }) : t("newHeading")}
        </h2>
        <p className="text-xs leading-relaxed text-muted-foreground">
          {detail ? t("editHint") : t("newHint")}
        </p>
        {!detail && (
          <SkillTemplatePicker draft={draft} onPick={onDraft} />
        )}
        <Textarea
          value={draft}
          onChange={(e) => onDraft(e.target.value)}
          spellCheck={false}
          aria-label="SKILL.md"
          className="min-h-[50vh] font-mono text-xs leading-relaxed"
        />
        <SkillLintPanel
          draft={draft}
          existingNames={existingNames}
          editingName={detail?.name ?? null}
        />
        <SkillPromptPreview draft={draft} />
        <div className="flex gap-2">
          <Button size="sm" disabled={busy || !draft.trim()} onClick={onSave}>
            {busy ? t("savingButton") : t("saveButton")}
          </Button>
          <Button
            size="sm"
            variant="outline"
            disabled={busy}
            onClick={onCancel}
          >
            {t("cancelButton")}
          </Button>
        </div>
      </CardContent>
    </Card>
  );
}
