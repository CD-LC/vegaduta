import type { Industry } from "./recipes-data";
import { Building2, CalendarDays } from "lucide-react";

// ---------------------------------------------------------------------------
// Facilities & Events workflow recipes — same "inline copy, no i18n keys"
// precedent and honesty rules as recipes-data.tsx. Every recipe is expressed
// ONLY in VegaDūta's real building blocks (Trigger / Agent / Knowledge /
// Condition / Approval / Output / Tool(MCP) / Voice / Device / Loop / Code /
// Parallel), so each maps 1:1 to something you can actually build in the
// Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — CAFM/CMMS, access-control, visitor-
// management, ticketing, event-registration, CRM — are reached the real way,
// via an inbound Webhook trigger and/or a Tool (MCP) node, and are LABELLED as
// such. Building IoT (doors, HVAC, lighting) is reached via the Device Control
// channel. Every consequential decision — vendor/spend on a work order, a
// refund — passes an Approval (human-in-the-loop) step. Impact lines describe
// the mechanism (why it helps), never invented ROI numbers.
// ---------------------------------------------------------------------------

export const INDUSTRIES_FACEVENT: Industry[] = [
  // =========================================== FACILITIES & CO-WORKING
  {
    id: "facilities",
    name: "Facilities management & co-working",
    icon: Building2,
    tagline:
      "Turn maintenance requests, visitor sign-ins and room bookings into instant, tracked action — without a facilities desk fielding every message by hand.",
    recipes: [
      {
        id: "facilities-maintenance",
        title: "Maintenance request → triage, dispatch and vendor approval",
        scenario:
          "A tenant or member reports a fault — AC not cooling, a leak, a jammed door — on WhatsApp or a portal. Today someone reads it, guesses the category, decides whether it needs an in-house handyman or a paid vendor, and manually raises a work order. Out of hours it just waits.",
        triggerLabel: "WhatsApp / Webhook (member portal maintenance request)",
        steps: [
          { kind: "trigger", title: "Request comes in", detail: "A WhatsApp trigger (or a Webhook from your member/tenant portal) starts the flow the moment a fault is reported." },
          { kind: "agent", title: "Triage + categorize", detail: "An Agent node classifies the issue (electrical / plumbing / HVAC / access / cleaning), sets a priority, and drafts a clear work-order summary from the member's own words." },
          { kind: "knowledge", title: "Ground it in the SLA + asset book", detail: "A Knowledge node holds your priority matrix, SLA response times and the site's asset/equipment list, so priority and routing follow policy — not a guess." },
          { kind: "device", title: "Auto-resolve if it's a controllable asset", detail: "For faults on building IoT (a meeting-room AC left off, lighting/HVAC schedule), a Device Control node can apply the fix directly — e.g. set the HVAC setpoint — before ever raising a work order." },
          { kind: "condition", title: "In-house or paid vendor?", detail: "A Condition node splits the request: routine in-house tasks go straight to dispatch; anything needing a paid external vendor branches to approval." },
          { kind: "approval", title: "Manager approves vendor spend", detail: "Any work order that carries vendor cost pauses at an Approval node — a facilities manager signs off the spend before a paid job is raised. This is the money gate." },
          { kind: "tool", title: "Raise the work order + notify", detail: "A Tool (MCP) node creates the ticket in your CAFM/CMMS and assigns the technician; an Output node confirms back to the member with a ticket number and expected window." },
        ],
        connectors: ["WhatsApp", "Webhook", "Knowledge base", "Device Control", "Approval / HITL", "Tool (MCP → CAFM/CMMS)"],
        outcome: "Every maintenance request is categorized, prioritized and either self-resolved or turned into a tracked work order in seconds, day or night — with paid vendor work held for a human sign-off.",
        impact: "Removes the read-classify-raise-by-hand loop and the out-of-hours wait, while the decision to spend on an outside vendor stays with a manager.",
        build: {
          agents: 1,
          agentsNote: "1 triage agent; the Device Control auto-fix, the in-house/vendor split, the spend approval and the CAFM write are workflow nodes.",
          prompt:
            "You are a facilities triage agent for {{site}}. From a member's maintenance message, output strict JSON {category (electrical|plumbing|hvac|access|cleaning|other), priority (P1|P2|P3 per the SLA matrix in Knowledge), work_order_summary (max 3 lines, factual), controllable_asset: boolean, needs_paid_vendor: boolean, location}. Set priority ONLY from the Knowledge SLA matrix, never invent one. Mark needs_paid_vendor=true whenever the fix likely requires an external contractor. You never approve spend and never dispatch a paid vendor yourself — you only triage.",
          mcp: ["CAFM / CMMS (work-order system) — via a Tool (MCP) node (no first-party connector)", "Member/tenant portal — inbound via Webhook trigger", "WhatsApp — request + confirmation channel"],
          tools: ["knowledge_search (SLA/priority matrix + site asset list)", "http_request (create/assign work order)"],
          guardrails: [
            "No paid vendor job is raised without the Approval node — the agent flags needs_paid_vendor, a manager approves the spend",
            "Priority is set only from the Knowledge SLA matrix, never guessed — so P1 emergencies aren't under-ranked",
            "Device Control is scope-locked to controllable building assets on this site; it can adjust a setpoint but never unlock access or override a safety system",
          ],
          cost:
            "The per-request triage agent runs on an economy tier — it's a short classify-and-summarize pass, and it's the only agent that fires. Device auto-resolve and in-house routing close cheap requests with zero vendor cost; a premium model is never needed here, so cost stays flat per request regardless of volume.",
          output:
            "Agent emits the triage JSON → a Device Control node auto-fixes controllable-asset faults where it can → the Condition node routes in-house vs paid vendor → vendor spend waits at the manager Approval node → on approval a Tool node creates the CAFM/CMMS work order and an Output node confirms the ticket number + window to the member on WhatsApp.",
        },
      },
      {
        id: "facilities-visitor-booking",
        title: "Visitor pre-registration & meeting-room booking self-serve",
        scenario:
          "Members message the front desk to pre-register a guest for tomorrow or to grab a meeting room. Today a receptionist keys the visitor into the access system, checks the room calendar by hand, and replies — and it only happens when the desk is staffed.",
        triggerLabel: "WhatsApp / Webhook (member request)",
        steps: [
          { kind: "trigger", title: "Member asks", detail: "A WhatsApp trigger (or a portal Webhook) starts on a request to pre-register a visitor or book a room." },
          { kind: "agent", title: "Understand + extract details", detail: "An Agent node works out whether it's a visitor pre-reg or a room booking and extracts the specifics (guest name, date/time, room, duration, purpose)." },
          { kind: "knowledge", title: "Check the house rules", detail: "A Knowledge node holds visitor policy and room-booking rules (member tier limits, opening hours, capacity) so requests are validated against real policy." },
          { kind: "condition", title: "Available and allowed?", detail: "A Condition node checks the requested slot against the live room calendar and the member's entitlement, and branches to confirm or offer alternatives." },
          { kind: "tool", title: "Register the visitor / hold the room", detail: "A Tool (MCP) node creates the visitor pass in the access-control/visitor system and books the room in the calendar system." },
          { kind: "output", title: "Confirm with the passes + details", detail: "An Output node replies on WhatsApp with the room confirmation and a guest QR/access pass, or proposes the nearest free alternatives." },
        ],
        connectors: ["WhatsApp", "Webhook", "Knowledge base", "Tool (MCP → visitor/access + room calendar)"],
        outcome: "Members pre-register guests and book rooms in one message, 24/7, with the visitor pass and calendar hold created automatically — no receptionist in the loop for the routine case.",
        impact: "Takes the highest-volume front-desk asks off a staffed desk and makes them instant and self-serve, while still validating every booking against real house rules.",
        build: {
          agents: 1,
          agentsNote: "1 request agent; the availability/entitlement check, the visitor+room writes and the confirmation are workflow nodes.",
          prompt:
            "You are a co-working front-desk agent for {{site}}. From a member message, output strict JSON {intent (visitor_prereg|room_booking|unclear), guest_name?, visit_date?, room?, start_time?, duration_min?, purpose?, member_id}. Validate the request against the visitor and room-booking rules in Knowledge — member-tier limits, opening hours, room capacity. If required details are missing, set intent-appropriate fields to null and ask ONE concise follow-up rather than assuming. You never override capacity, opening hours or an entitlement limit, and you never grant building-wide or after-hours access.",
          mcp: ["Visitor management / access control — via a Tool (MCP) node (no first-party connector)", "Room calendar — via a Tool (MCP) node", "Member portal — inbound via Webhook trigger", "WhatsApp — request + confirmation channel"],
          tools: ["knowledge_search (visitor policy + room-booking rules)", "http_request (visitor pass create + room availability/booking)"],
          guardrails: [
            "Bookings are validated against Knowledge house rules — member-tier limits, opening hours and room capacity are never exceeded",
            "Visitor passes are single-guest, single-day and time-boxed; the agent never grants standing or building-wide access",
            "A double-booking check on the room calendar before the hold is written, so two members can't be confirmed into the same slot",
          ],
          cost:
            "A single economy-tier agent handles the whole extract-and-validate pass — visitor pre-reg and room booking are structured, low-reasoning tasks that don't need a premium model. Because availability and entitlement checks are deterministic workflow nodes, not agent calls, cost per request stays minimal and predictable.",
          output:
            "The agent extracts the structured request → the Condition node checks live room availability + member entitlement → a Tool node creates the visitor pass in the access system and/or holds the room in the calendar → an Output node sends the confirmation with a guest QR/access pass, or offers the nearest free alternatives on WhatsApp.",
        },
      },
    ],
  },

  // ================================================= EVENT MANAGEMENT & VENUES
  {
    id: "events",
    name: "Event management & venues",
    icon: CalendarDays,
    tagline:
      "Register attendees, run the reminders, handle ticketing and refunds, and answer 'where's my session?' on the day — without a coordinator glued to the inbox.",
    recipes: [
      {
        id: "events-registration",
        title: "Attendee registration, reminders and on-day schedule Q&A",
        scenario:
          "People sign up through a form, then bombard the coordinator with 'what time does it start?', 'where's the parking?', 'is session X still on?'. Today someone manually confirms each registration, remembers to send reminders, and answers the same questions all week and all day.",
        triggerLabel: "Webhook (registration form) + scheduled reminder + WhatsApp Q&A",
        steps: [
          { kind: "trigger", title: "Someone registers", detail: "A Webhook trigger fires the moment the registration form is submitted — no first-party registration connector, the form pushes in via Webhook." },
          { kind: "agent", title: "Confirm + personalize", detail: "An Agent node validates the registration, drafts a warm confirmation with the attendee's ticket type and session picks, and answers in their language." },
          { kind: "output", title: "Send confirmation, then reminders", detail: "An Output node sends the confirmation on WhatsApp/Email immediately, and scheduled reminder sends go out at T-7 days, T-1 day and morning-of." },
          { kind: "knowledge", title: "Ground the agenda + logistics", detail: "A Knowledge node holds the live agenda, venue map, parking, timings and FAQ, so on-day answers are grounded — never a made-up session or time." },
          { kind: "condition", title: "Route the on-day question", detail: "A Condition node splits routine schedule/logistics questions (answer from Knowledge) from anything needing a human (special access, a complaint)." },
          { kind: "output", title: "Answer instantly on the day", detail: "An Output node replies to attendee questions on WhatsApp from the grounded agenda, and hands the rest to the on-site coordinator with context." },
        ],
        connectors: ["Webhook", "WhatsApp", "Email", "Knowledge base"],
        outcome: "Every registration is confirmed instantly, reminders run themselves, and the flood of on-day schedule questions is answered from the real agenda — with a human only for the genuine exceptions.",
        impact: "Removes the manual confirm-and-remind grind and the repetitive on-day Q&A that swamps a coordinator, so the team runs the event instead of the inbox.",
        build: {
          agents: 1,
          agentsNote: "1 registration/helpdesk agent; the scheduled reminders and the on-day routing are workflow nodes.",
          prompt:
            "You are an event assistant for {{event}}. Two jobs. (1) On a new registration {name, email, ticket_type, sessions[], language}, write a short, warm confirmation naming their ticket type and chosen sessions. (2) On an attendee question, answer ONLY from the agenda/logistics in Knowledge — session times, venue, parking, timings. Never invent a session, time, speaker or room; if it isn't in Knowledge, say so and offer the coordinator. Keep replies brief and in the attendee's language.",
          mcp: ["Registration form — inbound via Webhook trigger", "WhatsApp / Email — confirmation, reminder + Q&A channel"],
          tools: ["knowledge_search (live agenda, venue map, parking, timings, FAQ)"],
          guardrails: [
            "On-day answers are grounded in the Knowledge agenda only — the agent never invents a session, time, speaker or room",
            "Reminders are frequency-capped to the T-7 / T-1 / morning-of schedule — an attendee is never over-messaged",
            "Non-routine asks (special access, complaints, VIP handling) route to the on-site coordinator, not a guessed answer",
          ],
          cost:
            "One economy-tier agent covers confirmation, reminders and Q&A — all short, grounded, low-reasoning turns, so no premium model is warranted. Because reminders are scheduled workflow sends and answers are Knowledge lookups, cost scales gently with attendee count rather than spiking on the day.",
          output:
            "On registration the agent's confirmation goes out on WhatsApp/Email, and scheduled Output nodes fire the T-7 / T-1 / morning-of reminders; on the day, attendee questions are classified by the Condition node, answered from the grounded agenda by an Output node, or handed to the coordinator with context.",
        },
      },
      {
        id: "events-ticketing",
        title: "Ticketing changes & refunds with mandatory approval",
        scenario:
          "Attendees message asking to cancel, transfer a ticket, or get a refund. Refunds are money and policy-sensitive, but today they sit in an inbox until someone manually checks the ticket, the refund window and the payment, then processes it by hand.",
        triggerLabel: "WhatsApp (attendee request) or Webhook (support tool)",
        steps: [
          { kind: "trigger", title: "Change/refund requested", detail: "A WhatsApp trigger (or a Webhook from your support tool) starts on a cancel, transfer or refund request." },
          { kind: "agent", title: "Assemble the case", detail: "An Agent node pulls the ticket, the order and the refund-policy position into a one-screen summary — ticket type, amount paid, whether it's inside the refund window, and any prior changes." },
          { kind: "knowledge", title: "Apply the refund policy", detail: "A Knowledge node holds the event's refund/transfer policy (windows, fees, non-refundable tiers) so the case reflects real rules, not a guess." },
          { kind: "condition", title: "Free change vs money decision", detail: "A Condition node routes: a name-transfer or in-window no-cost change proceeds; anything that moves money (a refund) branches to approval." },
          { kind: "approval", title: "Organizer approves the refund", detail: "Any refund pauses at an Approval node — an organizer explicitly approves before money is returned. This is the non-negotiable money gate; the agent only assembles the case." },
          { kind: "tool", title: "Process the refund + update the ticket", detail: "On approval, a Tool (MCP → Razorpay) node issues the UPI refund and a Tool (MCP → ticketing) node cancels/transfers the ticket; an Output node confirms to the attendee." },
        ],
        connectors: ["WhatsApp", "Webhook", "Knowledge base", "Approval / HITL", "Razorpay UPI", "Tool (MCP → ticketing)"],
        outcome: "Ticket changes and refunds are decided in minutes with a clear approver and a full trail — free changes go straight through, and no refund is ever issued without an organizer's sign-off.",
        impact: "Combines speed with control: the mechanical case-building and policy check are instant, while the decision to return money stays with a person.",
        build: {
          agents: 1,
          agentsNote: "1 case-assembler agent; the refund approval and the Razorpay + ticketing writes are workflow nodes.",
          prompt:
            "You are a ticketing case assembler for {{event}}. Given a request {ticket_id, attendee, request_type (cancel|transfer|refund), reason, channel}, pull the ticket + original payment and produce a one-screen summary {ticket_id, ticket_type, paid_amount, purchase_date, within_refund_window (per the policy in Knowledge), refundable_amount, transfer_target?, prior_changes, risk_flags[]}. Apply the refund/transfer policy from Knowledge; never state or imply a refund is approved — you only assemble the case for a human approver. Flag anything unusual (out-of-window, repeat refunder, amount mismatch) in risk_flags. Never initiate a refund yourself.",
          mcp: ["Razorpay UPI refund — via a Tool (MCP) node, only after approval", "Ticketing platform — via a Tool (MCP) node (no first-party connector)", "Support tool — inbound via Webhook trigger", "WhatsApp — request + confirmation channel"],
          tools: ["knowledge_search (refund/transfer policy — windows, fees, non-refundable tiers)", "http_request (ticket + payment history lookup)"],
          guardrails: [
            "No refund is ever issued without the organizer Approval node — the agent only assembles the case, a human decides",
            "The agent may not initiate the Razorpay refund; the Tool node fires only on explicit approval",
            "Duplicate-request check on ticket_id + refund-window check from Knowledge, so the same ticket can't be refunded twice or refunded out of policy",
          ],
          cost:
            "A single economy-tier agent does the assemble-and-check pass; the expensive path (a refund) is a human decision, not a model call, so there's no premium-model spend here. Free changes resolve without an approval round, keeping the average cost per request low.",
          output:
            "The agent's policy-grounded case summary goes to the organizer Approval node for any refund → on approval a Tool node issues the Razorpay UPI refund and a Tool node cancels/transfers the ticket → an Output node confirms the outcome and timeline to the attendee on WhatsApp; free in-policy transfers skip the approval and process directly.",
        },
      },
    ],
  },
];
