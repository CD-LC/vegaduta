import type { Industry } from "./recipes-data";
import { BatteryCharging, Radar } from "lucide-react";

// ---------------------------------------------------------------------------
// EV charging networks & Drone services / aerial survey workflow recipes —
// same "inline copy, no i18n keys" precedent and honesty rules as
// recipes-data.tsx. Every recipe is expressed ONLY in VegaDūta's real building
// blocks (Trigger / Agent / Knowledge / Condition / Approval / Output /
// Tool(MCP) / Voice / Device / Loop / Code / Parallel / Supervisor /
// Sub-workflow), so each maps 1:1 to something you can actually build in the
// Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — the OCPP charge-point management
// system (CPMS), energy/DISCOM meter feeds, payment-settlement/reconciliation
// ledgers, the field-service dispatch tool, DGCA-style airspace/permission
// portals, GIS + photogrammetry pipelines, the asset registry (string/module
// and tower maps) — are reached the real way, via an inbound Webhook trigger
// and/or a Tool (MCP) node, and are LABELLED as such, with the actual call the
// node makes spelled out. Only real channels are used (Webhook, WhatsApp, SMS,
// Voice/Call, Email, Slack, Telegram, ElevenLabs, Sarvam, Razorpay UPI).
//
// The Device (Device Control) node is real and is used where it genuinely fits
// — a remote charge-point reset — but anything customer- or safety-affecting
// still passes an Approval node first.
//
// USER-DEFINED TOOLS, described honestly: in this platform a tenant-authored
// tool is a SpEL EXPRESSION evaluated against the tool's JSON input. It is
// sandboxed by construction — no method calls, no constructors, no bean or
// type references, and the grammar has no loop at all. So it is a
// data-transform / formula tool (pull a field, format a string, do arithmetic,
// branch with a ternary or elvis), NOT a scripting engine and NOT a way to
// call an API. Each industry below uses at least one, because it is how an
// operator encodes THEIR OWN thresholds, tariff bands and label formats
// without a developer or a redeploy.
//
// Every consequential decision — dispatching a field engineer, refunding a
// failed session, taking a charger offline, committing to a survey deliverable,
// anything flight-safety or regulatory — goes through an Approval
// (human-in-the-loop) step. Agents PROPOSE, humans execute. Impact lines
// describe the mechanism (why it helps), never invented ROI numbers, and no
// agent count is inflated.
// ---------------------------------------------------------------------------

