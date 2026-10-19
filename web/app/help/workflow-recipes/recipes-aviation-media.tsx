import type { Industry } from "./recipes-data";
import { Plane, Clapperboard } from "lucide-react";

// ---------------------------------------------------------------------------
// Aviation & Media/OTT workflow recipes — same "inline copy, no i18n keys"
// precedent and honesty rules as recipes-data.tsx. Every recipe is expressed
// ONLY in VegaDūta's real building blocks (Trigger / Agent / Knowledge /
// Condition / Approval / Output / Tool(MCP) / Voice / Device / Loop / Code /
// Parallel), so each maps 1:1 to something you can actually build in the
// Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — PSS/DCS/reservations, baggage systems,
// CRM, OTT/streaming backend, subscription billing — are reached the real way,
// via an inbound Webhook trigger and/or a Tool (MCP) node, and are LABELLED as
// such. Real channels only (Webhook, WhatsApp, SMS, Voice/Call, Email,
// ElevenLabs, Sarvam, Razorpay UPI). Every consequential decision — a refund,
// a compensation payout, a content takedown — goes through an Approval
// (human-in-the-loop) step. Impact lines describe the mechanism (why it helps),
// never invented ROI numbers.
// ---------------------------------------------------------------------------

export const INDUSTRIES_AVMEDIA: Industry[] = [
  // ============================================================== AVIATION
  {
    id: "aviation",
    name: "Aviation & airlines",
    icon: Plane,
    tagline: "Rebook disrupted passengers, log baggage claims and answer check-in questions — in the passenger's own language, before the queue forms.",
    recipes: [
      {
        id: "aviation-rebook",
        title: "Flight-disruption rebooking, proactively and multilingually",
        scenario:
          "A flight cancels or slips and the ops desk scrambles: staff pull the affected PNRs, work out alternatives, then call or email each passenger one by one — so travellers hear about it late, in one language, after they're already at the gate.",
        triggerLabel: "Webhook (disruption event from ops / PSS)",
        steps: [
          { kind: "trigger", title: "Disruption is posted", detail: "A Webhook trigger receives the cancel/delay event with the affected flight and passenger PNRs — pushed from your ops board / PSS (no first-party connector, it feeds in via Webhook)." },
          { kind: "loop", title: "Per affected passenger", detail: "A Loop node iterates the PNR list so each traveller gets an individually tailored message, not one blast." },
          { kind: "agent", title: "Draft rebooking options", detail: "An Agent node writes the disruption notice and the available alternatives in the passenger's language, grounded in fare/rebooking rules so it never offers something the fare doesn't allow." },
          { kind: "output", title: "Reach the passenger", detail: "An Output node sends it on WhatsApp/SMS, or speaks it on a Voice call synthesized with Sarvam (Indian languages) or ElevenLabs — whatever channel the PNR carries." },
          { kind: "condition", title: "Rebook or claim?", detail: "A Condition node routes the reply: accept an offered flight (write it back to the PSS) versus request a refund or compensation." },
          { kind: "approval", title: "Refund / compensation pauses for a human", detail: "Any refund or disruption-compensation routes to an Approval node before a rupee moves — an accepted involuntary rebooking within policy proceeds, but money decisions stay with a person." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS", "Voice / Call", "Sarvam (translate and speech tools)", "ElevenLabs", "Knowledge base", "Tool (MCP → PSS/reservations)", "Approval / HITL"],
        outcome: "Every affected passenger is told about the disruption and offered real, policy-valid alternatives within minutes — in their own language — while refunds and compensation stay human-approved.",
        impact: "The value is speed and reach: reaching every PNR at once, in the traveller's language, beats a serial phone tree that only clears a fraction of the list before the gate closes.",
        build: {
          agents: 1,
          agentsNote: "1 rebooking agent runs inside the Loop; the notify, routing, PSS write and refund approval are workflow nodes.",
          prompt:
            "You are a flight-disruption assistant for {{airline}}. For each affected passenger {pnr, name, flight, disruption_type, language, alternatives[]}, write a clear, calm notice in the passenger's language explaining what happened and listing ONLY the alternatives supplied in the input — never invent a flight, time, or upgrade. State refund/compensation eligibility only as it appears in the rebooking policy from Knowledge; never promise a payout. If the passenger asks for a refund or compensation, output intent=REFUND for the workflow to route to a human — do not confirm any refund yourself.",
          mcp: ["PSS / reservations — via a Tool (MCP) node (no first-party connector), for the rebooking write", "Disruption/ops feed — inbound via Webhook trigger", "WhatsApp / SMS / Voice — notify channels"],
          tools: ["knowledge_search (fare rules + involuntary-reroute / compensation policy)", "http_request (fetch PNR + alternatives detail)"],
          guardrails: [
            "Grounded-only: alternatives and eligibility come from the input + policy Knowledge — the agent never invents a flight, time, or payout",
            "Every refund or compensation is Approval-gated — the agent may state eligibility but never confirms money; only a human approves the payout",
            "Idempotent per PNR (keyed by pnr + disruption id) so a re-run never double-notifies or double-rebooks a passenger",
          ],
          cost:
            "The notify agent runs once per affected PNR, so on a mass disruption its model choice dominates total cost — keep it on the economy tier; the task is grounded templating, not reasoning, so premium buys nothing. Sarvam/ElevenLabs synthesis is billed only on the Voice legs, not for WhatsApp/SMS passengers. Run the Loop in Parallel batches for wall-clock speed without changing per-passenger cost.",
          output:
            "The Loop maps the agent over every affected PNR → the notice + policy-valid alternatives are sent on WhatsApp/SMS or spoken via Sarvam/ElevenLabs → the Condition routes accepted rebookings to a Tool node that writes them to the PSS, and any refund/compensation to a human Approval before it's issued.",
        },
      },
      {
        id: "aviation-baggage",
        title: "Baggage-claim & complaint intake, resolved end to end",
        scenario:
          "A bag is delayed or damaged and the passenger has to find a desk, wait, and dictate a report while an agent types a PIR by hand — then chases status and works out any compensation manually, in business hours, in one language.",
        triggerLabel: "WhatsApp / Voice (passenger reports)",
        steps: [
          { kind: "trigger", title: "Passenger reports the issue", detail: "A WhatsApp or Voice trigger starts the flow on any inbound baggage complaint, day or night." },
          { kind: "agent", title: "Capture the claim", detail: "An Agent node collects the PNR, bag-tag number and a description of the problem, working in the passenger's language and confirming the details back." },
          { kind: "tool", title: "Check baggage status", detail: "A Tool (MCP) node looks up the live tracing status in your DCS/baggage system — no first-party connector, MCP calls yours." },
          { kind: "knowledge", title: "Ground the entitlement", detail: "A Knowledge node grounds any interim-expense or compensation entitlement strictly in the airline's baggage policy, so nothing is over-promised." },
          { kind: "condition", title: "Traced or compensable?", detail: "A Condition node branches: a located bag gets a delivery ETA; a lost/damaged case routes toward a compensation claim." },
          { kind: "approval", title: "Compensation pauses for a human", detail: "Any interim-expense or compensation payout routes to an Approval node — the intake is instant, the money decision stays with a person." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Sarvam (translate and speech tools)", "ElevenLabs", "Tool (MCP → DCS/baggage)", "Knowledge base", "Approval / HITL"],
        outcome: "Every baggage report is captured cleanly, checked against live tracing status, and moved toward resolution — with compensation always human-approved.",
        impact: "Removes the manual PIR typing and the desk queue: the passenger self-reports on a channel they already use, and a human is spent only on the payout decision, not the data entry.",
        build: {
          agents: 1,
          agentsNote: "1 intake agent handles the conversation; the baggage lookup, routing and compensation approval are workflow nodes.",
          prompt:
            "You are a baggage-claim intake assistant for {{airline}}. From the passenger's message, capture strict JSON {pnr, bag_tag, issue_type: DELAYED|DAMAGED|LOST, description, contact, language}. Confirm the captured details back before filing. Call the tracing tool and relay the status exactly as returned — never guess a location, ETA, or that a bag is found. State compensation or interim-expense eligibility only as the baggage policy in Knowledge allows, and never confirm a payout — output issue_type so the workflow can route compensable cases to a human.",
          mcp: ["DCS / baggage tracing system — via a Tool (MCP) node (no first-party connector)", "WhatsApp / Voice — intake channel"],
          tools: ["knowledge_search (baggage / interim-expense / compensation policy)", "http_request (open + read the PIR / tracing record)"],
          guardrails: [
            "Status and ETAs are read from the DCS only — the agent never guesses a bag's location or that it has been found",
            "Every interim-expense or compensation payout is Approval-gated — the agent states policy eligibility but never confirms money",
            "Scope-locked to the reporting passenger's own PNR + bag tag — the tool can't read other travellers' records",
          ],
          cost:
            "One intake agent per conversation on the economy/standard tier — capturing a claim and reading a tracing record is not reasoning-heavy, so no premium tier is needed. Sarvam/ElevenLabs synthesis is billed only when the passenger is on a Voice call or an Indian-language leg, not for text chat. A per-conversation tool-call cap keeps a stuck tracing lookup from racking up API cost.",
          output:
            "The agent files a clean PIR from the captured JSON → a Tool node reads live tracing status from the DCS → the Condition gives a located bag a delivery ETA and routes lost/damaged cases to a human Approval for any compensation, confirmed back to the passenger on their channel.",
        },
      },
    ],
  },

  // =============================================================== MEDIA/OTT
  {
    id: "media",
    name: "Media, entertainment & OTT",
    icon: Clapperboard,
    tagline: "Handle subscriber support, moderate what viewers upload and win back cancellations — at streaming scale, with humans on the calls that bind.",
    recipes: [
      {
        id: "media-subscriber-support",
        title: "Subscriber support & cancellation win-back",
        scenario:
          "Streaming support is a flood of the same few things — a failed renewal, buffering on a smart TV, 'how do I cancel' — answered manually by agents who copy troubleshooting steps and escalate every refund by hand.",
        triggerLabel: "WhatsApp / Webhook (subscriber message)",
        steps: [
          { kind: "trigger", title: "Subscriber messages", detail: "A WhatsApp trigger (or an in-app Webhook) starts the flow on any inbound support message." },
          { kind: "agent", title: "Understand the issue", detail: "An Agent node classifies the intent — billing, playback, or cancellation — and drafts the next step in the subscriber's language." },
          { kind: "knowledge", title: "Ground the fix / offer", detail: "A Knowledge node grounds playback troubleshooting, plan details and any retention offer in your current help + billing docs, so nothing is invented." },
          { kind: "condition", title: "Route by intent", detail: "A Condition node branches: a self-serve playback fix, a billing lookup, or a cancellation → win-back path." },
          { kind: "approval", title: "Refund / credit pauses for a human", detail: "Any refund, credit or bespoke retention discount routes to an Approval node — the agent can propose it, a human approves it." },
          { kind: "output", title: "Reply on the channel", detail: "An Output node sends the grounded answer (or the approved offer) back on WhatsApp." },
        ],
        connectors: ["WhatsApp", "Sarvam (translate and speech tools)", "Knowledge base", "Tool (MCP → billing/subscription)", "Approval / HITL"],
        outcome: "The high-volume, repetitive tickets resolve themselves instantly and in-language, while refunds, credits and real retention offers stay a reviewed, human decision.",
        impact: "Deflects the buffering-and-billing volume that swamps support, and turns a cancellation into a conversation instead of a dead-end button — without letting an agent hand out money on its own.",
        build: {
          agents: 1,
          agentsNote: "1 support agent handles the conversation; intent routing, the billing lookup and the refund/offer approval are workflow nodes.",
          prompt:
            "You are a subscriber-support agent for {{service}}. Classify each message as BILLING, PLAYBACK, or CANCELLATION and help in the subscriber's language. Ground every troubleshooting step, plan detail and retention offer in the Knowledge base — never invent a discount, refund, price, or feature. For a cancellation, you may offer ONLY a retention option that Knowledge confirms exists; if the subscriber still wants to cancel, respect it. Never confirm a refund, credit, or bespoke discount yourself — propose it and let the Approval node decide.",
          mcp: ["Subscription / billing system — via a Tool (MCP) node (no first-party connector)", "OTT/streaming backend — via a Tool (MCP) node for account/entitlement lookups", "WhatsApp — trigger + reply channel"],
          tools: ["knowledge_search (playback troubleshooting, plan/billing docs, retention offers)", "http_request (subscription / payment status lookup)"],
          guardrails: [
            "Grounded-only: fixes, plans and offers come from Knowledge — the agent never invents a discount, refund, price, or feature",
            "Every refund, credit or bespoke discount is Approval-gated — the agent proposes, a human approves the money",
            "Retention offers are limited to ones Knowledge confirms exist; a subscriber who still wants to cancel is never blocked or looped",
          ],
          cost:
            "Put the triage/classification on the economy tier — it runs on every inbound message and most are routine; escalate to a standard tier only for a genuinely multi-turn troubleshooting or win-back conversation. Because refunds and offers are human-approved, no premium model ever sits on the money path. Sarvam is billed only on non-English legs.",
          output:
            "The agent classifies + drafts grounded in Knowledge → the Condition routes playback self-serve, billing lookups (via a Tool node) and cancellation win-back → any refund/credit/discount goes to a human Approval → the Output node sends the answer or the approved offer on WhatsApp.",
        },
      },
      {
        id: "media-ugc-moderation",
        title: "UGC & content-moderation triage with a human on takedowns",
        scenario:
          "Viewer uploads, comments and reports arrive faster than a moderation team can read them, so either everything waits in a backlog or borderline content is actioned inconsistently by whoever picks it up.",
        triggerLabel: "Webhook (new upload / user report)",
        steps: [
          { kind: "trigger", title: "New content or report", detail: "A Webhook trigger fires from your OTT/CMS backend on each new upload, comment, or user report." },
          { kind: "agent", title: "Classify against policy", detail: "An Agent node scores the item against the content policy — CLEAR / REVIEW / LIKELY_VIOLATING — with the specific rule and a short reason, in whatever language the item is in." },
          { kind: "knowledge", title: "Ground in the policy", detail: "A Knowledge node grounds the call strictly in your community/content guidelines, so the label maps to an actual written rule." },
          { kind: "condition", title: "Auto-clear vs escalate", detail: "A Condition node lets clearly-safe items through and routes anything flagged toward a human moderator — the agent never auto-removes." },
          { kind: "approval", title: "Takedown pauses for a moderator", detail: "Any takedown, age-gate or strike is an Approval decision — a binding action a human moderator confirms before it applies." },
          { kind: "output", title: "Apply + notify", detail: "On approval, a Tool (MCP) node applies the action in the OTT/CMS backend and an Output node notifies the uploader with the reason." },
        ],
        connectors: ["Webhook", "Sarvam (translate and speech tools)", "Knowledge base", "Tool (MCP → OTT/CMS backend)", "Approval / HITL", "Email"],
        outcome: "Every upload and report is triaged the moment it lands, with clearly-safe content cleared automatically and every actual takedown confirmed by a human against a written rule.",
        impact: "Turns an unreviewable backlog into a prioritized queue: the agent does the reading and the citation, a moderator makes the binding call — consistent, auditable, and fast, without a model silently removing content.",
        build: {
          agents: 1,
          agentsNote: "1 moderation-triage agent; auto-clear routing, the takedown approval and the CMS write are workflow nodes.",
          prompt:
            "You are a content-moderation triage agent for {{service}}. For each item {id, type, text?, metadata, language}, classify it against the content policy as strict JSON {label: CLEAR|REVIEW|LIKELY_VIOLATING, rule, reason, confidence 0-1}. Cite the SPECIFIC policy rule from Knowledge for anything not CLEAR — if you can't map it to a written rule, use REVIEW, never LIKELY_VIOLATING. You do NOT remove, age-gate, or strike anything — you only label and route; a human moderator owns the takedown decision. When unsure, prefer REVIEW.",
          mcp: ["OTT / CMS backend — via a Tool (MCP) node (no first-party connector), to apply an approved action", "Upload / report events — inbound via Webhook trigger", "Email — uploader notification channel"],
          tools: ["knowledge_search (community / content policy + rule catalogue)", "http_request (fetch the item + surrounding context)"],
          guardrails: [
            "The agent only labels and routes — it can never remove, age-gate, or strike content itself; every takedown is a human Approval",
            "Citation-required: any non-CLEAR label must map to a specific written policy rule, or it falls back to REVIEW (no ungrounded removals)",
            "Confidence floor: borderline / low-confidence items route to a human, never auto-cleared or auto-flagged as violating",
          ],
          cost:
            "The classifier runs on every uploaded item and report, so it is the dominant cost lever — keep it on the economy tier and let the confidence floor push only the genuinely borderline items to a human, rather than a premium model. Because humans own every takedown, no premium-tier spend sits on the removal path. Sarvam translation is billed only for non-English items that need it; run the queue in Parallel batches for throughput without changing per-item cost.",
          output:
            "The agent labels each item with a cited rule → the Condition auto-clears CLEAR items and routes the rest to a moderator → on an Approval takedown a Tool node applies the action in the OTT/CMS backend → an Output node emails the uploader the decision and the specific rule; every label, approval and action is logged for audit.",
        },
      },
    ],
  },
];
