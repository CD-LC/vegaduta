"use client";

// "How this works" for the Wait node, shown in its config panel.
//
// Every sentence mirrors core/workflow (WorkflowWaitService for the park,
// WorkflowWaitResumeService for the wake-up):
//   - the run is parked (status PAUSED + one stored wait row); no timer or
//     thread is held, so nothing executes while it waits;
//   - duration / until: a scheduled sweep resumes it at or after the time and
//     the next node gets the Wait node's own input, untouched;
//   - webhook: a POST to the signed resume URL continues it and the next node
//     gets {"resume": <posted JSON>, "input": <original input>};
//   - webhook timeout: there is NO separate timeout branch. onTimeout=fail
//     (default) fails the run; onTimeout=continue carries on down the same
//     path with {"timedOut": true, "input": ...}.
import { useTranslations } from "../../lib/i18n";
import FlowExplainer, { type FlowStep } from "./FlowExplainer";

export interface WaitNodeExplainerProps {
  /** The node's current wait mode; picks which wake-up paths are described. */
  mode: "duration" | "until" | "webhook";
  className?: string;
}

export default function WaitNodeExplainer({ mode, className }: WaitNodeExplainerProps) {
  const t = useTranslations("explainersWorkflow");
  const webhook = mode === "webhook";

  const steps: FlowStep[] = [
    { id: "reach", title: t("waitReachTitle"), description: t("waitReachBody") },
    { id: "parked", title: t("waitParkedTitle"), description: t("waitParkedBody") },
    webhook
      ? {
          id: "wake",
          title: t("waitWakeCallTitle"),
          outcomes: [
            { key: "call", label: t("waitOutcomeCallLabel"), description: t("waitOutcomeCallBody"), state: "success" },
            { key: "fail", label: t("waitOutcomeTimeoutFailLabel"), description: t("waitOutcomeTimeoutFailBody"), state: "error" },
            {
              key: "continue",
              label: t("waitOutcomeTimeoutContinueLabel"),
              description: t("waitOutcomeTimeoutContinueBody"),
              state: "warning",
            },
          ],
        }
      : { id: "wake", title: t("waitWakeTimeTitle"), description: t("waitWakeTimeBody") },
    {
      id: "resume",
      title: t("waitResumeTitle"),
      description: webhook ? t("waitResumeCallBody") : t("waitResumeTimeBody"),
    },
  ];

  return <FlowExplainer vertical steps={steps} intro={t("waitIntro")} className={className} />;
}
