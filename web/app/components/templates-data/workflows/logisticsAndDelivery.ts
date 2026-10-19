import { Truck, Wrench } from "lucide-react";
import type { WorkflowTemplate } from "./types";

// Workflow templates: Logistics & delivery.
export const logisticsAndDeliveryTemplates: WorkflowTemplate[] = [
  {
    key: "delivery-status-workflow",
    category: "Logistics & delivery",
    icon: Truck,
    description:
      "Drafts a delivery status update from an incoming shipment event and sends it to the customer's WhatsApp number (the event's \"phone\" field).",
    triggerChannel: "WEBHOOK",
    steps: [
      { type: "agent", label: "Delivery status update drafter" },
      {
        type: "output",
        label: "Notify customer on WhatsApp",
        config: { channel: "whatsapp", whatsappTo: "{{ $json.phone }}" },
      },
    ],
  },
  {
    key: "fleet-maintenance-workflow",
    category: "Logistics & delivery",
    icon: Wrench,
    description: "Flags overdue vehicle service from a schedule event and drafts a reminder.",
    triggerChannel: "WEBHOOK",
    steps: [
      { type: "agent", label: "Fleet maintenance scheduling assistant" },
      { type: "output", label: "Notify maintenance team", config: { channel: "log" } },
    ],
  },
];
