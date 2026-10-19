import type { Industry } from "./recipes-data";
import { Calculator, Sun } from "lucide-react";

// ---------------------------------------------------------------------------
// Accounting-firm & Solar-installer workflow recipes — same "inline copy, no
// i18n keys" precedent and honesty rules as recipes-data.tsx. Every recipe is
// expressed ONLY in VegaDūta's real building blocks (Trigger / Agent /
// Knowledge / Condition / Approval / Output / Tool(MCP) / Voice / Device /
// Loop / Code / Parallel), so each maps 1:1 to something you can actually
// build in the Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — practice-management / accounting
// software, CRM/schedulers, inverter & monitoring platforms — are reached the
// real way, via an inbound Webhook trigger, a Tool (MCP) node, or the Device
// Control node, and are LABELLED as such. Every consequential decision (a tax
// filing, a client onboarding, a quote / financial commitment, a chargeable
// service visit) goes through an Approval (human-in-the-loop) step. The
// accounting agents never give tax advice and never file — a qualified
// accountant reviews and files. Impact lines describe the mechanism (why it
// helps), never invented ROI numbers.
// ---------------------------------------------------------------------------

export const INDUSTRIES_ACCTSOLAR: Industry[] = [
  // ============================================ ACCOUNTING & CA / CPA FIRMS
  {
    id: "accounting-firm",
    name: "Accounting & CA / CPA firms",
    icon: Calculator,
    tagline:
      "Chase filing documents at every deadline and onboard clients with clean KYC — while a qualified accountant stays the one who reviews and files.",
    recipes: [
      {
        id: "acct-docs",
        title: "Deadline document collection across every client (map-reduce)",
        scenario:
          "At each GST / income-tax / TDS deadline a firm chases dozens of clients for the same missing papers by hand — one WhatsApp at a time — and only finds out who's still short when it's nearly too late to file.",
        triggerLabel: "Webhook (scheduled, N days before a filing deadline)",
        steps: [
          { kind: "trigger", title: "Deadline run kicks off", detail: "A scheduled Webhook trigger fires a set number of days before a GST/ITR/TDS deadline with the list of clients due." },
          { kind: "loop", title: "Iterate every client due", detail: "A Loop node fans a collector agent across each client on the deadline list — one focused pass per client, isolated context." },
          { kind: "agent", title: "Check what's missing, draft the chase", detail: "A cheap collector agent compares the documents already received against that client's required checklist and drafts a request for ONLY the missing items." },
          { kind: "output", title: "Nudge the client", detail: "An Output node sends the missing-document list on WhatsApp/Email with a secure upload link, in the client's language." },
          { kind: "agent", title: "Roll up a filing-readiness board", detail: "After the map completes, a reducer agent aggregates the per-client statuses into one ready/blocked readiness board for the practice." },
          { kind: "approval", title: "Accountant reviews before anything is filed", detail: "An Approval node puts the readiness board with a qualified accountant — the agents only collect and chase; a human reviews and files." },
        ],
        connectors: ["Webhook", "WhatsApp", "Email", "Tool (MCP → practice-mgmt)", "Approval / HITL"],
        outcome: "Every client due at the deadline gets a precise, timely chase for exactly the papers they're missing, and the accountant sees one readiness board instead of reconstructing it from a dozen inboxes.",
        impact: "Consistent, per-client chasing is what gets the documents in on time; the value is that no client silently falls through the cracks before a deadline — the filing itself stays with a qualified human.",
        build: {
          agents: 2,
          agentsNote: "1 collector (runs once per client via the Loop node) + 1 readiness reducer (runs once). Loop/Output/Approval are workflow nodes.",
          pattern: "Map-reduce: Loop (map, per client) → readiness reducer (reduce, once)",
          prompt:
            "You are a document-collection assistant for an accounting practice, handling ONE client per run. Compare the documents already received against {{client}}'s required checklist for this {{filing_type}} (GST / ITR / TDS). Return strict JSON {client_id, filing_type, status: READY|WAITING, received[], missing[], chase_message?}. If anything is missing, draft a short, polite chase in the client's language listing ONLY the missing items with a secure upload link. Never give tax, GST or filing advice, never compute a tax liability, and never state or imply anything has been filed — you only collect documents; a qualified accountant reviews and files.",
          agentRoles: [
            { role: "Collector / checker (per client)", model: "Economy tier — fast, cheap; runs once per client so its model choice dominates total cost", prompt: "For ONE client, compare received documents vs the required checklist for the filing type; draft a chase for the missing items only; return {status, received[], missing[], chase_message?}. No tax advice, no computed liabilities, no filing." },
            { role: "Readiness reducer (once)", model: "Standard tier — runs once per deadline", prompt: "Aggregate the per-client results into a filing-readiness board {ready[], waiting[{client, missing[]}], counts}. Use only the provided statuses — never mark a client ready that reported missing documents, never estimate. This board is for an accountant to action; it files nothing." },
          ],
          mcp: ["Practice-management / accounting software (Zoho Books, Tally, QuickBooks) — via a Tool (MCP) node", "WhatsApp / Email — chase channel", "Client checklist + deadline list — inbound via the scheduled Webhook trigger"],
          tools: ["knowledge_search (per-filing-type document checklist)", "http_request (fetch received-document status)"],
          guardrails: [
            "No tax advice, no computed liabilities, no filing — the agents only collect documents; a qualified accountant reviews the readiness board and files at the Approval node",
            "A client is marked READY only when every checklist item is received — the completeness gate never lets a partial set through as fileable",
            "Chasing is idempotent (keyed by client + deadline) so nobody is double-messaged in one run, and missing-item lists come from the checklist, never invented",
          ],
          cost:
            "The collector runs once per client — across a whole practice at a filing deadline that repetition is the dominant cost, so it sits on the economy tier (bulk, routine document-checking is exactly the cheap-model case). The readiness reducer runs once per deadline on a standard tier. Cost scales linearly with client count under a per-client tool/token cap, and no premium model is ever spent on routine chasing.",
          output:
            "The Loop runs the economy collector per client → each drafts a missing-items chase sent on WhatsApp/Email with a secure upload link → the completeness gate marks READY/WAITING → the reducer rolls one readiness board → the accountant reviews it at the Approval node before any client is filed; every client's status is logged.",
        },
      },
      {
        id: "acct-onboard",
        title: "Client onboarding with clean KYC — human-approved",
        scenario:
          "Onboarding a new client means collecting PAN, GST and incorporation papers over email, retyping the details into the practice software, and hoping the name/PAN actually match — slow, and easy to get wrong.",
        triggerLabel: "Webhook (intake form) or WhatsApp (new client)",
        steps: [
          { kind: "trigger", title: "New client starts onboarding", detail: "A Webhook from the firm's intake form (or a WhatsApp message) kicks off the flow with the prospective client's basics." },
          { kind: "agent", title: "Collect + structure the KYC", detail: "An Agent node requests and extracts PAN, GST registration, incorporation/partnership documents and contact details into structured data." },
          { kind: "knowledge", title: "Ground the checklist", detail: "A Knowledge node holds the KYC checklist per entity type (individual / partnership / company) so nothing required for that type is missed." },
          { kind: "condition", title: "Completeness + validity check", detail: "A Condition node confirms every required document is present and the name/PAN/GSTIN are internally consistent; incomplete or mismatched packs branch back to re-request." },
          { kind: "approval", title: "Accountant approves onboarding", detail: "An Approval node puts the assembled KYC pack in front of a qualified accountant — the client is accepted by a human, not the agent." },
          { kind: "tool", title: "Create the client record", detail: "On approval, a Tool (MCP) node creates the client in the practice-management software with the source documents linked." },
        ],
        connectors: ["Webhook", "WhatsApp", "Knowledge base", "Approval / HITL", "Tool (MCP → practice-mgmt)"],
        outcome: "A new client's KYC is collected, structured and validated once, and the accountant approves a clean, consistent pack instead of chasing scattered attachments.",
        impact: "Removes the retyping and the transcription errors that surface later as filing problems; the human step becomes a review of a ready pack, not manual data entry.",
        build: {
          agents: 1,
          agentsNote: "1 onboarding/KYC agent; the completeness check, approval and the record creation are workflow nodes.",
          prompt:
            "You are a client-onboarding assistant for an accounting practice. Collect and structure a new client's KYC for {{entity_type}} (individual / partnership / company): request and extract PAN, GST registration, incorporation/partnership documents and contact details into strict JSON {legal_name, entity_type, pan, gstin?, documents[{type, received}], mismatches[]}. Copy values EXACTLY from the documents — never invent an ID number — and flag any name/PAN/GSTIN mismatch in mismatches[]. Do NOT give tax or registration advice, and do NOT create the client record yourself — a qualified accountant reviews and approves onboarding first.",
          mcp: ["Practice-management software (Zoho Books, Tally, QuickBooks) — via a Tool (MCP) node", "Intake form / WhatsApp — client document channel"],
          tools: ["document parsing (PAN / GST / incorporation PDFs → text)", "knowledge_search (KYC checklist per entity type)"],
          guardrails: [
            "No tax or registration advice, and no self-onboarding — a qualified accountant reviews the KYC pack and approves before the client record is created (Approval node)",
            "IDs (PAN / GSTIN) are copied exactly from documents, never generated; any name/PAN/GSTIN mismatch is flagged, not silently accepted",
            "The client record is written only by the Tool node after approval, keyed to the PAN so the same client can't be onboarded twice",
          ],
          cost:
            "Onboarding is low-volume and detail-sensitive, so a single standard-tier agent handles the full KYC extraction and document parsing runs once per client. There's no premium model in the loop — the human accountant is the quality gate, not a heavyweight model — so cost per onboarding is small and predictable.",
          output:
            "The agent assembles a structured KYC pack, grounded in the per-entity checklist from Knowledge and validated at the Condition node → the accountant approves it at the Approval node → a Tool node creates the client in the practice-management software via MCP, with the source documents linked.",
        },
      },
    ],
  },

  // ============================================ SOLAR & RENEWABLE INSTALLERS
  {
    id: "solar",
    name: "Solar & renewable-energy installers",
    icon: Sun,
    tagline:
      "Qualify enquiries, book site surveys and catch underperforming inverters — with a human on every quote and every chargeable visit.",
    recipes: [
      {
        id: "solar-lead",
        title: "Qualify a solar enquiry, book the survey, approve the quote",
        scenario:
          "Enquiries from the website and portals sit unqualified; a salesperson eyeballs each one, guesses a system size, books a survey by phone tag, and quotes off the cuff — so good leads go cold and prices drift.",
        triggerLabel: "Webhook (website/portal lead form) or WhatsApp",
        steps: [
          { kind: "trigger", title: "New enquiry arrives", detail: "A Webhook fires the moment a website/portal lead form is submitted (or a WhatsApp enquiry lands)." },
          { kind: "agent", title: "Qualify + size the lead", detail: "An Agent node scores fit from roof type, monthly bill, ownership and location, and proposes an indicative system-size band from the bill." },
          { kind: "knowledge", title: "Ground product, pricing & subsidy", detail: "A Knowledge node holds the product catalogue, indicative pricing and current subsidy / net-metering eligibility, so nothing is invented." },
          { kind: "tool", title: "Book the site survey", detail: "For genuinely qualified leads, a Tool (MCP) node books a surveyor slot in the scheduler/CRM." },
          { kind: "approval", title: "Sales approves the quote", detail: "An Approval node holds any quote / price commitment for a human — the agent qualifies and books, a person signs off the money." },
          { kind: "output", title: "Confirm + follow up", detail: "An Output node sends the survey confirmation on WhatsApp/Email, then the approved quote and a follow-up nudge." },
        ],
        connectors: ["Webhook", "WhatsApp", "Email", "Knowledge base", "Tool (MCP → scheduler/CRM)", "Approval / HITL"],
        outcome: "Every enquiry is qualified and sized within seconds, qualified leads get a survey booked automatically, and a human approves every price before it reaches the customer.",
        impact: "Speed-to-lead plus a grounded, consistent size/quote is what wins solar deals; the value is instant qualification and booking with a human owning the financial commitment — not an auto-quoting bot.",
        build: {
          agents: 1,
          agentsNote: "1 qualifier/sizing agent; the survey booking, quote approval and follow-up are workflow nodes.",
          prompt:
            "You are a solar sales assistant for {{company}}. Qualify an incoming enquiry using its fields {name, location, monthly_bill, roof_type, ownership, phone} against the fit criteria, and propose an indicative system-size band from the bill. Ground every product, price and subsidy / net-metering detail in the Knowledge base — never invent pricing, a subsidy amount, or an eligibility you can't cite. Return strict JSON {fit: HOT|WARM|COLD, system_size_band, notes, book_survey: boolean}. You may schedule a site survey, but you must NOT send a final quote or price commitment yourself — a human approves any quote before it goes to the customer.",
          mcp: ["Scheduler / CRM (Google Calendar, HubSpot, Zoho CRM) — via a Tool (MCP) node", "WhatsApp / Email — reply channel", "Lead form (website/portal) — inbound via Webhook trigger"],
          tools: ["knowledge_search (product catalogue, indicative pricing, subsidy / net-metering rules)", "http_request (create the survey booking)"],
          guardrails: [
            "No quote or price commitment is sent without the Approval node — the agent qualifies and books surveys, a human approves any financial proposal",
            "All pricing, subsidy and net-metering figures come from Knowledge with a citation — never invented or estimated",
            "The survey slot is booked via the Tool node only for genuinely qualified (HOT/WARM) leads; poor-fit enquiries get an honest, polite reply, not a booking",
          ],
          cost:
            "A single standard-tier agent qualifies each enquiry, and cost routing lets it drop obviously low-intent or one-line enquiries to an economy model — so the premium reasoning is spent only on genuine, sizeable leads and no heavyweight model runs on low-intent traffic.",
          output:
            "The agent qualifies and sizes the lead grounded in Knowledge → for qualified leads a Tool node books a surveyor slot → the drafted quote goes to a human at the Approval node → an Output node sends the survey confirmation and, once approved, the quote and follow-up on WhatsApp/Email.",
        },
      },
      {
        id: "solar-monitor",
        title: "Post-install monitoring: catch an underperforming inverter",
        scenario:
          "After handover, a system's output is only checked when a customer complains their bill didn't drop. A dead string or offline inverter can lose generation for weeks before anyone notices.",
        triggerLabel: "Webhook (scheduled, daily telemetry run)",
        steps: [
          { kind: "trigger", title: "Daily telemetry run", detail: "A scheduled Webhook trigger fires each morning across the installed fleet." },
          { kind: "device", title: "Read the inverter", detail: "A Device Control node polls the inverter / monitoring platform for each site's yield, string status and fault codes — the reading comes from the hardware, not a guess." },
          { kind: "agent", title: "Diagnose the site", detail: "An Agent node compares actual vs expected generation for the site and season and classifies it {normal, underperforming, fault, offline} with a likely cause and severity." },
          { kind: "condition", title: "Act only on real issues", detail: "A Condition node logs healthy sites and stops; only genuine anomalies continue down the flow." },
          { kind: "approval", title: "Ops approves a chargeable visit", detail: "An Approval node holds any paid service visit / warranty dispatch for a human — the agent flags it, ops commits the cost." },
          { kind: "output", title: "Alert the homeowner + raise a ticket", detail: "An Output node WhatsApps the homeowner in plain language and a Tool node opens a service ticket in the CRM." },
        ],
        connectors: ["Webhook", "Device Control", "WhatsApp", "Approval / HITL", "Tool (MCP → service/CRM)"],
        outcome: "Underperformance is caught the morning after it happens from the inverter's own telemetry, the homeowner hears it from you first, and a human approves any visit that costs money.",
        impact: "Reading generation from the device instead of waiting for a complaint is what turns weeks of lost output into a next-day catch; the chargeable dispatch stays a human decision.",
        build: {
          agents: 1,
          agentsNote: "1 diagnosing agent runs once per site; the Device read, the anomaly gate, the approval and the ticket are workflow nodes.",
          prompt:
            "You are a solar fleet-monitoring assistant. Given one site's telemetry {site_id, expected_kwh, actual_kwh, string_status[], fault_codes[], date}, classify performance as NORMAL | UNDERPERFORMING | FAULT | OFFLINE with a likely cause and severity, as strict JSON {site_id, status, likely_cause, severity: LOW|MED|HIGH, homeowner_message?, needs_visit: boolean}. Read ONLY the telemetry provided — never guess a reading or a cause you can't derive from it. Set needs_visit=true when a physical or chargeable service is indicated, but you do NOT dispatch or promise a paid visit yourself — ops approves any chargeable work. Keep the homeowner message plain and reassuring.",
          mcp: ["Inverter / monitoring platform (SolarEdge, Fronius, Growatt) — via the Device Control node / a Tool (MCP)", "Service / CRM system — via a Tool (MCP) node", "WhatsApp — homeowner channel"],
          tools: ["http_request (poll telemetry, open a service ticket)", "knowledge_search (expected-generation baseline per site/season)"],
          guardrails: [
            "No chargeable service, truck-roll or warranty claim is dispatched without the Approval node — the agent flags needs_visit, ops approves the cost",
            "Diagnoses use only the device telemetry (yield, string status, fault codes) — the agent never fabricates a reading or an unsupported cause",
            "The Device Control node is read-only telemetry here — a device read never triggers a control action; any physical intervention is a human-dispatched visit",
          ],
          cost:
            "The diagnosing agent runs once per site per telemetry run, so it sits on the economy tier — across a fleet that repetition is the cost driver and actual-vs-expected comparison is a light classification task. Only real anomalies pass the Condition gate, so no premium model and no human time is spent on healthy sites.",
          output:
            "The scheduled run polls each inverter via the Device Control node → the economy agent classifies performance against the expected-generation baseline → the Condition gate drops healthy sites → anomalies needing a chargeable visit wait at the Approval node → on approval a Tool node opens a service ticket and an Output node WhatsApps the homeowner a plain-language heads-up.",
        },
      },
    ],
  },
];
