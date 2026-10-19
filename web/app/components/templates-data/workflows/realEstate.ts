import { Building2, Home } from "lucide-react";
import type { WorkflowTemplate } from "./types";

// Workflow templates: Real estate.
export const realEstateTemplates: WorkflowTemplate[] = [
  {
    key: "property-inquiry-workflow",
    category: "Real estate",
    icon: Building2,
    description:
      "Answers a listing inquiry from the listing sheet and keeps the reply in the run history - switch the last node to a channel to deliver it.",
    triggerChannel: "WEBHOOK",
    steps: [
      { type: "knowledge", label: "Current listing sheets" },
      { type: "agent", label: "Property listing assistant" },
      { type: "output", label: "Keep reply in run history", config: { channel: "log" } },
    ],
  },
  {
    key: "real-estate-lead-follow-up-workflow",
    category: "Real estate",
    icon: Home,
    description: "Drafts a follow-up message for a new buyer/renter lead, held for review.",
    triggerChannel: "WEBHOOK",
    steps: [
      { type: "agent", label: "Lead follow-up drafter" },
      {
        type: "approval",
        label: "Review before sending",
        config: { message: "Approve this lead follow-up before it's sent." },
      },
      { type: "output", label: "Send follow-up", config: { channel: "log" } },
    ],
  },
];
