import { CalendarClock, Headset } from "lucide-react";
import type { WorkflowTemplate } from "./types";

// Workflow templates: Small business.
export const smallBusinessTemplates: WorkflowTemplate[] = [
  // ---- Small business (wave 3, audience templates) ----
  // Both pair with an agent template of the same job (agentTemplates.ts:
  // "daily-briefing-writer", "whatsapp-reply-drafter"): create that agent
  // first, then assign it to the agent step on the canvas.
  {
    // The schedule is NOT part of a template's graph - it is the workflow's own
    // trigger setting (PUT /api/workflows/{id}/schedule, the hourly/daily/weekly
    // picker in the canvas trigger strip), so the description says to set it
    // after creating. Output "log" keeps the briefing in the run history; the
    // person can switch that node to a channel if they want it delivered.
    key: "daily-briefing-workflow",
    category: "Small business",
    icon: CalendarClock,
    description:
      "Runs a briefing agent each time it fires and keeps the result in the run history. After creating it, " +
      "assign your briefing agent and turn on a daily schedule in the trigger settings; switch the output to a " +
      "channel only if you want the briefing delivered somewhere.",
    triggerChannel: "WEBHOOK",
    steps: [
      { type: "agent", label: "Daily briefing writer" },
      { type: "output", label: "Keep briefing in run history", config: { channel: "log" } },
    ],
  },
  {
    // Same trigger -> agent -> approval -> output shape as the abandoned-cart
    // template above: nothing reaches the customer until a person approves it.
    key: "whatsapp-reply-with-approval-workflow",
    category: "Small business",
    icon: Headset,
    description:
      "Drafts a reply to each inbound WhatsApp message and holds it for your approval - nothing is sent to the " +
      "customer until you approve it. Needs a connected WhatsApp channel, and your reply agent assigned on the canvas.",
    triggerChannel: "WHATSAPP",
    steps: [
      { type: "agent", label: "WhatsApp reply drafter" },
      {
        type: "approval",
        label: "Review before sending",
        config: { message: "Review this drafted reply. Nothing is sent to the customer until you approve it." },
      },
      { type: "output", label: "Send reply", config: { channel: "whatsapp" } },
    ],
  },
];
