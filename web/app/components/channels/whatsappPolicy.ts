// WhatsApp policy guard (docs/channels/whatsapp-policy.md). The task values mirror
// core's WhatsAppTask enum; labels come from the "channels" i18n namespace.
export const WHATSAPP_TASKS = [
  "SUPPORT",
  "BOOKING",
  "ORDERS",
  "LEAD_QUALIFICATION",
  "REMINDERS",
  "PAYMENTS_FOLLOWUP",
] as const;

export type WhatsAppTask = (typeof WHATSAPP_TASKS)[number];

export const whatsAppTaskLabelKey = (task: WhatsAppTask) => `waTask_${task}`;

// Meta reports quality_rating as GREEN / NA / UNKNOWN in its documented examples; YELLOW and
// RED were not confirmed on the pages we fetched, so anything else is shown verbatim.
export type QualityTone = "good" | "unknown" | "other";

export function qualityTone(rating: string | null | undefined): QualityTone {
  if (!rating) return "unknown";
  const r = rating.toUpperCase();
  if (r === "GREEN") return "good";
  if (r === "NA" || r === "UNKNOWN") return "unknown";
  return "other";
}
