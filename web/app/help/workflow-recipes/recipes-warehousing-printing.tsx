import type { Industry } from "./recipes-data";
import { Warehouse, Printer } from "lucide-react";

// ---------------------------------------------------------------------------
// Warehousing / 3PL-fulfilment & Printing/packaging workflow recipes — same
// "inline copy, no i18n keys" precedent and honesty rules as recipes-data.tsx.
// Every recipe is expressed ONLY in VegaDūta's real building blocks (Trigger /
// Agent / Knowledge / Condition / Approval / Output / Tool(MCP) / Voice /
// Device / Loop / Code / Parallel / Supervisor / Sub-workflow), so each maps
// 1:1 to something you can actually build in the Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — the WMS/OMS, the client (brand) 3PL
// portal, the print MIS / print-ERP — are reached the real way, via an inbound
// Webhook trigger and/or a Tool (MCP) node, and are LABELLED as such. Only real
// channels are used (Webhook, WhatsApp, SMS, Voice/Call, Email, ElevenLabs,
// Sarvam, Razorpay UPI). Every consequential decision — an inventory
// adjustment, a chargeable service, a price/quote commitment, a proof sign-off
// / release-to-print — goes through an Approval (human-in-the-loop) step.
// Impact lines describe the mechanism (why it helps), never invented ROI
// numbers, and no agent count is inflated.
// ---------------------------------------------------------------------------

