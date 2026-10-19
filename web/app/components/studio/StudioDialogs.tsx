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
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Restart with a new idea?</AlertDialogTitle>
          <AlertDialogDescription>
            This clears your agents, documents, connectors and workflows in this sandbox and opens a clean one. You can
            pick any topic next.
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Keep working</AlertDialogCancel>
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
  return (
    <AlertDialog open={!!agent} onOpenChange={(o) => !o && onCancel()}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Delete {agent?.name}?</AlertDialogTitle>
          <AlertDialogDescription>
            Its documents and connectors go with it.
            {agent?.live &&
              " It is your live agent, so its share link and Telegram bridge stop until another agent is made live."}
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={() => agent && onDelete(agent)}>Delete agent</AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
