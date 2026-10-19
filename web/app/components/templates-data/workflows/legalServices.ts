import { Scale, ScrollText } from "lucide-react";
import type { WorkflowTemplate } from "./types";

// Workflow templates: Legal services.
export const legalServicesTemplates: WorkflowTemplate[] = [
  {
    key: "legal-intake-triage-workflow",
    category: "Legal services",
    icon: Scale,
    description: "Structures a new client-intake submission and routes it to an attorney for review.",
    triggerChannel: "WEBHOOK",
    steps: [
      { type: "agent", label: "Client intake triage assistant" },
      {
        type: "approval",
        label: "Attorney review",
        config: { message: "New intake summary ready - review before follow-up.", adminOnly: true },
      },
      { type: "output", label: "Notify intake team", config: { channel: "log" } },
    ],
  },
  {
    key: "legal-document-review-workflow",
    category: "Legal services",
    icon: ScrollText,
    description: "Drafts a first-pass summary of an incoming document against firm precedent.",
    triggerChannel: "WEBHOOK",
    steps: [
      { type: "knowledge", label: "Firm templates & precedent documents" },
      { type: "agent", label: "Document review drafting assistant" },
      {
        type: "approval",
        label: "Attorney review",
        config: { message: "Draft summary ready - review before it's relied on.", adminOnly: true },
      },
      { type: "output", label: "Notify reviewing attorney", config: { channel: "log" } },
    ],
  },
];
