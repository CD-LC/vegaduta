// Which agent templates suit a WhatsApp number (docs/channels/whatsapp-policy.md).
// WhatsApp's Business Solution Terms restrict general-purpose AI assistants, so a template that
// is a task-specific business agent is "ready" and an open-ended one is "unsuitable". Anything
// not listed is neutral (no badge): it may be fine if the tenant narrows the prompt.

export const WHATSAPP_READY_TEMPLATE_KEYS: readonly string[] = [
  "whatsapp-front-desk",
  "whatsapp-booking-assistant",
  "whatsapp-payment-followup",
  "whatsapp-lead-followup",
  "clinic-appointment-assistant",
  "restaurant-reservation-assistant",
  "salon-booking-assistant",
  "ecommerce-order-support",
  "real-estate-lead-follow-up",
  "sales-lead-qualifier",
];

export const WHATSAPP_UNSUITABLE_TEMPLATE_KEYS: readonly string[] = [
  "research-assistant",
  "literature-review-assistant",
  "explain-it-simply-tutor",
  "homework-helper",
  "exam-prep-coach",
  "language-learning-buddy",
  "writing-editor",
  "health-companion",
  "family-tech-helper",
  "data-analyst",
  "home-organizer",
  "daily-planner",
];

export type WhatsAppFit = "ready" | "unsuitable" | null;

export function whatsAppFit(templateKey: string): WhatsAppFit {
  if (WHATSAPP_READY_TEMPLATE_KEYS.includes(templateKey)) return "ready";
  if (WHATSAPP_UNSUITABLE_TEMPLATE_KEYS.includes(templateKey)) return "unsuitable";
  return null;
}
