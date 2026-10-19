import type { Industry } from "./recipes-data";
import { PawPrint, Scissors } from "lucide-react";

// ---------------------------------------------------------------------------
// Veterinary / pet-care & Beauty-salon / spa workflow recipes — same "inline
// copy, no i18n keys" precedent and honesty rules as recipes-data.tsx. Every
// recipe is expressed ONLY in VegaDūta's real building blocks (Trigger / Agent
// / Knowledge / Condition / Approval / Output / Tool(MCP) / Voice / Device /
// Loop / Code / Parallel), so each maps 1:1 to something you can actually build
// in the Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — a practice-management / PIMS system, a
// salon booking / POS system, a CRM — are reached the real way, via an inbound
// Webhook trigger and/or a Tool (MCP) node, and are LABELLED as such. Real
// channels only (Webhook, WhatsApp, SMS, Voice/Call, Email, ElevenLabs, Sarvam,
// Razorpay UPI). NON-NEGOTIABLY: the veterinary agent NEVER diagnoses or gives
// clinical/medical advice — it does grounded, structured triage and routes
// every urgency/clinical call to a licensed vet through an Approval (human-in-
// the-loop) step; the vet decides. Impact lines describe the mechanism (why it
// helps), never invented ROI numbers.
// ---------------------------------------------------------------------------

