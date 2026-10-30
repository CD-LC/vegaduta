"use client";

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
import type { StudioAgent } from "./studioClient";
import { useTranslations } from "../../lib/i18n";

// The /try studio's two confirmations: restart the sandbox, and delete an agent.

export function RestartSandboxDialog({
  open,
  onOpenChange,
  onRestart,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onRestart: () => void;
}) {
    const tHC = useTranslations("hcComponentsStudioStudioDialogs");
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>{tHC("s5")}</AlertDialogTitle>
          <AlertDialogDescription>
            {tHC("s4")}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>{tHC("s3")}</AlertDialogCancel>
          <AlertDialogAction onClick={onRestart}>Restart</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}

export function DeleteAgentDialog({
  agent,
  onCancel,
  onDelete,
}: {
  agent: StudioAgent | null;
  onCancel: () => void;
  onDelete: (agent: StudioAgent) => void;
}) {
    const tHC = useTranslations("hcComponentsStudioStudioDialogs");
  return (
    <AlertDialog open={!!agent} onOpenChange={(o) => !o && onCancel()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Delete {agent?.name}?</AlertDialogTitle>
          <AlertDialogDescription>
            {tHC("s2")}
            {agent?.live &&
              " It is your live agent, so its share link and Telegram bridge stop until another agent is made live."}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={() => agent && onDelete(agent)}>{tHC("s1")}</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
