import type { Industry } from "./recipes-data";
import { Pickaxe, FileCheck } from "lucide-react";

// ---------------------------------------------------------------------------
// Mining/metals & Customs-brokerage workflow recipes — same "inline copy, no
// i18n keys" precedent and honesty rules as recipes-data.tsx. Every recipe is
// expressed ONLY in VegaDūta's real building blocks (Trigger / Agent /
// Knowledge / Condition / Approval / Output / Tool(MCP) / Voice / Device /
// Loop / Code / Parallel), so each maps 1:1 to something you can actually
// build in the Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — SCADA / CMMS, EHS / incident
// systems, mine-planning & permit registers, ERP, customs / EDI filing
// portals — are reached the real way, via an inbound Webhook trigger, a Tool
// (MCP) node, or the Device Control node, and are LABELLED as such. Real
// channels only (Webhook, WhatsApp, SMS, Voice/Call, Email, ElevenLabs,
// Sarvam, Razorpay UPI). Every consequential decision — a safety sign-off, a
// stop-work / shutdown call, an HS classification, a customs filing — goes
// through an Approval (human-in-the-loop) step. The customs agents SUGGEST;
// a licensed broker reviews, classifies and files. Impact lines describe the
// mechanism (why it helps), never invented ROI numbers.
// ---------------------------------------------------------------------------

