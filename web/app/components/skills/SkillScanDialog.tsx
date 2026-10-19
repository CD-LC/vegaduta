"use client";

// "Here is what the scan found - install?" Shown BEFORE a skill is saved, uploaded or installed
// from the registry. Nothing has been stored yet when this opens; Cancel leaves the workspace
// untouched. A scan reduces risk, it does not prove safety, and the copy says so.

import { useTranslations } from "../../lib/i18n";
import type { SkillScanPreview } from "../../lib/api/skillSecurity";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../ui/alert-dialog";
import SkillScanFindings from "./SkillScanFindings";

export type SkillScanPrompt = {
  preview: SkillScanPreview;
  confirmLabel: string;
  onConfirm: () => void;
};

export default function SkillScanDialog({
  prompt,
  busy,
  onCancel,
}: {
  prompt: SkillScanPrompt | null;
  busy: boolean;
  onCancel: () => void;
}) {
  const t = useTranslations("skillsPage");
  return (
    <AlertDialog open={!!prompt} onOpenChange={(open) => !open && !busy && onCancel()}>
      <AlertDialogContent>
        {prompt && (
          <>
            <AlertDialogHeader>
              <AlertDialogTitle>{t("scanDialogTitle", { name: prompt.preview.name })}</AlertDialogTitle>
              <AlertDialogDescription>
                {t("scanDialogIntro")}
                {prompt.preview.replacesExisting ? ` ${t("scanDialogReplaces")}` : ""}
              </AlertDialogDescription>
            </AlertDialogHeader>
            <div className="max-h-[50vh] overflow-y-auto pr-1">
              <SkillScanFindings report={prompt.preview.scan} />
            </div>
            <AlertDialogFooter>
              <AlertDialogCancel disabled={busy}>{t("cancelButton")}</AlertDialogCancel>
              <AlertDialogAction
                disabled={busy}
                onClick={(e) => {
                  e.preventDefault();
                  prompt.onConfirm();
                }}
              >
                {prompt.confirmLabel}
              </AlertDialogAction>
            </AlertDialogFooter>
          </>
        )}
      </AlertDialogContent>
    </AlertDialog>
  );
}
