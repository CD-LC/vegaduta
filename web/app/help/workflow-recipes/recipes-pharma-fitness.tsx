import type { Industry } from "./recipes-data";
import { Pill, Dumbbell } from "lucide-react";

// ---------------------------------------------------------------------------
// Pharma / life-sciences & Fitness / wellness workflow recipes — same "inline
// copy, no i18n keys" precedent and honesty rules as recipes-data.tsx. Every
// recipe is expressed ONLY in VegaDūta's real building blocks (Trigger / Agent
// / Knowledge / Condition / Approval / Output / Tool(MCP) / Voice / Device /
// Loop / Code / Parallel), so each maps 1:1 to something you can actually build
// in the Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — a pharmacovigilance / safety database,
// CTMS, CRM, a class-booking or membership-management system — are reached the
// real way, via an inbound Webhook trigger and/or a Tool (MCP) node, and are
// LABELLED as such. Every consequential decision, and NON-NEGOTIABLY anything
// touching safety, adverse events or regulatory reporting, goes through an
// Approval (human-in-the-loop) step. Impact lines describe the mechanism (why
// it helps), never invented ROI numbers.
// ---------------------------------------------------------------------------

export const INDUSTRIES_HEALTHPLUS: Industry[] = [
  // =============================================== PHARMA & LIFE SCIENCES
  {
    id: "pharma",
    name: "Pharma & life sciences",
    icon: Pill,
    tagline: "Adverse-event intake, field-rep reporting and trial pre-screening — with mandatory human review on anything safety or regulatory.",
    recipes: [
      {
        id: "pharma-ae",
        title: "Adverse-event / pharmacovigilance intake with mandatory review",
        scenario:
          "A patient or HCP reports a suspected side effect by call or message. Today someone transcribes it by hand, works out whether it's a reportable adverse event, and keys it into the safety database — slow, inconsistent, and any missed detail is a compliance risk.",
        triggerLabel: "Call / WhatsApp (adverse-event report)",
        steps: [
          { kind: "trigger", title: "Report comes in", detail: "A Call or WhatsApp trigger captures the report in the reporter's own words and language — spoken reports are transcribed live." },
          { kind: "agent", title: "Structure the ICSR fields", detail: "An Agent node extracts the four minimum-report elements (patient, reporter, product, event) plus dose, onset and outcome into a structured draft — it never decides reportability on its own." },
          { kind: "knowledge", title: "Ground in the safety reference", detail: "A Knowledge node checks the product label and MedDRA/coding reference so terms are grounded and consistent, with citations — no guessing at causality." },
          { kind: "condition", title: "Flag seriousness & completeness", detail: "A Condition node flags seriousness criteria and won't advance a case until the mandatory fields are present." },
          { kind: "approval", title: "Pharmacovigilance officer reviews", detail: "An Approval node hands every case to a qualified PV officer — the reportability and causality assessment is a human decision, non-negotiable and fully logged. Nothing is filed automatically." },
          { kind: "tool", title: "File to the safety database", detail: "On approval, a Tool (MCP) node writes the reviewed case into your pharmacovigilance / safety database — no first-party safety-DB connector, MCP calls yours." },
        ],
        connectors: ["Voice / Call", "WhatsApp", "Sarvam (translate and speech tools)", "Knowledge base", "Approval / HITL", "Tool (MCP → safety database)"],
        outcome: "Every report is captured completely and structured the moment it arrives, but reportability and causality stay with a qualified human, with a full audit trail on each case.",
        impact: "Removes the manual transcription and inconsistent capture from the front of PV intake, while the safety decision — the part that must never be automated — stays with a trained officer.",
        build: {
          agents: 1,
          agentsNote: "1 intake agent structures the report; seriousness-flagging, PV review and the safety-DB write are workflow nodes.",
          prompt:
            "You are a pharmacovigilance intake assistant. From the report, capture strict JSON {patient (age/sex/initials only), reporter, product, dose, event_description, onset_date, outcome, seriousness_indicators[]}. Record the reporter's own words faithfully in the event description; use the product label and MedDRA reference from Knowledge only to code terms consistently, with a citation. You do NOT decide whether this is a reportable adverse event and you do NOT assess causality — that is the qualified PV officer's decision at the approval step. Never reassure the reporter about the product's safety and never give medical advice; if they describe an emergency, tell them to contact a doctor or emergency services. If a minimum element (patient, reporter, product, event) is missing, set needs_followup with the missing field.",
          mcp: ["Pharmacovigilance / safety database — via a Tool (MCP) node (no first-party connector)", "Telephony + WhatsApp — via the Voice/WhatsApp channels"],
          tools: ["knowledge_search (product label + MedDRA/coding reference)", "transfer_to_human"],
          guardrails: [
            "Never decides reportability or causality — every case goes to the qualified PV officer at the Approval node before anything is filed",
            "Grounded-only coding: terms come from the label/MedDRA reference with a citation, never invented; no safety reassurance or medical advice to the reporter",
            "A case can't advance until the four minimum elements are present; patient identity is minimised to what PV needs (initials/age/sex) and every case is logged for audit",
          ],
          output:
            "The agent emits a structured ICSR draft → the Condition node flags seriousness and blocks incomplete cases → the PV officer makes the reportability/causality call at the Approval node → on approval a Tool node writes the reviewed case into the safety database via MCP, with a full audit trail per case.",
        },
      },
      {
        id: "pharma-rep-visit",
        title: "Medical-rep field-visit reporting, straight from the field",
        scenario:
          "After each HCP call, medical reps are meant to log what happened, samples left, and follow-ups — but it's done from memory hours later on a laptop, so detail is lost and the CRM rots.",
        triggerLabel: "WhatsApp / Call (rep dictates visit)",
        steps: [
          { kind: "trigger", title: "Rep files the visit", detail: "A WhatsApp or Call trigger lets the rep dictate or message the visit summary the moment they leave the clinic." },
          { kind: "agent", title: "Structure the call report", detail: "An Agent node extracts the HCP, products discussed, samples left, sentiment and agreed next step into a clean, standard call report." },
          { kind: "condition", title: "Watch for a safety signal", detail: "A Condition node checks whether the rep mentioned any suspected side effect — if so it branches to the adverse-event intake flow rather than logging it as routine." },
          { kind: "approval", title: "Manager signs off sample records", detail: "An Approval node routes the sample-accountability portion to a manager, since dispensed-sample records are a compliance-controlled decision." },
          { kind: "tool", title: "Write to the CRM", detail: "A Tool (MCP) node updates the HCP record and the follow-up task in your CRM — reached via MCP, not a native connector." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Approval / HITL", "Tool (MCP → CRM)"],
        outcome: "Visits are logged in full while they're fresh, any safety mention is routed to proper PV intake, and sample records stay under human sign-off.",
        impact: "Replaces from-memory end-of-day data entry with capture at the moment of the visit, and guarantees a stray side-effect mention can't be quietly filed as a routine note.",
        build: {
          agents: 1,
          agentsNote: "1 agent structures the visit; the safety-signal branch, sample sign-off and CRM write are workflow nodes.",
          prompt:
            "You are a medical field-force reporting assistant. From the rep's dictated or typed visit, extract strict JSON {hcp_name, specialty, products_discussed[], samples_left[{product, qty}], sentiment, agreed_next_step, follow_up_date, mentions_side_effect: boolean}. Set mentions_side_effect=true if the rep reports ANY suspected side effect, product complaint or patient harm — however casually mentioned — so it can be routed to proper adverse-event intake; never log a safety mention as a routine note. Record only what the rep actually said; do not infer clinical outcomes or make promotional or off-label claims.",
          mcp: ["CRM (Veeva/Salesforce/etc.) — via a Tool (MCP) node (no first-party connector)", "WhatsApp + Telephony — via the WhatsApp/Voice channels"],
          tools: ["knowledge_search (approved product + sample catalogue)"],
          guardrails: [
            "Any suspected side-effect mention forces mentions_side_effect=true and branches to the adverse-event flow — a safety signal can never be filed as a routine visit note",
            "Dispensed-sample records go to the manager Approval node — sample accountability is a compliance-controlled human sign-off, not an automated write",
            "The agent transcribes only what the rep said — no invented clinical outcomes and no off-label or promotional claims",
          ],
          output:
            "The agent emits a structured call report → the Condition node diverts any safety mention to adverse-event intake → the manager signs off the sample-accountability portion at the Approval node → a Tool node updates the HCP record and follow-up task in the CRM via MCP.",
        },
      },
    ],
  },

  // ==================================================== FITNESS & WELLNESS
  {
    id: "fitness",
    name: "Fitness & wellness",
    icon: Dumbbell,
    tagline: "Class & PT booking, no-show reminders, membership renewals and ad-lead nurture — for gyms, studios and spas.",
    recipes: [
      {
        id: "fitness-booking",
        title: "Class / PT booking with automatic waitlist and reminders",
        scenario:
          "Members book classes and personal-training slots by DM and call; front desk juggles a paper waitlist, forgets to backfill cancellations, and no-shows leave paid slots empty.",
        triggerLabel: "WhatsApp / Call (booking request)",
        steps: [
          { kind: "trigger", title: "Member requests a slot", detail: "A WhatsApp or Call trigger captures the class or PT booking request." },
          { kind: "agent", title: "Confirm class, coach & timing", detail: "An Agent node identifies the member, the desired class/coach and time, and confirms the details." },
          { kind: "condition", title: "Full? go to the waitlist", detail: "A Condition node checks capacity: if the class is full it adds the member to the waitlist and auto-offers the spot when a cancellation frees it; otherwise it proceeds to book." },
          { kind: "tool", title: "Write to the booking system", detail: "A Tool (MCP) node records the booking (or waitlist position) in your class-booking / scheduling system — no first-party connector, MCP calls yours." },
          { kind: "output", title: "Reminder to cut no-shows", detail: "An Output node sends a confirmation, then a day-of reminder on WhatsApp/SMS with one-tap confirm or cancel — a cancel instantly releases the slot to the next waitlisted member." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "SMS", "Tool (MCP → booking system)"],
        outcome: "Classes book and backfill themselves, the waitlist works without the front desk, and reminders turn silent no-shows into freed-up slots.",
        impact: "Freed cancellations and timely reminders are what keep paid slots full — the manual waitlist skips exactly the backfills that a busy front desk never gets to.",
        build: {
          agents: 1,
          agentsNote: "1 booking agent; the capacity/waitlist branch, the booking write and the reminders are workflow nodes.",
          prompt:
            "You are a class and personal-training booking assistant for {{studio_name}}. Identify the member, the class or coach they want and the time, and confirm the details back before booking. Only offer classes, coaches and times the booking system returns as real — never invent a slot or a coach's availability. If the class is full, offer the waitlist and explain they'll be auto-offered the next cancellation. Do not give fitness, injury, diet or medical advice; if asked, suggest they speak to their coach or a professional.",
          mcp: ["Class-booking / scheduling system — via a Tool (MCP) node (no first-party connector)", "WhatsApp + SMS — via the messaging channels"],
          tools: ["http_request (check live class capacity + coach availability)"],
          guardrails: [
            "Only books slots the scheduling system confirms as available — no invented classes, coaches or times",
            "A cancel or no-show instantly releases the slot to the next waitlisted member; the booking write is idempotent so a repeated confirm never double-books",
            "No fitness, injury, diet or medical advice — those are referred to a coach or a professional",
          ],
          output:
            "The agent confirms the request → the Condition node books directly or adds to the waitlist by live capacity → a Tool node records the booking/waitlist position in the scheduling system via MCP → an Output node sends the confirmation and a day-of reminder with one-tap confirm/cancel on WhatsApp/SMS.",
        },
      },
      {
        id: "fitness-renewal",
        title: "Membership renewal, win-back and ad-lead nurture",
        scenario:
          "Lapsing members drift away with no nudge, win-back is never done, and leads from Instagram/Google ads sit unanswered for hours — so the same acquisition spend keeps refilling a leaky bucket.",
        triggerLabel: "Webhook (renewal-due run, and ad-lead form)",
        steps: [
          { kind: "trigger", title: "Renewal run + new leads", detail: "A scheduled Webhook trigger evaluates memberships approaching expiry each morning, and a Webhook trigger also fires the instant an Instagram/Google lead form is submitted — ad platforms have no first-party connector, so leads arrive by Webhook." },
          { kind: "loop", title: "Per member / lead", detail: "A Loop node iterates the due-and-lapsed list so each person gets a message tailored to how long they've been away." },
          { kind: "agent", title: "Personalise the nudge", detail: "An Agent node writes a renewal reminder, a win-back offer for lapsed members, or a warm opener for a fresh lead — referencing their plan or the class they enquired about." },
          { kind: "approval", title: "Manager approves discounts", detail: "An Approval node holds any win-back discount or fee waiver until a manager signs off — a pricing concession is a financial decision that stays with a person." },
          { kind: "output", title: "Reach out + collect payment", detail: "An Output node sends the message on WhatsApp with a Razorpay UPI link, so approved renewals and joins can be paid in one tap." },
        ],
        connectors: ["Webhook", "Loop", "WhatsApp", "Approval / HITL", "Razorpay UPI"],
        outcome: "Every lapsing member gets a timely, personal nudge, win-back offers stay under manager approval, and ad leads get a warm reply within seconds instead of hours.",
        impact: "Consistent, personal follow-up and instant lead response are the mechanism that plugs the leaky bucket — the manual process skips the members and leads that a busy team never gets around to, while pricing concessions stay human-approved.",
        build: {
          agents: 1,
          agentsNote: "1 outreach agent per member/lead; the per-person Loop, the discount Approval and the pay-link send are workflow nodes.",
          prompt:
            "You are a membership retention and lead-nurture assistant for {{studio_name}}. Depending on the record type, write a renewal reminder, a win-back note for a lapsed member, or a warm opener for a new ad lead — referencing their actual plan, last visit, or the class they enquired about. Only mention a discount, waiver or offer AFTER it has been approved at the approval step; never promise or invent pricing on your own. Keep it personal and respectful, honour opt-outs, and don't over-message someone who hasn't replied.",
          mcp: ["Membership / CRM system — via a Tool (MCP) node (no first-party connector)", "Razorpay UPI — pay-link for approved renewals/joins", "Instagram/Google ad leads — inbound via Webhook"],
          tools: ["knowledge_search (current plans + membership records)"],
          guardrails: [
            "No discount, fee waiver or pricing concession goes out until a manager signs off at the Approval node — the agent never invents an offer",
            "Frequency cap + opt-out honoured per member/lead so nobody is over-messaged",
            "Payment is collected only through the Razorpay UPI link on approved offers — the agent never handles card or account details itself",
          ],
          output:
            "A Loop node runs the agent per due/lapsed member and per new lead → the agent drafts a tailored message → any discount is held for the manager Approval node → an Output node sends it on WhatsApp with a Razorpay UPI link so approved renewals and joins pay in one tap.",
        },
      },
    ],
  },
];
