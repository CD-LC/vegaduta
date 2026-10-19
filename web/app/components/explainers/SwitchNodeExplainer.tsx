"use client";

// "How this works" for the Switch node, shown in its config form. Mirrors core
// WorkflowDataOps#switchRoute: cases are tested in order, the FIRST case whose
// conditions hold names the output port the run leaves through (there is no
// "all matching cases" mode), no match leaves through the fixed "default"
// port, and the input passes through unchanged. A case with no name or no
// conditions is skipped.
import { useTranslations } from "../../lib/i18n";
import FlowExplainer, { type FlowStep } from "./FlowExplainer";

export default function SwitchNodeExplainer({ className }: { className?: string }) {
  const t = useTranslations("explainersWorkflow");

  const steps: FlowStep[] = [
    { id: "arrive", title: t("switchArriveTitle"), description: t("switchArriveBody") },
    {
      id: "test",
      title: t("switchTestTitle"),
      description: t("switchTestBody"),
      outcomes: [
        { key: "match", label: t("switchOutcomeMatchLabel"), description: t("switchOutcomeMatchBody"), state: "success" },
        { key: "default", label: t("switchOutcomeDefaultLabel"), description: t("switchOutcomeDefaultBody"), state: "idle" },
      ],
    },
    { id: "pass", title: t("switchPassTitle"), description: t("switchPassBody") },
  ];

  return <FlowExplainer vertical steps={steps} className={className} />;
}
