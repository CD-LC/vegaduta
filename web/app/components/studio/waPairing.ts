import type { WhatsAppStatus } from "../try/demoClient";

// Pure decisions for the Deploy-stage WhatsApp card, kept out of the component
// so they can be unit-tested. The card shows ONLY when the deployment has a
// real platform WhatsApp number (mode LIVE + available); every other
// combination renders nothing - there is no simulated chat here.

export type WhatsAppView = "hidden" | "idle" | "waiting" | "linked";

/** Only ever hand a wa.me link to the QR / anchor; never an arbitrary string from the wire. */
export function safeWaLink(link: string | null | undefined): string | null {
  if (!link) return null;
  return /^https:\/\/wa\.me\/\d{6,15}(\?text=[A-Za-z0-9%+._~-]{0,80})?$/.test(link) ? link : null;
}

export function whatsappView(status: WhatsAppStatus | null | undefined, expired: boolean): WhatsAppView {
  if (!status || status.mode !== "LIVE" || !status.available) return "hidden";
  if (status.state === "LINKED") return "linked";
  if (expired) return "hidden";
  if (status.state === "PENDING" && !!status.pairingCode && !!safeWaLink(status.deepLink)) return "waiting";
  return "idle";
}
