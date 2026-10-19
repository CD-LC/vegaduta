"use client";

import { Bot, Plus, Trash2 } from "lucide-react";
import { Button } from "../ui/button";
import { Badge } from "../ui/badge";
import type { StudioAgent } from "./studioClient";
import { LaunchChecklist, type LaunchProgress } from "./Conversion";
import { pick } from "./studioTheme";

// The /try studio's left rail: the visitor's own agents (select / delete), the
// "New agent" launcher button, the sandbox cap note and the launch checklist.

export default function AgentsRail({
  mine,
  maxAgents,
  selectedId,
  showingSelected,
  readOnly,
  atCap,
  progress,
  onSelect,
  onDelete,
  onNew,
}: {
  mine: StudioAgent[];
  /** The palette's per-sandbox cap, or null until the palette has loaded. */
  maxAgents: number | null;
  selectedId: string | null;
  /** True when the stage body shows the selected agent (not the launcher). */
  showingSelected: boolean;
  readOnly: boolean;
  atCap: boolean;
  progress: LaunchProgress;
  onSelect: (id: string) => void;
  onDelete: (agent: StudioAgent) => void;
  onNew: () => void;
}) {
  const agentButton = (a: StudioAgent) => (
    <div
      key={a.id}
      className={`group flex items-start gap-2 rounded-xl border p-3 text-sm transition-colors ${pick(
        "violet",
        selectedId === a.id && showingSelected
      )}`}
    >
      <button type="button" className="min-w-0 flex-1 text-left" onClick={() => onSelect(a.id)}>
        <div className="flex items-center gap-2 font-medium">
          <Bot aria-hidden="true" className="h-4 w-4 shrink-0" />
          <span className="truncate">{a.name}</span>
          {a.live && <Badge className="text-[10px]">live</Badge>}
        </div>
        <div className="mt-0.5 text-[11px] text-muted-foreground">
          {a.toolsOff ? "chatbot mode" : `${a.tools.length} tool${a.tools.length === 1 ? "" : "s"}`} ·{" "}
          {a.reasoningDepth.toLowerCase()}
        </div>
      </button>
      {!readOnly && a.mine && (
        <button
          type="button"
          onClick={() => onDelete(a)}
          className="text-muted-foreground transition hover:text-destructive group-hover:opacity-100 focus-visible:opacity-100"
          aria-label={`Delete ${a.name}`}
        >
          <Trash2 aria-hidden="true" className="h-3.5 w-3.5 shrink-0" />
        </button>
      )}
    </div>
  );

  return (
    <aside className="space-y-2" data-tour="agents-rail">
      <div className="flex items-center justify-between">
        <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Your agents</div>
        <span className="text-[11px] text-muted-foreground">
          {mine.length}
          {maxAgents !== null ? `/${maxAgents}` : ""}
        </span>
      </div>
      {mine.map(agentButton)}
      {mine.length === 0 && (
        <p className="rounded-xl border border-dashed border-border/60 p-3 text-xs text-muted-foreground">
          None yet. Describe a job on the right and one appears here.
        </p>
      )}
      {!readOnly && (
        <Button variant="outline" size="sm" className="w-full" disabled={atCap} onClick={onNew}>
          <Plus aria-hidden="true" className="mr-1 h-3.5 w-3.5 shrink-0" /> New agent
        </Button>
      )}
      {atCap && (
        <p className="text-[11px] text-muted-foreground">
          Sandbox full - delete one or create a free account for more.
        </p>
      )}

      <div className="pt-3">
        <LaunchChecklist progress={progress} />
      </div>

      <p className="pt-2 text-[11px] leading-relaxed text-muted-foreground">
        Your sandbox is yours. Restart it any time and start from a different idea - nothing carries over.
      </p>
    </aside>
  );
}
