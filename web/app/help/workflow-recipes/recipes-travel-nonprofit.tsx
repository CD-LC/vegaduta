import type { Industry } from "./recipes-data";
import { Plane, HeartHandshake } from "lucide-react";

// ---------------------------------------------------------------------------
// Additional industry workflow recipes — Travel & tourism and Nonprofit / NGO.
// Same rules as recipes-data.tsx: every recipe is expressed ONLY in VegaDūta's
// real building blocks (Trigger, Agent, Knowledge, Condition, Approval, Output,
// Tool/MCP, Voice, Device, Loop, Code, Parallel), on channels/connectors that
// actually exist (Webhook, WhatsApp, SMS, Voice/Call, Email, Slack, plus
// ElevenLabs, Sarvam for 22+ Indian languages, Razorpay for UPI).
//
// Honesty rule (verbatim intent): systems VegaDūta does NOT ship a first-party
// connector for (GDS/booking engines, donor CRMs, grant/reporting systems) are
// reached the real way — an inbound Webhook trigger and/or a Tool (MCP) node —
// and are labelled "(MCP)" / "via Webhook", never implying native integration.
// Every consequential/financial decision goes through an Approval step. Impact
// lines describe the mechanism (why it helps), never invented ROI numbers.
// ---------------------------------------------------------------------------