export const INDUSTRIES_VETSALON: Industry[] = [
  // ================================================ VETERINARY & PET CARE
  {
    id: "veterinary",
    name: "Veterinary & pet care",
    icon: PawPrint,
    tagline:
      "Vaccination and appointment reminders, non-diagnostic symptom triage, reorders and post-visit follow-up — with a licensed vet owning every clinical call.",
    recipes: [
      {
        id: "vet-triage",
        title: "Non-diagnostic symptom intake with vet-owned urgency triage",
        scenario:
          "An owner messages that their pet is unwell — vomiting, limping, off its food. Today the front desk fields it live, has no consistent way to tell an emergency from a routine visit, and either over-books the vet or, worse, tells someone to 'wait and see' when they shouldn't. Nobody wants a receptionist making that call.",
        triggerLabel: "WhatsApp / Call (owner reports a symptom)",
        steps: [
          { kind: "trigger", title: "Owner reports a symptom", detail: "A WhatsApp or Call trigger captures the concern in the owner's own words and language — spoken reports are transcribed live." },
          { kind: "agent", title: "Structure the intake (never diagnose)", detail: "An Agent node collects the facts a vet needs — species, breed, age, the symptom, how long, appetite, any known conditions — into a clean structured summary. It explicitly does NOT name a condition, suggest a cause, or recommend treatment." },
          { kind: "knowledge", title: "Ground against the clinic's triage protocol", detail: "A Knowledge node checks the report against the clinic's own red-flag / urgency reference (e.g. bloat, seizures, trouble breathing, toxin ingestion) so the urgency banding is grounded and cited — not the model's opinion." },
          { kind: "condition", title: "Band the urgency", detail: "A Condition node maps the grounded flags to a band — EMERGENCY, SAME-DAY, or ROUTINE — and never lets a red-flagged case fall into a lower band." },
          { kind: "approval", title: "A licensed vet decides", detail: "An Approval node puts the structured summary and proposed band in front of a vet/vet nurse — the clinical decision (see now, book, or advise) is always a human's, not the agent's." },
          { kind: "output", title: "Tell the owner the next step", detail: "An Output node relays the vet's decision to the owner on WhatsApp — come in now, a booking link, or a vet call-back — in the owner's language, with a clear 'if it worsens, call us / an emergency line' line." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Knowledge base", "Approval / HITL", "Sarvam (translate and speech tools)"],
        outcome: "Every 'my pet is unwell' message becomes a consistent, structured triage summary with a vet making the actual call within minutes — instead of a receptionist guessing or a worried owner waiting.",
        impact: "The value is a consistent, grounded intake plus a fast human decision — the agent does the note-taking and red-flag surfacing that a busy front desk skips, while the clinical judgement stays with a licensed vet where it legally and ethically belongs.",
        build: {
          agents: 1,
          agentsNote: "1 intake/triage agent; the urgency banding, the vet approval and the reply are workflow nodes. Kept deliberately single-agent — the hard call is a human's, not another model's.",
          prompt:
            "You are a veterinary intake assistant for {{clinic}}. You are NOT a vet and you must NEVER diagnose, name a possible condition, suggest a cause, or recommend any treatment, medication, dose or home remedy. Your only job is to collect a clear, structured history — species, breed, age, weight if known, the presenting symptom, duration, appetite/water/energy, any known conditions or current medication — in the owner's language, one question at a time. Then compare the report ONLY against the clinic's triage red-flag reference in Knowledge and propose an urgency band (EMERGENCY / SAME-DAY / ROUTINE) with the specific flag you matched, cited. State plainly that a vet will confirm the next step. If the owner asks 'what's wrong with my pet' or for advice, say you can't give clinical advice and that a vet will decide. If any red flag is present or you are unsure, propose EMERGENCY and escalate.",
          mcp: ["Practice-management / PIMS (appointment slots, patient history) — via a Tool (MCP) node (no first-party connector)", "WhatsApp / Voice — owner channel", "Sarvam — 22+ Indian languages"],
          tools: ["knowledge_search (the clinic's own triage red-flag / urgency protocol)"],
          guardrails: [
            "No diagnosis, no clinical or medication advice — enforced in the prompt AND by the design: the agent only structures the history and cites a red-flag band, it never states what is wrong or what to do",
            "Every urgency call is Approval-gated to a licensed vet/vet nurse — the agent proposes a band, a human decides the actual next step and it is never auto-sent as clinical instruction",
            "Fail-safe banding: any matched red flag or any uncertainty forces EMERGENCY, and the Condition node cannot down-band a red-flagged case",
          ],
          cost:
            "Keep the premium reasoning tier OFF this flow entirely: the intake is structured extraction plus a grounded lookup, which the economy/standard tier handles well — and the one judgement that would justify a premium model (the clinical call) is a human's, not the agent's, by design. Cap tool-calls per conversation so a back-and-forth intake can't run up cost.",
          output:
            "The agent structures the history → a Knowledge lookup grounds the red-flag match → the Condition node bands the urgency (red flags force EMERGENCY) → the vet Approval node confirms the actual next step → an Output node relays the vet's decision to the owner on WhatsApp in their language, with a clear worsening/emergency-line fallback; the intake and the vet's decision are logged.",
        },
      },
      {
        id: "vet-care-schedule",
        title: "Vaccination & appointment reminders, reorders and post-visit follow-up",
        scenario:
          "Vaccination boosters lapse because nobody chases them, appointment no-shows leave the vet idle, food and chronic-medication run out before owners reorder, and the day-after 'how is she doing?' check-in never happens on a busy day. It's all manual, so it's all inconsistent.",
        triggerLabel: "Webhook (scheduled daily care run + visit-completed event)",
        steps: [
          { kind: "trigger", title: "Daily care run + visit-completed", detail: "A scheduled Webhook trigger each morning pulls pets with a vaccination/booster due, an appointment tomorrow, or a repeat food/medication likely due; a second Webhook fires when a visit is completed, for the post-visit check-in. The PIMS has no first-party connector, so these arrive by Webhook." },
          { kind: "loop", title: "Per pet / owner", detail: "A Loop node iterates the due list so each pet gets exactly the message its situation calls for — a booster reminder, an appointment reminder, a reorder nudge, or a post-visit check-in." },
          { kind: "agent", title: "Draft the right message", detail: "An Agent node writes the appropriate reminder referencing the pet by name and the specific item (which vaccine, which appointment time, which food/med) — grounded in the PIMS record, never inventing a due date or a product." },
          { kind: "condition", title: "Route the reply", detail: "A Condition node classifies the owner's reply — CONFIRM / RESCHEDULE / REORDER / DECLINE / UNCLEAR — and routes unclear or clinical questions to a human rather than guessing." },
          { kind: "tool", title: "Update the PIMS / raise the reorder", detail: "A Tool (MCP) node books or frees the slot in the practice-management system, or raises the food/medication reorder against the pet's record — repeat prescriptions still route to a vet's existing authorisation rules, never auto-dispensed by the agent." },
          { kind: "output", title: "Reach out + collect payment", detail: "An Output node sends the message on WhatsApp/SMS; for a reorder it attaches a Razorpay UPI link so an approved repeat can be paid in one tap." },
        ],
        connectors: ["Webhook", "Loop", "WhatsApp", "SMS", "Tool (MCP → PIMS)", "Razorpay UPI"],
        outcome: "Boosters get chased, tomorrow's list gets confirmed, food and repeat meds get reordered before they run out, and every visit gets a day-after check-in — consistently, without the front desk having to remember.",
        impact: "Consistent, timely follow-up is the whole mechanism: the manual process skips exactly the boosters, reminders and reorders a busy clinic never gets around to. Payment stays on a Razorpay UPI link and repeat prescriptions stay within the vet's authorisation rules — the agent chases, it doesn't dispense.",
        build: {
          agents: 1,
          agentsNote: "1 outreach agent per pet/owner; the per-record Loop, the reply routing, the PIMS/reorder write and the pay-link send are workflow nodes.",
          prompt:
            "You are a pet-care follow-up assistant for {{clinic}}. Depending on the record type, write ONE short, warm message in the owner's language: a vaccination/booster-due reminder, an appointment reminder for tomorrow, a food/medication reorder nudge, or a day-after post-visit check-in — always referencing the pet by name and the specific item from the record. Use ONLY the details in the PIMS record: never invent a due date, a vaccine, a product, or a price. For a reorder of prescription medication, say it is subject to the vet's approval — do not imply it is dispensed. Do NOT give any clinical, dietary or medical advice; if the owner reports a health concern or asks what's wrong, hand off to the clinic's triage flow / a human. Honour opt-outs and don't over-message someone who hasn't replied.",
          mcp: ["Practice-management / PIMS (due lists, appointments, patient history) — via a Tool (MCP) node (no first-party connector)", "Razorpay UPI — pay-link for approved reorders", "WhatsApp + SMS — owner channels"],
          tools: ["http_request (read the due list + pet record, write the booking/reorder)", "knowledge_search (vaccination schedule + repeat-order rules)"],
          guardrails: [
            "No clinical, dietary or medical advice — a reported health concern is handed to the vet triage flow / a human, never answered by this agent",
            "Repeat prescriptions are raised against the vet's existing authorisation rules and never auto-dispensed by the agent; the PIMS/reorder write is idempotent per pet + item so a retried run never double-books or double-orders",
            "Payment is collected only through the Razorpay UPI link on an approved reorder — the agent never handles card or account details itself — and opt-outs/frequency caps are honoured per owner",
          ],
          cost:
            "This is high-volume, low-complexity outreach, so the message-drafting agent should sit on the economy tier — that single choice dominates total cost because it runs once per due pet. Batch the Loop and cap tool-calls per record so a large morning run stays predictable and linear in cost.",
          output:
            "A Loop runs the agent per due pet / completed visit → the agent drafts the matching message from the PIMS record → the Condition node routes the reply (unclear/clinical → human) → a Tool node updates the PIMS booking or raises the reorder under the vet's rules → an Output node sends it on WhatsApp/SMS, with a Razorpay UPI link on approved reorders.",
        },
      },
    ],
  },

  // =========================================== BEAUTY, SALONS & SPAS
  {
    id: "salon",
    name: "Beauty, salons & spas",
    icon: Scissors,
    tagline:
      "Booking, waitlist and no-show reminders, package renewal and win-back, review solicitation and service Q&A — on the channels clients already use.",
    recipes: [
      {
        id: "salon-booking",
        title: "Booking with waitlist, no-show reminders and service Q&A",
        scenario:
          "Clients book by DM and call while the front desk is mid-blow-dry; the waitlist lives on a sticky note, cancellations aren't backfilled, no-shows leave a stylist's chair empty, and half the messages are just 'how long does balayage take?' or 'do you do gel removal?'.",
        triggerLabel: "WhatsApp / Call (booking request or service question)",
        steps: [
          { kind: "trigger", title: "Client messages", detail: "A WhatsApp or Call trigger captures a booking request or a service question." },
          { kind: "agent", title: "Confirm service, stylist & timing — or answer the question", detail: "An Agent node identifies the client, the service, a preferred stylist and time and confirms the details; for a service question (duration, what's included, aftercare, prep) it answers grounded in the salon's menu and policy — never inventing a price or a service you don't offer." },
          { kind: "condition", title: "Full? go to the waitlist", detail: "A Condition node checks live availability: if the stylist/slot is full it adds the client to the waitlist and auto-offers the spot when a cancellation frees it; otherwise it proceeds to book." },
          { kind: "tool", title: "Write to the booking / POS system", detail: "A Tool (MCP) node records the appointment (or waitlist position) in your salon booking / POS system — no first-party connector, MCP calls yours." },
          { kind: "output", title: "Confirm + no-show reminder", detail: "An Output node sends the confirmation, then a day-before reminder on WhatsApp/SMS with one-tap confirm or cancel — a cancel instantly releases the chair to the next waitlisted client." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "SMS", "Knowledge base", "Tool (MCP → booking/POS)"],
        outcome: "Appointments book and backfill themselves, the waitlist works without the front desk, service questions answer themselves from the real menu, and reminders turn silent no-shows into freed-up chairs.",
        impact: "Freed cancellations and timely reminders are what keep chairs full — the manual waitlist skips exactly the backfills a busy front desk never gets to — while grounded service answers stop the endless 'how long / how much' back-and-forth from interrupting the floor.",
        build: {
          agents: 1,
          agentsNote: "1 booking + Q&A agent; the capacity/waitlist branch, the booking write and the reminders are workflow nodes.",
          prompt:
            "You are a booking and front-desk assistant for {{salon}}. Identify the client, the service they want, any preferred stylist and time, and confirm the details before booking. Only offer services, stylists, durations and prices that the booking system and salon menu return as real — never invent a slot, a stylist's availability, a price, or a service you don't offer. If the slot is full, offer the waitlist and explain they'll be auto-offered the next cancellation. For service questions (how long, what's included, prep, aftercare), answer only from the salon menu/policy in Knowledge and cite it; if it isn't there, say so and offer to check with the team. Do not give dermatological, medical or allergy advice — for skin/allergy concerns, advise a patch test or a professional.",
          mcp: ["Salon booking / POS system — via a Tool (MCP) node (no first-party connector)", "WhatsApp + SMS — via the messaging channels"],
          tools: ["http_request (check live stylist/slot availability + write the booking)", "knowledge_search (service menu, durations, prices, policies)"],
          guardrails: [
            "Only books slots the booking system confirms as available, and only quotes services/prices from the salon menu — no invented slots, stylists, prices or services",
            "A cancel or no-show instantly releases the slot to the next waitlisted client; the booking write is idempotent so a repeated confirm never double-books a chair",
            "No dermatological, medical or allergy advice — skin/allergy concerns are referred to a patch test or a professional",
          ],
          cost:
            "Booking and menu Q&A is structured, grounded work, so keep it on the economy/standard tier — reserve any premium tier for genuinely open-ended reasoning this flow doesn't need. Cap tool-calls per conversation so a chatty booking exchange stays cost-predictable.",
          output:
            "The agent confirms the request or answers the service question from the menu → the Condition node books directly or adds to the waitlist by live availability → a Tool node records the appointment/waitlist position in the booking/POS system via MCP → an Output node sends the confirmation and a day-before reminder with one-tap confirm/cancel on WhatsApp/SMS.",
        },
      },
      {
        id: "salon-winback",
        title: "Package renewal, win-back and post-visit review requests",
        scenario:
          "Prepaid packages and memberships lapse with no nudge, clients who haven't been back in months are never re-invited, and the five-star review that a delighted client would happily leave never gets asked for — so reputation and repeat revenue both leak quietly.",
        triggerLabel: "Webhook (renewal/lapse run + visit-completed event)",
        steps: [
          { kind: "trigger", title: "Renewal run + completed visits", detail: "A scheduled Webhook trigger each day evaluates packages/memberships nearing their end and clients who've lapsed; a second Webhook fires when a visit is completed, to ask for a review. The booking/CRM has no first-party connector, so these arrive by Webhook." },
          { kind: "loop", title: "Per client", detail: "A Loop node iterates the list so each client gets the right message — a package/membership renewal reminder, a win-back note tailored to how long they've been away, or a post-visit review request." },
          { kind: "agent", title: "Personalise the message", detail: "An Agent node writes a renewal reminder, a warm win-back referencing the service they usually book, or a friendly review request referencing today's visit and stylist — never inventing an offer or a service history." },
          { kind: "approval", title: "Manager approves discounts", detail: "An Approval node holds any win-back discount, package upgrade or fee waiver until a manager signs off — a pricing concession is a financial decision that stays with a person." },
          { kind: "output", title: "Reach out — review link or pay link", detail: "An Output node sends the message on WhatsApp: a review link for the post-visit request, or a Razorpay UPI link so an approved renewal/package can be paid in one tap." },
        ],
        connectors: ["Webhook", "Loop", "WhatsApp", "Approval / HITL", "Razorpay UPI"],
        outcome: "Lapsing packages get a timely, personal nudge, dormant clients get a warm re-invite, and happy clients actually get asked for the review they'd have left anyway — with every discount held for manager approval.",
        impact: "Consistent, personal follow-up and a well-timed review ask are the mechanism that plugs the leak — the manual process skips exactly the renewals, win-backs and review requests a busy salon never gets around to, while pricing concessions stay human-approved and payment stays on a Razorpay UPI link.",
        build: {
          agents: 1,
          agentsNote: "1 outreach agent per client; the per-client Loop, the discount Approval and the pay-link/review-link send are workflow nodes.",
          prompt:
            "You are a retention and reputation assistant for {{salon}}. Depending on the record type, write ONE short, personal message in the client's language: a package/membership renewal reminder, a win-back note for a lapsed client (referencing the service they usually book and how long it's been), or a post-visit review request (referencing today's service and stylist). Use only the client's actual history from the record — never invent a visit, a service, or a loyalty balance. Only mention a discount, package upgrade or waiver AFTER it has been approved at the approval step; never promise or invent pricing on your own. For a review request, invite honest feedback and route anything negative to the manager rather than pushing for a public rating. Honour opt-outs and don't over-message someone who hasn't replied.",
          mcp: ["Booking / CRM / membership system — via a Tool (MCP) node (no first-party connector)", "Razorpay UPI — pay-link for approved renewals/packages", "Completed-visit + renewal-due events — inbound via Webhook"],
          tools: ["knowledge_search (current packages, prices, membership records)"],
          guardrails: [
            "No discount, package upgrade or fee waiver goes out until a manager signs off at the Approval node — the agent never invents an offer or a price",
            "A review request invites honest feedback and routes negatives to the manager privately — it never incentivises or gates a public rating; frequency caps and opt-outs are honoured per client",
            "Payment is collected only through the Razorpay UPI link on an approved renewal/package — the agent never handles card or account details itself",
          ],
          cost:
            "This is per-client outreach that runs at list scale, so the drafting agent belongs on the economy tier — that one choice dominates total cost since it runs once per client. Batch the Loop and cap tool-calls per record so a large renewal run stays linear and predictable.",
          output:
            "A Loop runs the agent per due/lapsed client and per completed visit → the agent drafts the matching message from the client's real history → any discount is held for the manager Approval node → an Output node sends it on WhatsApp with a review link (post-visit) or a Razorpay UPI link (approved renewal/package).",
        },
      },
    ],
  },
];
