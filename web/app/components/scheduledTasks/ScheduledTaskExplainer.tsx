"use client";

import { useTranslations } from "../../lib/i18n";
import { DELIVERY_CHANNELS, type DeliveryChannel } from "../../lib/scheduledTasks";
import FlowExplainer, { type FlowOutcome } from "../explainers/FlowExplainer";

/**
 * "How this works" for /scheduled, written against core's ScheduledTaskRunner / ScheduledTaskResultDelivery:
 *   1. the schedule fires (or Run now);
 *   2. the agent runs the prompt unattended; a failed SCHEDULED run is retried at most once
 *      (app.scheduled-tasks.retry-failed-once, on by default);
 *   3. the result is delivered: in-app to the owner always works; an external channel only when the deployment
 *      allows it (`deliveryChannels` from /capabilities) and its send can park for approval;
 *   4. a streak of final failures pauses the task. The threshold is app.scheduled-tasks.max-consecutive-failures,
 *      3 by default; /capabilities does not expose it, so the copy says "by default" rather than state it as fact.
 */
export default function ScheduledTaskExplainer({ deliveryChannels, className }: { deliveryChannels: readonly string[]; className?: string }) {
  const t = useTranslations("scheduledTasks");
  // Only the external channels this deployment actually allows, in the product's usual order.
  const external = DELIVERY_CHANNELS.filter((c: DeliveryChannel) => c !== "in_app" && deliveryChannels.includes(c));

  const outcomes: FlowOutcome[] = [
    { key: "in_app", label: t("delivery_in_app"), description: t("explainDeliverInApp"), state: "success" },
  ];
  if (external.length > 0) {
    outcomes.push({
      key: "external",
      label: external.map((c) => t(`delivery_${c}`)).join(", "),
      description: t("explainDeliverExternal"),
      state: "running",
    });
  }

  return (
    <FlowExplainer
      className={className}
      intro={t("explainIntro")}
      steps={[
        { id: "fire", title: t("explainFireTitle"), description: t("explainFireBody") },
        { id: "run", title: t("explainRunTitle"), description: t("explainRunBody") },
        { id: "deliver", title: t("explainDeliverTitle"), description: t("explainDeliverBody"), outcomes },
        { id: "pause", title: t("explainPauseTitle"), description: t("explainPauseBody") },
      ]}
    />
  );
}
