import type { Industry } from "./recipes-data";
import { Zap, HardHat } from "lucide-react";

// ---------------------------------------------------------------------------
// Utilities & construction workflow recipes — a companion data module to
// recipes-data.tsx, same "inline copy, no i18n keys" precedent. Every recipe is
// expressed ONLY in VegaDūta's real building blocks so it maps 1:1 to something
// you can actually build in the Workflow designer.
//
// Honesty rule (copied verbatim in spirit from recipes-data.tsx): systems
// VegaDūta does NOT ship a first-party connector for (OMS, CIS/billing,
// project-management, ERP, GIS) are reached the real way — an inbound Webhook
// trigger and/or a Tool (MCP) node — and are labelled as such. Impact lines
// describe the mechanism (why it helps), never invented ROI numbers. No recipe
// is marked featured. Every consequential decision (cost/safety/billing) goes
// through an Approval step.
// ---------------------------------------------------------------------------

export const INDUSTRIES_INFRA: Industry[] = [
  // ============================================================== UTILITIES
  {
    id: "utilities",
    name: "Utilities & energy (power / water / gas)",
    icon: Zap,
    tagline: "Turn grid and meter events into proactive, multilingual customer updates.",
    recipes: [
      {
        id: "util-outage",
        title: "Proactive outage notifications to affected customers",
        scenario:
          "The grid trips or a water main is isolated. Today the control room knows first, but affected customers only find out when they start calling in — so the call centre floods at exactly the wrong moment.",
        triggerLabel: "Webhook (OMS / SCADA outage event)",
        steps: [
          { kind: "trigger", title: "OMS reports an outage", detail: "A Webhook trigger receives the outage/isolation event from your OMS/SCADA layer — feeder, area, estimated restoration. OMS has no first-party connector, so it arrives via Webhook (or a Tool/MCP node that polls it)." },
          { kind: "condition", title: "Confirm scope + affected area", detail: "A Condition node checks it's a genuine sustained outage (not a momentary trip) and resolves which customer group is affected before anyone is messaged." },
          { kind: "agent", title: "Draft a clear, calm update", detail: "An Agent node writes the cause (where known), the affected area and the estimated restoration time in plain language — no jargon, in the customer's own language." },
          { kind: "output", title: "Notify affected customers", detail: "An Output node sends the update on WhatsApp/SMS to the affected group (Sarvam covers 22+ Indian languages), with a link to live status." },
          { kind: "device", title: "Confirm restoration from the grid", detail: "A Device Control node reads the feeder/IoT restoration signal so the 'power's back' all-clear is triggered by the grid itself, not a manual guess." },
        ],
        connectors: ["Webhook (OMS/SCADA)", "WhatsApp", "SMS", "Sarvam (translate and speech tools)", "Device Control"],
        outcome: "Affected customers hear about the outage — and the restoration — from you first, in their language, instead of finding out by calling in.",
        impact: "Replaces the manual 'wait for complaints, then broadcast' cycle with an event-driven notify at the moment the grid detects the fault and again when it clears.",
        build: {
          agents: 1,
          agentsNote: "1 agent writes the customer update; the scope Condition, the affected-group send and the Device Control all-clear are workflow nodes.",
          prompt:
            "You are a utility outage-communications writer. From the outage event (feeder/area, cause where known, estimated restoration time), write a short, calm update in plain language and in the customer's language: what's affected, the cause if known, and the estimated restoration. Only state a restoration time if it is present in the event — never invent or promise one. No technical jargon. Keep it to a few reassuring lines with a link to live status.",
          mcp: ["OMS / SCADA — inbound via Webhook trigger (no first-party connector)", "Feeder / IoT restoration signal — via a Device Control node", "WhatsApp + SMS — send channels", "Sarvam — in-language delivery"],
          tools: ["http_request (resolve the affected-customer group for the feeder)"],
          guardrails: [
            "Only a confirmed sustained outage triggers messages — the Condition node filters momentary trips",
            "Scope-locked to the affected feeder/area group — never a blanket blast to all customers",
            "The 'restored' all-clear fires only on the Device Control grid signal, not a manual guess",
          ],
          output:
            "The agent drafts the update → an Output node sends it to the affected group on WhatsApp/SMS (Sarvam multilingual) with a live-status link → when the Device Control node reads the restoration signal, the all-clear message goes out the same way.",
        },
      },
      {
        id: "util-high-usage",
        title: "High-usage alerts & bill-dispute intake",
        scenario:
          "A smart meter reports a usage spike, or a customer messages disputing a bill. Both are handled manually — a spike is only noticed at billing time, and a dispute bounces between email and the billing desk for days.",
        triggerLabel: "Webhook (meter reading) or WhatsApp (dispute)",
        steps: [
          { kind: "trigger", title: "Spike or dispute arrives", detail: "A Webhook trigger (smart-meter/CIS reading) or a WhatsApp trigger (customer disputing a bill) starts the flow." },
          { kind: "agent", title: "Explain the usage in plain terms", detail: "An Agent node compares the reading to the customer's own history and drafts a clear, in-language explanation of what changed." },
          { kind: "knowledge", title: "Ground it in tariff rules", detail: "A Knowledge node answers strictly from the current tariff/slab and billing-policy documents — with citations, no guessing at rates." },
          { kind: "condition", title: "Genuine anomaly vs. explainable", detail: "A Condition node splits cases the data explains (seasonal, added load) from genuine anomalies (suspected leak, faulty meter, mis-read)." },
          { kind: "approval", title: "Billing officer signs off adjustments", detail: "An Approval node routes any credit, re-bill or meter-inspection order to a human — no money on the bill changes without a person approving." },
          { kind: "tool", title: "Log to the billing system", detail: "A Tool (MCP) node records the disposition and any approved adjustment in your CIS/billing system." },
        ],
        connectors: ["Webhook (meter/CIS)", "WhatsApp", "Sarvam (multilingual)", "Knowledge base", "Approval / HITL", "Tool (MCP → CIS/billing)"],
        outcome: "Customers get an instant, grounded explanation of their usage, and real disputes reach a billing officer as a complete, structured case.",
        impact: "Removes the manual triage of every high-bill query while keeping every actual bill adjustment behind a human approval and an audit trail.",
        build: {
          agents: 1,
          agentsNote: "1 agent explains the usage; the anomaly Condition, the billing Approval and the CIS write are workflow nodes.",
          prompt:
            "You are a utility billing-support agent. Compare the meter reading against the customer's own consumption history and explain, in plain language and the customer's language, what changed (seasonal, added load, or a possible anomaly). Ground every rate or slab strictly in the tariff/billing-policy documents in Knowledge — never quote a rate that isn't there. Do not promise a credit, re-bill or adjustment; if the data suggests a genuine anomaly (suspected leak, faulty meter, mis-read), flag it for the billing officer.",
          mcp: ["CIS / billing system — via a Tool (MCP) node (no first-party connector)", "WhatsApp — receive + send channel", "Sarvam — in-language delivery"],
          tools: ["knowledge_search (tariff/slab + billing policy)", "http_request (pull the customer's usage history)"],
          guardrails: [
            "No credit, re-bill or adjustment without the billing-officer Approval node — nothing on the bill changes on the agent's word",
            "Rates and slabs are grounded-only in the tariff Knowledge — no invented figures",
            "Scope-locked to the customer's own account and meter",
          ],
          output:
            "The agent explains the usage grounded in the tariff → the Condition node splits explainable cases from genuine anomalies → adjustments route to the billing-officer Approval → a Tool node logs the disposition and any approved adjustment in the CIS/billing system via MCP.",
        },
      },
    ],
  },

  // ============================================================ CONSTRUCTION
  {
    id: "construction",
    name: "Construction & infrastructure",
    icon: HardHat,
    tagline: "Structure RFIs, route safety incidents and keep site teams in sync — approval-gated.",
    recipes: [
      {
        id: "constr-rfi",
        title: "RFI intake, checked against drawings, routed to the engineer",
        scenario:
          "A subcontractor raises a request-for-information by WhatsApp or email. Today it's re-typed into a log, someone hunts the relevant drawing by hand, and it waits in an inbox until an engineer happens to see it.",
        triggerLabel: "WhatsApp / Email (RFI raised)",
        steps: [
          { kind: "trigger", title: "RFI comes in", detail: "A WhatsApp or Email trigger captures the RFI in the subcontractor's own words, with any attached photos." },
          { kind: "agent", title: "Structure the RFI", detail: "An Agent node extracts the discipline, location/grid ref, question and priority into a standard RFI record — consistently, every time." },
          { kind: "knowledge", title: "Check drawings & specs", detail: "A Knowledge node searches the current drawing set and specifications for the relevant detail — with citations, so the reply points at the exact sheet." },
          { kind: "approval", title: "Route to the responsible engineer", detail: "An Approval node puts the answer where it belongs — the agent drafts and cites, the engineer of record approves the response before it's issued." },
          { kind: "tool", title: "Log to the PM system", detail: "A Tool (MCP) node writes the RFI and its response into your project-management/PM system — no first-party connector, MCP calls yours." },
          { kind: "output", title: "Close the loop with the sub", detail: "An Output node sends the answered RFI back to the subcontractor on WhatsApp/Email with the reference and drawing citation." },
        ],
        connectors: ["WhatsApp", "Email", "Knowledge base", "Approval / HITL", "Tool (MCP → PM system)"],
        outcome: "Every RFI becomes a structured, drawing-referenced record the moment it's raised, and reaches the right engineer instead of sitting in an inbox.",
        impact: "Removes the manual re-typing and drawing-hunt from the front of every RFI while keeping the engineered answer behind a human sign-off.",
        build: {
          agents: 1,
          agentsNote: "1 agent structures the RFI and drafts the cited answer; the engineer Approval and the PM-system write are workflow nodes.",
          prompt:
            "You are a construction RFI-intake assistant. From the subcontractor's message and any attached photos, extract the discipline, location/grid reference, the question and the priority into a standard RFI record. Search the current drawing set and specifications in Knowledge for the relevant detail and draft a proposed answer that cites the exact sheet or spec clause — never guess a detail that isn't in the documents. Do not issue the answer: it must be approved by the engineer of record first.",
          mcp: ["Project-management / PM system — via a Tool (MCP) node (no first-party connector)", "WhatsApp + Email — receive + send channels"],
          tools: ["knowledge_search (drawing set + specifications)", "document parsing (attached site photos / PDFs)"],
          guardrails: [
            "The engineered answer is never issued without the engineer-of-record Approval node — the agent drafts and cites, a person signs off",
            "Every cited detail points at an exact drawing sheet or spec clause — no guessing beyond the documents",
            "Scope-locked to the raising subcontractor's RFI",
          ],
          output:
            "The agent structures the RFI and drafts a drawing-cited answer → the engineer of record approves the response at the Approval node → a Tool node writes the RFI + response into the PM system via MCP → an Output node returns the answered RFI with its sheet citation to the subcontractor.",
        },
      },
      {
        id: "constr-safety",
        title: "Site safety-incident report → structured intake → sign-off",
        scenario:
          "A near-miss or incident on site is reported by a call or a WhatsApp photo. It gets logged inconsistently (or late), delaying the safety review that regulations and insurers require.",
        triggerLabel: "WhatsApp / Voice / Call (incident reported)",
        steps: [
          { kind: "trigger", title: "Incident reported from site", detail: "A WhatsApp, Voice or Call trigger captures the report in the worker's own words and language, with photos where available." },
          { kind: "agent", title: "Structure the incident record", detail: "An Agent node extracts location, time, people involved, injury/near-miss type and immediate action into a standard safety-intake record." },
          { kind: "condition", title: "Grade the severity", detail: "A Condition node classifies severity so a reportable/serious incident is escalated on a different path from a minor near-miss." },
          { kind: "approval", title: "Safety officer signs off", detail: "An Approval node routes the incident to the site safety officer for review and disposition — every safety decision stays with a qualified human." },
          { kind: "tool", title: "Log to the QHSE / PM system", detail: "A Tool (MCP) node files the incident in your QHSE/project system and links the site and contractor." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Sarvam (multilingual)", "Approval / HITL", "Tool (MCP → QHSE/PM)"],
        outcome: "Every incident becomes a complete, structured, time-stamped record the moment it's reported, on the safety officer's desk for sign-off.",
        impact: "Removes the inconsistent, delayed manual logging that slows safety reviews and audits, while keeping the safety judgement with a qualified officer.",
        build: {
          agents: 1,
          agentsNote: "1 agent structures the incident record; the severity Condition, the safety-officer Approval and the QHSE write are workflow nodes.",
          prompt:
            "You are a site safety-incident intake assistant. From the worker's report (in their words and language) and any photos, extract into a standard record: location, time, people involved, whether it was an injury or a near-miss, the injury/near-miss type, and the immediate action taken. Grade the severity strictly using the classification in Knowledge. Record only what was reported — do not speculate on cause or fault, and never downgrade, close or disposition the incident. That decision belongs to the safety officer.",
          mcp: ["QHSE / project system — via a Tool (MCP) node", "WhatsApp + Voice/Call — receive channels", "Sarvam — in-language capture"],
          tools: ["document parsing (site photos)", "knowledge_search (severity classification / reportable criteria)"],
          guardrails: [
            "Every safety disposition stays with the safety-officer Approval node — the agent only structures the record",
            "Reportable/serious severity escalates on its own path (Condition node), separate from minor near-misses",
            "Scope-locked to the reported incident — no speculation on cause or fault",
          ],
          output:
            "The agent structures the incident record → the Condition node grades severity and routes serious cases separately → the safety officer signs off at the Approval node → a Tool node files the incident and links the site/contractor in the QHSE/PM system via MCP.",
        },
      },
    ],
  },
];
