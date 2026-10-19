"use client";

// "How this works" for the tool argument policies card.
//
// Mirrors core/guardrails/.../toolargs (ToolArgPolicyCallback, ToolArgPolicyEvaluator) and
// ToolArgPolicyService#onViolation - change the copy only together with that code:
//   the check runs before the approval gate, so a refused call is never queued for a person;
//   a refusal is a tool RESULT the agent reads (it names the argument and the rule, never the value);
//   a tool no policy matches is not checked here (policies only tighten);
//   every refusal is written to the audit log as tool.arg_policy.denied.
import { useTranslations } from "../../lib/i18n";
import FlowExplainer from "../explainers/FlowExplainer";

export default function ToolPolicyExplainer({ className }: { className?: string }) {
  const t = useTranslations("explainersAgents") as (key: string) => string;
  return (
    <FlowExplainer
      className={className}
      intro={t("policyIntro")}
      steps={[
        { id: "ask", title: t("policyAskTitle"), description: t("policyAskBody") },
        {
          id: "check",
          title: t("policyCheckTitle"),
          description: t("policyCheckBody"),
          outcomes: [
            { key: "allowed", label: t("policyAllowed"), description: t("policyAllowedBody"), state: "success" },
            { key: "denied", label: t("policyDenied"), description: t("policyDeniedBody"), state: "error" },
          ],
        },
        { id: "record", title: t("policyRecordTitle"), description: t("policyRecordBody") },
      ]}
    />
  );
}
