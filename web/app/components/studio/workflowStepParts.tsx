"use client";

import { useState } from "react";
import { Bot, GitBranch, Plus, Send, UserCheck, Zap } from "lucide-react";
import type { WorkflowStepType } from "./studioClient";
import { STEP_TYPES } from "./studioContent";

// Small pieces of the studio workflow canvas: the icon per step type and the
// "+" menu that inserts a step between two cards.

export function StepIcon({ type }: { type: WorkflowStepType }) {
  const cls = "h-4 w-4";
  switch (type) {
    case "trigger":
      return <Zap aria-hidden="true" className={cls} />;
    case "agent":
      return <Bot aria-hidden="true" className={cls} />;
    case "condition":
      return <GitBranch aria-hidden="true" className={cls} />;
    case "approval":
      return <UserCheck aria-hidden="true" className={cls} />;
    case "output":
      return <Send aria-hidden="true" className={cls} />;
  }
}

export function AddMenu({ onAdd }: { onAdd: (type: WorkflowStepType) => void }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="mt-1 rounded-full border border-border/60 p-1 text-muted-foreground hover:border-primary/60 hover:text-foreground"
        aria-label="Add a step here"
      >
        <Plus aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
      </button>
      {open && (
        <div className="absolute left-1/2 z-10 mt-1 w-56 -translate-x-1/2 rounded-xl border border-border/60 bg-background p-1 shadow-lg">
          {STEP_TYPES.map((t) => (
            <button
              key={t.type}
              type="button"
              onClick={() => {
                onAdd(t.type);
                setOpen(false);
              }}
              className="flex w-full items-start gap-2 rounded-lg p-2 text-left text-sm hover:bg-muted"
            >
              <StepIcon type={t.type} />
              <span>
                <span className="font-medium">{t.label}</span>
                <span className="block text-[11px] text-muted-foreground">{t.blurb}</span>
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
