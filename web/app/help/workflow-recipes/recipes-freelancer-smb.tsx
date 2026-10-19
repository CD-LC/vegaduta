import type { Industry } from "./recipes-data";
import { FREELANCER_INDUSTRIES } from "./recipes-freelancer";
import { SMALL_BUSINESS_INDUSTRIES } from "./recipes-smb";

// ---------------------------------------------------------------------------
// Freelancers / solo consultants & small business owners (1–10 people) —
// same "inline copy, no i18n keys" precedent and honesty rules as
// recipes-data.tsx and recipes-warehousing-printing.tsx.
//
// These two packs are deliberately NOT the enterprise sales/marketing/agency
// packs that already exist. The reader here IS the sales team, the support
// team and the accounts team — one person, one phone, no ops department and
// no developer on call. Every recipe is expressed only in VegaDūta's real
// building blocks (Trigger / Agent / Knowledge / Condition / Approval /
// Output / Tool(MCP) / Voice / Device / Loop / Code / Parallel / Supervisor /
// Sub-workflow) so each maps 1:1 to something buildable in the designer.
//
// Honesty rules kept verbatim in posture:
//  • Real first-party channels only — Webhook, WhatsApp, SMS/Twilio,
//    Voice/Call, Email, Slack, Discord, Telegram, Instagram, Messenger, X,
//    ElevenLabs, Sarvam, Razorpay (UPI). Accounting software, calendars,
//    marketplaces, invoicing apps and payment dashboards have NO first-party
//    connector — they are reached via an inbound Webhook and/or a Tool (MCP)
//    node, and are LABELLED as such everywhere they appear.
//  • The agent NEVER invents a price, an availability, a discount or a
//    delivery date. It answers from the owner's own Knowledge (rate card,
//    price list, policies, hours) and PROPOSES; the owner confirms.
//  • Anything consequential — sending a quote or price commitment, accepting
//    a booking that blocks the calendar, issuing a refund, publishing a
//    public post — sits behind an Approval node.
//  • Impact lines describe the mechanism. No invented ROI numbers, no
//    inflated agent counts.
//  • User-defined tools are described honestly: a tenant-authored SpEL
//    expression over the tool's JSON input — sandboxed by construction (no
//    method calls, no constructors, no bean or type references, and no loop
//    in the grammar). It is a data-transform / formula tool, NOT a scripting
//    engine — which is exactly why a solo operator can safely own their own
//    rate card maths without a developer or a redeploy.
// ---------------------------------------------------------------------------

export const INDUSTRIES_FREELANCE: Industry[] = [
  ...FREELANCER_INDUSTRIES,
  ...SMALL_BUSINESS_INDUSTRIES,
];