export const INDUSTRIES_CIVIC: Industry[] = [
  // ================================================================== TRAVEL
  {
    id: "travel",
    name: "Travel & tourism",
    icon: Plane,
    tagline: "Answer itinerary questions, change bookings and collect travel documents — in the traveller's language.",
    recipes: [
      {
        id: "travel-concierge",
        title: "Itinerary Q&A and booking changes on WhatsApp",
        scenario:
          "Travellers message the agency at all hours asking what's included, when the transfer leaves, or to move a date — and an agent digs through the itinerary PDF and the booking system by hand, only in office hours.",
        triggerLabel: "WhatsApp / Call (traveller message)",
        steps: [
          { kind: "trigger", title: "Traveller reaches out", detail: "A WhatsApp or Call trigger starts on any inbound message or call, in whatever language the traveller uses." },
          { kind: "knowledge", title: "Answer from the itinerary", detail: "A Knowledge node grounds the reply in the trip's itinerary, inclusions and policy documents — with citations, so nothing is invented." },
          { kind: "agent", title: "Understand + handle the request", detail: "An Agent node interprets the question (what's included, timings, or a change request) and works in the traveller's language via Sarvam." },
          { kind: "condition", title: "Route change requests", detail: "A Condition node branches: simple questions answer straight away; a date/room/booking change continues to the update path." },
          { kind: "approval", title: "Agent confirms fare-affecting changes", detail: "An Approval node holds any change that alters price or availability for a human to confirm — nothing that costs the traveller money moves on its own." },
          { kind: "tool", title: "Update the booking", detail: "On approval, a Tool (MCP) node applies the change in your GDS / booking engine. There's no first-party connector — the change is pushed via MCP (or queued to ops)." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Sarvam (translate and speech tools)", "Knowledge base", "Approval / HITL", "Tool (MCP → booking system)"],
        outcome: "Travellers get accurate, in-language answers instantly and simple changes are made for them — while any fare-affecting change stays human-confirmed.",
        impact: "Replaces the manual itinerary lookup and after-hours silence with an always-on concierge, and keeps the money decision on any change with a person.",
        build: {
          agents: 1,
          agentsNote: "1 concierge agent handles the conversation; the change-routing Condition, the fare Approval and the booking write are workflow nodes.",
          prompt:
            "You are a travel concierge for {{agency}}. Answer the traveller's questions strictly from the trip's itinerary, inclusions and policy documents in Knowledge, with citations — never invent an inclusion, timing or fee. Work in the traveller's language (Sarvam covers 22+ Indian languages). If the traveller asks to change a date, room or booking, gather the details and prepare the change, but do not apply anything that affects fare or availability yourself — that must be human-confirmed. State clearly that a fare-affecting change is pending confirmation.",
          mcp: ["GDS / booking engine — via a Tool (MCP) node (no first-party connector)", "WhatsApp — receive + send channel", "Sarvam — in-language conversation"],
          tools: ["knowledge_search (itinerary + inclusions + policy)"],
          guardrails: [
            "Grounded-only on the trip's own documents — no invented inclusions, timings or fees",
            "No fare- or availability-affecting change without the Approval node — nothing that costs the traveller money moves on its own",
            "Scope-locked to the traveller's own booking",
          ],
          output:
            "The agent answers in-language grounded in the itinerary → the Condition node routes change requests → fare-affecting changes go to the Approval node → on approval a Tool node applies the change in the GDS/booking engine via MCP (or queues it to ops).",
        },
      },
      {
        id: "travel-visa",
        title: "Visa & travel-document collection with a completeness gate",
        scenario:
          "Before every departure, a coordinator emails travellers back and forth for passport scans, photos and visa forms, then manually checks each file is present and legible — days of chasing per booking.",
        triggerLabel: "Webhook (booking confirmed) or WhatsApp",
        steps: [
          { kind: "trigger", title: "Trip is booked", detail: "A Webhook trigger (from your booking system) or a WhatsApp message opens document collection for the traveller." },
          { kind: "agent", title: "Request exactly what's needed", detail: "An Agent node asks for the specific documents this destination requires (passport, photo, visa form) and accepts them right in the chat." },
          { kind: "condition", title: "Completeness gate", detail: "A Condition node won't advance the file until every mandatory document is present — incomplete cases loop back with a clear list of what's still missing." },
          { kind: "output", title: "Pre-trip reminders", detail: "An Output node sends timed reminders on WhatsApp/SMS for anything outstanding and the pre-departure checklist as the date nears." },
          { kind: "tool", title: "File the documents", detail: "A Tool (MCP) node stores the collected documents against the booking in your system." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS", "Sarvam (multilingual)", "Tool (MCP → booking system)"],
        outcome: "Every traveller's documents arrive complete and on time, checked automatically, without a coordinator chasing email threads.",
        impact: "Removes the days of manual document chasing and the risk of a missing paper surfacing at the airport — the completeness gate does the checking, not a person.",
        build: {
          agents: 1,
          agentsNote: "1 agent requests and checks the documents; the completeness Condition, the reminders and the filing step are workflow nodes.",
          prompt:
            "You are a travel-document collection assistant. In the traveller's language, ask for exactly the documents this destination requires (per the destination checklist in Knowledge) — passport, photo, visa form — and accept them in the chat, guiding on clarity, format and validity when an image is unusable. Confirm each document as it arrives. Never mark the file complete while any mandatory document is missing or illegible; instead list clearly what is still outstanding.",
          mcp: ["Booking system / document store — via a Tool (MCP) node (no first-party connector)", "WhatsApp + SMS — receive + send channels", "Sarvam — in-language conversation"],
          tools: ["document parsing (check passport/photo/form legibility)", "knowledge_search (per-destination required-document list)"],
          guardrails: [
            "The file never advances until the Condition completeness gate passes — every mandatory document present and legible",
            "Scope-locked to the traveller's own booking and documents",
            "PII (passport scans) is handled only within this case and filed to the booking, nowhere else",
          ],
          output:
            "The agent requests and collects the documents → the Condition gate holds until all mandatory documents are present and legible → an Output node sends timed reminders for anything outstanding → a Tool node files the documents against the booking via MCP.",
        },
      },
    ],
  },

  // =============================================================== NONPROFIT
  {
    id: "nonprofit",
    name: "Nonprofit & NGO",
    icon: HeartHandshake,
    tagline: "Thank donors, coordinate volunteers and answer beneficiaries — approval-gated on anything financial.",
    recipes: [
      {
        id: "ngo-donor",
        title: "UPI donation → instant thank-you and receipt",
        scenario:
          "A supporter donates over UPI, and today a staffer eventually notices it on the dashboard, drafts a receipt, and sends a thank-you by hand — slowly, and receipts get missed at campaign volume.",
        triggerLabel: "Webhook (Razorpay UPI donation.captured)",
        steps: [
          { kind: "trigger", title: "Donation captured", detail: "A Webhook trigger receives the Razorpay UPI donation event the instant the money lands." },
          { kind: "condition", title: "Verify amount & signature", detail: "A Condition node checks the amount and validates the payment signature before anything runs." },
          { kind: "agent", title: "Draft receipt + thank-you", detail: "An Agent node generates a branded donation receipt and a warm thank-you in the donor's language." },
          { kind: "approval", title: "Finance signs off the receipt", detail: "An Approval node holds the tax receipt for a human to confirm — anything financial is never issued automatically." },
          { kind: "output", title: "Thank the donor on WhatsApp", detail: "On approval, an Output node sends the receipt and thank-you on WhatsApp (or Email) within moments of the gift." },
          { kind: "tool", title: "Log to the donor CRM", detail: "A Tool (MCP) node records the donation against the donor's record. There's no first-party CRM connector — it's written via MCP." },
        ],
        connectors: ["Webhook (Razorpay UPI)", "WhatsApp", "Email", "Approval / HITL", "Tool (MCP → donor CRM)"],
        outcome: "Every donor is thanked and receipted promptly and the gift is logged automatically — with a human confirming the financial document.",
        impact: "Removes the manual 'did a donation come in?' check and the receipts that slip at volume, while the money/receipt step stays approval-gated.",
        build: {
          agents: 1,
          agentsNote: "1 agent drafts the receipt + thank-you; verification, the finance Approval and the CRM write are workflow nodes.",
          prompt:
            "You are a donations assistant for {{organization}}. Given a verified UPI donation {amount, donor_name, language}, write a warm thank-you in the donor's language and a clean donation receipt (receipt no., amount, date, and 80G/tax details only if present in the record). Never state a donation was successful unless the input is marked verified=true. Do not invent tax-exemption details or amounts — use only the fields provided. Prepare the receipt for finance; do not present it as issued until it is approved.",
          mcp: ["Razorpay UPI — inbound via Webhook trigger", "Donor CRM — via a Tool (MCP) node (no first-party connector)", "WhatsApp + Email — send channels"],
          tools: ["http_request (fetch the donor record for the receipt)"],
          guardrails: [
            "Signature + amount verification at the Condition node before anything runs — stops spoofed donation webhooks",
            "Idempotent on the Razorpay payment_id — a retried webhook never double-receipts or double-thanks",
            "The tax receipt is never issued without the finance Approval node",
          ],
          output:
            "On verify, the agent drafts the receipt + thank-you → finance confirms the tax receipt at the Approval node → an Output node sends it on WhatsApp/Email → a Tool node logs the donation against the donor's record in the CRM via MCP, keyed idempotently on the payment id.",
        },
      },
      {
        id: "ngo-volunteer",
        title: "Volunteer sign-up and coordination",
        scenario:
          "Volunteer interest arrives across forms, calls and messages, and a coordinator manually collects availability, confirms slots and reminds people — so shifts go unfilled and no-shows are common.",
        triggerLabel: "Webhook (sign-up form) or WhatsApp",
        steps: [
          { kind: "trigger", title: "Volunteer signs up", detail: "A Webhook (from your sign-up form) or a WhatsApp message starts the flow per volunteer." },
          { kind: "agent", title: "Capture availability + skills", detail: "An Agent node collects when they're free and what they can help with, in their language, into a clean record." },
          { kind: "condition", title: "Match to an open shift", detail: "A Condition node routes the volunteer to a suitable open slot, or holds them for the coordinator when nothing fits." },
          { kind: "output", title: "Confirm + remind", detail: "An Output node confirms the assigned shift and sends day-before reminders on WhatsApp/SMS to cut no-shows." },
          { kind: "tool", title: "Log to the volunteer roster", detail: "A Tool (MCP) node records the assignment in your roster/CRM system." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS", "Sarvam (multilingual)", "Tool (MCP → volunteer roster)"],
        outcome: "Volunteers are matched, confirmed and reminded automatically, so shifts fill and fewer people forget to turn up.",
        impact: "Replaces the manual back-and-forth of collecting availability and chasing confirmations with a self-serve flow the coordinator only supervises.",
        build: {
          agents: 1,
          agentsNote: "1 agent captures availability and skills; the shift-matching Condition, the reminders and the roster write are workflow nodes.",
          prompt:
            "You are a volunteer-coordination assistant for {{organization}}. In the volunteer's language, collect their availability and the skills/tasks they can help with into a clean record {name, availability, skills, location}. Match them only against the genuinely open shifts you are given — never invent a shift or over-promise a slot. If nothing suitable is open, tell them you'll hold their details for the coordinator rather than forcing a fit. Keep it friendly and brief.",
          mcp: ["Volunteer roster / CRM — via a Tool (MCP) node (no first-party connector)", "WhatsApp + SMS — receive + send channels", "Sarvam — in-language conversation"],
          tools: ["http_request (fetch the current open shifts)"],
          guardrails: [
            "Only assigns against genuinely open shifts — no double-booking a slot or inventing one",
            "Unmatched volunteers are held for the coordinator, not force-fit into an unsuitable shift",
            "Scope-locked to the volunteer's own record",
          ],
          output:
            "The agent captures availability + skills → the Condition node matches to an open shift or holds the volunteer for the coordinator → an Output node confirms the assigned shift and sends day-before reminders on WhatsApp/SMS → a Tool node records the assignment in the roster/CRM via MCP.",
        },
      },
    ],
  },
];
