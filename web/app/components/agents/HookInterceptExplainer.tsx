"use client";

// "How this works" for the hooks card: where a tool call meets the before-tool and after-tool hooks.
//
// Every step and outcome below mirrors core/agents/.../hooks (HookPoint, HookActionType#allowedAt, HookEngine,
// HookDecisionSink) - change the copy only together with that code:
//   BEFORE_TOOL allows block, require_approval, redact, limit, webhook, log;
//   AFTER_TOOL  allows block, redact, append_context, webhook, log;
//   a redaction that cannot be applied safely refuses the call (redact is always fail-closed);
//   every decision is written to the audit log as agent.hook.decision.
// The other four points (before/after model, on error, on run end) are outside this picture on purpose.
import { useTranslations } from "../../lib/i18n";
import FlowExplainer from "../explainers/FlowExplainer";

export default function HookInterceptExplainer({ className }: { className?: string }) {
  const t = useTranslations("explainersAgents") as (key: string) => string;
  return (
    <FlowExplainer
      className={className}
      intro={t("hookIntro")}
      steps={[
        { id: "ask", title: t("hookAskTitle"), description: t("hookAskBody") },
        {
          id: "before",
          title: t("hookBeforeTitle"),
          description: t("hookBeforeBody"),
          outcomes: [
            { key: "allow", label: t("hookAllow"), description: t("hookAllowBody"), state: "success" },
            { key: "redact", label: t("hookRedact"), description: t("hookRedactBody"), state: "running" },
            { key: "approval", label: t("hookApproval"), description: t("hookApprovalBody"), state: "warning" },
            { key: "block", label: t("hookBlock"), description: t("hookBlockBody"), state: "error" },
          ],
        },
        { id: "run", title: t("hookRunTitle"), description: t("hookRunBody") },
        {
          id: "after",
          title: t("hookAfterTitle"),
          description: t("hookAfterBody"),
          outcomes: [
            { key: "pass", label: t("hookAfterPass"), description: t("hookAfterPassBody"), state: "success" },
            { key: "redact", label: t("hookRedact"), description: t("hookAfterRedactBody"), state: "running" },
            { key: "withhold", label: t("hookAfterBlock"), description: t("hookAfterBlockBody"), state: "error" },
          ],
        },
        { id: "record", title: t("hookRecordTitle"), description: t("hookRecordBody") },
      ]}
    />
  );
}
