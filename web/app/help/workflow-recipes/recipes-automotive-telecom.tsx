import type { Industry } from "./recipes-data";
import { Car, Signal } from "lucide-react";

// ---------------------------------------------------------------------------
// Automotive & Telecom workflow recipes — same "inline copy, no i18n keys"
// precedent and honesty rules as recipes-data.tsx. Every recipe is expressed
// ONLY in VegaDūta's real building blocks (Trigger / Agent / Knowledge /
// Condition / Approval / Output / Tool(MCP) / Voice / Device / Loop / Code /
// Parallel), so each maps 1:1 to something you can actually build in the
// Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — DMS, CRM, service scheduler, OSS/BSS,
// billing — are reached the real way, via an inbound Webhook trigger and/or a
// Tool (MCP) node, and are LABELLED as such. Every consequential decision
// (KYC, billing/plan changes) goes through an Approval (human-in-the-loop)
// step. Impact lines describe the mechanism (why it helps), never invented
// ROI numbers.
// ---------------------------------------------------------------------------

export const INDUSTRIES_AUTO: Industry[] = [
  // ============================================================= AUTOMOTIVE
  {
    id: "automotive",
    name: "Automotive & dealerships",
    icon: Car,
    tagline: "Book test drives, chase service due dates and answer parts enquiries — before a rival dealer calls back.",
    recipes: [
      {
        id: "auto-testdrive",
        title: "Test-drive booking & follow-up, end to end",
        scenario:
          "Enquiries land by WhatsApp and missed calls at all hours. Someone has to check which variant is in stock, offer a slot, then remember to follow up — so leads go cold and showroom slots sit empty.",
        triggerLabel: "WhatsApp / Call (enquiry)",
        steps: [
          { kind: "trigger", title: "Buyer enquires", detail: "A WhatsApp or Call trigger starts the flow on any inbound enquiry about a model or a test drive." },
          { kind: "agent", title: "Qualify the interest", detail: "An Agent node captures the model/variant, budget and preferred timing, and confirms the buyer's city/dealership." },
          { kind: "knowledge", title: "Check live inventory", detail: "A Knowledge node grounds the reply in your current stock and demo-car availability — so it never offers a variant you don't have." },
          { kind: "output", title: "Offer + book a slot", detail: "An Output node proposes test-drive slots and confirms the chosen one on WhatsApp." },
          { kind: "tool", title: "Write to the DMS", detail: "A Tool (MCP) node records the booking and the lead in your dealer management system — no first-party DMS connector, MCP calls yours." },
          { kind: "output", title: "Reminder + follow-up", detail: "An Output node sends a day-of reminder, then a post-drive follow-up to move the buyer toward a quote." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Knowledge base", "Tool (MCP → DMS)"],
        outcome: "Every enquiry is qualified, offered a real in-stock slot, logged, and followed up — without a salesperson tied to the phone.",
        impact: "Speed-to-lead and a booked slot are what convert walk-in intent; doing both instantly beats a callback hours later when the buyer has moved on.",
        build: {
          agents: 1,
          agentsNote: "1 sales agent handles the conversation; inventory grounding, the DMS write and the reminders are workflow nodes.",
          prompt:
            "You are a dealership sales assistant for {{dealership}}. From the buyer's message, capture the model/variant of interest, their budget band and preferred timing, and confirm their city/dealership. Only offer a test-drive slot for a variant that Knowledge confirms is in stock or has a demo car available — never promise a variant you can't confirm. Work in the buyer's language. Reply warmly and briefly. Once the buyer picks a slot, hand off to the booking step; do not claim the booking is confirmed until it is written to the DMS.",
          mcp: ["Dealer management system (DMS) — via a Tool (MCP) node (no first-party connector)", "WhatsApp — send channel"],
          tools: ["knowledge_search (live inventory + demo-car availability)"],
          guardrails: [
            "Grounded-only: slots are offered only for variants Knowledge confirms in stock — never an unavailable model",
            "The booking is not treated as confirmed until the Tool node has written it to the DMS",
            "Scope-locked to the enquiring buyer and their chosen dealership — the agent can't read or book other stores",
          ],
          output:
            "The agent proposes real in-stock slots → the buyer confirms one on WhatsApp → a Tool node records the booking + lead in the DMS via MCP → Output nodes send a day-of reminder and a post-drive follow-up toward a quote.",
        },
      },
      {
        id: "auto-service-reminder",
        title: "Proactive service reminders that book themselves",
        scenario:
          "Service due dates are tracked in a spreadsheet and reminders are called manually — so cars miss their service, the workshop bay sits idle, and post-service feedback is never collected.",
        triggerLabel: "Webhook (scheduled, service-due run)",
        steps: [
          { kind: "trigger", title: "Service-due run", detail: "A scheduled Webhook trigger evaluates vehicles approaching their service date each morning (fed from your DMS via Webhook)." },
          { kind: "loop", title: "Per due vehicle", detail: "A Loop node iterates the due list so each owner gets a tailored reminder." },
          { kind: "agent", title: "Draft the reminder + slot options", detail: "An Agent node writes a friendly reminder with the car's due item and offers workshop slots." },
          { kind: "output", title: "Reach the owner", detail: "An Output node sends it on WhatsApp/SMS and lets the owner pick a slot in one tap." },
          { kind: "tool", title: "Book the workshop bay", detail: "A Tool (MCP) node writes the confirmed booking back to the DMS / workshop scheduler." },
          { kind: "output", title: "Post-service feedback", detail: "An Output node sends a short feedback ask after the service is marked complete." },
        ],
        connectors: ["Webhook", "Loop", "WhatsApp", "SMS", "Tool (MCP → DMS/scheduler)"],
        outcome: "Due vehicles are reminded and booked automatically, bays fill up, and feedback arrives without service advisors chasing it.",
        impact: "Consistent, timely reminders are what bring cars back in — the manual process skips exactly the customers who forget, which is most of them.",
        build: {
          agents: 1,
          agentsNote: "1 agent drafts each reminder inside the Loop; the scheduled trigger, the loop, the booking write and the feedback ask are workflow nodes.",
          prompt:
            "You are a service-reminder assistant for {{dealership}}'s workshop. For each vehicle you receive (owner name, model, registration, due service item, due date), write a short, friendly reminder in the owner's language that names the specific due item and invites them to pick a workshop slot. Do not invent service items, prices, or offers beyond what the record provides. Keep it to a couple of lines and one clear call to action.",
          mcp: ["DMS / workshop scheduler — via a Tool (MCP) node", "WhatsApp + SMS — send channels"],
          tools: ["http_request (pull the vehicle's due item / service history)"],
          guardrails: [
            "Idempotent per vehicle per service cycle — a re-run of the daily job never double-reminds the same owner",
            "Scope-locked to the vehicles in the due list for this run — no messaging owners who aren't due",
            "A slot is only treated as booked once the Tool node writes it back to the scheduler",
          ],
          output:
            "The Loop iterates the due list → the agent drafts a tailored reminder → an Output node sends it on WhatsApp/SMS with one-tap slot pick → a Tool node books the bay in the DMS/scheduler via MCP → after the service is marked complete, an Output node sends a short feedback ask.",
        },
      },
    ],
  },

  // ================================================================ TELECOM
  {
    id: "telecom",
    name: "Telecom",
    icon: Signal,
    tagline: "Plan changes, complaint resolution, KYC collection and outage notices — multilingual, with billing decisions gated.",
    recipes: [
      {
        id: "telecom-planchange",
        title: "Plan-change request with a billing approval gate",
        scenario:
          "Subscribers message asking to upgrade, downgrade or add a pack; today an agent looks it up, explains options, then keys the change into the billing system by hand — slow, and inconsistent across languages.",
        triggerLabel: "WhatsApp / Call (plan request)",
        steps: [
          { kind: "trigger", title: "Subscriber asks to change plan", detail: "A WhatsApp or Call trigger starts the flow in the subscriber's own language." },
          { kind: "agent", title: "Understand + respond in-language", detail: "An Agent node works in the caller's language (Sarvam covers 22+ Indian languages) and identifies the current plan." },
          { kind: "knowledge", title: "Ground in the tariff catalogue", detail: "A Knowledge node answers strictly from your current plans/tariffs and eligibility rules — with citations, no guessing." },
          { kind: "approval", title: "Human approves the billing change", detail: "An Approval node holds any tariff/billing change until a person signs off — the non-negotiable control for anything that moves money." },
          { kind: "tool", title: "Apply in OSS/BSS", detail: "On approval, a Tool (MCP) node applies the plan change in your OSS/BSS or billing system — reached via MCP, not a native connector." },
          { kind: "output", title: "Confirm to the subscriber", detail: "An Output node confirms the new plan, effective date and revised bill on WhatsApp." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Sarvam (translate and speech tools)", "Knowledge base", "Approval / HITL", "Tool (MCP → OSS/BSS)"],
        outcome: "Plan changes are explained accurately in any language and applied fast — but never committed to billing without a human approval and a full trail.",
        impact: "Combines multilingual self-serve with control: the mechanical lookup and messaging are instant, the billing decision stays with a person.",
        build: {
          agents: 1,
          agentsNote: "1 care agent explains the options; the billing Approval and the OSS/BSS write are workflow nodes.",
          prompt:
            "You are a telecom care agent. Identify the subscriber's current plan and understand the change they want (upgrade, downgrade, add-on pack). Explain the options strictly from the tariff catalogue in Knowledge — cite the plan, price and eligibility, and never quote a tariff or benefit that isn't in the catalogue. Work in the subscriber's language (Sarvam covers 22+ Indian languages). Prepare the requested change for approval; state clearly that it takes effect only after it is confirmed. Never tell the subscriber the change is done before the Approval and OSS/BSS steps complete.",
          mcp: ["OSS/BSS / billing system — via a Tool (MCP) node (no first-party connector)", "Sarvam — in-language voice/text", "WhatsApp — send channel"],
          tools: ["knowledge_search (tariff catalogue + eligibility rules)"],
          guardrails: [
            "No tariff or billing change is applied without the Approval node — money never moves on the agent's word",
            "Grounded-only on the tariff catalogue — no invented plans, prices or benefits",
            "Scope-locked to the subscriber's own account — the agent can't read or change another line",
          ],
          output:
            "The agent explains options in-language grounded in the tariff catalogue → a human approves the billing change at the Approval node → a Tool node applies it in the OSS/BSS via MCP → an Output node confirms the new plan, effective date and revised bill on WhatsApp.",
        },
      },
      {
        id: "telecom-sim-kyc",
        title: "SIM / KYC document collection with a verification gate",
        scenario:
          "New activations and re-KYC need identity documents; today staff chase photos over calls and email for days, and quality/format issues bounce the case back repeatedly.",
        triggerLabel: "WhatsApp / Webhook (activation or re-KYC)",
        steps: [
          { kind: "trigger", title: "KYC case opens", detail: "A WhatsApp trigger (or a Webhook from your activation flow) starts the case in the subscriber's language." },
          { kind: "agent", title: "Ask for exactly what's needed", detail: "An Agent node requests the specific document(s) and captures them right in the chat, guiding on format/clarity." },
          { kind: "condition", title: "Completeness check", detail: "A Condition node won't advance until every mandatory document is present and legible." },
          { kind: "approval", title: "Officer verifies identity", detail: "An Approval node hands the complete, structured case to a human officer — KYC verification is a decision a person must make." },
          { kind: "tool", title: "Record the verified KYC", detail: "A Tool (MCP) node writes the verified record into your OSS/BSS / KYC system on approval." },
          { kind: "output", title: "Confirm activation", detail: "An Output node tells the subscriber their KYC is complete and the SIM/plan is active." },
        ],
        connectors: ["WhatsApp", "Webhook", "Sarvam (multilingual)", "Approval / HITL", "Tool (MCP → OSS/BSS)"],
        outcome: "Documents arrive complete and legible the first time; officers spend time verifying, not chasing, and every KYC approval is logged.",
        impact: "Removes the days of manual document chasing from the front of every activation, while the identity decision stays with an authorised human.",
        build: {
          agents: 1,
          agentsNote: "1 agent collects and checks the documents; the completeness Condition, the officer Approval and the OSS/BSS write are workflow nodes.",
          prompt:
            "You are a KYC document-collection assistant. In the subscriber's language, request exactly the documents this case requires (per the required-document checklist in Knowledge), accept them in the chat, and guide the subscriber on clarity, format and framing when an image is unusable. Confirm each document received. Never state that identity is verified or KYC is complete — that decision belongs to a human officer. Do not proceed while any mandatory document is missing or illegible.",
          mcp: ["OSS/BSS / KYC system — via a Tool (MCP) node", "WhatsApp — receive + send channel"],
          tools: ["document parsing (read/legibility-check the uploaded ID)", "knowledge_search (required-document checklist per case type)"],
          guardrails: [
            "The case never advances until every mandatory document is present and legible (Condition node)",
            "Identity is never marked verified without the officer Approval node — the agent only collects",
            "PII (ID scans) is scope-locked to this case and only written to the KYC system on approval",
          ],
          output:
            "The agent requests + captures the documents → the Condition node gates on completeness/legibility → the officer verifies identity at the Approval node → a Tool node writes the verified record into the OSS/BSS/KYC system via MCP → an Output node confirms activation to the subscriber.",
        },
      },
    ],
  },
];