export const INDUSTRIES_MINETRADE: Industry[] = [
  // ================================ MINING, METALS & NATURAL RESOURCES
  {
    id: "mining",
    name: "Mining, metals & natural resources",
    icon: Pickaxe,
    tagline:
      "Turn sensor telemetry into work orders and near-miss reports into a logged, reviewed safety record — while a person always signs off on stopping a machine or closing an incident.",
    recipes: [
      {
        id: "mining-maintenance",
        title: "Equipment maintenance alerts from sensor telemetry",
        scenario:
          "Someone has to keep half an eye on the SCADA/CMMS dashboards and manually raise a maintenance ticket when a haul truck or crusher starts running hot — so a drifting vibration or temperature reading gets noticed late, on the shift that happens to be watching.",
        triggerLabel: "Webhook (telemetry feed) / Device Control (live sensor read)",
        steps: [
          { kind: "trigger", title: "Telemetry arrives", detail: "A Webhook trigger receives the periodic telemetry batch from the SCADA/CMMS historian (vibration, temperature, pressure, run-hours per asset). There is no first-party SCADA connector — the feed comes in via Webhook." },
          { kind: "device", title: "Read the live sensor values", detail: "A Device Control node reads the current readings for the flagged asset, so the evaluation is against live values, not just the batch snapshot." },
          { kind: "agent", title: "Evaluate against thresholds", detail: "An Agent node compares each reading against the asset's alarm/warning limits and classifies severity (OK / WATCH / ALARM), with the specific out-of-range signal and a recommended action." },
          { kind: "condition", title: "Only act on a real breach", detail: "A Condition node stops-and-logs anything within tolerance; only WATCH/ALARM readings continue, so routine noise never raises a ticket." },
          { kind: "approval", title: "A human owns the stop-work call", detail: "Any recommendation to stop or shut down an asset pauses at an Approval node for the maintenance lead — the agent never takes a machine offline on its own." },
          { kind: "tool", title: "Raise the work order + alert the crew", detail: "A Tool (MCP) node creates the maintenance work order in the CMMS and the crew is notified on WhatsApp/SMS with the asset, the signal and the severity." },
        ],
        connectors: ["Webhook", "Device Control", "Tool (MCP → CMMS)", "WhatsApp", "SMS", "Approval / HITL"],
        outcome: "A drifting reading becomes a categorized, actionable work order the moment it crosses a limit — and any decision to actually stop a machine stays with a person.",
        impact: "The value is consistent, round-the-clock threshold-watching that a human dashboard-watcher can't sustain across every asset and every shift; the stop-work decision is deliberately kept human because taking production equipment offline is a binding, high-stakes call.",
        build: {
          agents: 1,
          agentsNote: "1 telemetry-evaluation agent; the sensor read, breach check, stop-work approval and CMMS write are workflow nodes.",
          prompt:
            "You are a condition-monitoring assistant for mining equipment. Given an asset's readings {asset_id, vibration, temperature, pressure, run_hours} and its documented alarm/warning limits, classify severity as OK | WATCH | ALARM and return strict JSON {asset_id, severity, breached_signals[{signal, value, limit}], recommended_action, requires_stop: boolean}. Use only the provided readings and documented limits — never invent a threshold or a reading. Set requires_stop=true only when a documented limit for an unsafe condition is breached; you do NOT stop the machine yourself, you flag it for a human to decide.",
          mcp: ["SCADA / historian telemetry — inbound via the Webhook trigger", "Live sensor read — via the Device Control node", "CMMS (maintenance work orders) — via a Tool (MCP) node", "WhatsApp / SMS — crew alert channel"],
          tools: ["knowledge_search (per-asset alarm/warning limits + maintenance rulebook)", "http_request (create CMMS work order)"],
          guardrails: [
            "The agent never stops or controls an asset — a stop/shutdown recommendation is Approval-gated to the maintenance lead before anything is taken offline",
            "Severity is derived only from documented limits and the actual readings — no invented thresholds, no estimated values",
            "Work-order creation is idempotent (keyed by asset + signal + shift) so the same drifting reading can't spawn duplicate tickets in one run",
          ],
          cost:
            "The evaluation agent runs on the routine telemetry cadence, so it sits on the economy tier — threshold-checking is exactly the cheap, high-frequency case and there is no reasoning premium to pay for it. A per-run tool/token cap bounds cost per telemetry batch, and the Condition gate means most within-tolerance readings stop before any expensive work happens.",
          output:
            "Live readings are read via Device Control → the economy agent classifies severity against documented limits → the Condition gate drops in-tolerance noise → stop-work recommendations wait at the Approval node → a Tool node opens the CMMS work order and the crew is alerted on WhatsApp/SMS; every alert + decision is logged against the asset.",
        },
      },
      {
        id: "mining-safety",
        title: "Near-miss & safety-incident reporting with mandatory review",
        scenario:
          "Near-misses get reported verbally at shift-change or on a paper form that sits in a tray — so they're logged inconsistently, categorized differently every time, and the EHS team reconstructs the record days later, if at all.",
        triggerLabel: "WhatsApp / Voice (worker reports a near-miss or incident)",
        steps: [
          { kind: "trigger", title: "A worker reports it", detail: "A WhatsApp or inbound Voice trigger lets a worker report a near-miss or incident in their own language, from the field, the moment it happens." },
          { kind: "agent", title: "Structure the report", detail: "An Agent node turns the free-text or spoken report into a structured record (what, where, when, asset/area, hazard type, people involved) without changing the worker's account." },
          { kind: "knowledge", title: "Classify against the safety taxonomy", detail: "A Knowledge node grounds the hazard type and provisional severity in the site's own safety taxonomy/rulebook, so categories are consistent run to run." },
          { kind: "condition", title: "Fast-track the serious ones", detail: "A Condition node routes anything with an injury or a high provisional severity onto an immediate-escalation path rather than the routine queue." },
          { kind: "approval", title: "Safety officer signs off — mandatory", detail: "An Approval node holds every incident for a qualified safety officer to review, correct the classification and close it. This human sign-off is the non-negotiable control; nothing is filed as reviewed without it." },
          { kind: "tool", title: "Log to the EHS system + confirm", detail: "On sign-off, a Tool (MCP) node writes the record to the EHS/incident system and the reporter gets a confirmation with the reference number." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Sarvam (translate and speech tools)", "Knowledge base", "Tool (MCP → EHS)", "Approval / HITL"],
        outcome: "Every near-miss is captured the moment it happens, categorized against one consistent taxonomy, and closed only after a safety officer has reviewed it — instead of being lost between the field and the paper tray.",
        impact: "Reporting friction is what suppresses near-miss data; letting a worker report in their own language from the field, then forcing a qualified human review before close, is what makes the record both complete and trustworthy — the sign-off is mandatory by design because closing a safety incident is a consequential decision.",
        build: {
          agents: 1,
          agentsNote: "1 report-structuring agent; the taxonomy grounding, severity fast-track, safety sign-off and EHS write are workflow nodes.",
          prompt:
            "You are a safety-incident intake assistant on a mine site. Turn a worker's free-text or transcribed spoken report into strict JSON {reported_at, location, asset_or_area, hazard_type, description, people_involved, injury: boolean, provisional_severity} in the site's own terms. Preserve the worker's account faithfully — never downplay, embellish or resolve it, and never assign blame. Classify hazard_type and provisional_severity ONLY against the site safety taxonomy in Knowledge. You do NOT determine the final severity or close the incident — a qualified safety officer reviews and signs off; your output is a draft for that review.",
          mcp: ["EHS / incident-management system — via a Tool (MCP) node (no first-party connector)", "WhatsApp / Voice — reporting channel", "Voice reports transcribed + replies voiced via ElevenLabs / Sarvam (Indian languages)"],
          tools: ["knowledge_search (site safety taxonomy + severity rubric)", "http_request (create EHS incident record)"],
          guardrails: [
            "No incident is closed or filed-as-reviewed without the safety officer Approval — the agent only drafts a structured record; a qualified human classifies and signs off",
            "Hazard type and provisional severity come only from the site taxonomy in Knowledge — never an invented category, never a severity the rubric doesn't support",
            "The worker's account is preserved verbatim in the record — the agent never edits away, minimizes or reinterprets what was reported",
          ],
          cost:
            "Structuring one report and classifying it against a fixed taxonomy is a light, bounded task, so the intake agent runs on the economy/standard tier — there's no need for a premium reasoning model to transcribe and categorize a single incident. Voice reports add a transcription/synthesis step (ElevenLabs/Sarvam) only on the calls that actually come in, so cost tracks real reporting volume.",
          output:
            "The worker's WhatsApp/voice report → the agent drafts a structured record classified against the site taxonomy → the Condition node fast-tracks injuries/high-severity → the safety officer reviews and signs off at the Approval node → a Tool node writes it to the EHS system and the reporter gets a confirmation reference; the draft, the officer's edits and the final record are all logged.",
        },
      },
    ],
  },

  // ============================ CUSTOMS BROKERAGE & TRADE COMPLIANCE
  {
    id: "customs",
    name: "Customs brokerage & trade compliance",
    icon: FileCheck,
    tagline:
      "Collect a complete import/export document set, draft an HS classification and answer clearance questions — while a licensed broker stays the one who classifies and files.",
    recipes: [
      {
        id: "customs-docs",
        title: "Import document collection, completeness gate & HS-code suggestion",
        scenario:
          "For each consignment a broker chases the importer for the invoice, packing list and BOE particulars, checks by hand that the set is complete, then squints at the product description to work out the HS heading — slow, and the missing document only surfaces at the counter.",
        triggerLabel: "Email (docs inbox) or Webhook (upload)",
        steps: [
          { kind: "trigger", title: "Shipment documents arrive", detail: "An Email trigger on the docs inbox (or an upload Webhook) catches the commercial invoice, packing list and BOE particulars for a consignment." },
          { kind: "agent", title: "Extract fields + check completeness", detail: "An intake agent reads each document, extracts the key fields (parties, goods description, values, quantities) and checks the set against the required-document checklist for that trade lane." },
          { kind: "condition", title: "Completeness gate", detail: "A Condition node holds the consignment as WAITING and drafts a precise chase for anything missing; only a complete set proceeds to classification." },
          { kind: "agent", title: "Suggest the HS classification", detail: "A classification-advisor agent proposes a candidate HS code from the goods description, with the tariff heading it reasoned from, a rationale and a confidence — explicitly as a suggestion for a broker to check." },
          { kind: "approval", title: "Licensed broker classifies & files", detail: "An Approval node puts the completed set and the suggested code in front of a licensed customs broker, who confirms or corrects the classification and authorizes filing. The agents never classify with finality and never file." },
          { kind: "tool", title: "File on approval + confirm", detail: "Only after the broker approves, a Tool (MCP) node submits the entry to the customs/EDI portal and the importer is notified with the reference." },
        ],
        connectors: ["Email", "Webhook", "Knowledge base", "Tool (MCP → customs/EDI portal)", "WhatsApp", "Approval / HITL"],
        outcome: "Every consignment reaches the broker as a complete, extracted set with a defensible candidate HS code already drafted — so the broker spends their time deciding, not chasing paper and re-typing fields.",
        impact: "The completeness gate stops incomplete sets from reaching the counter, and the HS suggestion gives the broker a starting point with its reasoning attached; the classification and the filing stay human because they are regulated, licensed decisions — the agents assist, they don't decide.",
        build: {
          agents: 2,
          agentsNote: "1 document-intake/completeness agent + 1 HS-classification advisor. The completeness gate, broker approval and EDI filing are workflow nodes.",
          pattern: "Sequential specialists: intake / completeness → classification advisor → licensed-broker approval",
          prompt:
            "(Classification advisor) Propose a CANDIDATE HS classification for the goods, using ONLY the extracted description and the tariff schedule / classification notes in Knowledge. Return strict JSON {candidate_hs_code, tariff_heading, rationale, confidence 0-1, alternatives[]}. You are ADVISORY ONLY: never present a code as final, never authorize or perform a customs filing, and never state duty as owed. A licensed customs broker must review and decide the classification and the filing — if the description is ambiguous, lower the confidence and say what's missing rather than guessing.",
          agentRoles: [
            { role: "Document intake / completeness", model: "Economy tier — fast, cheap; runs once per consignment on routine extraction", prompt: "Extract the key fields from the invoice / packing list / BOE particulars and check the set against the required-document checklist for the trade lane. Return {parties, goods_description, values, quantities, checklist_status: COMPLETE|WAITING, missing[], chase_message?}. Copy values exactly — never compute, correct or invent a figure; if a document is missing, draft a short chase for only the missing items." },
            { role: "HS-classification advisor", model: "Standard tier — reasons over the tariff schedule when the set is complete", prompt: "Suggest a CANDIDATE HS code from the extracted description and the tariff schedule in Knowledge, with tariff heading, rationale and confidence. Advisory only — never final, never a filing, never a duty determination; ambiguous → lower confidence and flag what's missing for the broker." },
          ],
          mcp: ["Customs / EDI filing portal (e.g. ICEGATE-type submission) — via a Tool (MCP) node, only after broker approval", "Document intake — via the Email / upload Webhook trigger", "WhatsApp — importer chase + confirmation channel"],
          tools: ["document parsing (PDF/scan → text)", "knowledge_search (required-document checklist per trade lane + HS tariff schedule / classification notes)"],
          guardrails: [
            "No final classification and no filing without the licensed-broker Approval — both agents are advisory; a licensed broker confirms the HS code and authorizes the entry",
            "Completeness gate: a consignment is marked COMPLETE only when every checklist item is present, so an incomplete set can never proceed to filing",
            "The advisor never states duty as owed and never presents a code as final — it returns a confidence and its tariff-heading reasoning for a human to check, and lowers confidence on ambiguity instead of guessing",
          ],
          cost:
            "The high-frequency, routine work — extraction and the completeness check — runs on the economy tier, so the per-consignment baseline stays cheap. The standard-tier classification advisor runs only once the set is complete (the gate stops wasted work on incomplete consignments), and a premium model is never required because a licensed human makes the actual call. Cost scales with consignment volume under a per-consignment tool/token cap.",
          output:
            "Documents in → the economy intake agent extracts fields and runs the completeness gate (chasing missing items on WhatsApp) → on a complete set the advisor drafts a candidate HS code with rationale + confidence → the licensed broker confirms/corrects the classification and authorizes filing at the Approval node → a Tool node submits the entry to the customs/EDI portal and confirms to the importer; the draft, the broker's decision and the filing reference are logged.",
        },
      },
      {
        id: "customs-status",
        title: "Duty & clearance status Q&A with milestone updates",
        scenario:
          "Importers and exporters phone or email the broker's ops desk all day — 'is my container cleared yet, what stage is it at, how much duty' — and staff break off to look each one up in the portal manually, then repeat it when the status changes.",
        triggerLabel: "WhatsApp (client query) or Webhook (portal status-change event)",
        steps: [
          { kind: "trigger", title: "Query or status change", detail: "A WhatsApp trigger fires on a client question, or a Webhook fires when the customs/EDI portal posts a milestone/status-change event for a consignment." },
          { kind: "agent", title: "Identify + verify", detail: "An Agent node extracts the job/BOE reference from the message and verifies the requester is party to that consignment before disclosing anything." },
          { kind: "tool", title: "Look up live status", detail: "A Tool (MCP) node fetches the current clearance stage and duty amount from the customs/EDI portal — the answer is read from the portal, not remembered or estimated." },
          { kind: "knowledge", title: "Ground any explanation", detail: "A Knowledge node grounds any procedure or duty-line explanation in the broker's documented process notes, so the reply explains the status without straying into advice the portal doesn't support." },
          { kind: "output", title: "Reply or push the update", detail: "An Output node answers the client on WhatsApp, or — on a status-change event — proactively pushes the new milestone to the parties on the consignment." },
        ],
        connectors: ["WhatsApp", "Webhook", "Knowledge base", "Tool (MCP → customs/EDI portal)"],
        outcome: "Clients get an accurate, on-demand clearance-and-duty answer in their own language, and a proactive nudge each time the consignment hits a milestone — without the ops desk breaking off to look it up.",
        impact: "This is the read-only status lane: it removes the highest-volume, lowest-judgement interruptions from the desk while every figure it quotes is read live from the portal — it reports status, it never makes a classification, duty determination or filing decision, so no regulated call is ever automated here.",
        build: {
          agents: 1,
          agentsNote: "1 status-lookup agent; the verification, portal read and milestone push are workflow nodes.",
          prompt:
            "You are a customs status assistant for a licensed brokerage. From a client message, extract the job/BOE reference and verify the sender is a party to that consignment before revealing anything. Call the status tool and reply in the client's language with the current clearance stage and the duty amount EXACTLY as the portal returns them. Never estimate a duty figure, never predict a clearance date the portal hasn't given, and never make or confirm an HS classification or a filing decision — those belong to the licensed broker. If the lookup fails or the sender can't be matched to the consignment, say so and hand off to the desk.",
          mcp: ["Customs / EDI portal (clearance stage + duty status) — via a Tool (MCP) node", "WhatsApp — query + reply + milestone-push channel", "Portal status-change events — inbound via the Webhook trigger"],
          tools: ["http_request (fetch clearance stage / duty status by reference)", "knowledge_search (documented clearance-procedure notes)"],
          guardrails: [
            "Identity check before disclosing any consignment detail — the sender must be a party to the job/BOE referenced",
            "Every clearance stage and duty figure is read live from the portal — never estimated, never a predicted date the portal hasn't provided",
            "Read-only lane: the agent reports status only and never makes or confirms an HS classification, duty determination or filing — those stay with the licensed broker (see the document-collection recipe's Approval gate)",
          ],
          cost:
            "Identifying a reference and relaying a portal reading is a light lookup, so this runs on the economy tier — there's no reasoning premium for reporting a status. Proactive milestone pushes are event-driven off the portal Webhook, so you pay per real status change rather than polling, and the whole lane stays cheap because the expensive judgement calls are deliberately not made here.",
          output:
            "A client query (or a portal status-change event) → the agent verifies the requester and reads live clearance + duty from the customs/EDI portal via a Tool node → any explanation is grounded in documented procedure notes → an Output node replies on WhatsApp, or pushes the new milestone to the parties on a status change; lookups that fail or can't be matched to the sender are handed to the desk.",
        },
      },
    ],
  },
];
