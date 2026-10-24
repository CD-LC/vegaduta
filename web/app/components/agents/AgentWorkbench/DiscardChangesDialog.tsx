"use client";

import { useTranslations } from "../../../lib/i18n";
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from "../../ui/alert-dialog";
import { Button } from "../../ui/button";

/**
 * "Leave without saving?" for the Behavior tab. Staying is the default (focus lands on it, Escape means stay);
 * leaving drops the unsaved edits. Used for in-app links and for switching to another agent.
 */
export default function DiscardChangesDialog({
  open,
  onStay,
  onDiscard,
}: {
  open: boolean;
  onStay: () => void;
  onDiscard: () => void;
}) {
  const t = useTranslations("agentBehavior");
  return (
    <AlertDialog
      open={open}
      onOpenChange={(o) => {
        if (!o) onStay();
      }}
    >
      <AlertDialogContent data-testid="discard-changes-dialog">
        <AlertDialogHeader>
          <AlertDialogTitle>{t("discard.title")}</AlertDialogTitle>
          <AlertDialogDescription>{t("discard.description")}</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <Button type="button" variant="outline" onClick={onDiscard} data-testid="discard-confirm">
            {t("discard.leave")}
          </Button>
          <Button type="button" onClick={onStay} autoFocus data-testid="discard-stay">
            {t("discard.stay")}
          </Button>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