export const INDUSTRIES_EVDRONE: Industry[] = [
  // ===================================================== EV CHARGING NETWORKS
  {
    id: "ev-charging",
    name: "EV charging networks",
    icon: BatteryCharging,
    tagline:
      "Triage charge-point downtime, classify failed sessions into refund proposals and answer drivers in their own language — with every dispatch, refund and offline decision human-approved.",
    recipes: [
      {
        id: "ev-downtime-triage",
        title: "Charge-point downtime triage (map-reduce, 2 agents)",
        scenario:
          "Today: the CPMS dashboard is open on a second monitor all day, and the network ops engineer scrolls a wall of OCPP alarms — Faulted, comms lost, ground-fault trip, connector-lock failure — most of which clear themselves. Anything that looks real gets typed into a shared Google Sheet called 'Downtime Tracker', colour-coded by whoever is on shift, and the field team is told in a WhatsApp group ('Sec 62 DC-2 down again, anyone nearby?'). Whether a charger actually got reset is remembered, not recorded. In practice the genuinely dead ones are found by a driver at 9pm, not by the wall.",
        triggerLabel: "Webhook (CPMS alarm stream / scheduled open-alarm pull)",
        steps: [
          { kind: "trigger", title: "Alarm batch arrives", detail: "A Webhook trigger receives the alarm batch pushed by the OCPP charge-point management system, or fires on a schedule and pulls the open-alarm list. The CPMS has no first-party connector — it is reached inbound via Webhook and read back via a Tool (MCP) node." },
          { kind: "loop", title: "Map: iterate every alarm", detail: "A Loop node fans a cheap triage agent across each alarm — one focused pass per record, isolated context — so a 400-alarm morning is handled exactly like a 4-alarm one." },
          { kind: "agent", title: "Triager classifies + proposes", detail: "The triage agent returns strict JSON per alarm (fault family, station/EVSE id, whether it is self-clearing noise, and a proposed action: WATCH / REMOTE_RESET / DISPATCH / TAKE_OFFLINE) grounded in the fault-code playbook in Knowledge. It proposes only — it never resets or dispatches." },
          { kind: "tool", title: "Enrich with utilisation + site context", detail: "A Tool (MCP) node pulls the last 7 days of sessions and uptime for each flagged station from the CPMS, and any site-level power event from the energy/DISCOM meter feed — so a whole site down on an upstream trip is not triaged as twelve independent charger faults." },
          { kind: "code", title: "Band the priority with the operator's own rule", detail: "A Code node calls the tenant's user-defined SpEL tool to turn severity plus recent usage into a P1/P2/P3 band. The threshold lives in the tool, so ops can retune what counts as P1 without a developer or a redeploy." },
          { kind: "agent", title: "Reduce: build the shift picture", detail: "A reducer agent aggregates the validated items into one shift briefing: repeat offenders, sites sharing a common upstream cause, the reset candidates and the dispatch candidates ranked by driver impact." },
          { kind: "approval", title: "Ops approves resets, dispatches and offlines", detail: "An Approval node holds every proposed remote reset, engineer dispatch and take-offline decision. Taking a charger offline strands drivers and a dispatch spends a field slot — a human owns both." },
          { kind: "device", title: "Execute the approved remote resets", detail: "For approved items only, a Device (Device Control) node issues the remote reset to the charge point — the platform's real device-control path, still gated behind the Approval above." },
          { kind: "output", title: "Post the briefing + open the tickets", detail: "A Tool (MCP) node raises work orders in the field-service tool for approved dispatches and marks approved stations unavailable in the CPMS; an Output node posts the shift briefing to Slack and Email. Unapproved items stay a watch-list — visible, not lost." },
        ],
        connectors: ["Webhook", "Tool (MCP → CPMS / OCPP)", "Tool (MCP → field-service dispatch)", "Device Control", "Approval / HITL", "Slack", "Email"],
        outcome: "The whole alarm wall is triaged into a ranked shift briefing every run, with self-clearing noise separated from genuinely dead chargers, a written record of what was reset, and a human owning every reset, dispatch and offline.",
        impact: "Map-reduce turns an unbounded manual scroll into cheap per-alarm passes plus one synthesis, and the shift briefing replaces the colour-coded sheet and the WhatsApp group as the record of what was actually done. The engineer's attention moves from sorting alarms to approving the handful of actions that cost money or strand a driver.",
        build: {
          agents: 2,
          agentsNote: "1 triage agent (runs once per alarm via the Loop node) + 1 reducer (runs once). Loop, Code, Approval and Device Control are workflow nodes, not agents.",
          pattern: "Map-reduce: Loop (map) → reducer (reduce) → human approves resets/dispatches → Device Control executes",
          prompt:
            "(Triager) Triage ONE charge-point alarm. Return strict JSON {fault_family (COMMS_LOST|GROUND_FAULT|CONNECTOR_LOCK|OVER_TEMP|EVSE_FAULTED|UNKNOWN), station_id, evse_id, self_clearing (true|false), severity (LOW|MED|HIGH), proposed_action (WATCH|REMOTE_RESET|DISPATCH|TAKE_OFFLINE), reason}. Judge only from the alarm's own fields and the fault-code playbook in the ops Knowledge collection — never invent a fault code, a station id or a repair. A proposed REMOTE_RESET or DISPATCH is a RECOMMENDATION only; you never execute either. If the record is malformed or the code is unknown, set proposed_action=WATCH with reason='needs verification'.",
          agentRoles: [
            { role: "Alarm triager (per alarm)", model: "Economy tier, with a fallback model configured — it runs N times per batch so its model choice dominates total cost, and a provider outage must degrade the triage, never stall the shift", prompt: "Classify ONE OCPP alarm into schema-valid JSON with a proposed action. Null for missing fields, zero invented fault codes or station ids, and REMOTE_RESET/DISPATCH/TAKE_OFFLINE are only ever recommendations." },
            { role: "Shift reducer (once)", model: "Standard tier — reasoning over the whole batch, one call per shift", prompt: "Aggregate the validated per-alarm results into one shift briefing: repeat-offender stations, sites sharing an upstream power cause, the reset candidates and the dispatch candidates ranked by driver impact. Use only the provided items and the enrichment data — cite real session counts, never estimate uptime." },
          ],
          mcp: [
            "OCPP charge-point management (CPMS) — via Tool (MCP): pull the open-alarm list, fetch connector status and 7-day session/uptime history per station, and write the availability state for approved offlines. Inbound alarms arrive by Webhook. No first-party connector.",
            "Field-service dispatch / work-order tool — via Tool (MCP): create a work order for an approved dispatch with the station id, fault family and site access notes, and read back its status. No first-party connector.",
            "Energy / DISCOM meter feed — via Tool (MCP): fetch site-level power events in the alarm window, so an upstream trip is identified as one cause rather than twelve faults. No first-party connector.",
            "Slack + Email — first-party channels for the shift briefing.",
          ],
          tools: [
            "knowledge_search (scoped to the OCPP fault-code playbook + per-site runbooks collection)",
            "http_request (pull alarms and session history, write availability, open work orders)",
            "user-defined tool `alarm_priority_band` (SpEL): input.severity == 'HIGH' && input.sessionsLast7d > 40 ? 'P1' : (input.severity == 'HIGH' || input.sessionsLast7d > 40 ? 'P2' : 'P3') — a pure formula over the tool's JSON input, no API calls, no loops. It matters because 'what counts as P1' is an operator-specific judgement about traffic and SLA, and encoding it in a tenant-authored expression means ops can retune the thresholds themselves without a developer or a new deployment.",
          ],
          guardrails: [
            "The agents only propose — every remote reset, engineer dispatch and take-offline passes the Approval node before the Device Control node or the CPMS is touched",
            "Schema validation on each triage output — a malformed or unknown-code alarm is dropped to WATCH, never auto-reset and never silently dispatched",
            "Idempotent per alarm id and per station, so a replayed alarm batch cannot double-dispatch an engineer or reset a charger twice",
            "Remote reset is only ever offered for fault families the playbook lists as reset-safe; anything with a ground-fault or over-temp signature is forced down the DISPATCH path for a human, never reset remotely",
            "Unapproved items become a persisted watch-list rather than disappearing — the failure mode of the old spreadsheet was silent loss, not wrong decisions",
          ],
          cost:
            "Cost is linear and predictable: N × (economy-tier, hard-capped triager) + ONE standard-tier reduce. Because the triager runs across the entire alarm stream — the highest-volume step by far — it MUST sit on the economy tier; that single choice is the dominant cost lever. Configure a fallback model on it so a provider outage degrades the triage instead of stalling the shift. The reducer's one call per shift is where the reasoning budget belongs. Run the map in Parallel batches for wall-clock speed without changing per-item cost.",
          output:
            "Loop maps the cheap triager over every alarm → a Tool (MCP) node enriches the flagged stations with session history and site power events → the Code node bands priority with the operator's own SpEL rule → the reducer synthesizes one shift briefing → ops approves at the Approval node → a Device Control node fires only the approved remote resets, a Tool (MCP) node opens the approved work orders and sets approved stations unavailable in the CPMS, and an Output node posts the briefing to Slack and Email.",
        },
      },
      {
        id: "ev-failed-session-refund",
        title: "Failed charging session → grounded refund proposal (2 agents)",
        scenario:
          "Today: a driver pays, plugs in, gets 0.4 kWh, and the session dies. They message the support number on the charger. The support executive opens four things — the CPMS session view for the meter start/stop, the alarm history for that connector around that timestamp, the payment gateway dashboard for the capture, and a Google Sheet where refunds are logged before someone in finance actions them in a batch on Friday. Deciding one ₹280 refund takes fifteen minutes of tab-switching, so the queue is triaged by who complained loudest, and a good share end up as bank disputes instead.",
        triggerLabel: "Webhook (session-ended event) / WhatsApp (driver complaint)",
        steps: [
          { kind: "trigger", title: "Failed session detected or reported", detail: "A Webhook from the CPMS fires on a session that ended abnormally (zero/near-zero energy, error stop reason), or a WhatsApp/SMS message from the driver starts the same flow — so the network finds these before the driver does." },
          { kind: "tool", title: "Assemble the evidence in one pass", detail: "Tool (MCP) nodes pull the session record and meter start/stop values from the CPMS, the connector's alarm history in a ±15-minute window, and the payment capture and settlement state from the payment ledger. This is the four-tabs step, done once, deterministically." },
          { kind: "agent", title: "Fault-attribution agent", detail: "An Agent node classifies the session against the refund policy in a scoped Knowledge collection: network fault (charger faulted, comms lost, mid-session abort), vehicle-side stop, or driver-initiated stop — citing the exact fields it used, with an explicit UNCLEAR verdict when the evidence does not decide it." },
          { kind: "condition", title: "Evidence sufficient?", detail: "A Condition node branches: a clean, evidenced verdict flows to the refund proposal; UNCLEAR or missing meter values route straight to a human queue rather than being guessed into a decision." },
          { kind: "code", title: "Band the remedy with the operator's own formula", detail: "A Code node calls the tenant's user-defined SpEL tool to turn energy-delivered-vs-promised into a refund band. The operator's own commercial policy lives in that expression, not in a prompt — so it is auditable and identical on every single session." },
          { kind: "agent", title: "Resolution drafter", detail: "A second agent drafts the proposed remedy — full refund, partial (energy actually delivered vs charged, shown arithmetically), or no refund with a plain-language reason — plus the driver-facing message in the driver's own language via Sarvam." },
          { kind: "approval", title: "Support lead approves the refund", detail: "An Approval node holds the refund amount and the outgoing message. A refund moves real money and a 'no refund' reply is a customer commitment — both are human decisions, never agent-executed." },
          { kind: "output", title: "Issue + reply", detail: "On approval, the refund is issued through Razorpay (UPI) for the approved amount, a Tool (MCP) node stamps the resolution on the session record, and an Output node replies to the driver on WhatsApp/SMS in their language." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS", "Razorpay (UPI)", "Sarvam (Indian languages)", "Tool (MCP → CPMS)", "Tool (MCP → payment ledger)", "Approval / HITL"],
        outcome: "Every failed session arrives at the support lead as an evidenced verdict with a banded amount and a ready-to-send reply in the driver's language — decided in one place instead of four browser tabs and a Friday spreadsheet.",
        impact: "The slow part of a refund was never the decision, it was assembling meter values, alarm history and the payment record by hand. Automating the assembly, banding the amount with the operator's own formula, and keeping the money movement human-approved is what turns a Friday batch into a same-day answer — the lead still owns every rupee that moves.",
        build: {
          agents: 2,
          agentsNote: "1 fault-attribution agent + 1 resolution drafter. The evidence pull, the sufficiency branch, the refund banding (Code + SpEL tool), the approval and the refund execution are workflow nodes.",
          pattern: "Evidence assembly → attribution agent → Condition gate → operator formula → drafter → human approves the money",
          prompt:
            "(Fault attribution) You classify ONE failed EV charging session. Inputs: session record {start, stop, stop_reason, meter_start, meter_stop, energy_kwh, amount_charged}, the connector's alarm history in a ±15-minute window, and the payment capture record. Return strict JSON {verdict (NETWORK_FAULT|VEHICLE_SIDE|DRIVER_STOPPED|UNCLEAR), evidence[] (the exact fields/alarms you relied on), energy_delivered_kwh, amount_charged}. Apply ONLY the refund policy in the Knowledge collection you are scoped to. Never infer a meter value that is absent — if meter values are missing or the alarms do not decide it, return UNCLEAR and stop. You do not decide, band or issue a refund; you produce the evidenced verdict a human will act on.",
          agentRoles: [
            { role: "Fault attribution (per session)", model: "Economy tier with a fallback model — this is the high-volume step (every abnormal session runs it) and it reads structured fields, not prose, so a small model genuinely suffices", prompt: "Classify one failed session into NETWORK_FAULT / VEHICLE_SIDE / DRIVER_STOPPED / UNCLEAR, citing the exact evidence fields. Missing meter values ⇒ UNCLEAR, never a guessed verdict." },
            { role: "Resolution drafter (decided verdicts only)", model: "Standard tier — policy reasoning plus customer-facing writing, Sarvam-backed for Indian languages; it never sees the UNCLEAR cases, which is the second cost lever", prompt: "Given a decided verdict and the refund band from the operator's own SpEL tool, draft the remedy — showing the partial-refund arithmetic (energy delivered vs amount charged) — and the driver message in their language. Present it as a proposal for approval; never say the refund has been issued." },
          ],
          mcp: [
            "OCPP CPMS — via Tool (MCP): fetch the session record with meter start/stop, pull the connector's alarm history for a ±15-minute window around the failure, and stamp the approved resolution back onto the session. Inbound session-ended events arrive by Webhook. No first-party connector.",
            "Payment settlement / reconciliation ledger — via Tool (MCP): look up the capture and settlement state for the session's payment reference, so a refund is never proposed against an uncaptured or already-refunded charge. No first-party connector.",
            "Razorpay (UPI) — real first-party connector: issues ONLY the approved refund amount, after the Approval node.",
            "WhatsApp / SMS — first-party driver channels for the complaint and the reply.",
            "Sarvam — first-party Indian-language drafting for the driver reply.",
          ],
          tools: [
            "knowledge_search (scoped to the refund + fault-attribution policy collection)",
            "http_request (pull session, meter, alarm and payment records; stamp the resolution)",
            "user-defined tool `session_refund_band` (SpEL): input.kwhDelivered == 0 ? 'FULL_REFUND' : (input.kwhDelivered < input.kwhPromised * 0.5 ? 'PARTIAL' : 'NONE') — a formula over the tool's JSON input only: no API calls, no loops, no code execution. It matters because the refund threshold is a commercial policy the operator owns, not a model judgement; putting it in a tenant-authored expression makes it identical on every session, visible to an auditor, and changeable by the support lead without a developer.",
            "user-defined tool `driver_locale` (SpEL): input.preferredLanguage ?: (input.simCircle != null ? input.simCircle : 'en') — an elvis fallback that picks the reply language deterministically instead of asking the model to guess it.",
          ],
          guardrails: [
            "No refund is ever issued by an agent — the amount and the outgoing message both pass the Approval node, and only then does the Razorpay UPI refund execute",
            "UNCLEAR is a first-class verdict: missing meter values or inconclusive alarms route to a human queue instead of being reasoned into a refund",
            "The refund band comes from the tenant's SpEL formula, not from the model — the model explains the number, it does not choose it",
            "Partial-refund arithmetic is shown explicitly (energy delivered vs amount charged) so the approver can check the figure rather than trust it",
            "Idempotent per session id and per payment reference — a replayed session-ended webhook or a duplicate driver message can never trigger a second refund",
            "The agent may never state or imply to a driver that a refund has been issued; only the post-approval Output node says that",
          ],
          cost:
            "Tier by volume: the attribution agent runs on EVERY abnormal session, so it sits on the economy tier with a hard token cap and a fallback model for provider outages. The drafter runs only on sessions that reached a decided verdict, so the standard tier is bought for a fraction of the traffic — and UNCLEAR cases never reach it at all. The refund amount itself costs nothing to compute, because it is a SpEL formula rather than an inference call; that is deliberate, since a policy threshold should be deterministic and auditable, not sampled.",
          output:
            "The Tool (MCP) nodes assemble session, alarm and payment evidence in one pass → the economy-tier agent produces an evidenced verdict → the Condition node routes UNCLEAR to humans → the Code node bands the remedy with the operator's own SpEL formula → the standard-tier drafter proposes the amount and the driver message → the support lead approves at the Approval node → Razorpay issues the approved refund, a Tool (MCP) node stamps the session record, and an Output node replies on WhatsApp/SMS in the driver's language.",
        },
      },
      {
        id: "ev-auth-failure-support",
        title: "RFID / app authorisation failures — first-line driver support in their language",
        scenario:
          "Today: a driver taps their RFID card at 10pm and the screen says 'Authorization failed'. They call the helpline printed on the unit. It rings into a shared mobile handled by whoever is free, usually in English or Hindi only, and that person has no idea which of five different things went wrong — expired tag, tag never assigned to the account, a roaming/eMSP token the network rejected, a failed pre-auth on the card, or the charger itself being offline — because checking means logging into the CPMS on a laptop they are not sitting at. So the answer is 'sir, please try another charger', and the driver is standing at a kerb in the dark deciding whether to trust this network again.",
        triggerLabel: "WhatsApp / Voice call / SMS (driver stuck at a charger)",
        steps: [
          { kind: "trigger", title: "Driver reaches out", detail: "A WhatsApp, SMS or inbound Voice/Call trigger starts the session — the driver typically reads out the station id printed on the unit, or shares their location." },
          { kind: "voice", title: "Voice handling in the driver's language", detail: "On the call path, a Voice node handles the conversation with ElevenLabs for natural speech and Sarvam for Indian-language understanding, so a driver in Coimbatore or Nagpur is not forced into English at a kerbside at night." },
          { kind: "tool", title: "Look up the real reason", detail: "Tool (MCP) nodes query the CPMS for that station's live connector status and its last authorisation attempts, and the token/account registry for the tag or app-account state — the laptop step, done in two seconds inside the conversation." },
          { kind: "agent", title: "Diagnose + guide", detail: "An Agent node maps the evidence to a documented cause in a scoped Knowledge collection (expired tag, unassigned tag, roaming token rejected, pre-auth failed, charger offline) and gives the driver the specific next step — including whether another connector at the same site is genuinely free right now." },
          { kind: "condition", title: "Self-serve or escalate?", detail: "A Condition node branches: causes with a documented driver-side fix are handled in the conversation; anything needing a network-side change (re-issuing a tag, releasing a stuck pre-auth, marking the charger down) routes onward." },
          { kind: "approval", title: "Human owns any account or network change", detail: "An Approval node holds every network-side action — re-issuing or re-binding an RFID tag, releasing a held pre-auth, flagging the station as down. These touch a driver's account, their money, or the network's availability state." },
          { kind: "output", title: "Resolve or hand over", detail: "An Output node confirms the outcome and, when the fix needs a human, hands the full diagnosis and transcript to the support queue — so the driver never re-explains it to a second person." },
        ],
        connectors: ["WhatsApp", "SMS", "Voice / Call", "ElevenLabs (voice)", "Sarvam (Indian languages)", "Tool (MCP → CPMS)", "Tool (MCP → token / account registry)", "Approval / HITL"],
        outcome: "A stuck driver gets the actual reason and one concrete next step in their own language within the first exchange, at any hour — and anything that touches their account or the network's state reaches a human with the diagnosis already done.",
        impact: "The failure mode here is information, not effort: 'Authorization failed' hides five causes that need five different actions, and the person answering the shared mobile cannot see any of them. Grounding the answer in the live station and token state, spoken in the driver's own language, is what turns 'try another charger' into a first-exchange resolution.",
        build: {
          agents: 1,
          agentsNote: "1 diagnosis agent. The voice handling, the self-serve/escalate branch, the account-change approval and the queue handover are workflow nodes.",
          prompt:
            "You are first-line support for {{network}}'s EV charging drivers. A driver is standing at a charge point and authorisation failed. Using ONLY the live station status, the last authorisation attempts and the token/account state provided to you, identify the cause from the documented list in your Knowledge collection (TAG_EXPIRED | TAG_UNASSIGNED | ROAMING_TOKEN_REJECTED | PREAUTH_FAILED | CHARGER_OFFLINE | UNKNOWN) and give ONE clear next step. Reply in the driver's language. Never invent a station id, a tariff, a tag number or an availability claim; if the lookup did not return it, say you are checking and hand over. You may NOT re-issue a tag, release a pre-auth, refund anything or change a station's state — propose it and the workflow routes it to a human. If the cause is UNKNOWN, say so plainly and hand over rather than guessing.",
          agentRoles: [
            { role: "Driver-support diagnosis agent", model: "Standard tier with a fallback model — it is live, in-conversation and multilingual via Sarvam (ElevenLabs handles speech on the call path); a fallback matters more here than anywhere else, because the driver is standing at a kerb and a provider outage cannot mean silence", prompt: "Map live station + token evidence to one documented cause, give one concrete next step in the driver's language, and escalate anything requiring a network-side change. Never claim availability, tariffs or account changes that were not returned by the lookup." },
          ],
          mcp: [
            "OCPP CPMS — via Tool (MCP): fetch live connector status for the station the driver names, list the last authorisation attempts for that connector with their reject reasons, and check sibling connectors at the same site for genuine current availability. No first-party connector.",
            "RFID token / driver-account registry — via Tool (MCP): resolve the tag or app account to its state (valid, expired, unassigned, roaming token) and its bound account, read-only from the agent's side. No first-party connector.",
            "WhatsApp / SMS / Voice — first-party driver channels.",
            "ElevenLabs — first-party voice output on the call path.",
            "Sarvam — first-party Indian-language understanding and reply.",
          ],
          tools: [
            "knowledge_search (scoped to the authorisation-failure playbook + per-site tariff and access FAQ)",
            "http_request (station status, authorisation attempts, token state)",
            "user-defined tool `auth_next_step_code` (SpEL): input.tagState == 'EXPIRED' ? 'RENEW_TAG' : (input.tagState == 'UNASSIGNED' ? 'BIND_TAG' : (input.connectorStatus == 'Faulted' || input.connectorStatus == 'Unavailable' ? 'USE_ALT_CONNECTOR' : (input.preauthFailed ? 'RETRY_PAYMENT' : 'ESCALATE'))) — a pure decision formula over the tool's JSON input: no API calls, no loops. It matters because the mapping from state to advice is the operator's own support policy; encoding it as a tenant-authored expression means the same state always yields the same instruction, the agent explains it rather than inventing it, and support can change the policy without touching a prompt or shipping a release.",
          ],
          guardrails: [
            "The agent diagnoses and guides only — re-issuing or re-binding a tag, releasing a pre-auth, issuing any credit, or marking a station down all pass the Approval node",
            "Every factual claim (station status, free connectors, tariff, tag state) must come from the live lookup; no availability or price is ever asserted from memory",
            "The next-step instruction comes from the tenant's SpEL decision tool, so identical states never produce differently-worded advice on different calls",
            "UNKNOWN is an allowed answer and triggers a handover with the full transcript — the agent never guesses a cause to close the conversation",
            "The escalation handover carries the diagnosis and transcript, so the driver never repeats themselves to the human agent",
          ],
          cost:
            "This is a live, one-at-a-time conversation rather than a batch, so there is no per-item multiplier — but it IS the driver-facing surface, so the standard tier is the right floor: a cheap model that hallucinates a tariff or a free connector costs far more than the model saving. Keep cost down structurally instead — the Tool (MCP) lookups and the SpEL decision tool do the factual and policy work so the prompt stays short, and documented self-serve causes close in the conversation without ever reaching a human. Configure a fallback model so the helpline degrades gracefully rather than going quiet.",
          output:
            "The Voice/WhatsApp trigger opens the session (ElevenLabs + Sarvam on the call path) → Tool (MCP) nodes fetch live station and token state → the SpEL tool maps that state to the operator's own next step → the agent explains it in the driver's language → the Condition node splits self-serve from network-side → any account or availability change waits at the Approval node → an Output node confirms the resolution or hands the full diagnosis and transcript to the support queue.",
        },
      },
      {
        id: "ev-tariff-queue-comms",
        title: "Tariff change & site-status broadcast, approved before it reaches a single driver",
        scenario:
          "Today: a site goes down for a transformer upgrade on Saturday, or the tariff changes at midnight. Someone in marketing exports a driver list to Excel, tries to filter it down to people who have actually used that site, gives up and sends to everyone. The Hindi and Tamil versions are done by whoever in the office speaks them, pasted into the bulk-SMS tool by hand, and one of them says ₹18/kWh where the record says ₹18.50 — which becomes a pricing argument on Monday that support cannot win, because the message went out over the company's name.",
        triggerLabel: "Webhook (tariff change / planned outage from the CPMS or ops tool)",
        steps: [
          { kind: "trigger", title: "Change is published", detail: "A Webhook fires when a tariff revision or a planned site outage is entered in the CPMS/ops tool, carrying the exact rate, the effective timestamps and the affected site list — one source of truth instead of a forwarded email." },
          { kind: "tool", title: "Build the affected-driver audience", detail: "A Tool (MCP) node queries the CPMS/CRM for drivers who actually charged at the affected sites in the recent window, with their preferred language — so the audience is a query result, not an Excel filter someone gave up on." },
          { kind: "agent", title: "Draft the message per language", detail: "An Agent node drafts the notice for each language via Sarvam, restating the exact effective date, the exact new rate or outage window, and the nearest alternative sites — copying every figure verbatim from the change record, never re-deriving it." },
          { kind: "code", title: "Verify the numbers survived translation", detail: "A Code node re-checks each drafted variant against the source record using the operator's own SpEL formatter: the rate, the effective timestamp and the site list must match exactly across every language, or the draft is rejected before a human ever sees it. This is the ₹18-vs-₹18.50 bug, caught by arithmetic instead of by a customer." },
          { kind: "approval", title: "Ops + comms sign off the broadcast", detail: "An Approval node holds the final audience count and every language variant. A tariff notice is a pricing commitment and a bad broadcast cannot be recalled — a human approves the exact text that goes out." },
          { kind: "output", title: "Send on the driver's channel", detail: "An Output node delivers the approved variant on WhatsApp or SMS in each driver's preferred language, with the site name and the effective time." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS", "Sarvam (Indian languages)", "Tool (MCP → CPMS / driver CRM)", "Approval / HITL"],
        outcome: "Only the drivers who actually use an affected site hear about it, in their own language, with the rate and the window matching the source record exactly — and no notice leaves without a human approving the final text.",
        impact: "Two things go wrong with these broadcasts: the wrong audience and a number that drifted during hand-translation. Deriving the audience from real session history, and machine-checking every figure against the source record before the human sees it, is what turns the sign-off into a quick yes instead of a proofreading job in four languages.",
        build: {
          agents: 1,
          agentsNote: "1 drafting agent producing all language variants. The audience build, the numeric verification (Code node + SpEL formatter), the broadcast approval and the send are workflow nodes.",
          prompt:
            "You draft driver notices for {{network}}. Given a change record {type (TARIFF|PLANNED_OUTAGE), sites[], effective_from, effective_to, old_rate, new_rate, reason} and a target language, write a short notice for WhatsApp/SMS. Copy every number, date and site name VERBATIM from the change record — never round, re-derive, convert or restate a rate in your own words. Name alternative sites only from the list provided. No apology boilerplate, no invented compensation, no promise of a credit or discount. Output {language, body, numbers_used[]} so the workflow can verify every figure against the source. This is a draft for human approval; never present it as already sent.",
          agentRoles: [
            { role: "Multilingual notice drafter", model: "Standard tier with a fallback model, Sarvam-backed for Indian languages — it runs once per LANGUAGE variant, not per driver, so its cost never scales with audience size", prompt: "Produce one faithful notice per language with every figure copied verbatim from the change record, list the numbers used for verification, and never invent compensation or alternative sites." },
          ],
          mcp: [
            "OCPP CPMS / ops tool — via Tool (MCP): read the full tariff-revision or planned-outage record (rate, effective window, affected site ids, reason) that the Webhook announced, so the drafter works from the record rather than the notification. No first-party connector.",
            "Driver CRM / session history — via Tool (MCP): query drivers with a session at the affected sites inside the recent window and return their contact channel and preferred language, producing the audience as a query result. No first-party connector.",
            "WhatsApp / SMS — first-party driver channels for the approved send.",
            "Sarvam — first-party Indian-language drafting.",
          ],
          tools: [
            "knowledge_search (scoped to the comms policy + approved notice templates collection)",
            "http_request (pull the change record and the affected-driver audience)",
            "user-defined tool `tariff_line` (SpEL): 'Rate at ' + input.siteName + ': ' + input.newRate + ' per kWh (was ' + input.oldRate + ') from ' + input.effectiveFrom — string concatenation over the tool's JSON input, no method calls and no loops. It matters because this one line is the entire legal substance of the notice: generating it from the record with the operator's own format, and diffing it against what the model wrote in each language, is how a translated draft is proven to still carry the right number.",
            "user-defined tool `rate_delta_direction` (SpEL): input.newRate > input.oldRate ? 'INCREASE' : (input.newRate < input.oldRate ? 'DECREASE' : 'UNCHANGED') — so the notice's framing is derived from arithmetic rather than inferred by the model.",
          ],
          guardrails: [
            "Nothing sends without the Approval node — the final audience count and every language variant are approved as the exact text that will go out",
            "The Code node hard-verifies each variant's rate, effective timestamp and site list against the source record via the SpEL formatter; a mismatch in any language blocks the WHOLE broadcast rather than sending the good ones",
            "The audience is derived from real session history at the affected sites — no whole-base blasts, and drivers with no relationship to the site are never messaged",
            "The agent may not invent compensation, credits, discounts or alternative sites not present in the source record",
            "Idempotent per change-record id, so a resent webhook cannot broadcast the same notice twice",
          ],
          cost:
            "Cost here scales with LANGUAGES, not with drivers — the agent drafts one variant per language and the Output node fans the approved text to the whole audience with no further model calls, which is the entire cost design and is what keeps a standard-tier model affordable for a large base. The verification is a deterministic SpEL formula plus a Code-node diff and costs nothing; using a model to check the numbers would be both costlier and less trustworthy. A fallback model keeps a time-boxed midnight tariff notice from missing its window on a provider outage.",
          output:
            "The Webhook change record and the Tool (MCP) audience query feed the drafter → one variant per language → the Code node rebuilds the tariff line with the operator's SpEL formatter and blocks the broadcast on any mismatch → ops and comms approve the exact text at the Approval node → an Output node sends on WhatsApp/SMS in each driver's preferred language.",
        },
      },
    ],
  },

  // ============================================ DRONE SERVICES & AERIAL SURVEY
  {
    id: "drone-survey",
    name: "Drone services & aerial survey",
    icon: Radar,
    tagline:
      "Pre-check permissions, hold weather windows, pre-filter thousands of frames and turn flight logs into deliverables — with every flight-safety, regulatory and client commitment human-signed.",
    recipes: [
      {
        id: "drone-permission-precheck",
        title: "Pre-flight permission & airspace pre-check (agents assemble, the pilot signs)",
        scenario:
          "Today: the crew is loading the van at 5am and the pre-check is a WhatsApp exchange — 'permission still valid na?', 'ha, checked last month'. The zone check was done on a phone browser standing in the yard, the airframe's insurance and the pilot's currency dates live in a Google Sheet that one person maintains, and the client's own site conditions ('no flying over the tank farm, escort required past gate 3') are buried in an email chain from six weeks ago. Nothing is written down for this specific flight. It is fine every time until the one time it is not, and then there is no record of what anyone actually checked.",
        triggerLabel: "Webhook (mission scheduled) / Email (client booking confirmed)",
        steps: [
          { kind: "trigger", title: "Mission is scheduled", detail: "A Webhook from the scheduling tool (or an Email confirming a client booking) fires with the site coordinates, planned date/time, airframe and assigned pilot — so the pre-check starts when the mission is booked, not in the yard at 5am." },
          { kind: "parallel", title: "Run the checks side by side", detail: "A Parallel node runs the independent lookups at once — airspace/zone classification, permission and airframe/pilot validity, and the client's own site access conditions — so the pre-check is one wait, not three sequential ones." },
          { kind: "tool", title: "Pull the regulatory + registry facts", detail: "Tool (MCP) nodes query the airspace/permission portal and the airframe/pilot registry. There is NO first-party connector for DGCA-style permission portals, airspace services or registries — each is reached over MCP or an inbound Webhook, and the workflow treats their output as evidence to show a human, never as clearance." },
          { kind: "knowledge", title: "Apply the operator's own SOP", detail: "A Knowledge node scoped to your operations manual grounds the check in airframe-specific limits, client-site conditions and the internal rules that are stricter than the baseline regulation — the email chain, made searchable and current." },
          { kind: "code", title: "Band the expiries deterministically", detail: "A Code node runs the operator's user-defined SpEL tool over the returned validity fields to label each as EXPIRED / RENEW_SOON / VALID. Dates are arithmetic, not a judgement call, and this keeps them out of the model entirely." },
          { kind: "agent", title: "Assemble the pre-flight brief", detail: "An Agent node assembles the evidence into a single brief: each check with its source and timestamp, an explicit list of anything unverified, and the blockers first — never a clearance verdict of its own." },
          { kind: "condition", title: "Anything unverified or blocking?", detail: "A Condition node routes any missing, stale or blocking check to the top of the brief; an unverified item is never quietly dropped or treated as passed." },
          { kind: "approval", title: "Pilot-in-command signs off", detail: "An Approval node holds the go/no-go. This is a flight-safety and regulatory decision — the pilot-in-command owns it, with the assembled brief in front of them. The workflow never issues clearance." },
          { kind: "output", title: "Attach the signed brief to the mission", detail: "On sign-off, an Output node files the brief against the mission record and sends it to the crew on Slack, WhatsApp and Email — so what was checked, when, from which source and by whom is auditable afterwards." },
        ],
        connectors: ["Webhook", "Email", "Slack", "WhatsApp", "Knowledge base", "Tool (MCP → airspace / permission portal)", "Tool (MCP → airframe & pilot registry)", "Approval / HITL"],
        outcome: "Every mission carries a timestamped, source-cited pre-flight brief with an explicit unverified list, signed off by the pilot-in-command before the van leaves — instead of a WhatsApp 'ha, checked'.",
        impact: "The risk in a pre-check is not the flying, it is the checks quietly skipped under time pressure and the ones that silently went stale in someone's spreadsheet. Assembling every source with its timestamp, computing expiries as arithmetic, and forcing unverified items to the top instead of letting them vanish is what makes the pilot's sign-off an informed one — and leaves a record. The decision itself never leaves the pilot.",
        build: {
          agents: 1,
          agentsNote: "1 brief-assembly agent. The parallel lookups, the expiry banding (Code + SpEL tool), the blocking-item branch and the pilot sign-off are workflow nodes — deliberately, because the go/no-go must not sit inside a model.",
          pattern: "Parallel evidence gathering → deterministic expiry banding → assembly agent → pilot-in-command approval",
          prompt:
            "You assemble a PRE-FLIGHT BRIEF for {{operator}}. You do NOT grant, imply or withhold clearance — a pilot-in-command decides that. Inputs: mission {site_coords, datetime, airframe, pilot}, airspace/zone lookup results, permission + airframe + pilot validity records with their computed expiry bands, client site conditions, and the operations manual in your Knowledge collection. Return {checks[{name, status (PASS|FAIL|UNVERIFIED), source, checked_at, note}], blockers[], unverified[], summary}. Mark UNVERIFIED — never PASS — whenever a lookup failed, returned nothing, or returned data older than the manual's freshness rule. Never infer a zone classification, a permission validity or an expiry date that was not returned, and never recompute an expiry band yourself. Never write 'cleared', 'approved to fly' or any equivalent anywhere in your output.",
          agentRoles: [
            { role: "Pre-flight brief assembler", model: "Standard tier with a fallback model — one call per mission, low volume, and faithful citation of regulatory evidence matters far more than the tier saving; the fallback exists so a provider outage never becomes a reason to fly on an unwritten check", prompt: "Assemble every check with its source and timestamp, force failed or stale lookups to UNVERIFIED, surface blockers first, and never state or imply clearance." },
          ],
          mcp: [
            "Airspace / zone + permission portal (DGCA-style) — via Tool (MCP): classify the site coordinates against restricted/controlled zones for the planned window and fetch the operator's permission record with its validity dates. Output is treated as EVIDENCE for a human, never as clearance. No first-party connector.",
            "Airframe + pilot registry / currency records — via Tool (MCP): resolve the assigned airframe to its registration, insurance and maintenance validity, and the assigned pilot to their licence and currency dates. No first-party connector.",
            "Scheduling tool — inbound Webhook announces the mission; write-back via Tool (MCP): attach the signed brief to the mission record with the approver's identity and timestamp. No first-party connector.",
            "Slack / WhatsApp / Email — first-party channels for crew distribution of the signed brief.",
          ],
          tools: [
            "knowledge_search (scoped to the operations manual, airframe limits and per-client site-condition collection)",
            "http_request (airspace/zone lookup, permission and currency validity, mission write-back)",
            "user-defined tool `validity_band` (SpEL): input.daysToExpiry == null ? 'UNVERIFIED' : (input.daysToExpiry <= 0 ? 'EXPIRED' : (input.daysToExpiry < input.renewWindowDays ? 'RENEW_SOON' : 'VALID')) — a formula over the tool's JSON input: no API calls, no loops, no code. It matters because an expiry is arithmetic, not an opinion, and this is exactly the fact a model should never be trusted to eyeball. The operator sets their own renewal window in the expression and changes it without a developer, and a null input yields UNVERIFIED rather than a false pass.",
          ],
          guardrails: [
            "The workflow NEVER issues clearance — the go/no-go is the pilot-in-command's decision at the Approval node, and the agent is prompt-forbidden from writing 'cleared' or any equivalent",
            "A failed, empty or stale lookup becomes UNVERIFIED, never PASS — unverified items are surfaced at the top of the brief and can never be silently dropped",
            "Expiry bands are computed by the tenant's SpEL formula, not read off by the model; a null or missing date yields UNVERIFIED by construction",
            "Every check carries its source and a checked_at timestamp, so the brief is auditable after the flight rather than a snapshot of nothing",
            "Where the operator's own manual is stricter than the baseline regulation, the manual wins — the agent grounds in the scoped Knowledge collection, not in general knowledge",
            "The signed brief is attached to the mission record with the approver's identity, giving a defensible trail of what was checked and who accepted it",
          ],
          cost:
            "One standard-tier call per mission and no batch multiplier — volume is bounded by how many flights you actually fly, making this among the cheapest recipes here per run. Do not cheapen it further: the whole value is faithful citation of regulatory evidence, and a hallucinated zone classification is not a cost saving. The Parallel node buys wall-clock speed at zero extra model cost because the lookups are tool calls rather than agent turns, and the expiry arithmetic is a free SpEL evaluation rather than an inference.",
          output:
            "The Parallel node fires the airspace, registry and site-condition lookups at once → the Code node bands every validity date with the operator's SpEL formula → the agent assembles a source-cited brief with blockers and unverified items first → the Condition node keeps anything unverified visible → the pilot-in-command signs off at the Approval node → an Output node files the signed brief against the mission record and pushes it to the crew on Slack, WhatsApp and Email.",
        },
      },
      {
        id: "drone-weather-window",
        title: "Weather-window watch → reschedule proposal, client-confirmed",
        scenario:
          "Today: the survey is booked for Thursday in a shared Google Calendar. Nobody looks at the forecast until Wednesday night, when the ops coordinator opens a weather app on their phone, squints at gust figures against a wind limit they half-remember, and starts calling — the pilot, the client's site contact, then the crew WhatsApp group to move the 4am departure. Half the time the decision is made too late, the crew drives three hours, stands in the wind for two, and drives back. The client hears about it from the crew, in the field, which is the worst possible place to renegotiate a date.",
        triggerLabel: "Webhook (scheduled watch on upcoming missions)",
        steps: [
          { kind: "trigger", title: "Watch the booked missions", detail: "A scheduled Webhook trigger runs against the upcoming mission list — so the forecast is checked every cycle from booking onward, not once, panicking, the night before." },
          { kind: "loop", title: "Map: check each mission's window", detail: "A Loop node runs a cheap agent per upcoming mission, so watching forty bookings costs the same per item as watching four." },
          { kind: "code", title: "Compare against the airframe's real limits", detail: "A Code node runs the operator's user-defined SpEL tool over the forecast and the airframe's documented gust limit to produce a hard flyability band — the number the coordinator was half-remembering on their phone, made deterministic." },
          { kind: "agent", title: "Assess flyability with the full picture", detail: "The per-mission agent takes the computed band plus visibility, precipitation, cloud base and light window against the limits in the airframe Knowledge collection, returning FLYABLE / MARGINAL / NOT_FLYABLE with the specific figures it used." },
          { kind: "condition", title: "Marginal or worse?", detail: "A Condition node lets FLYABLE missions pass silently — no notification noise, so the alerts stay credible — and pushes MARGINAL and NOT_FLYABLE onward for a reschedule proposal." },
          { kind: "agent", title: "Propose the next viable window", detail: "A second agent proposes alternative slots that satisfy the forecast limits, the site's access hours and crew availability, ranked, each with the reason it clears the limits." },
          { kind: "approval", title: "Ops approves before the client hears anything", detail: "An Approval node holds the reschedule proposal. Moving a booked mission is a client commitment and a crew-cost decision — ops approves the option before a single message goes out, so the client is never renegotiating with a crew standing in a field." },
          { kind: "output", title: "Offer it to the client", detail: "An Output node sends the approved options to the client on WhatsApp or Email with the plain reason, and on their pick a Tool (MCP) node updates the mission record and Slack notifies the crew." },
        ],
        connectors: ["Webhook", "WhatsApp", "Email", "Slack", "Tool (MCP → scheduling tool)", "Tool (MCP → weather service)", "Approval / HITL"],
        outcome: "Marginal weather is caught days out with a ranked, limit-checked alternative already drafted, and no client is told anything until ops has approved the move — instead of a Wednesday-night phone tree and a wasted Thursday.",
        impact: "The cost of a weather scrub is mostly travel and a lost crew day, and both are avoidable only if the call is made early. Running the check continuously from booking onward, and grounding flyability in the airframe's documented limits via a deterministic formula rather than a remembered number, is the mechanism — the commitment to the client still waits for a human.",
        build: {
          agents: 2,
          agentsNote: "1 flyability checker (runs once per upcoming mission per watch cycle via the Loop node) + 1 reschedule proposer (runs only for marginal-or-worse missions). The wind banding, the approval and the calendar write are workflow nodes.",
          pattern: "Map (cheap per-mission check) → Condition filter → proposer on the exceptions only → human approves the client-facing move",
          prompt:
            "(Flyability checker) Assess ONE booked drone mission's weather window. Inputs: mission {site_coords, window_start, window_end, airframe}, the forecast for that site and window, the computed wind band, and the airframe's operating limits from your Knowledge collection. Return strict JSON {verdict (FLYABLE|MARGINAL|NOT_FLYABLE), limiting_factors[{factor (WIND_MEAN|WIND_GUST|VISIBILITY|PRECIP|CLOUD_BASE|LIGHT), forecast_value, limit_value}], confidence_note}. Compare ONLY against the documented limits — never soften a limit, never average a gust away, never override the computed wind band, and never mark FLYABLE on a missing forecast field (return MARGINAL with the gap named). You do not reschedule or contact anyone.",
          agentRoles: [
            { role: "Flyability checker (per mission, per watch cycle)", model: "Economy tier with a fallback model — it runs across every upcoming mission on every cycle, making it by far the highest-volume step here and the dominant cost lever twice over", prompt: "Combine the computed wind band with visibility, precipitation, cloud base and light against documented limits, and return FLYABLE / MARGINAL / NOT_FLYABLE with the exact figures. Missing forecast data ⇒ MARGINAL, never FLYABLE." },
            { role: "Reschedule proposer (exceptions only)", model: "Standard tier — multi-constraint reasoning across forecast, site access hours and crew availability; it only ever runs on missions the Condition node flagged", prompt: "Propose ranked alternative windows that clear the documented limits and fit the site's access hours and crew availability, each with the reason it clears. Propose only — never message the client, never write the calendar." },
          ],
          mcp: [
            "Scheduling / crew-roster tool — via Tool (MCP): list upcoming missions with their site, window, airframe and assigned crew, read crew availability for candidate slots, and write the approved reschedule back to the mission record. The watch cycle is fired by a scheduled Webhook. No first-party connector.",
            "Weather / forecast service — via Tool (MCP): fetch the point forecast for the site coordinates across the planned window, including mean and gust wind, visibility, precipitation probability, cloud base and civil light times. No first-party connector.",
            "WhatsApp / Email — first-party client channels for the approved options.",
            "Slack — first-party crew notification channel.",
          ],
          tools: [
            "knowledge_search (scoped to the airframe operating-limits + per-site access-conditions collection)",
            "http_request (forecast pull, mission list, mission update)",
            "user-defined tool `wind_band` (SpEL): input.gustForecast == null ? 'MARGINAL' : (input.gustForecast >= input.airframeGustLimit ? 'NOT_FLYABLE' : (input.gustForecast >= input.airframeGustLimit * 0.8 ? 'MARGINAL' : 'FLYABLE')) — arithmetic and a ternary over the tool's JSON input, nothing more: no API calls, no loops. It matters because the operator's own safety margin (here, 80% of the airframe limit) is a decision they should own explicitly rather than hope a model applies consistently; the margin sits in the expression, is identical on every mission, and can be tightened for a new airframe without a developer.",
          ],
          guardrails: [
            "No client is contacted and no booking is moved until ops approves at the Approval node — the agents propose windows, they never commit one",
            "Flyability is decided strictly against documented airframe limits, with the wind margin applied by the tenant's SpEL formula; the agent is forbidden from softening a limit, averaging away a gust, or overriding the computed band",
            "A missing or partial forecast yields MARGINAL, never FLYABLE — the absence of bad news is not treated as good news, and a null gust value is banded MARGINAL by construction",
            "FLYABLE missions produce no notification at all, so the alerting stays credible instead of becoming background noise the crew learns to ignore",
            "The mission write-back is keyed to the mission id, so repeated watch cycles cannot create duplicate reschedules",
          ],
          cost:
            "This runs on a schedule, so per-run cost multiplies by CYCLES as well as by missions — which is exactly why the checker sits on the economy tier with a hard cap and a fallback model. The expensive proposer only runs on missions the Condition node flagged, so a settled week of good weather costs almost nothing. The wind banding is a free SpEL evaluation rather than an inference. If you want more value, widen the watch window before you upgrade the checker's model — earlier warning is worth more than a smarter forecast reader.",
          output:
            "The scheduled watch loops the economy-tier checker over every upcoming mission, with the SpEL wind band computed first → the Condition node lets FLYABLE pass silently and forwards the rest → the standard-tier proposer drafts ranked alternative windows → ops approves at the Approval node → an Output node offers the options to the client on WhatsApp/Email, and on their pick a Tool (MCP) node updates the mission and Slack notifies the crew.",
        },
      },
      {
        id: "drone-defect-detection",
        title: "Defect detection on solar farms & towers — cheap pre-filter, expert second pass, human sign-off (3 agents)",
        scenario:
          "Today: a single solar-farm or tower inspection comes back with thousands of thermal and RGB frames on an SD card. A junior analyst scrubs through them in a viewer for two days, marking anything that looks warm, writing 'String 14, module 7 — hotspot?' into an Excel sheet with a screenshot pasted in the next column, and matching frames to actual modules by counting rows on a site map PDF. By hour six they are tired, so the report either misses a real hotspot or floods the client with false positives — and either way it lands a week after the flight, when the crew is on another site.",
        triggerLabel: "Webhook (flight imagery uploaded / processing job complete)",
        steps: [
          { kind: "trigger", title: "Imagery lands", detail: "A Webhook fires when the flight's imagery finishes uploading or the processing job completes. The imagery store and photogrammetry pipeline have no first-party connector — the workflow is notified via Webhook and reads assets through a Tool (MCP) node." },
          { kind: "loop", title: "Map: pre-filter every frame", detail: "A Loop node runs a cheap vision pre-filter over every frame, keeping only those with a plausible anomaly. This is the step that replaces two days of scrubbing — and the step that makes the whole recipe affordable." },
          { kind: "parallel", title: "Batch the pre-filter for wall-clock speed", detail: "A Parallel node runs the pre-filter in batches — same per-frame cost, a fraction of the elapsed time on a multi-thousand-frame set." },
          { kind: "agent", title: "Expert pass on the survivors only", detail: "A stronger vision agent examines only the flagged frames, classifying the defect against the client's asset taxonomy in a scoped Knowledge collection (PV: hotspot, cracked cell, soiling, string outage; towers: corrosion, loose fixing, antenna misalignment, obstruction) with a severity and its visual reasoning." },
          { kind: "tool", title: "Locate it on the real asset", detail: "A Tool (MCP) node resolves each finding to the actual asset — string/module id, tower leg and level — by joining the frame's geotag and the flight log against the GIS/asset registry. This is the counting-rows-on-a-PDF step, done by a spatial join." },
          { kind: "code", title: "Rank with the client's own severity rule", detail: "A Code node applies the operator's user-defined SpEL tool to turn defect type and severity into the priority band that client's contract actually uses — different clients band the same hotspot differently, and that is a commercial fact, not a model judgement." },
          { kind: "agent", title: "Draft the findings report", detail: "A third agent composes the client report: findings grouped by band and asset, each with its frame reference and resolved location, and an explicit list of areas the flight did not adequately cover." },
          { kind: "approval", title: "Inspection lead signs off every finding", detail: "An Approval node holds the report. A defect call drives maintenance spend and a missed one is a liability — a qualified inspector confirms, downgrades or rejects each finding before anything reaches the client." },
          { kind: "output", title: "Deliver the signed report", detail: "On sign-off, an Output node emails the report to the client and a Tool (MCP) node files it against the asset registry with the sign-off recorded." },
        ],
        connectors: ["Webhook", "Email", "Knowledge base", "Tool (MCP → imagery / photogrammetry pipeline)", "Tool (MCP → GIS & asset registry)", "Approval / HITL"],
        outcome: "Thousands of frames become a short list of located, severity-banded findings with an explicit coverage-gap list, every one confirmed by a qualified inspector — days earlier, and without anyone counting rows on a site-map PDF.",
        impact: "Two-stage vision is the whole point: a cheap pass removes the overwhelming majority of empty frames so the expensive, accurate pass only ever looks at candidates. That is what makes reviewing every single frame affordable at all — where a tired human on hour six was effectively sampling. The human sign-off is what keeps a model's defect call from silently becoming a maintenance work order.",
        build: {
          agents: 3,
          agentsNote: "1 vision pre-filter (runs once per frame via the Loop node) + 1 expert classifier (flagged frames only) + 1 report writer (once). Loop, Parallel, the asset join, the severity banding and the sign-off are workflow nodes.",
          pattern: "Two-stage vision funnel: cheap per-frame filter → expert pass on survivors → client-specific banding → report writer → qualified human signs off every finding",
          prompt:
            "(Pre-filter) Look at ONE inspection frame. Your ONLY job is to decide whether it plausibly contains an anomaly worth an expert look. Return strict JSON {candidate (true|false), hint (THERMAL_ANOMALY|SURFACE_DAMAGE|STRUCTURAL|OBSTRUCTION|NONE), frame_ref, unusable (true|false)}. Be deliberately generous: a false positive costs one extra expert call, a false negative loses a real defect — when uncertain, return candidate=true. Do NOT classify the defect, do NOT assign severity, do NOT name an asset. If the frame is unusable (blurred, over-exposed, obscured), return candidate=false with unusable=true so coverage can be reported honestly.",
          agentRoles: [
            { role: "Vision pre-filter (per frame)", model: "Economy tier, vision-capable, with a fallback model and a hard token cap — it runs on EVERY frame, so it alone determines whether the recipe is affordable, and a mid-batch provider outage must not orphan a flight", prompt: "Binary candidate/not-candidate per frame, biased toward false positives. No classification, no severity, no asset naming. Flag unusable frames so coverage gaps are reported." },
            { role: "Expert defect classifier (candidates only)", model: "Premium tier, vision-capable — the hard step, affordable precisely because it only ever sees the small surviving fraction", prompt: "Classify each candidate frame against the client's asset taxonomy in the scoped Knowledge collection, with defect type, severity and the visual reasoning. Return UNCERTAIN rather than guessing; never infer a defect from context outside the frame." },
            { role: "Report writer (once)", model: "Standard tier — synthesis and client-facing writing, one call per inspection", prompt: "Group the confirmed findings by the computed priority band and by asset, cite each frame reference and resolved location, and list explicitly the areas the flight did not adequately cover. Never add a finding that is not in the classifier output, and never soften or upgrade a severity." },
          ],
          mcp: [
            "Imagery store / photogrammetry + orthomosaic pipeline — via Tool (MCP): list the flight's frames, fetch individual frames and their EXIF geotags for the vision passes, and read the processed orthomosaic reference for the report. Completion is announced by Webhook. No first-party connector.",
            "GIS + asset registry — via Tool (MCP): spatially join a frame's geotag and the flight log to the actual asset id (PV string and module, or tower leg and level) and return the asset's metadata, replacing manual row-counting on a site-map PDF. No first-party connector.",
            "Client asset-management system — via Tool (MCP): file the signed report against each affected asset with the inspector's sign-off recorded. No first-party connector.",
            "Email — first-party channel for delivering the signed report to the client.",
          ],
          tools: [
            "knowledge_search (scoped to the per-client asset taxonomy + defect severity rubric collection)",
            "http_request (fetch frames and the flight log, resolve geotags against the registry, file the signed report)",
            "user-defined tool `defect_priority_band` (SpEL): input.severity == 'HIGH' && input.assetCriticality == 'PRIMARY' ? 'P1' : (input.severity == 'HIGH' || (input.severity == 'MEDIUM' && input.assetCriticality == 'PRIMARY') ? 'P2' : 'P3') — a pure formula over the tool's JSON input, with no API access and no loops. It matters because two clients band the same hotspot differently depending on what the asset feeds and what their contract says; keeping that mapping in a tenant-authored expression means the commercial rule is explicit, identical across every finding in a report, and editable per client without a developer or a redeploy.",
          ],
          guardrails: [
            "No finding reaches the client without the inspection lead confirming, downgrading or rejecting it at the Approval node — a model defect call is never a work order",
            "The pre-filter is prompt-biased toward false positives: an extra expert call is cheap, a missed defect is not",
            "The expert classifier may return UNCERTAIN and must not infer a defect from anything outside the frame it was given",
            "Priority banding comes from the tenant's SpEL rule, so the model never quietly re-prioritises a client's contractual severity mapping",
            "Unusable frames (blur, over-exposure, obstruction) are tracked and reported as coverage gaps — the report says what was NOT adequately inspected, so silence is never mistaken for a clean bill",
            "The report writer cannot add findings or shift a severity beyond the classifier output; the report is grouped, not reinterpreted",
            "Findings are keyed to frame reference and resolved asset id, so a re-processed flight cannot duplicate a defect against an asset",
          ],
          cost:
            "This is the clearest tiering case in the guide. The pre-filter runs on EVERY frame — thousands per flight — so it must be the cheapest vision-capable model available, hard-capped, with a fallback so a mid-batch outage does not orphan the flight; that one choice sets the cost of the whole recipe. The premium classifier is affordable precisely because it only ever sees the small surviving fraction, and the report writer runs once. Inverting this (premium on every frame) is what makes full-coverage review economically impossible — which is why the manual process quietly became sampling. Parallel batching cuts elapsed time without touching per-frame cost, and the priority banding is a free SpEL evaluation.",
          output:
            "The Loop and Parallel nodes run the economy-tier pre-filter across every frame → surviving candidates go to the premium classifier → a Tool (MCP) node resolves each finding to a real asset via the geotag, flight log and GIS registry → the Code node bands priority with the client's own SpEL rule → the writer drafts the report with an explicit coverage-gap section → the inspection lead signs off each finding at the Approval node → an Output node emails the signed report and a Tool (MCP) node files it against the asset registry with the sign-off recorded.",
        },
      },
      {
        id: "drone-flightlog-deliverable",
        title: "Flight log → deliverable pipeline with a completeness gate",
        scenario:
          "Today: the crew lands, copies the cards to a laptop in the van, and uploads over a hotel wifi that night. Whether the capture is actually good enough is a judgement someone makes by opening a few frames and going 'looks fine'. The GCP sheet is a photo of a paper form in the crew WhatsApp group. Two weeks later the client rejects the orthomosaic because overlap was short over the north-east corner — and by then the crew, the site access window and the weather are all gone, so the reflight is at the operator's cost and the client relationship takes the hit.",
        triggerLabel: "Webhook (flight log + imagery uploaded from the field)",
        steps: [
          { kind: "trigger", title: "Crew uploads the flight", detail: "A Webhook fires when the flight log and imagery are uploaded from the field — so the check happens that night, while the crew is still near the site, not a fortnight later." },
          { kind: "code", title: "Deterministic capture checks first", detail: "A Code node does the arithmetic before any model runs: frame count vs planned waypoints, geotag presence, altitude and overlap consistency, GCP records, battery-swap gaps in the log. Facts that can be computed are computed, not inferred." },
          { kind: "code", title: "Score the shortfall with the operator's own formula", detail: "A second Code node runs the user-defined SpEL tool to convert measured overlap against the contracted spec into a shortfall percentage and a reflight scope band — the operator's tolerance, encoded once, applied identically to every flight." },
          { kind: "agent", title: "Explain the gaps against the job spec", detail: "An Agent node compares the computed capture picture against the contracted deliverable spec in a scoped Knowledge collection (product type, GSD, coverage polygon, accuracy class) and states plainly whether the capture supports the promised deliverable, and exactly where it does not." },
          { kind: "condition", title: "Completeness gate", detail: "A Condition node blocks the pipeline on any material gap — a partial capture never quietly proceeds into processing and becomes a deliverable that fails on the client's desk instead of yours." },
          { kind: "approval", title: "Survey lead approves the reflight or the commitment", detail: "An Approval node holds both branches: authorising a reflight (crew cost, another window) and, on a clean capture, committing to the deliverable and its date. Both are client and cost commitments — a human owns them." },
          { kind: "subworkflow", title: "Kick the processing run", detail: "On approval of a clean capture, a Sub-workflow node starts the processing/deliverable run and tracks it to completion, keeping this workflow readable rather than nesting the whole pipeline inline." },
          { kind: "output", title: "Tell the client what is actually happening", detail: "An Output node sends the approved message on Email or WhatsApp — either the confirmed deliverable and date, or the honest reflight reason and the revised date, while it is still cheap to fix." },
        ],
        connectors: ["Webhook", "Email", "WhatsApp", "Slack", "Knowledge base", "Tool (MCP → photogrammetry / processing pipeline)", "Approval / HITL"],
        outcome: "Every flight is checked against its contracted deliverable spec the night it lands, so gaps surface while the crew and the site access window are still reachable — and no deliverable date is promised without a survey lead approving it.",
        impact: "A capture gap is cheap to fix on the day and expensive to fix a fortnight later, at the operator's cost. Doing the deterministic checks the moment the data lands, banding the shortfall with the operator's own tolerance, and refusing to let a partial capture slide into processing is what moves the discovery earlier — the reflight decision and the client commitment stay human because both spend real money.",
        build: {
          agents: 1,
          agentsNote: "1 capture-assessment agent. The arithmetic (Code nodes + SpEL tool), the completeness gate, the approvals and the processing run (Sub-workflow) are workflow nodes — the numbers are computed, not reasoned.",
          pattern: "Deterministic Code checks → operator tolerance formula → agent explains against the spec → completeness gate → human approves reflight or deliverable commitment → Sub-workflow processes",
          prompt:
            "You assess whether a completed drone flight supports its CONTRACTED deliverable for {{operator}}. Inputs: the computed capture summary {frames, planned_waypoints, geotagged_pct, mean_overlap, overlap_shortfall_pct, altitude_variance, gcp_count, log_gaps[]} and the job spec from your Knowledge collection {product, target_gsd, coverage_polygon, accuracy_class}. Return {supports_deliverable (true|false|UNCLEAR), gaps[{area_or_metric, computed_value, spec_value, consequence}], reflight_scope (NONE|PARTIAL|FULL), plain_summary}. Use ONLY the computed numbers you were given — never estimate coverage, overlap or GSD yourself, never recompute a shortfall, and never restate a computed figure differently. If a required metric is missing, return UNCLEAR and name it. You do not authorise a reflight, start processing, or promise a delivery date.",
          agentRoles: [
            { role: "Capture-vs-spec assessor", model: "Standard tier with a fallback model — one call per flight, reasoning over a small computed summary rather than over imagery, so it stays cheap without dropping a tier; the fallback matters because this check is time-critical while the crew is still near the site", prompt: "Compare the computed capture summary to the contracted spec, name every gap with its computed and required value and the consequence, and scope the reflight. Never recompute or estimate a metric; a missing metric yields UNCLEAR." },
          ],
          mcp: [
            "Field upload / imagery store — inbound Webhook announces the upload; via Tool (MCP): read the flight log, frame manifest, geotag coverage and GCP records for the deterministic checks. No first-party connector.",
            "Photogrammetry / processing pipeline — via Tool (MCP): submit the flight for processing with the contracted product and accuracy class, then poll the orthomosaic/point-cloud job to completion. No first-party connector.",
            "Scheduling tool — via Tool (MCP): book the approved reflight against the site and crew, keyed to the original mission id. No first-party connector.",
            "Email / WhatsApp — first-party client channels for the confirmed date or the reflight message.",
            "Slack — first-party crew notification channel.",
          ],
          tools: [
            "knowledge_search (scoped to the contracted deliverable specs, accuracy classes and capture SOP collection)",
            "http_request (read the flight log and capture metrics, submit and poll the processing job, book a reflight)",
            "user-defined tool `overlap_shortfall_pct` (SpEL): input.meanOverlap == null ? -1 : (input.specOverlap - input.meanOverlap) / input.specOverlap * 100 — arithmetic over the tool's JSON input, no method calls and no loops, with -1 as an explicit 'not measured' sentinel that routes to UNCLEAR instead of a false pass. It matters because overlap tolerance is where operators genuinely differ by product and client, and this is precisely the number a model must never eyeball; the formula makes it identical across every flight and adjustable by the survey lead without a developer.",
            "user-defined tool `reflight_scope_band` (SpEL): input.shortfallPct < 0 ? 'UNCLEAR' : (input.shortfallPct <= 0 ? 'NONE' : (input.shortfallPct < input.partialThresholdPct ? 'PARTIAL' : 'FULL')) — so the crew-cost decision is scoped by the operator's own threshold rather than by a model's sense of 'material'.",
          ],
          guardrails: [
            "The agent never authorises a reflight, starts processing or promises a date — the Approval node gates both the reflight (crew cost) and the deliverable commitment (client promise)",
            "All capture metrics are computed deterministically in Code nodes and the tenant's SpEL formulas; the agent may only explain them, never recompute, estimate or restate them differently",
            "The completeness gate blocks processing on any material gap — a partial capture cannot slide through and become a failed client deliverable two weeks later",
            "A missing required metric yields the sentinel value and routes to UNCLEAR for a human, rather than being reasoned into a pass",
            "The processing run is started only once per flight id via the Sub-workflow node, so a re-uploaded flight cannot double-bill a processing job",
          ],
          cost:
            "Cheap by construction: the expensive work — counting frames, checking geotags, measuring overlap, banding the shortfall — is deterministic Code and SpEL, not model inference, so exactly ONE standard-tier call per flight reads a small computed summary. Resist the temptation to have a model 'look at the imagery' to judge coverage; that inverts this recipe's cost profile for a worse answer. Per-flight cost stays flat as frame counts grow, because it never scales with imagery volume.",
          output:
            "The Code nodes compute the capture metrics and the operator's shortfall band the moment the upload lands → the agent explains them against the contracted spec → the Condition node's completeness gate blocks any material gap → the survey lead approves either the scoped reflight or the deliverable commitment at the Approval node → a Sub-workflow node submits and tracks the processing job → an Output node tells the client the confirmed deliverable and date, or the honest reflight reason and revised date, on Email/WhatsApp with the crew notified on Slack.",
        },
      },
    ],
  },
];
