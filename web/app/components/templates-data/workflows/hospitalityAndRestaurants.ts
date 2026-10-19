import { BedDouble, UtensilsCrossed } from "lucide-react";
import type { WorkflowTemplate } from "./types";

// Workflow templates: Hospitality & restaurants.
export const hospitalityAndRestaurantsTemplates: WorkflowTemplate[] = [
  {
    key: "restaurant-reservation-workflow",
    category: "Hospitality & restaurants",
    icon: UtensilsCrossed,
    description: "Answers a reservation or menu question and replies on the same channel.",
    triggerChannel: "WHATSAPP",
    steps: [
      { type: "agent", label: "Reservation & guest questions agent" },
      { type: "output", label: "Reply to guest", config: { channel: "whatsapp" } },
    ],
  },
  {
    key: "hotel-guest-services-workflow",
    category: "Hospitality & restaurants",
    icon: BedDouble,
    description: "Answers a guest's amenity or property question from the property guide.",
    triggerChannel: "WHATSAPP",
    steps: [
      { type: "knowledge", label: "Property guide & local recommendations" },
      { type: "agent", label: "Hotel guest services concierge" },
      { type: "output", label: "Reply to guest", config: { channel: "whatsapp" } },
    ],
  },
];
