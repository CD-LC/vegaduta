import type { Industry } from "./recipes-data";
import { RadioTower, Recycle } from "lucide-react";

// ---------------------------------------------------------------------------
// Telecom-tower (passive infrastructure) & Waste-management workflow recipes —
// same "inline copy, no i18n keys" precedent and honesty rules as
// recipes-data.tsx. Every recipe is expressed ONLY in VegaDūta's real building
// blocks (Trigger / Agent / Knowledge / Condition / Approval / Output /
// Tool(MCP) / Voice / Device / Loop / Code / Parallel), so each maps 1:1 to
// something you can actually build in the Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — NOC/OSS platforms, CMMS / work-order
// systems, route & fleet / bin-telematics software, ERP/billing — are reached
// the real way, via an inbound Webhook trigger, a Tool (MCP) node, or the
// Device Control node (site telemetry / IoT), and are LABELLED as such. Every
// consequential decision (a chargeable field dispatch, vendor spend, a route
// change, a landlord binding/site-access commitment, a chargeable service) goes
// through an Approval (human-in-the-loop) step. Impact lines describe the
// mechanism (why it helps), never invented ROI numbers.
// ---------------------------------------------------------------------------

export const INDUSTRIES_TOWERWASTE: Industry[] = [
  // ================================ TELECOM TOWERS & PASSIVE INFRASTRUCTURE
  {
    id: "telecom-tower",
    name: "Telecom towers & passive infrastructure",
    icon: RadioTower,
    tagline:
      "Turn site telemetry — mains outage, DG fuel, battery — into a triaged, approval-gated field dispatch, and keep landlord rent & site-access coordination off the phone.",
    recipes: [
      {
        id: "tower-outage",
        title: "Site outage / low DG-fuel alert → triaged, approved field dispatch",
        scenario:
          "A site drops to diesel-generator power and the fuel is running low, or mains fails at an unmanned tower. Today the NOC spots it late on a dashboard, calls around to find a technician, and every dispatch is a chargeable visit approved over WhatsApp with no trail.",
        triggerLabel: "Webhook (NOC/OSS alarm) or Device Control (site telemetry)",
        steps: [
          { kind: "trigger", title: "Site alarm fires", detail: "A Webhook trigger receives the NOC/OSS alarm (mains-fail, DG-on, low-battery). NOC/OSS platforms have no first-party connector — the alarm comes in via Webhook." },
          { kind: "device", title: "Read live site telemetry", detail: "A Device Control node polls the site's power/fuel/battery telemetry (RMS/IoT) to confirm the real state — mains status, DG-fuel level, battery voltage — so the decision is grounded in the hardware, not a stale dashboard row." },
          { kind: "agent", title: "Triage severity + draft the dispatch", detail: "An Agent node classifies the fault (transient blip vs. genuine fuel/mains outage risking downtime), estimates urgency, and drafts the work order — nearest eligible vendor, SLA window, and the chargeable-visit cost line." },
          { kind: "condition", title: "Only escalate real outages", detail: "A Condition node stops on transient/auto-recovered events (log only) and continues only when telemetry confirms a genuine outage or fuel-out risk." },
          { kind: "approval", title: "Ops approves the chargeable dispatch", detail: "An Approval node holds the work order until a human approves — a field visit is billable vendor spend, so it never auto-dispatches. This is the core control." },
          { kind: "output", title: "Dispatch + confirm", detail: "On approval, a Tool (MCP) node raises the work order in the CMMS and an Output node sends the technician the site details on WhatsApp/SMS; the NOC gets the same summary." },
        ],
        connectors: ["Webhook (NOC/OSS)", "Device Control (site RMS/IoT)", "Tool (MCP → CMMS)", "WhatsApp", "SMS", "Approval / HITL"],
        outcome: "A confirmed outage becomes a triaged, costed work order in front of an approver within minutes of the alarm — and the technician is briefed the moment it's approved, with the whole chain logged.",
        impact: "Grounding the call in live telemetry (not a lagging dashboard) plus an approval gate turns 'call around and hope' into a fast, auditable dispatch — the value is detection-to-dispatch speed and a spend trail, not an autonomous dispatch bot.",
        build: {
          agents: 1,
          agentsNote: "1 triage/dispatch agent. The Device read, Condition, Approval and CMMS Tool node are workflow steps, not extra agents.",
          prompt:
            "You are a passive-infrastructure NOC triage agent. Given a site alarm plus live telemetry {site_id, mains_status, dg_running, dg_fuel_pct, battery_voltage, alarm_type, timestamp}, classify severity as TRANSIENT (auto-recovered / momentary), MONITOR (degraded but stable), or DISPATCH (mains-fail with low fuel/battery risking downtime). When DISPATCH, draft a work order {site_id, fault_summary, suggested_vendor, sla_window, chargeable: true, est_cost_note}. Use only the telemetry provided — never assume a fuel level or invent a site's state. Never mark a dispatch approved; you only propose it for a human. Reply as strict JSON.",
          mcp: ["NOC/OSS alarm source — inbound via Webhook trigger", "CMMS / work-order system — via a Tool (MCP) node (no first-party connector)", "Site RMS/IoT telemetry — via the Device Control node", "WhatsApp / SMS — technician + NOC notification channel"],
          tools: ["knowledge_search (site master: access notes, vendor SLA, DG capacity)", "http_request (nearest-vendor / rota lookup)"],
          guardrails: [
            "Every chargeable field dispatch passes the Approval node — the agent proposes vendor spend, a human authorises it before any work order is raised",
            "Severity is grounded in the Device Control telemetry read — a transient/auto-recovered alarm is logged only, never escalated to a paid visit",
            "Scope-locked to the alarming site: the CMMS Tool node and telemetry read are keyed to that site_id, and each alarm is idempotent so a re-fired alarm never double-dispatches",
          ],
          cost:
            "The triage agent runs on an economy/standard tier — this is classification over a small telemetry payload, not deep reasoning, so it needs no premium model. Telemetry is read once per confirmed alarm via the Device node; transient alarms are filtered at the Condition node before the agent ever drafts a costly dispatch. The real cost lever is the Approval gate keeping billable visits from firing on noise — model spend here is negligible next to a wasted truck-roll.",
          output:
            "Confirmed-outage JSON → Condition drops transients → the drafted, costed work order goes to the Approval node → on approval a Tool node raises it in the CMMS via MCP and an Output node briefs the technician on WhatsApp/SMS; alarm, telemetry snapshot, approver and dispatch are all logged for the site record.",
        },
      },
      {
        id: "tower-landlord",
        title: "Landlord rent & site-access coordination without the phone tag",
        scenario:
          "A crew needs access to a rooftop/ground-based tower, or a landlord messages about overdue rent or a lease query. Today this is scattered WhatsApp threads and calls — access gets granted verbally, rent commitments get made ad hoc, and nothing is on record.",
        triggerLabel: "WhatsApp (landlord / crew) or Webhook (access request)",
        steps: [
          { kind: "trigger", title: "Landlord or crew reaches out", detail: "A WhatsApp trigger (or an access-request Webhook from the field app) starts the flow — a rent question, a lease query, or a site-access request." },
          { kind: "agent", title: "Understand + ground the request", detail: "An Agent node classifies intent (access request / rent query / lease query) and answers factual questions from the site + lease record — rent due, access hours, contact — in the landlord's language." },
          { kind: "knowledge", title: "Pull the lease terms", detail: "A Knowledge node grounds every rent/access answer in the current lease and site-access policy so nothing is invented or misquoted." },
          { kind: "condition", title: "Route bindings vs. FYIs", detail: "A Condition node lets pure information (next rent date, access window) reply straight away, but sends anything binding — granting access, promising a payment date — down the approval path." },
          { kind: "approval", title: "Site manager authorises the commitment", detail: "An Approval node holds any binding decision (a site-access grant or a rent/payment commitment) for a human — a verbal 'yes' becomes a recorded, authorised one." },
          { kind: "output", title: "Confirm on WhatsApp", detail: "An Output node sends the authorised confirmation — access window + crew details, or the acknowledged rent position — to the landlord and crew." },
        ],
        connectors: ["WhatsApp", "Knowledge base", "Tool (MCP → property/lease system)", "Approval / HITL", "Webhook"],
        outcome: "Landlords get an instant, grounded answer on the channel they already use, and every access grant or rent commitment is authorised by a human and recorded — no more verbal-only approvals lost in a chat thread.",
        impact: "Coordination that used to mean phone tag becomes a self-serve, always-on conversation for the factual part, with a human owning only the binding decisions — the value is fewer dropped requests and a real audit trail on access and money.",
        build: {
          agents: 1,
          agentsNote: "1 coordination agent; the lease grounding, binding/FYI split and site-manager sign-off are workflow nodes.",
          prompt:
            "You are a tower site-coordination assistant for {{company}}. Landlords and field crews message you about rent, lease terms, and site access. Classify each message as ACCESS_REQUEST, RENT_QUERY, LEASE_QUERY, or OTHER. Answer factual questions (next rent date, access hours, permitted contact) ONLY from the lease and site-access record in Knowledge, in the sender's language. Never grant site access, never confirm or promise a rent/payment date, and never agree to a lease change yourself — for anything binding, state that it needs authorisation and hand it to the approval step. Never invent a rent amount, date or access permission not present in the record.",
          mcp: ["Property / lease-management system — via a Tool (MCP) node (no first-party connector)", "Site-access request source (field app) — inbound via Webhook trigger", "WhatsApp — landlord + crew channel"],
          tools: ["knowledge_search (lease terms, rent schedule, site-access policy per site)", "http_request (rent-ledger / access-log lookup)"],
          guardrails: [
            "Every binding decision — a site-access grant or a rent/payment commitment — passes the Approval node; the agent handles facts only, a human authorises commitments",
            "Rent amounts, dates and access permissions are read from the lease record via Knowledge/Tool — the agent never invents or negotiates a figure",
            "Access grants are scoped to the requesting crew + a specific window and written back to the access log, so nothing is granted verbally or off the record",
          ],
          cost:
            "A single standard-tier agent covers this comfortably — it is grounded Q&A plus classification, not multi-step reasoning, so no premium model is warranted. Factual replies (the bulk of traffic) resolve on the first turn without ever reaching the approval path, and only genuine bindings incur a human step; run the agent's simple turns on its economy model via cost routing to keep the always-on channel cheap.",
          output:
            "The agent classifies + answers grounded rent/access questions on WhatsApp; the Condition node lets FYIs reply directly while routing any access grant or rent commitment to the site-manager Approval node; on authorisation a Tool node records it in the property system and an Output node confirms the window/position to landlord and crew.",
        },
      },
    ],
  },

  // ============================== WASTE MANAGEMENT & ENVIRONMENTAL SERVICES
  {
    id: "waste-management",
    name: "Waste management & environmental services",
    icon: Recycle,
    tagline:
      "Handle missed-collection reports and bin/route Q&A automatically, and gate every route change or chargeable service behind a human — with the compliance paper trail intact.",
    recipes: [
      {
        id: "waste-pickup",
        title: "Missed-collection report → verified, approval-gated route fix",
        scenario:
          "A resident or commercial client reports a missed bin. Today it's a call the office logs by hand, someone checks whether the truck actually skipped it, and a make-good pickup or route tweak is arranged informally — slow, inconsistent, and easy to lose.",
        triggerLabel: "WhatsApp / SMS (resident report) or Webhook (portal ticket)",
        steps: [
          { kind: "trigger", title: "Missed pickup reported", detail: "A WhatsApp/SMS trigger (or a Webhook from the client portal) captures the report with the address/account and bin type." },
          { kind: "agent", title: "Log + answer the routine part", detail: "An Agent node identifies the account, answers bin/route status questions ('when is my next collection?') from the schedule, and drafts a structured missed-collection case for anything needing action." },
          { kind: "tool", title: "Check the collection record", detail: "A Tool (MCP) node queries the route/fleet system for that stop — was the bin served, was the truck on-route — so a make-good is only arranged when the miss is real. Route/fleet software has no first-party connector; MCP calls yours." },
          { kind: "condition", title: "Real miss vs. already served", detail: "A Condition node closes cases where the record shows the stop was served (with an explanation to the reporter), and continues only genuine misses to the fix path." },
          { kind: "approval", title: "Dispatcher approves the route change", detail: "An Approval node holds any make-good pickup or route change for a dispatcher — a truck re-route is an operational + cost decision, so it never auto-schedules. This is the core control." },
          { kind: "output", title: "Confirm the make-good", detail: "On approval, a Tool (MCP) node adds the make-good to the route and an Output node tells the reporter the day/window on WhatsApp/SMS." },
        ],
        connectors: ["WhatsApp", "SMS", "Webhook", "Tool (MCP → route/fleet)", "Approval / HITL"],
        outcome: "Every missed-collection report is logged, verified against the actual collection record, and turned into an approved make-good — with the reporter kept informed automatically, day or night.",
        impact: "Verifying against the fleet record before acting stops needless truck re-rolls on stops that were actually served, and the approval gate keeps a human owning the route decision — the value is a consistent, checked process and a trail, not an autonomous scheduler.",
        build: {
          agents: 1,
          agentsNote: "1 intake/triage agent; the collection-record check, real-miss split and dispatcher sign-off are workflow nodes.",
          prompt:
            "You are a waste-collection support agent for {{company}}. Residents and commercial clients message about missed pickups and collection schedules. Identify the account from the address/account id. For a schedule question, answer the next collection day/window from the route schedule only. For a missed-collection report, produce a structured case {account, address, bin_type, reported_date, scheduled_date}. Do NOT confirm a make-good pickup or any new collection date yourself — only propose the case; a dispatcher approves the route change. Never invent a collection date, and never promise a service the schedule doesn't show.",
          mcp: ["Route / fleet-management system — via a Tool (MCP) node (no first-party connector)", "Client portal tickets — inbound via Webhook trigger", "WhatsApp / SMS — report + confirmation channel"],
          tools: ["knowledge_search (collection calendar, service areas, bin-type rules)", "http_request (collection-record / GPS-served lookup)"],
          guardrails: [
            "Every make-good pickup or route change passes the Approval node — the agent proposes, a dispatcher authorises the operational + cost decision",
            "A make-good is only proposed when the fleet record shows a genuine miss — stops the record confirms were served are closed with an explanation, never re-rolled",
            "Collection dates and windows are read from the route schedule/record — the agent never invents a date or promises unscheduled service; each report is idempotent per account+date so a re-send never double-books",
          ],
          cost:
            "An economy/standard-tier agent handles this — account matching, schedule lookup and case-drafting are lightweight, so no premium model is needed. Pure schedule questions (the highest-volume, lowest-value traffic) resolve on the first turn without a human, and the fleet-record check filters non-misses at the Condition node before any costly re-route reaches the dispatcher; run simple turns on the economy model via cost routing.",
          output:
            "The agent logs the report and answers schedule questions directly; a Tool node checks the collection record, the Condition node closes already-served stops, and genuine misses go to the dispatcher Approval node; on approval a Tool node adds the make-good to the route and an Output node confirms the day/window to the reporter on WhatsApp/SMS.",
        },
      },
      {
        id: "waste-manifest",
        title: "Compliance manifest & document collection for a commercial pickup",
        scenario:
          "Regulated / commercial waste pickups need the paperwork right — waste manifests, hazardous-material declarations, disposal certificates. Today staff chase clients by email for missing documents and eyeball each one before filing, and a gap only surfaces at audit.",
        triggerLabel: "Email (documents in) or Webhook (pickup scheduled)",
        steps: [
          { kind: "trigger", title: "Pickup needs its paperwork", detail: "A Webhook trigger fires when a regulated commercial pickup is scheduled (or an Email trigger catches documents the client sends in)." },
          { kind: "agent", title: "Read + check each document", detail: "An Agent node extracts the key fields from each submitted document (waste code, quantity, generator, carrier, disposal facility, dates) and checks them against what this pickup requires." },
          { kind: "knowledge", title: "Ground in the compliance rules", detail: "A Knowledge node holds the manifest/declaration requirements by waste category, so the agent checks against the actual rules — with citations — not a guess." },
          { kind: "condition", title: "Complete or chase?", detail: "A Condition node lets a complete, consistent packet flow on, and branches an incomplete/mismatched one back to the client with a precise list of what's missing." },
          { kind: "output", title: "Request exactly what's missing", detail: "An Output node emails the client a specific, itemised request (via a secure upload link) — no generic 'please send your documents'." },
          { kind: "approval", title: "Compliance officer signs off filing", detail: "An Approval node puts the completed manifest packet in front of a compliance officer before it's filed to the regulatory/records system — the agent assembles and checks, a human certifies." },
        ],
        connectors: ["Email", "Webhook", "Knowledge base", "Tool (MCP → records/compliance system)", "Approval / HITL"],
        outcome: "Manifest packets are assembled, checked against the real requirements, and chased down to completion automatically — the compliance officer signs off a clean, complete packet instead of hunting for gaps.",
        impact: "Catching a missing declaration at intake instead of at audit is the whole point — the agent does the tedious cross-check and chasing, and a qualified human still certifies before anything is filed. Mechanism, not a compliance guarantee.",
        build: {
          agents: 1,
          agentsNote: "1 document-check agent; the completeness split, the missing-doc chase and the compliance sign-off are workflow nodes.",
          prompt:
            "You are a waste-compliance documentation agent for {{company}}. For a scheduled regulated pickup, you receive its documents (waste manifest, hazardous declaration, disposal certificate). Extract strict JSON per document {doc_type, waste_code, quantity, unit, generator, carrier, disposal_facility, issue_date, expiry?}. Check the packet against the requirements for this waste category in Knowledge and return {complete: bool, missing[], mismatches[{field, expected, found}]}. Copy values exactly — never fill in, correct, or assume a field; a missing or unreadable value is 'missing', not a guess. You prepare and check the packet only — you never certify or file it; a compliance officer approves that.",
          mcp: ["Records / compliance filing system — via a Tool (MCP) node (no first-party connector)", "Client documents — inbound via Email trigger", "Pickup scheduling — inbound via Webhook trigger"],
          tools: ["document parsing (manifest/declaration PDF/scan → fields)", "knowledge_search (manifest requirements + hazardous rules by waste category)"],
          guardrails: [
            "The agent checks and assembles only — the completed packet is filed to the records system only after the compliance-officer Approval node certifies it",
            "Every field is transcribed exactly from the document against the Knowledge requirements — no field is auto-filled or 'corrected', a missing/unreadable value is reported as missing",
            "Client documents are collected only through a secure upload link (never as loose chat attachments), and the missing-item request lists exactly what's absent so nothing extra is solicited",
          ],
          cost:
            "Document parsing plus a rules cross-check runs well on a standard tier; reserve a premium model only for genuinely dense or ambiguous manifests via cost routing, so routine packets stay cheap. Cost scales with document volume, and the completeness check runs once per submission — the itemised chase avoids repeated full re-reviews of the same packet, keeping spend proportional to real work.",
          output:
            "Per-document JSON → checked against the Knowledge requirements → the Condition node passes complete packets and bounces incomplete ones with an itemised, secure-upload chase via the Output/Email node → the completed packet goes to the compliance-officer Approval node → on certification a Tool node files it to the records system, with every document, check and approver logged.",
        },
      },
    ],
  },
];
