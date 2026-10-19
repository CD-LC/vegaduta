import type { Industry } from "./recipes-data";
import { QCOMMERCE_INDUSTRIES } from "./recipes-qcommerce";
import { CLOUD_KITCHEN_INDUSTRIES } from "./recipes-cloudkitchen";

// ---------------------------------------------------------------------------
// Quick-commerce / dark-store & cloud-kitchen (delivery-only brand) workflow
// recipes — same "inline copy, no i18n keys" precedent and honesty rules as
// recipes-data.tsx. Every recipe is expressed ONLY in VegaDūta's real building
// blocks (Trigger / Agent / Knowledge / Condition / Approval / Output /
// Tool(MCP) / Voice / Device / Loop / Code / Parallel / Supervisor /
// Sub-workflow), so each maps 1:1 to something you can actually build in the
// Workflow designer.
//
// Scope note: this file is deliberately about DARK-STORE OPS and DELIVERY-ONLY
// KITCHEN OPS — sub-10-minute promise breaches, mid-pick substitutions,
// near-expiry sweeps, aggregator rating drops, prep-capacity surges, shared-
// kitchen complaint clustering, settlement disputes. It is NOT storefront
// retail, D2C brand marketing or dine-in hospitality; those industries already
// exist elsewhere in this guide and are not duplicated here.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — the marketplace/aggregator order feed
// and partner portal, the POS, the inventory/WMS and batch/expiry system, the
// rider-dispatch platform, the kitchen display system (KDS), the settlement/
// chargeback console — are reached the real way, via an inbound Webhook trigger
// and/or a Tool (MCP) node, and each mcp[] line names the ACTUAL call being
// made. Only real channels are used (Webhook, WhatsApp, SMS, Voice/Call, Email,
// Slack, Telegram, ElevenLabs, Sarvam, Razorpay UPI). Every consequential
// action — a refund or goodwill credit, cancelling or re-firing an order,
// delisting/86-ing an item, changing a price or markdown, marking stock out —
// goes through an Approval (human-in-the-loop) step. Agents PROPOSE; humans
// execute. Impact lines describe the mechanism (why it helps), never invented
// ROI numbers, and no agent count is inflated.
//
// User-defined tools, described honestly: on this platform a tenant-authored
// tool is a SpEL EXPRESSION evaluated against the tool's JSON input. It is
// sandboxed by construction — no method calls, no constructors, no bean or type
// references, and the grammar has no loop. So it is a DATA-TRANSFORM / FORMULA
// tool: read a field out of `input`, do arithmetic, format a string, branch with
// a ternary or elvis. It cannot call an API and cannot run arbitrary code. What
// it is genuinely great at is letting an ops team encode THEIR OWN thresholds —
// goodwill bands, markdown depths, dispute-fight cut-offs — and change them per
// city or per brand without a developer and without a deployment. Each industry
// below uses at least one, labelled as such.
// ---------------------------------------------------------------------------

export const INDUSTRIES_QCOMMKITCHEN: Industry[] = [
  ...QCOMMERCE_INDUSTRIES,
  ...CLOUD_KITCHEN_INDUSTRIES,
];