export const INDUSTRIES_WHPRINT: Industry[] = [
  // ============================================ WAREHOUSING & 3PL FULFILMENT
  {
    id: "warehousing",
    name: "Warehousing & 3PL fulfilment",
    icon: Warehouse,
    tagline:
      "Batch-triage stock exceptions, schedule inbound dock appointments and keep brand clients informed — with every inventory adjustment human-approved.",
    recipes: [
      {
        id: "wh-exceptions",
        title: "Batch-triage stock exceptions (map-reduce, 2 agents)",
        scenario:
          "Every shift the WMS spits out a long list of exceptions — low-stock alerts, cycle-count variances, negative-on-hand, mis-slots. A supervisor reads them one by one, decides which need an inventory adjustment, and most sit untouched until they become a pick failure.",
        triggerLabel: "Webhook (WMS exception export — end of shift / scheduled)",
        steps: [
          { kind: "trigger", title: "Exception batch is ready", detail: "A scheduled Webhook trigger fires with the batch reference from the WMS (or the workflow pulls the open-exception list). The WMS/OMS has no first-party connector — it arrives via Webhook and is read back via a Tool (MCP) node." },
          { kind: "loop", title: "Map: iterate every exception", detail: "A Loop node fans a cheap mapper agent across each exception — one focused pass per record, isolated context, so a 900-line export is handled the same way as a 9-line one." },
          { kind: "agent", title: "Mapper classifies + proposes", detail: "The mapper returns strict JSON per exception (type, SKU, location, severity, proposed action: RECOUNT / ADJUST / RESLOT / IGNORE) grounded in the count/variance rules — it proposes, it never posts an adjustment." },
          { kind: "agent", title: "Reduce: reducer rolls it up", detail: "After the map completes, a reducer agent aggregates the validated items into a shift summary: top variance drivers, the SKUs needing an adjustment, and the recount worklist." },
          { kind: "approval", title: "Ops approves the adjustments", detail: "An Approval node holds every proposed inventory adjustment (and any chargeable recount/rework) for a supervisor — nothing changes on-hand or bills the client without a click." },
          { kind: "output", title: "Apply approved + post the report", detail: "A Tool (MCP) node posts only the approved adjustments back to the WMS and files the shift summary; unapproved items stay a worklist." },
        ],
        connectors: ["Webhook", "Tool (MCP → WMS/OMS)", "Approval / HITL", "Email"],
        outcome: "The whole exception queue is triaged and rolled into a decision-ready shift summary each run, with a human owning every on-hand change.",
        impact: "Map-reduce turns an unbounded manual read-through into cheap per-item passes plus one synthesis — the supervisor spends their time approving adjustments, not sorting a raw export.",
        build: {
          agents: 2,
          agentsNote: "1 mapper (runs once per exception via the Loop node) + 1 reducer (runs once). Loop/Approval are workflow nodes.",
          pattern: "Map-reduce: Loop (map) → reducer (reduce) → human approves adjustments",
          prompt:
            "(Mapper) Triage ONE warehouse exception. Return strict JSON {type (LOW_STOCK|CYCLE_COUNT_VAR|NEG_ON_HAND|MIS_SLOT), sku, location, severity (LOW|MED|HIGH), proposed_action (RECOUNT|ADJUST|RESLOT|IGNORE), reason}. Judge only from the record's own fields and the counting rules in Knowledge — never invent a quantity, and NEVER treat a proposed ADJUST as done; you only recommend. If the record is malformed, set proposed_action=RECOUNT with reason='needs verification'.",
          agentRoles: [
            { role: "Mapper (per exception)", model: "Economy tier — fast, cheap; runs N times so its model choice dominates total cost", prompt: "Classify ONE exception into strict schema-valid JSON with a proposed action; null for missing fields; zero invented quantities; ADJUST is only ever a recommendation." },
            { role: "Reducer (once)", model: "Standard tier — reasoning", prompt: "Aggregate the validated per-exception results into a shift summary: top variance drivers, the ADJUST worklist with SKUs/locations, and a recount list. Use only the provided items; cite real counts, never estimate." },
          ],
          mcp: ["WMS / OMS (exception export + adjustment post) — via Tool (MCP)", "Client 3PL portal / email — for the shift summary"],
          tools: ["knowledge_search (cycle-count + variance-tolerance SOP)", "http_request (fetch exceptions + post approved adjustments)"],
          guardrails: [
            "The agents only propose — every on-hand adjustment and any chargeable recount/rework passes the Approval node before the WMS is touched",
            "Schema validation on each mapper output — a malformed exception is dropped from the reduce and queued for recount, never silently adjusted",
            "Idempotent per exception id, so a re-run of the batch never double-posts an adjustment",
          ],
          cost:
            "Cost is linear and predictable: N × (economy-tier, hard-capped mapper) + ONE standard-tier reduce. Because the mapper runs across the whole exception batch, it MUST sit on the economy tier — that single choice is the dominant cost lever; the reducer's one call is where the reasoning budget goes. Run the map in Parallel batches for wall-clock speed without changing per-item cost.",
          output:
            "Loop maps the cheap agent over every exception → reducer synthesizes the shift summary once → supervisor Approval gates the adjustments → a Tool (MCP) node posts only the approved changes back to the WMS and files the report; unapproved items remain a worklist, never auto-applied.",
        },
      },
      {
        id: "wh-inbound-asn",
        title: "Inbound ASN → dock appointment, self-scheduled",
        scenario:
          "Carriers and brand clients email or portal-in an ASN, then phone the desk to book a dock slot. A coordinator eyeballs the dock calendar, plays phone tag, and trailers still show up double-booked or outside receiving hours.",
        triggerLabel: "Email / Webhook (ASN submitted by carrier or client)",
        steps: [
          { kind: "trigger", title: "ASN arrives", detail: "An Email trigger on the inbound-receiving inbox (or a Webhook from the client 3PL portal) catches the ASN — carrier, PO, cartons/pallets, requested window." },
          { kind: "agent", title: "Parse + propose a slot", detail: "An Agent node reads the ASN, checks the requested window against receiving hours and dock-capacity rules in Knowledge, and proposes the best available appointment." },
          { kind: "condition", title: "Capacity conflict?", detail: "A Condition node branches: a clean, in-hours, non-conflicting slot flows to confirmation; an overlap or out-of-hours request routes for a human decision." },
          { kind: "approval", title: "Dock supervisor confirms", detail: "An Approval node holds the binding slot commitment (and any capacity override or after-hours exception) for a supervisor — a dock booking commits a door and labour, so a human owns it." },
          { kind: "tool", title: "Write the appointment", detail: "On approval, a Tool (MCP) node books the slot in the dock-scheduling / WMS system, keyed to the ASN so a resend can't double-book." },
          { kind: "output", title: "Confirm to the carrier", detail: "An Output node sends the confirmed door, date and window to the carrier/client on Email or WhatsApp, with the receiving instructions." },
        ],
        connectors: ["Email", "Webhook", "WhatsApp", "Tool (MCP → dock/WMS scheduler)", "Approval / HITL"],
        outcome: "ASNs turn into confirmed, conflict-checked dock appointments without phone tag, and every binding slot is signed off by a supervisor.",
        impact: "Replaces the eyeball-the-calendar-and-call-back loop with a grounded proposal a human just confirms — the value is fewer double-bookings and out-of-hours arrivals, with the commitment still human-owned.",
        build: {
          agents: 1,
          agentsNote: "1 scheduling agent; the capacity branch, the supervisor confirmation and the calendar write are workflow nodes.",
          prompt:
            "You are an inbound-dock scheduling assistant for {{warehouse}}. From an ASN {carrier, po, units, pallets, requested_window}, check the request against receiving hours and dock-capacity rules in Knowledge and propose the best available appointment {door, date, window} with a short reason. Only propose a slot the capacity rules actually allow — never invent a door or extend receiving hours. If the requested window conflicts or falls out of hours, do NOT confirm; flag it {conflict: true, options[]} for a human. You propose only — the appointment is booked by the workflow after a supervisor confirms.",
          mcp: ["Dock-scheduling / WMS system — via a Tool (MCP) node (no first-party connector)", "Client 3PL portal — inbound via Webhook", "Email / WhatsApp — confirmation channel"],
          tools: ["knowledge_search (receiving hours + dock-capacity rules)", "http_request (read dock calendar; write the appointment)"],
          guardrails: [
            "The agent proposes only — the binding slot, any capacity override and any after-hours exception pass the Approval node before the calendar is written",
            "Proposed slots come from the documented receiving hours and capacity rules — no invented doors or extended hours",
            "The appointment write is keyed to the ASN id — a resent ASN is idempotent and never double-books a door",
          ],
          cost:
            "A single standard-tier agent runs once per ASN; there is no batch, so no per-item multiplier to manage. Keep the prompt tight and let clean, non-conflicting requests skip straight to confirmation — the human's time (and the only cost that matters here) is spent on the genuine conflicts the Condition node surfaces.",
          output:
            "The agent's grounded proposal flows through the Condition node; on a supervisor Approval, a Tool (MCP) node books the slot in the dock scheduler keyed to the ASN, and an Output node confirms the door/date/window plus receiving instructions to the carrier on Email or WhatsApp.",
        },
      },
    ],
  },

  // ==================================================== PRINTING & PACKAGING
  {
    id: "printing",
    name: "Printing & packaging",
    icon: Printer,
    tagline:
      "Turn quote requests and proof chasing into grounded, gated workflows — with every price commitment and proof sign-off human-approved.",
    recipes: [
      {
        id: "print-quote",
        title: "Quote request → grounded estimate, price approved before it goes out",
        scenario:
          "Estimate requests land by email, WhatsApp and web form with half the spec missing. The estimator re-reads the rate card, chases the customer for the missing details, prices it by hand, and quotes drift out days later.",
        triggerLabel: "Email / WhatsApp / Webhook (quote request)",
        steps: [
          { kind: "trigger", title: "Quote request arrives", detail: "An Email, WhatsApp or Webhook (web form) trigger catches the request." },
          { kind: "agent", title: "Parse the job spec", detail: "An Agent node extracts the printable spec — product, stock/GSM, size, quantity, colours, finish, turnaround — into structured fields." },
          { kind: "condition", title: "Spec complete?", detail: "A Condition node checks the mandatory fields; an incomplete spec branches to a single, specific clarification message instead of a wrong price." },
          { kind: "knowledge", title: "Price from the rate card", detail: "A Knowledge node grounds the estimate in your current rate card / material pricing — the agent computes the estimate only from documented rates, never a made-up number." },
          { kind: "approval", title: "Estimator signs off the price", detail: "An Approval node holds the estimate — a price to a customer is a binding commitment, so a human confirms (or edits) before it is ever sent." },
          { kind: "output", title: "Send the quote", detail: "An Output node sends the approved estimate on the customer's channel, itemised, with validity and next step." },
        ],
        connectors: ["Email", "WhatsApp", "Webhook", "Knowledge base", "Approval / HITL", "Tool (MCP → print MIS)"],
        outcome: "Requests become itemised, rate-card-grounded estimates within the hour, and no price reaches a customer without an estimator's sign-off.",
        impact: "Removes the re-keying and back-and-forth while keeping the commitment human — the agent assembles a complete, grounded estimate; the estimator just approves or adjusts.",
        build: {
          agents: 1,
          agentsNote: "1 estimating agent; the completeness gate, the price approval and the MIS lookup are workflow nodes.",
          prompt:
            "You are a print estimator's assistant for {{press}}. From a quote request, extract the spec {product, stock, gsm, size, quantity, colours (e.g. 4/0, 4/4), finish[], turnaround}. If any mandatory field is missing, do NOT price — return {complete: false, missing[]} so the workflow asks one clear clarification. When the spec is complete, compute an itemised estimate USING ONLY the rate card in Knowledge {line_items[{desc, unit, rate, amount}], subtotal, tax, total, validity}. Never invent a rate, discount, or lead time not in the rate card. This is a draft for an estimator to approve — never present it as a confirmed price.",
          mcp: ["Print MIS / print-ERP (job + customer history) — via a Tool (MCP) node (no first-party connector)", "Email / WhatsApp — request + quote channel", "Web form — inbound via Webhook"],
          tools: ["knowledge_search (current rate card / material pricing)", "http_request (pull customer / prior-job pricing from the MIS)"],
          guardrails: [
            "Every price is a draft until the estimator approves at the Approval node — no quote leaves without a human sign-off",
            "Estimates are computed only from the rate card in Knowledge — no invented rates, discounts or lead times",
            "An incomplete spec forces a single clarification (completeness gate) instead of a guessed price",
          ],
          cost:
            "One standard-tier agent per request handles parsing and rate-card pricing in a single pass; there is no batch multiplier. If request volume grows, drop the completeness-check pass to an economy-tier model and reserve the standard tier for the priced estimate — the clarification path never needs the bigger model.",
          output:
            "The agent parses the spec → the Condition node gates completeness (clarifying if needed) → it prices from the rate card in Knowledge → the estimator approves or edits at the Approval node → an Output node sends the itemised quote with validity on the customer's channel, and the draft is linked to the MIS job.",
        },
      },
      {
        id: "print-proof",
        title: "Proof approval collection with a completeness gate",
        scenario:
          "A job can't go to plate until the customer signs off the proof — but sign-offs come back partial ('page 2 looks fine') or never, and someone has to chase each one and confirm every element was actually approved before releasing to print.",
        triggerLabel: "Webhook (proof ready from prepress) / Email",
        steps: [
          { kind: "trigger", title: "Proof is ready", detail: "A Webhook from prepress (or an Email) fires when the proof PDF is ready, with the job and its list of proof elements (pages, dielines, spot colours)." },
          { kind: "agent", title: "Compose the sign-off request", detail: "An Agent node builds a clear approval request with an explicit checklist of every element that must be signed off, and sends the proof link." },
          { kind: "output", title: "Send + collect the sign-off", detail: "An Output node delivers the proof and checklist on WhatsApp or Email and collects the customer's responses." },
          { kind: "condition", title: "Completeness gate", detail: "A Condition node checks that EVERY listed element is explicitly approved — a partial or ambiguous reply is not treated as a full sign-off; it loops back for the outstanding items." },
          { kind: "approval", title: "Release to print", detail: "An Approval node holds the release-to-plate decision — a proof sign-off is binding, so production confirms full customer approval before the job advances." },
          { kind: "tool", title: "Mark approved-to-print", detail: "On release, a Tool (MCP) node sets the job to approved-to-print in the MIS and attaches the sign-off record." },
        ],
        connectors: ["Webhook", "Email", "WhatsApp", "Approval / HITL", "Tool (MCP → print MIS)"],
        outcome: "Every proof element is explicitly signed off before a job goes to plate, and the release is a human decision with the approval trail attached.",
        impact: "Replaces manual chasing and the risk of plating on a partial 'looks fine' — the completeness gate makes 'fully approved' a checked fact, not an assumption.",
        build: {
          agents: 1,
          agentsNote: "1 proof-coordination agent; the completeness gate, the release approval and the MIS write are workflow nodes.",
          prompt:
            "You are a prepress proof coordinator for {{press}}. Given a job {job_id, proof_url, elements[]} (each element is a page/dieline/spot colour that needs sign-off), compose a clear approval request that lists EVERY element and asks the customer to approve each one. As replies come in, map them to the checklist and return {approved[], outstanding[], ambiguous[]}. Treat only an explicit approval as approved — a comment like 'looks fine' on one page does NOT approve the others. Never mark the proof fully signed off yourself; report the checklist state so the workflow's gate and the human release decide.",
          mcp: ["Print MIS / job-tracking — via a Tool (MCP) node (no first-party connector)", "Prepress output — inbound via Webhook", "WhatsApp / Email — proof + sign-off channel"],
          tools: ["knowledge_search (per-job element checklist / proofing SOP)", "http_request (set job status in the MIS)"],
          guardrails: [
            "The job advances to plate only on the Approval node — the agent tracks the checklist but never releases to print itself",
            "Completeness gate: every listed element must be explicitly approved; a partial or ambiguous reply is not a full sign-off and loops back for the outstanding items",
            "The MIS status write is keyed to the job id and stamped with the sign-off record — auditable and never double-released",
          ],
          cost:
            "A single standard-tier agent coordinates one job's proof cycle across however many reply rounds it takes; there is no per-item batch. Because the work is short, structured checklist bookkeeping, an economy-tier model is enough for the reply-mapping turns — reserve any heavier tier for jobs with large element lists.",
          output:
            "The agent sends the proof with an explicit element checklist → an Output node collects replies → the Condition node's completeness gate loops until every element is approved → production releases at the Approval node → a Tool (MCP) node marks the job approved-to-print in the MIS with the sign-off record attached.",
        },
      },
    ],
  },
];
