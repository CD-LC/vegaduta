"use client";

// "How this works" for a node's error handling (onError + retry), shown under
// NodeErrorStrategyEditor. Mirrors core NodeErrorStrategy and the engine's
// failure path (WorkflowExecutionEngine):
//   - retry runs first: up to `maxAttempts` TOTAL attempts, and a node with an
//     irreversible side effect is not retried without retryUnsafe;
//   - onError=fail (default): the run fails, except that a wired red "error"
//     port already receives the failure (the pre-existing implicit routing);
//   - onError=continue: the node's output becomes {error, nodeId} and the run
//     follows the normal outgoing edge; a plan/quota denial or a validation
//     failure still fails the run;
//   - onError=branch: the red "error" port when wired, else the run fails.
// The three outcome labels are the option names the editor's own select uses.
import { useTranslations } from "../../lib/i18n";
import FlowExplainer, { type FlowStep } from "./FlowExplainer";

export default function ErrorStrategyExplainer({ className }: { className?: string }) {
  const t = useTranslations("explainersWorkflow");

  const steps: FlowStep[] = [
    { id: "fail", title: t("errorFailsTitle"), description: t("errorFailsBody") },
    { id: "retry", title: t("errorRetryTitle"), description: t("errorRetryBody") },
    {
      id: "decide",
      title: t("errorDecideTitle"),
      outcomes: [
        { key: "fail", label: t("errorOutcomeStopLabel"), description: t("errorOutcomeStopBody"), state: "error" },
        { key: "continue", label: t("errorOutcomeContinueLabel"), description: t("errorOutcomeContinueBody"), state: "warning" },
        { key: "branch", label: t("errorOutcomeBranchLabel"), description: t("errorOutcomeBranchBody"), state: "running" },
      ],
    },
  ];

  return <FlowExplainer vertical steps={steps} intro={t("errorIntro")} className={className} />;
}
