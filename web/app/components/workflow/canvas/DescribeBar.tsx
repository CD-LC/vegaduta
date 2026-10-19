"use client";

import { Wand2 } from "lucide-react";
import DescribeModeToggle from "../DescribeModeToggle";
import { isPlanEmpty, type DescribeMode, type WorkflowPlan } from "../workflowPlan";

type T = (key: string, values?: Record<string, unknown>) => string;

/**
 * The "Describe it, automate it" bar under the graph (#392): a sentence in,
 * a workflow out. A normal row below the canvas, not an overlay.
 */
export default function DescribeBar({
  aiPrompt,
  setAiPrompt,
  aiGenerating,
  aiMode,
  setAiMode,
  aiPlan,
  aiPlanOpen,
  setAiPlanOpen,
  canvasHasContent,
  generateWithAi,
  t,
  tDescribe,
}: {
  aiPrompt: string;
  setAiPrompt: (v: string) => void;
  aiGenerating: boolean;
  aiMode: DescribeMode;
  setAiMode: (m: DescribeMode) => void;
  aiPlan: WorkflowPlan | null;
  aiPlanOpen: boolean;
  setAiPlanOpen: (open: boolean) => void;
  canvasHasContent: boolean;
  generateWithAi: () => void;
  t: T;
  tDescribe: T;
}) {
  return (
    <div className="ax-wf-describe-bar" style={{ flexWrap: "wrap" }}>
      <Wand2 size={15} className="ax-wf-describe-icon" />
      {canvasHasContent && <DescribeModeToggle mode={aiMode} onChange={setAiMode} disabled={aiGenerating} />}
      <input
        type="text"
        className="ax-wf-describe-input"
        style={{ flex: "1 1 12rem" }}
        value={aiPrompt}
        onChange={(e) => setAiPrompt(e.target.value)}
        onKeyDown={(e) => e.key === "Enter" && generateWithAi()}
        placeholder={tDescribe(
          !canvasHasContent ? "barPlaceholderNew" : aiMode === "edit" ? "barPlaceholderEdit" : "barPlaceholderReplace"
        )}
        disabled={aiGenerating}
      />
      <button
        type="button"
        className="ax-wf-describe-btn"
        onClick={() => generateWithAi()}
        disabled={aiGenerating || !aiPrompt.trim()}
      >
        {aiGenerating ? t("buildingWithAi") : t("buildWithAi")}
      </button>
      {aiPlan && !aiPlanOpen && !isPlanEmpty(aiPlan) && (
        <button
          type="button"
          className="h-8 shrink-0 rounded-md border border-border bg-background px-3 text-xs font-medium text-foreground transition-colors hover:bg-accent"
          onClick={() => setAiPlanOpen(true)}
          data-testid="workflow-plan-open"
        >
          {tDescribe("planShow")}
        </button>
      )}
    </div>
  );
}
