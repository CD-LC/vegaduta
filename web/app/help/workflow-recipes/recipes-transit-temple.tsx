import type { Industry } from "./recipes-data";
import { Bus, Landmark } from "lucide-react";

// ---------------------------------------------------------------------------
// Public transport / fleet mobility & Temple-trust / pilgrimage administration
// workflow recipes — same "inline copy, no i18n keys" precedent and honesty
// rules as recipes-data.tsx. Every recipe is expressed ONLY in VegaDūta's real
// building blocks (Trigger / Agent / Knowledge / Condition / Approval / Output
// / Tool(MCP) / Voice / Device / Loop / Code / Parallel / Supervisor /
// Sub-workflow), so each maps 1:1 to something you can actually build in the
// Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — AVL/GPS telematics, the ticketing/ETM
// back office, the depot roster/scheduling system, the trust's donation and
// accounting ledger, the darshan/queue-slot booking system, the accommodation
// (cottage) allotment system — are reached the real way, via an inbound Webhook
// trigger and/or a Tool (MCP) node, and each mcp[] entry says what the call
// actually does. Only real channels are used (Webhook, WhatsApp, SMS, Voice/
// Call, Email, Telegram, ElevenLabs, Sarvam, Razorpay UPI). Every consequential
// action — cancelling or diverting a service, publishing a public advisory,
// issuing a refund, committing a roster change, committing a donation receipt,
// allocating a slot — goes through an Approval (human-in-the-loop) step. Impact
// lines describe the mechanism (why it helps), never invented ROI numbers, and
// no agent count is inflated.
//
// USER-DEFINED TOOLS, described honestly: a tenant-authored tool in this
// platform is a SpEL expression evaluated against the tool's JSON input. It is
// sandboxed by construction — no method calls, no constructors, no bean or type
// references, and the grammar contains no loop — so it is a data-transform /
// formula tool, NOT a scripting engine and NOT a way to call an API. Every
// recipe below uses at least one, and only for what it can genuinely do: pull a
// field out of the input, format a string, do arithmetic, branch with a ternary
// or elvis. The point is that the depot or the trust encodes ITS OWN thresholds,
// slot rules and receipt formats — and changes them for a festival week —
// without a developer and without a redeploy.
//
// Two scope notes, deliberate:
//   • Transit here is PUBLIC PASSENGER OPERATIONS (commuters, depots, crews,
//     accessibility) — not freight, which "Logistics & delivery" already covers,
//     and not trip-selling, which "Travel & tourism" already covers.
//   • The trust recipes are ADMINISTRATIVE ONLY — crowd flow, queue slots,
//     donations and receipts, annadanam/prasad logistics, accommodation,
//     lost-and-found, multilingual pilgrim information. The agents answer from
//     the trust's own published Knowledge and NEVER generate doctrinal, ritual
//     or religious-advice content; anything of that kind is routed to a human of
//     the trust. Safety calls are never made by an agent either — it surfaces
//     and escalates.
// ---------------------------------------------------------------------------

export const INDUSTRIES_TRANSITTEMPLE: Industry[] = [
  // ================================ PUBLIC TRANSPORT & FLEET MOBILITY
  {
    id: "public-transit",
    name: "Public transport & fleet mobility",
    icon: Bus,
    tagline:
      "Answer commuters in their own language, draft disruption advisories for a controller to approve, and turn lost property, shift swaps and accessibility requests into tracked work — with your own thresholds encoded as SpEL tools and every service and safety decision human-owned.",
    recipes: [
      {
        id: "transit-disruption-advisory",
        title: "Route disruption → multilingual commuter advisory, controller-approved",
        scenario:
          "Today: a vehicle fails on the corridor, the driver phones the depot, the depot phones the control room. The duty controller — already on two phones — types a notice into a WhatsApp broadcast group, then asks whoever is around whether they can put it into Kannada and Hindi. Someone walks a printed sheet to the terminus notice board. Commuters at the twelve stops in between find out by standing there. By the time the third language goes out the diversion has already changed, and nobody remembers to send the all-clear.",
        triggerLabel: "Webhook (AVL/telematics threshold or control-room incident raise)",
        steps: [
          { kind: "trigger", title: "Disruption is raised", detail: "A Webhook trigger fires from the AVL/GPS telematics platform or from the control room's own incident form — route, direction, affected stops, cause, expected duration. Telematics has no first-party connector; it reaches the workflow via Webhook and is read back through a Tool (MCP) node." },
          { kind: "tool", title: "Pull the live service picture", detail: "A Tool (MCP) node reads the affected route's current vehicle positions, headway and the published timetable so the draft is grounded in what is actually running, not in a guess." },
          { kind: "code", title: "Your own severity rule decides the channel", detail: "A user-defined SpEL tool the operations team wrote themselves — `disruption_tier` — grades the incident from the delay minutes and the number of affected stops. It is a formula, not a script: the ops team can raise the public-advisory threshold for a monsoon week without a developer or a deployment." },
          { kind: "agent", title: "Draft the advisory", detail: "An Agent node writes a short, factual commuter advisory — what is affected, from which stop to which stop, the alternative (a documented parallel route or interchange from a scoped Knowledge collection), and how long the information is valid for. It writes a draft only." },
          { kind: "parallel", title: "Translate for the corridor", detail: "A Parallel node fans the draft across the languages that corridor actually needs, using Sarvam for Indian languages, keeping the facts identical across every version." },
          { kind: "approval", title: "Controller approves before anything is published", detail: "An Approval node holds the whole bundle — English plus every translation — for the duty controller. Publishing a public advisory, and any statement about cancelling or diverting a service, is a human decision; the agent never publishes." },
          { kind: "output", title: "Push to commuter channels", detail: "On approval an Output node sends the exact approved text on WhatsApp and SMS to commuters subscribed to that route, plus Telegram for the public channel — one advisory, one approved wording, several channels." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS / Twilio", "Telegram", "Sarvam (Indian languages)", "Tool (MCP → AVL/telematics + timetable)", "Approval / HITL"],
        outcome:
          "Within minutes of an incident being raised, the duty controller has a grounded advisory in every language the corridor needs, tiered by the operator's own threshold rule, ready to approve and push — instead of typing it into a broadcast group while running the incident.",
        impact:
          "The mechanism is preparation, not automation: the drafting, the timetable lookup and the translation all happen before the controller looks at it, so the human spends their attention on whether the message is right rather than on producing it. The severity threshold lives in a SpEL tool the ops team owns, so 'what counts as advisory-worthy' is a setting they change for a monsoon week, not a ticket they raise. Nothing reaches a commuter that a controller has not read and approved.",
        build: {
          agents: 2,
          agentsNote: "1 drafting agent (once per incident) + 1 translation agent invoked once per target language through the Parallel node. The AVL lookup, the severity formula, the controller approval and the channel push are workflow nodes and tools, not agents.",
          pattern: "Ground → tier by the operator's own SpEL rule → draft → parallel translate → human approves the whole bundle → publish",
          prompt:
            "You draft commuter service advisories for {{operator}}. From an incident {route, direction, affected_stops[], cause, expected_duration} plus the live AVL/timetable data supplied to you, write a factual advisory of at most 60 words: what is affected, between which stops, the documented alternative from Knowledge, and a validity time. Use ONLY the supplied facts and the published network information in Knowledge — never invent a stop, a replacement service, a restoration time or a cause. Do NOT write reassurance, apology framing beyond one plain line, or any safety instruction. If the incident report is missing route or affected stops, return {draft: null, missing[]} instead of guessing. This is a DRAFT for the duty controller; never state that a service is cancelled or diverted as fact unless the incident record says so.",
          agentRoles: [
            { role: "Advisory drafter (once per incident)", model: "Standard tier — this is the hard step: it must stay strictly inside the supplied facts under time pressure. Configure a fallback model on a second provider, because an incident is exactly when you cannot afford the primary provider to be having an outage.", prompt: "Turn the incident record plus live AVL/timetable data into a ≤60-word factual advisory with a documented alternative and a validity time. No invented stops, causes or restoration times. Draft only — never assert a cancellation the record does not contain." },
            { role: "Translator (once per target language, via Parallel)", model: "Economy tier — routes Indian languages through Sarvam; runs N times per incident, so it is the cost multiplier", prompt: "Translate the controller-bound draft into the target language with the facts, stop names, route numbers and times byte-identical. Do not localise a stop name that has an official published form in Knowledge. Add nothing; drop nothing." },
          ],
          mcp: [
            "AVL / GPS telematics — via Tool (MCP): read live vehicle positions and current headway for the affected route; incident itself arrives inbound via Webhook",
            "Timetable / scheduling system — via Tool (MCP): read the published trips, parallel routes and interchange points for the affected stop pair",
            "Passenger-subscription list — via Tool (MCP): resolve which commuters are subscribed to this route and in which language",
            "WhatsApp / SMS / Telegram — first-party commuter channels for the approved advisory",
          ],
          tools: [
            "user-defined tool `disruption_tier` (SpEL): input.delayMinutes > 45 ? 'PUBLIC_ADVISORY' : (input.delayMinutes > 15 ? 'ROUTE_NOTICE' : 'LOG_ONLY') — the ops team's own threshold, editable for a monsoon week without a developer or a redeploy",
            "user-defined tool `advisory_headline` (SpEL): 'Route ' + input.route + ': ' + input.fromStop + ' → ' + input.toStop + ' affected until ' + input.validUntil — the operator's exact house format, so every language version opens identically",
            "knowledge_search (published network map, parallel routes, interchange rules, advisory style guide — a scoped collection, not the whole corpus)",
            "http_request (read live vehicle positions and the timetable for the affected route)",
          ],
          guardrails: [
            "The agent never publishes — the duty controller approves the English text AND every translation at the Approval node before a single message leaves",
            "No agent ever declares a service cancelled, diverted, suspended or safe to board on its own; it restates only what the incident record and timetable say, and anything beyond that is escalated to the controller",
            "Advisory content is grounded in the incident record, the live AVL read and the published network data in the scoped Knowledge collection — invented stops, replacement services or restoration times are prohibited by prompt and caught in review",
            "Severity tiering is a deterministic SpEL formula owned by the operator, not a model judgement — the model never decides how widely a message is broadcast",
            "Translations are fact-locked: route numbers, stop names, times and the validity window must match the approved English exactly, so approving one bundle really does approve all of it",
            "Idempotent per incident id — a telematics system that re-fires the same incident does not produce a second advisory blast",
          ],
          cost:
            "Cost is one standard-tier draft plus L economy-tier translations per incident, where L is the number of languages that corridor actually needs — so translation, not drafting, is what scales. Keep translation on the economy tier (it is a constrained, fact-locked rewrite, not reasoning) and spend the reasoning budget on the single drafting call, which is where an error would actually reach commuters. The `disruption_tier` SpEL tool costs nothing at all and removes the cheapest-to-get-wrong decision from the model entirely. Run translations through the Parallel node for wall-clock speed; that does not change per-language cost.",
          output:
            "Webhook incident → Tool (MCP) reads live AVL and timetable → the `disruption_tier` SpEL tool grades it against the operator's own threshold → the drafting agent produces a grounded ≤60-word advisory → a Parallel node fans translation across the corridor's languages via Sarvam → the duty controller approves the entire bundle at the Approval node → an Output node pushes the exact approved wording to route subscribers on WhatsApp and SMS and to the public Telegram channel.",
        },
      },
      {
        id: "transit-lost-found",
        title: "Lost-property intake and matching across depots",
        scenario:
          "Today: a passenger leaves a bag on a bus and rings the terminus. The clerk there opens a hardbound register, runs a finger down the column, and says try tomorrow. The bag is actually at the far depot where that vehicle ended its shift, written into a different register in a different hand — 'blue bag, ladies' — with no route, no time. The two halves meet only if someone bothers to ring around three depots, and most never do. Anyone who does not speak the clerk's language is asked to call back with a relative.",
        triggerLabel: "WhatsApp / Voice (passenger report) + Webhook (crew hand-in)",
        steps: [
          { kind: "trigger", title: "Both halves arrive", detail: "A passenger reports a loss on WhatsApp or by Voice/Call; separately, a Webhook from the depot hand-in form (or a crew WhatsApp message) records found property. Two independent streams, one workflow." },
          { kind: "voice", title: "Take the report by phone too", detail: "A Voice node handles callers who will not use a form: ElevenLabs for natural speech, Sarvam so the passenger can describe the item in their own language, transcribed into the same structured fields as the WhatsApp path — no calling back with a relative." },
          { kind: "agent", title: "Normalise into a comparable record", detail: "An Agent node turns either stream into one schema — item category, colour, distinguishing marks, route/vehicle, direction, approximate time, boarding and alighting stop, contact — so a lost record and a found record can actually be compared." },
          { kind: "code", title: "Retention clock from your own policy", detail: "A user-defined SpEL tool, `retention_days`, sets the hold period per item category straight from the operator's published policy — a formula the property office edits itself when the policy changes, not a constant buried in code." },
          { kind: "agent", title: "Propose matches", detail: "A matching agent scores open found-property records against the new loss report (or vice versa) on route, vehicle, time window and item description, and returns ranked candidates with the reasons for each — it proposes, it never confirms a match." },
          { kind: "approval", title: "Depot staff confirm the match and the release", detail: "An Approval node holds every proposed match: handing someone else's property to the wrong person is not recoverable, so a depot officer confirms identity and authorises release. The agent's ranking is advice." },
          { kind: "subworkflow", title: "Tell the passenger, in their language", detail: "A shared Sub-workflow — the same multilingual notify block the accessibility and advisory recipes reuse — messages the passenger in the language they reported in: which depot holds the item, its counter hours, and what proof of ownership to bring." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "ElevenLabs (voice)", "Sarvam (Indian languages)", "Webhook", "Tool (MCP → depot property register)", "Approval / HITL"],
        outcome:
          "Loss reports and depot hand-ins land in one comparable register searchable across every depot, candidate matches are surfaced with their reasons, and a depot officer confirms every release.",
        impact:
          "The mechanism is a common schema plus a search that runs across all depots instead of one register at one counter: today the two halves of a match sit in different books in different formats, so they meet only if a human happens to ring around. Ranking candidates does not decide anything — the release stays a staffed, identity-checked decision.",
        build: {
          agents: 2,
          agentsNote: "1 intake/normalisation agent (runs once per report, from either stream) + 1 matching agent (runs once per new record against the open register). The voice capture, the retention formula, the register read/write and the release approval are workflow nodes and tools.",
          pattern: "Two-stream intake → shared schema → candidate ranking → human confirms identity and release → shared multilingual notify Sub-workflow",
          prompt:
            "(Intake) You take lost-property reports for {{operator}}. From a passenger message, a voice transcript or a depot hand-in form, extract strict JSON {stream (LOST|FOUND), item_category, colour, brand, distinguishing_marks[], contents_claimed[], route, vehicle_no, direction, time_window, boarding_stop, alighting_stop, reporter_contact, language}. Use null for anything not stated — NEVER infer a brand, a colour or a route the reporter did not give. Do not ask for identity documents or any government ID number, and do not record card, bank or ID numbers even if the passenger volunteers them; note only 'documents claimed inside' as a category.",
          agentRoles: [
            { role: "Intake / normaliser (per report)", model: "Economy tier — high volume, and the work is structured extraction from a short message or transcript; a fallback model on a second provider keeps the helpline answering during an outage", prompt: "Extract one lost- or found-property report into the shared strict JSON schema. Null for anything unstated. Never infer route, brand or colour. Never record card, bank or government ID numbers — record only that documents were claimed." },
            { role: "Matcher (per new record)", model: "Standard tier — this is the hard step: weighing partial, differently-worded descriptions across route and time", prompt: "Score open counterpart records against the new one on route, vehicle, time window and description overlap. Return ranked candidates with an explicit reason per candidate and a confidence. Never declare a match; if nothing scores well, return an empty list rather than a weak guess." },
          ],
          mcp: [
            "Depot lost-property register — via Tool (MCP): query open counterpart records across ALL depots by route + time window, write the new intake record, stamp a release",
            "Fleet/duty records — via Tool (MCP): resolve which vehicle and crew worked that route and time, so a vague report still lands on the right depot",
            "Depot hand-in form — inbound via Webhook: crew hand-ins enter the same register as passenger reports",
            "WhatsApp / Voice — first-party passenger channels for intake and the release message",
          ],
          tools: [
            "user-defined tool `retention_days` (SpEL): input.category == 'DOCUMENTS' ? 90 : (input.category == 'ELECTRONICS' ? 60 : 30) — the property office's own published hold policy, edited by them when the policy changes",
            "user-defined tool `claim_reference` (SpEL): input.depotCode + '-' + input.route + '-' + input.reportId — one readable reference the passenger can quote at the counter, in the operator's own format",
            "knowledge_search (depot counter hours, proof-of-ownership policy, retention and disposal rules)",
            "http_request (query the property register across depots; write the intake record; stamp a release)",
          ],
          guardrails: [
            "The agent never releases property — a depot officer confirms the claimant's identity and authorises the handover at the Approval node; the ranking is advice only",
            "No match is ever stated to a passenger as found until it is confirmed; the passenger message goes out only after the approval",
            "The intake agent is prohibited from capturing or storing card, bank-account or government ID numbers even when a passenger volunteers them — contents are recorded only at category level",
            "Retention and disposal timing comes from the operator's own SpEL policy tool, never from the model's idea of a reasonable hold period",
            "Contact details are used only to return the item and are not reused for marketing or broadcast lists",
            "Register writes are keyed to the report id, so a passenger who reports twice does not create a duplicate open case",
          ],
          cost:
            "Intake runs on every report from both streams and is by far the higher-volume step, so it must sit on the economy tier — that single choice dominates the bill. Matching runs once per new record and is the genuinely hard reasoning (partial descriptions, fuzzy times), which is where the standard tier earns its cost. Cap the candidate set the matcher sees, using the SpEL-derived route/time window rather than the whole register, so the expensive call stays a fixed size as the register grows.",
          output:
            "WhatsApp, Voice (ElevenLabs + Sarvam) or the depot Webhook feeds the intake agent → one shared record is written to the property register via a Tool (MCP) node with a `claim_reference` and a `retention_days` clock → the matcher ranks counterpart records with reasons → a depot officer confirms identity and authorises release at the Approval node → the shared notify Sub-workflow tells the passenger, in their own language, which depot holds the item, its counter hours and what proof to bring.",
        },
      },
      {
        id: "transit-shift-swap",
        title: "Crew shift-swap and leave requests → roster proposal for the depot manager",
        scenario:
          "Today: a conductor wants Thursday off, so he catches the roster clerk at the window between duties and writes it on a slip that goes under a paperweight. A driver calls in sick at 05:10 and the depot clerk starts phoning down a handwritten list of who might be free — badge by badge, most phones ringing out — while checking a wall chart for who is out of rest hours and whose route endorsement has lapsed. Half the swap slips surface after the roster is already published, and the ones that do get done are remembered rather than recorded.",
        triggerLabel: "WhatsApp (crew request) / Webhook (depot kiosk form)",
        steps: [
          { kind: "trigger", title: "Crew asks for a swap or leave", detail: "A WhatsApp message from the crew member (or a Webhook from the depot kiosk form) starts the request — in whatever language they type, and at whatever hour their shift ends, instead of a slip under a paperweight." },
          { kind: "agent", title: "Structure the request", detail: "An Agent node extracts the request into fields: requester, badge/duty id, dates, the duty being given up, the proposed counterpart if there is one, reason category, and whether it is a swap, a leave or a cover request." },
          { kind: "tool", title: "Check it against the roster", detail: "A Tool (MCP) node reads the depot roster and crew-records system — is the counterpart free, is their licence and route endorsement valid for that duty, what is the actual gap since their last duty ended?" },
          { kind: "code", title: "Rest-hour rule the depot owns", detail: "A user-defined SpEL tool, `rest_gap_band`, turns the returned gap into BREACH / REVIEW / OK against the depot's own rule. It is arithmetic and a ternary — no loops, no code execution — which is exactly right for a rule that must be deterministic and that the depot changes when the rule changes." },
          { kind: "condition", title: "Rule breach or clean?", detail: "A Condition node splits the two cases: a request that would breach a rest-hour, licence-validity or duty-hour rule is flagged as non-compliant with the specific rule named; a clean one goes forward as a proposal." },
          { kind: "approval", title: "Depot manager decides", detail: "An Approval node holds every roster change. A swap commits a person to a safety-critical duty, so the manager approves — the agent has only structured the request and the workflow has only checked the rules." },
          { kind: "output", title: "Confirm both crew members and the roster", detail: "On approval a Tool (MCP) node writes the change to the roster and an Output node confirms on WhatsApp to both crew members with the new duty details; a rejection goes back with the named reason, not silence." },
        ],
        connectors: ["WhatsApp", "Webhook", "Sarvam (Indian languages)", "Tool (MCP → depot roster + crew records)", "Approval / HITL"],
        outcome:
          "Swap, leave and sick-cover requests arrive in one queue already checked against licence validity, rest hours and coverage, so the manager approves or rejects a decision rather than assembling one from a wall chart at 05:10.",
        impact:
          "The mechanism is moving the rule-checking to the moment of the request instead of the moment the clerk gets to it: the rest-hour and licence checks are deterministic lookups plus a SpEL formula, which is what makes a request that arrives at 23:40 actionable at 05:10. The rule itself stays the depot's — written as an expression they can edit — and the commitment of a person to a safety-critical duty stays entirely with the depot manager.",
        build: {
          agents: 1,
          agentsNote: "1 request-structuring agent per request. The roster and licence checks are Tool (MCP) lookups, the rest-hour band is a SpEL tool, the compliance split is a Condition node, and the roster write happens only after the manager's Approval — none of those are agents.",
          prompt:
            "You structure crew roster requests for {{depot}}. From a crew member's message in any language, extract strict JSON {type (SWAP|LEAVE|COVER), requester_badge, duty_id, dates[], counterpart_badge (null if none proposed), reason_category (MEDICAL|FAMILY|PERSONAL|OTHER), free_text_note, language}. Use null for anything not stated and never infer a badge number or a duty id. Do not record medical details beyond the category MEDICAL — the specific condition is not needed and must not be stored. You do NOT evaluate whether the swap is allowed and you never tell the crew member it is approved; the workflow checks the rules and the depot manager decides.",
          agentRoles: [
            { role: "Request structurer (per request)", model: "Economy tier — short, multilingual, high-volume extraction with a fixed schema; Sarvam handles the Indian-language input, and a fallback model keeps the 05:10 sick-call path alive during a provider outage", prompt: "Extract one swap/leave/cover request into the strict schema in the crew member's own language. Null for unstated fields, never infer a badge or duty id, never store a medical detail beyond the category. Never state or imply that the request is approved." },
          ],
          mcp: [
            "Depot roster / duty-scheduling system — via Tool (MCP): read the duty being given up and who is genuinely free that day; write the approved swap back",
            "Crew records — via Tool (MCP): read licence validity, route endorsement and the exact end time of the member's previous duty for the rest-hour calculation",
            "Depot kiosk form — inbound via Webhook: the same queue as the WhatsApp path, for crew who prefer the counter",
            "WhatsApp — first-party crew channel for the request and the confirmed duty details",
          ],
          tools: [
            "user-defined tool `rest_gap_band` (SpEL): input.restHours < 9 ? 'BREACH' : (input.restHours < 11 ? 'REVIEW' : 'OK') — the depot's own rest rule as an editable formula, so tightening it for a festival period is a settings change, not a release",
            "user-defined tool `duty_label` (SpEL): input.dutyId + ' (' + input.signOn + '–' + input.signOff + ', ' + input.route + ')' — one unambiguous duty string reused in the manager's approval card and both crew confirmations",
            "knowledge_search (rest-hour rules, maximum consecutive duties, swap eligibility and leave policy as published by the depot)",
            "http_request (read roster and crew records; write the approved roster change)",
          ],
          guardrails: [
            "No roster change is written without the depot manager's Approval — the agent structures the request and the workflow checks rules, but a person is never committed to a safety-critical duty by an agent",
            "Licence validity, route endorsement and rest-hour compliance are deterministic Tool (MCP) lookups plus a SpEL formula, not something the model is asked to judge or recall",
            "A request that breaches a rest-hour, duty-hour or licence rule is surfaced to the manager with the specific rule named — the workflow never quietly suppresses it and never auto-rejects a safety-relevant case without a human seeing it",
            "Medical reasons are stored only as a category; free-text medical detail is not extracted or retained",
            "The agent never tells a crew member their request is approved — only the post-Approval Output node does",
          ],
          cost:
            "This is a genuinely cheap workflow: one economy-tier extraction per request, and the expensive-looking part (rest hours, licence validity, coverage) is a deterministic lookup plus a zero-cost SpEL formula — do not pay a premium model to reason about rules the roster system can answer exactly. The only reason to move up a tier would be very messy free-text requests; even then, prefer tightening the prompt over upgrading the model.",
          output:
            "WhatsApp or kiosk Webhook → the economy-tier agent structures the request → a Tool (MCP) node reads the roster, licence validity and previous duty end time → the `rest_gap_band` SpEL tool bands it BREACH/REVIEW/OK against the depot's own rule → the Condition node splits compliant from rule-breaching → the depot manager approves or rejects at the Approval node → on approval a Tool (MCP) node writes the roster change and an Output node confirms the `duty_label` to both crew members on WhatsApp in their own language.",
        },
      },
      {
        id: "transit-accessibility",
        title: "Accessibility assistance booking, by voice and in any language",
        scenario:
          "Today: a passenger who uses a wheelchair wants to travel on Friday. She calls the general enquiry number, is transferred twice, and is finally told to 'ask the conductor on the day'. There is no record anywhere — not at the boarding stop, not with the crew, not at the interchange where she will need help changing. On Friday the vehicle that arrives has no working ramp and the conductor has never heard of her. Meanwhile the station staff who would gladly have helped had no advance notice at all.",
        triggerLabel: "Voice / Call (assistance line) / WhatsApp / SMS",
        steps: [
          { kind: "trigger", title: "Passenger asks for assistance", detail: "A Voice/Call trigger on the assistance line, or a WhatsApp/SMS message — the request comes in whatever way the passenger can actually use, including a plain phone call to a number that answers." },
          { kind: "voice", title: "A voice interface that actually works", detail: "A Voice node with ElevenLabs for clear, unhurried speech and Sarvam for Indian languages takes the request conversationally: which journey, which stops, what assistance, any mobility aid to be accommodated." },
          { kind: "knowledge", title: "Check what the network can actually offer", detail: "A scoped Knowledge collection holds the operator's published accessibility information — which stops are step-free, which vehicle types have ramps, which interchanges are staffed and when — so the passenger is told the truth, not a hope." },
          { kind: "agent", title: "Assemble the assistance request", detail: "An Agent node turns the conversation into a structured assistance booking against a specific service and stop pair, and flags anything the published information says cannot be provided on that journey." },
          { kind: "code", title: "Route it by the operator's own notice rule", detail: "A user-defined SpEL tool, `assistance_lead_band`, classifies the request by hours to departure — the operator's own published notice policy as a formula, so a request made two hours out is routed as short notice rather than dropped into the same queue as one made a week ahead." },
          { kind: "approval", title: "Station or depot staff commit the assistance", detail: "An Approval node routes the request to the staff who would actually perform it. Allocating a person and equipment to a time and place is a commitment, so a human accepts it — and can offer an alternative service instead of a silent decline." },
          { kind: "output", title: "Confirm, and brief the crew", detail: "On acceptance a shared multilingual notify Sub-workflow confirms to the passenger in her own language, and a Tool (MCP) node posts the assistance note to the duty/crew system so the crew on that service knows before it arrives." },
        ],
        connectors: ["Voice / Call", "ElevenLabs (voice)", "Sarvam (Indian languages)", "WhatsApp", "SMS / Twilio", "Knowledge base", "Tool (MCP → duty/crew notes)", "Approval / HITL"],
        outcome:
          "A passenger can arrange assistance by simply phoning, in her own language, and the crew and station staff have the request in hand before the vehicle arrives — instead of 'ask the conductor on the day'.",
        impact:
          "The mechanism is meeting the passenger on the channel she can actually use — a phone call in her own language — and then grounding the answer in the operator's own published accessibility data, so a request is either genuinely accepted by the staff who will perform it or honestly declined with an alternative. It replaces a transfer and a hope with a commitment someone has accepted and the crew has been told about.",
        build: {
          agents: 1,
          agentsNote: "1 conversational assistance agent handling the whole call or chat. The voice layer, the accessibility Knowledge grounding, the lead-time formula, the staff acceptance and the crew note are workflow nodes and tools.",
          pattern: "Voice-first multilingual intake → grounded feasibility check → staff accept the commitment → crew briefed in advance",
          prompt:
            "You take accessibility assistance requests for {{operator}} by phone and message, in the passenger's own language. Collect {journey_date, service_or_route, boarding_stop, alighting_stop, assistance_type[] (RAMP|RESERVED_SEAT|BOARDING_HELP|INTERCHANGE_ESCORT|OTHER), mobility_aid, companion (yes/no), contact}. Answer questions about step-free access, ramp-equipped vehicles and staffed interchanges ONLY from the published accessibility information in Knowledge — if it is not published, say you do not have that confirmed and pass it to staff. NEVER promise that assistance will be provided; you are collecting a request that station or depot staff must accept. Do not ask for or record any disability certificate number, medical detail or government ID. Be direct and unhurried; if the passenger asks for a human, hand off immediately.",
          agentRoles: [
            { role: "Assistance intake agent (per request)", model: "Standard tier — a real-time voice conversation with a passenger who may need patience and repetition is the wrong place to economise; pair it with a fallback model so the assistance line does not simply stop answering during a provider outage", prompt: "Hold a calm multilingual conversation, collect the structured assistance request, answer feasibility questions strictly from published accessibility Knowledge, never promise the assistance, never collect medical or ID details, and hand off to a human the moment it is asked for." },
          ],
          mcp: [
            "Duty / crew-notes system — via Tool (MCP): post the accepted assistance note against the specific service and duty, so the crew sees it at sign-on",
            "Station staffing roster — via Tool (MCP): read who is on duty at the boarding stop and the interchange at that hour, so the approval goes to the person who would actually perform it",
            "Fleet register — via Tool (MCP): read which vehicle type is allocated to that trip, to check ramp availability against published data",
            "Voice / WhatsApp / SMS — first-party passenger channels, including a plain phone call",
          ],
          tools: [
            "user-defined tool `assistance_lead_band` (SpEL): input.hoursToDeparture >= 12 ? 'STANDARD' : (input.hoursToDeparture >= 2 ? 'SHORT_NOTICE' : 'AT_STATION') — the operator's own published notice policy, editable by the accessibility desk without a release",
            "user-defined tool `crew_note_line` (SpEL): input.assistanceType + ' at ' + input.boardingStop + ' for ' + input.serviceOrRoute + (input.mobilityAid != null ? ' — aid: ' + input.mobilityAid : '') — one consistent line in the crew's own note format",
            "knowledge_search (published step-free stop list, ramp-equipped vehicle types, staffed-interchange hours, assistance policy — a scoped collection so nothing unpublished can leak into an answer)",
            "http_request (post the accepted assistance note to the duty/crew system)",
          ],
          guardrails: [
            "The agent never confirms that assistance will be provided — station or depot staff accept the commitment at the Approval node, and only then is the passenger told it is arranged",
            "Feasibility answers come only from the operator's published accessibility information in the scoped Knowledge collection; where the data is not published the agent says so plainly and escalates rather than guessing whether a stop is step-free",
            "The agent never makes a boarding-safety or vehicle-suitability judgement — whether a particular mobility aid can be safely carried on a particular vehicle is surfaced to staff, never decided in the conversation",
            "No disability certificate number, medical detail or government ID is requested or stored; assistance type and mobility-aid type are enough",
            "An explicit request for a human ends the automated conversation immediately and transfers, with the partial request preserved so nothing has to be repeated",
          ],
          cost:
            "This is the one place in this pack to deliberately not economise: a low-volume, high-stakes real-time voice conversation, so a standard-tier model plus ElevenLabs/Sarvam is the right spend — a cheap model that mishears a stop name or rushes a passenger costs far more than the token difference. Volume is naturally bounded by the number of journeys needing assistance, so there is no per-item multiplier; keep the Knowledge collection scoped so each turn stays a short grounded lookup, and let the zero-cost SpEL lead-time band do the routing.",
          output:
            "Voice, WhatsApp or SMS → the standard-tier agent converses in the passenger's language via ElevenLabs and Sarvam → a scoped Knowledge collection grounds every feasibility answer in published accessibility data → `assistance_lead_band` routes it by the operator's own notice policy → the staff who would perform it accept at the Approval node → the shared notify Sub-workflow confirms to the passenger in her own language and a Tool (MCP) node posts the `crew_note_line` to the duty system so the crew is briefed before the service arrives.",
        },
      },
    ],
  },

  // ================== TEMPLES, TRUSTS & PILGRIMAGE ADMINISTRATION
  {
    id: "temple-trust",
    name: "Temples, trusts & pilgrimage administration",
    icon: Landmark,
    tagline:
      "Administrative help for the people who run a pilgrimage site — multilingual pilgrim information, queue-slot and accommodation enquiries, UPI donation receipts, annadanam planning and crowd advisories drafted for the trust to approve, with the trust's own rules written as editable formulas. Never doctrinal, never a safety call.",
    recipes: [
      {
        id: "temple-pilgrim-helpline",
        title: "Multilingual pilgrim helpline — administrative questions only",
        scenario:
          "Today: one person sits in the trust office with a landline and a register. She answers the same fifteen questions all day — what time is the morning darshan, where do I leave my footwear, is my cottage booking confirmed, where is the medical post — and she answers them in the two languages she happens to speak. A family from another state stands at the counter while a stranger in the queue translates for them. After the office closes at six the phone simply rings. The information is all printed on a board by the gate, in one language, which nobody at the far end of a phone line can read.",
        triggerLabel: "WhatsApp / Voice / Call (pilgrim helpline)",
        steps: [
          { kind: "trigger", title: "Pilgrim asks a question", detail: "A WhatsApp message or a Voice/Call to the helpline number — most pilgrims will phone, many will message, and both land in the same workflow at any hour." },
          { kind: "voice", title: "Answer in the pilgrim's own language", detail: "A Voice node with ElevenLabs for speech and Sarvam for Indian languages holds the conversation in whatever language the caller uses, rather than forcing them onto the two the desk covers today or onto a stranger in the queue." },
          { kind: "knowledge", title: "Answer only from the trust's published information", detail: "A scoped Knowledge collection holds ONLY the trust's own published administrative material: darshan and aarti timings, queue and slot rules, facility locations, footwear and luggage counters, accommodation policy, medical and help-desk positions, prohibited items, dress-code notice as published. The board by the gate, now readable over a phone line in any language." },
          { kind: "code", title: "The trust's own routing rule", detail: "A user-defined SpEL tool, `enquiry_route`, maps the classified enquiry type to ANSWER_FROM_KNOWLEDGE, HUMAN_TRUST_STAFF or ESCALATE_NOW. The boundary that matters most here is a formula the trust owns and can tighten — not a behaviour buried in a prompt." },
          { kind: "condition", title: "Administrative, or something else?", detail: "A Condition node acts on that routing: an administrative or logistics question is answered; anything doctrinal, ritual, religious-advice or personal-guidance shaped is NOT answered and goes to a person of the trust. So does anything about a medical or safety situation." },
          { kind: "agent", title: "Answer, or hand over cleanly", detail: "An Agent node answers the administrative question from Knowledge and names the notice it came from, or says plainly that it is a question for the trust's staff and passes it on with the conversation so far — it never improvises a religious answer." },
          { kind: "output", title: "Reply, and log the gap", detail: "An Output node replies on the pilgrim's channel; unanswered questions are logged so the trust can see exactly which of its notices are missing or unclear and publish them." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "ElevenLabs (voice)", "Sarvam (Indian languages)", "SMS / Twilio", "Knowledge base", "Approval / HITL (escalation to trust staff)"],
        outcome:
          "Routine administrative questions are answered accurately in the pilgrim's own language at any hour, including after the office closes, and everything outside that narrow scope reaches a person of the trust instead of being improvised.",
        impact:
          "The mechanism is scope plus grounding: the same fifteen administrative facts are asked thousands of times and are already published by the trust on the board by the gate, so answering them from that published text in many languages is a retrieval-and-translation problem, not a judgement one. The strict boundary is what makes it safe — the agent's job includes knowing what it must not answer, and that boundary is a formula the trust controls, not a hope about model behaviour.",
        build: {
          agents: 1,
          agentsNote: "1 helpline agent. The language layer, the administrative/doctrinal boundary formula, the routing Condition and the escalation to trust staff are workflow nodes and tools, not extra agents.",
          pattern: "Multilingual voice + chat intake → strict administrative-only scope enforced by the trust's own rule → grounded answer or clean human handover",
          prompt:
            "You are an ADMINISTRATIVE helpdesk assistant for {{trust}}, speaking to pilgrims in their own language. You answer ONLY practical, logistical questions using the trust's published information in Knowledge: darshan and aarti timings, queue and slot procedure, facility and counter locations, footwear/luggage/cloakroom arrangements, accommodation policy, transport and parking, medical post and help desk, prohibited items, published dress-code notice, lost-and-found procedure. Always answer from Knowledge and name which published notice it comes from. If Knowledge does not contain the answer, say so plainly and hand over to the trust's staff — never estimate a timing or a fee. You must NOT answer anything doctrinal, scriptural, ritual, astrological or spiritual, must not advise on what a pilgrim should offer, perform or observe, must not interpret or generate religious content, and must not comment on any dispute or on other institutions — route ALL of that to a person of the trust with a short, respectful line. If anyone describes a medical, crowd or safety situation, stop answering and escalate immediately to trust staff. Be brief, warm and factual.",
          agentRoles: [
            { role: "Pilgrim helpline agent (per conversation)", model: "Economy tier — this is the very high-volume step and the work is retrieval plus multilingual restatement of published facts, not reasoning; Sarvam carries the Indian languages, and a fallback model on a second provider means the helpline keeps answering during an outage instead of going silent like the six o'clock landline", prompt: "Answer administrative pilgrim questions strictly from the trust's published Knowledge, in the pilgrim's own language, citing the notice. Refuse and hand over anything doctrinal, ritual, advisory or safety-related. Never estimate a timing, a fee or an availability." },
          ],
          mcp: [
            "Darshan / queue-slot booking system — via Tool (MCP): read-only status lookup for a slot reference the pilgrim quotes (never a booking, never an allocation)",
            "Accommodation allotment system — via Tool (MCP): read-only confirmation status for a cottage booking reference",
            "Trust helpdesk queue — via Tool (MCP): file an escalation with the full conversation attached, so the staff member does not start from nothing",
            "WhatsApp / Voice / SMS — first-party pilgrim channels, answering at any hour",
          ],
          tools: [
            "user-defined tool `enquiry_route` (SpEL): input.category == 'DOCTRINAL' ? 'HUMAN_TRUST_STAFF' : (input.category == 'SAFETY' ? 'ESCALATE_NOW' : 'ANSWER_FROM_KNOWLEDGE') — the trust's own boundary rule as a deterministic formula they can tighten, e.g. adding a category that must always reach a person during a festival week",
            "user-defined tool `counter_directions` (SpEL): input.facility + ' — ' + input.gate + ', open ' + input.openTime + '–' + input.closeTime — the trust's own house phrasing, identical in every language version",
            "knowledge_search (the trust's own published notices only: timings, queue procedure, facilities, accommodation policy, prohibited items — a scoped collection, so nothing unpublished is reachable)",
            "http_request (read-only status lookup for a booking or slot reference the pilgrim quotes)",
          ],
          guardrails: [
            "Scope is administrative and logistical ONLY — the agent answers nothing doctrinal, scriptural, ritual, astrological or spiritual, gives no religious advice, generates no religious content, and routes every such question to a person of the trust",
            "That boundary is enforced twice: by the prompt and by the trust's own `enquiry_route` SpEL rule feeding a Condition node, so it does not depend on model behaviour alone",
            "Every answer is grounded in the trust's own published material in the scoped Knowledge collection and names the notice it came from; where Knowledge is silent the agent says so and hands over rather than estimating a timing, fee or availability",
            "Any mention of a medical emergency, a crowd or safety situation, or a missing person ends the automated conversation and escalates to trust staff immediately — the agent never assesses or advises on a safety situation",
            "The agent takes no bookings, allocates no slot and confirms no accommodation in this workflow — it can only read and restate a status the trust's own system returns",
            "Unanswered questions are logged as a Knowledge gap for the trust to fill, so the fix is publishing the information rather than letting the model fill the void",
          ],
          cost:
            "This is the highest-volume workflow in the pack by a wide margin, so the model tier here is the single biggest cost decision — put it on the economy tier and keep it there. It is genuinely economy-tier work: a tightly scoped Knowledge collection of published notices, retrieval, and restatement in the pilgrim's language. Resist upgrading when an answer is wrong; the fix is almost always a missing or ambiguous notice, which the gap log will show you, not a bigger model.",
          output:
            "WhatsApp, Voice (ElevenLabs + Sarvam) or SMS → the `enquiry_route` SpEL rule and a Condition node enforce the administrative-only boundary → the economy-tier agent answers from the trust's scoped published Knowledge, citing the notice → anything doctrinal, advisory or safety-related is filed to the trust's helpdesk queue with the conversation attached → an Output node replies in the pilgrim's language, and every unanswered question is logged as a Knowledge gap for the trust to publish.",
        },
      },
      {
        id: "temple-donation-receipt",
        title: "Donation and offering intake on UPI → receipt and acknowledgement, treasurer-approved",
        scenario:
          "Today: a devotee in another city wants to contribute towards annadanam. He makes a bank transfer with no reference, then phones the office three days running to ask whether it arrived. In the office, a clerk writes receipts by hand from a counterfoil book, matching bank lines to names by guesswork, and posts them out weeks later — with the donor's name spelt as he heard it on the phone. Acknowledgement letters for tax purposes are a second handwritten queue on top of that, and at least one receipt a month has to be reissued because the name or the fund is wrong.",
        triggerLabel: "WhatsApp / Webhook (donation intent) + Razorpay (UPI payment event)",
        steps: [
          { kind: "trigger", title: "Devotee starts a contribution", detail: "A WhatsApp message or a Webhook from the trust's donation page starts the flow — purpose (annadanam, building fund, general offering), amount, and the name and address the receipt should carry." },
          { kind: "agent", title: "Capture the receipt details correctly", detail: "An Agent node collects and confirms exactly what a valid receipt needs — donor name spelt back for confirmation, postal address, purpose checked against the trust's published list of funds, and whether a tax-exemption acknowledgement is wanted. It works in the donor's own language, which is how the spelling stops being a guess." },
          { kind: "tool", title: "Take the payment on Razorpay UPI", detail: "Razorpay is a real first-party connector: the workflow raises a UPI collect request or payment link for the stated amount, tagged with the purpose, and waits for the payment event. Money is handled by Razorpay, never by the agent — and the reference exists from the first second, which is what kills the reconciliation guesswork." },
          { kind: "condition", title: "Paid, and matched?", detail: "A Condition node proceeds only on a confirmed Razorpay payment event whose amount and reference match the intent; a partial, failed or unmatched payment goes to the office queue rather than generating a receipt." },
          { kind: "code", title: "The trust's own receipt format and acknowledgement rule", detail: "Two user-defined SpEL tools: `receipt_line` formats the receipt body in the trust's exact wording, and `ack_class` decides from the amount and the fund whether a tax-exemption acknowledgement is prepared — the trust's published policy as an editable formula, not a hard-coded rule." },
          { kind: "approval", title: "Treasurer approves before a receipt is committed", detail: "An Approval node holds the drafted receipt and any acknowledgement. A receipt is a financial and legal document of the trust — the treasurer or authorised officer signs it off; the agent only drafts." },
          { kind: "output", title: "Issue and file it", detail: "On approval a Tool (MCP) node writes the entry to the trust's donation ledger and accounting system with the Razorpay reference attached, and an Output node sends the receipt to the donor on WhatsApp and by send_email in their own language — same day, not three weeks." },
        ],
        connectors: ["Razorpay (UPI)", "WhatsApp", "Email", "Webhook", "Sarvam (Indian languages)", "Tool (MCP → donation ledger / accounting)", "Approval / HITL"],
        outcome:
          "A devotee can contribute by UPI from anywhere and receive a correct, treasurer-approved receipt promptly in his own language, with the ledger entry and the payment reference already reconciled — no phone calls asking whether it arrived, no counterfoil book.",
        impact:
          "The mechanism is capturing receipt-quality details at the moment of the donation, and having a payment reference from the first second, rather than reconstructing both afterwards from an unlabelled bank line — that is what makes same-day receipting possible at all, and what removes the misspelt-name reissue. Razorpay handles the money and the confirmation event; the agent never touches funds, and the treasurer still signs every document the trust issues.",
        build: {
          agents: 1,
          agentsNote: "1 intake/receipt-drafting agent. The payment itself is the Razorpay connector, the payment match is a Condition node, the receipt format and acknowledgement rule are SpEL tools, and the treasurer sign-off and ledger write are workflow nodes.",
          pattern: "Detail capture → real UPI collection via Razorpay → strict payment match → trust's own format rules → treasurer approves → ledger + receipt",
          prompt:
            "You take donation details for {{trust}} in the donor's own language. Collect strict JSON {donor_name_for_receipt, postal_address, email, phone, amount, purpose (must be one of the trust's published funds in Knowledge), acknowledgement_wanted (bool), language}. Confirm the spelling of the name and the purpose back to the donor before proceeding — a receipt with a wrong name has to be reissued. Use ONLY the published fund list in Knowledge; if the donor names a purpose that is not on it, ask rather than mapping it yourself. NEVER state that a payment has been received — only the Razorpay payment event does that. Do not ask for or accept any card number, bank account number, UPI PIN or OTP, and never ask for a government ID number; if the donor volunteers one, do not record it. Draft the receipt text for the treasurer to approve — never tell the donor a receipt has been issued before approval.",
          agentRoles: [
            { role: "Donation intake / receipt drafter (per donation)", model: "Standard tier — a receipt is a legal document of the trust and a mis-captured name or fund costs a manual reissue and a phone call; volume is bounded by donations, so the tier is affordable, and a fallback model keeps the donation path open during a provider outage", prompt: "Collect and confirm receipt-grade donor details in the donor's language, restricted to the trust's published fund list. Never confirm payment, never handle or request card/bank/PIN/OTP or ID numbers, and draft the receipt strictly for treasurer approval." },
          ],
          mcp: [
            "Donation ledger / trust accounting system — via Tool (MCP): write the approved entry against the correct fund and record the Razorpay payment reference on it, so reconciliation is already done",
            "Receipt-number series — via Tool (MCP): draw the next receipt number from the trust's own series at approval time, so numbers stay sequential and auditable",
            "Trust donation page — inbound via Webhook: the same flow as the WhatsApp path",
            "Razorpay — first-party connector: raise the UPI collect request tagged with the fund, and receive the payment-confirmation event",
            "WhatsApp / Email — first-party donor channels for the approved receipt",
          ],
          tools: [
            "user-defined tool `receipt_line` (SpEL): input.donorName + ' — ' + input.purpose + ' — ' + input.currency + ' ' + input.amount + ' — Ref ' + input.paymentRef + ' — ' + input.receiptDate — the trust's exact receipt wording as an editable formula, so changing the format for a new financial year needs no developer",
            "user-defined tool `ack_class` (SpEL): input.amount >= input.ackThreshold ? 'ACK_LETTER' : 'RECEIPT_ONLY' — the trust's own published acknowledgement threshold, which the treasurer can raise or lower directly",
            "knowledge_search (published fund list, receipt format, acknowledgement policy and eligibility exactly as the trust has published it)",
            "http_request (draw the receipt number; write the approved ledger entry with the payment reference attached)",
            "send_email (deliver the approved receipt and acknowledgement to the donor)",
          ],
          guardrails: [
            "The agent never confirms a payment — only a matched Razorpay payment event advances the workflow, and a partial, failed or unmatched payment goes to the office queue instead of generating a receipt",
            "No receipt and no tax-exemption acknowledgement is issued without the treasurer's Approval — the agent drafts, an authorised officer of the trust commits",
            "The agent never handles funds and never asks for a card number, bank account number, UPI PIN or OTP; payment is taken entirely by the Razorpay connector",
            "No government ID number is requested or stored, and any volunteered ID number is not recorded — the receipt is drafted from the name, address and purpose only",
            "Fund purposes are restricted to the trust's published list; the agent asks rather than mapping an unfamiliar purpose onto a fund itself",
            "The ledger write and the receipt number draw are keyed to the Razorpay payment reference, so a retried webhook cannot create a duplicate receipt or a double ledger entry",
            "The agent gives no tax advice — the acknowledgement wording and threshold come from the trust's published policy via `ack_class`, and any question beyond that goes to the trust's office",
            "The flow is administrative only: it records a contribution and issues a receipt, and never generates religious content, blessing text or any statement about what a contribution means",
          ],
          cost:
            "Volume is bounded by the number of donations, so there is no runaway multiplier and a standard-tier model is the right call — the failure mode here is a misspelt donor name or a wrong fund, both of which cost a reissue and a phone call, far more than the model difference. If the trust runs a high-volume small-offering season, split the flow: an economy-tier pass for short purpose-only offerings, standard tier only where an address and an acknowledgement are being captured. The formatting and threshold work sits in zero-cost SpEL tools rather than in tokens.",
          output:
            "WhatsApp or the donation-page Webhook → the agent captures and confirms receipt-grade details in the donor's language from the published fund list → Razorpay raises the UPI collection and returns the payment event → the Condition node advances only on a matched payment → `receipt_line` and `ack_class` apply the trust's own format and acknowledgement policy → the treasurer approves at the Approval node → a Tool (MCP) node draws the next receipt number and writes the ledger entry keyed to the Razorpay reference, and an Output node sends the receipt on WhatsApp and via send_email.",
        },
      },
      {
        id: "temple-annadanam-forecast",
        title: "Annadanam and prasad demand forecast → kitchen and procurement handoff",
        scenario:
          "Today: the kitchen in-charge decides at nine the night before how much to cook for annadanam. He does it by pulling out last year's register for roughly the same day, looking at the wall calendar, asking the cottage office how full they are, and adding a bit for the weather — all of it in his head, all of it his alone. If he is short, people who travelled a long way are turned away at the second sitting. If he is long, the surplus is a loss the trust absorbs quietly. The stores list is then written out by hand and given to a volunteer to buy in the morning.",
        triggerLabel: "Scheduled Webhook (evening-before / weekly planning run)",
        steps: [
          { kind: "trigger", title: "Planning run fires", detail: "A scheduled Webhook starts the planning cycle at the cadence the kitchen actually plans on — the evening before, or weekly ahead of a festival period." },
          { kind: "parallel", title: "Gather the real signals at once", detail: "A Parallel node pulls simultaneously: booked darshan slots and accommodation occupancy from the trust's systems (via Tool (MCP)), the trust's own calendar entries from Knowledge, and the historical served counts the kitchen already writes in its register. The same four inputs the in-charge uses — assembled in seconds instead of from memory." },
          { kind: "agent", title: "Build the forecast", detail: "An Agent node produces a served-count range — not a single number — per sitting, showing which signal drove it (slot bookings, occupancy, a calendar day, a comparable past day) and stating its uncertainty honestly." },
          { kind: "code", title: "Convert to quantities deterministically", detail: "A Code node plus a user-defined SpEL tool, `per_head_qty`, turn the counts into ingredient quantities using the kitchen's own documented per-head portions. This is arithmetic against published recipes, so it belongs in a formula the kitchen can edit for a festival menu — not in a model's head." },
          { kind: "approval", title: "Kitchen in-charge decides the number", detail: "An Approval node hands the range and the draft procurement list to the kitchen in-charge, who sets the actual number. Committing food quantity and spend is the trust's decision, and the person who knows the day is the one who makes it — now with the four signals in front of him instead of in his memory." },
          { kind: "output", title: "Issue the plan", detail: "On approval an Output node sends the confirmed sitting plan and procurement list to the kitchen and stores teams on WhatsApp and via send_email, and a Tool (MCP) node files it against the trust's stores system — so the volunteer buying in the morning has a costed list, not a scrap of paper." },
        ],
        connectors: ["Webhook (scheduled)", "WhatsApp", "Email", "Knowledge base", "Tool (MCP → slot/accommodation systems + stores)", "Approval / HITL"],
        outcome:
          "The kitchen in-charge starts each planning cycle with a reasoned served-count range, its drivers, and a costed ingredient list to adjust — instead of last year's register, a wall calendar and his own memory at nine at night.",
        impact:
          "The mechanism is assembling signals that already exist but sit in four different places — booked slots, cottage occupancy, the trust's own calendar, last year's served counts — into one view at the moment the decision is made. It deliberately outputs a range with its drivers rather than a single confident number, because a forecast presented as certainty is worse than no forecast; the commitment stays with the person who knows the day.",
        build: {
          agents: 2,
          agentsNote: "1 forecasting agent (once per planning run) + 1 procurement-conversion agent (once, after the forecast). The parallel signal gathering, the per-head arithmetic, the kitchen in-charge's decision and the stores filing are workflow nodes and tools.",
          pattern: "Parallel signal gather → range forecast with drivers → recipe-grounded procurement draft → kitchen in-charge commits the number",
          prompt:
            "(Forecast) You prepare an annadanam planning estimate for {{trust}}. From the supplied signals — booked darshan slots, accommodation occupancy, the trust's own calendar entries, and recorded historical served counts for comparable days — produce per meal sitting {low, likely, high} served counts, the ONE signal that most drove the estimate, and an explicit confidence (LOW|MEDIUM|HIGH). Use only the supplied data and the trust's own records; never invent a historical figure and never use outside assumptions about attendance. If a comparable historical day is missing, say so and mark confidence LOW rather than extrapolating. Output a RANGE, never a single number — the kitchen in-charge sets the actual figure. This is an operational planning estimate only: do not comment on the observance a sitting relates to and do not produce any religious or ritual content.",
          agentRoles: [
            { role: "Forecaster (once per planning run)", model: "Standard tier — this is the hard step: weighing several partial signals and being honest about uncertainty, and it runs only once per cycle so the tier costs little in absolute terms", prompt: "Produce per-sitting {low, likely, high} served counts from the supplied signals only, name the dominant driver, and state confidence. Never invent a historical figure; missing comparables mean LOW confidence, not extrapolation. Range only, never a single number." },
            { role: "Procurement converter (once, after the forecast)", model: "Economy tier — this is arithmetic against the kitchen's documented per-head recipes, not judgement; the Code node and the `per_head_qty` SpEL tool do the actual multiplication", prompt: "Convert the served-count range into ingredient quantities using ONLY the documented per-head portions and recipes in Knowledge. Show the per-head basis for each line so the kitchen can check it. Never substitute an ingredient, never assume a portion size that is not documented." },
          ],
          mcp: [
            "Darshan / queue-slot booking system — via Tool (MCP): read booked slots per sitting for the target day",
            "Accommodation allotment system — via Tool (MCP): read cottage occupancy and expected check-ins, the signal the in-charge currently gets by walking to the cottage office",
            "Kitchen served-count records — via Tool (MCP): read the recorded counts for comparable past days, replacing the annual register hunt",
            "Kitchen stores / procurement system — via Tool (MCP): file the approved sitting plan and ingredient list against stores, and pull current stock on hand",
            "WhatsApp / Email — first-party channels to the kitchen and stores teams",
          ],
          tools: [
            "user-defined tool `per_head_qty` (SpEL): input.perHeadGrams * input.servedCount / 1000 — the kitchen's own per-head portion arithmetic as an editable formula, adjusted for a festival menu without touching code",
            "user-defined tool `planning_number` (SpEL): input.confidence == 'LOW' ? input.high : input.likely — the trust's own conservatism rule, so a low-confidence day is planned to the top of the range on purpose rather than by argument",
            "knowledge_search (the trust's calendar entries, documented per-head portions and recipes, historical served-count records)",
            "http_request (read booked slots, occupancy and stock on hand; file the approved plan against stores)",
            "send_email (issue the confirmed sitting plan and procurement list to the stores team)",
          ],
          guardrails: [
            "The agent forecasts, it does not decide — the kitchen in-charge sets the committed number and approves the procurement list at the Approval node before any spend or cooking quantity is fixed",
            "Output is always a range with its dominant driver and an explicit confidence; a single confident number is prohibited, because a forecast that hides its uncertainty is worse than none",
            "Estimates use only the trust's own signals and records — booked slots, occupancy, its own calendar, its own served-count history; no outside attendance assumptions and no invented historical figures",
            "Procurement quantities are computed by a Code node and the kitchen's own SpEL per-head formula against documented portions, with the per-head basis shown per line so a human can check it; no ingredient substitution",
            "The agent makes no food-safety, hygiene or dietary judgement of any kind — quantity planning only; anything of that nature is the kitchen's own process",
            "The forecast is planning input for the trust's administration and carries no religious or ritual significance — it never comments on the observance a sitting relates to",
          ],
          cost:
            "This runs once per planning cycle, not per pilgrim, so absolute cost is small — spend it where the judgement is. One standard-tier forecasting call is worth it because that is the step weighing partial signals; the procurement conversion is arithmetic against documented recipes and belongs on the economy tier, with the multiplication itself done by a zero-cost SpEL tool rather than by tokens. The Parallel gather is I/O, not model calls, so widening the signal set costs latency rather than money.",
          output:
            "A scheduled Webhook fires → a Parallel node gathers booked slots, occupancy, calendar entries and historical served counts → the standard-tier forecaster returns per-sitting {low, likely, high} with its dominant driver and confidence → `planning_number` applies the trust's conservatism rule and a Code node plus `per_head_qty` compute ingredient quantities from documented recipes → the kitchen in-charge sets the number and approves at the Approval node → an Output node issues the sitting plan and costed procurement list to the kitchen and stores teams on WhatsApp and by send_email, and a Tool (MCP) node files it against stores.",
        },
      },
      {
        id: "temple-crowd-advisory",
        title: "Crowd-pressure signal → advisory drafted for the trust, never issued by an agent",
        scenario:
          "Today: on a heavy day the queue is held by volunteers with a rope line and one hand-held mic, shouting the same instruction down a line that stretches past where the sound carries. The office is answering the same question on a hundred calls — how long is the wait — while trying to manage the situation. When the trust decides to ask people to arrive later, someone has to write that message, find three people to translate it, and get it onto the trust's channels; by then it is twenty minutes old and the pilgrims already inside the complex have heard none of it.",
        triggerLabel: "Webhook (queue/slot system pressure signal or duty-officer raise)",
        steps: [
          { kind: "trigger", title: "A pressure signal arrives", detail: "A Webhook from the queue/darshan slot system (waiting time or throughput crossing a threshold the trust itself sets) or a duty officer raising it manually from the rope line. The slot system has no first-party connector; it reaches the workflow via Webhook and Tool (MCP)." },
          { kind: "tool", title: "Assemble the current picture", detail: "A Tool (MCP) node reads current slot occupancy, queue-line status and accommodation check-ins so the draft describes what the trust's own systems are actually reporting — nothing beyond that." },
          { kind: "code", title: "The trust's own pressure bands", detail: "A user-defined SpEL tool, `pressure_band`, compares the reported wait against thresholds the trust set, and decides whether this is an officer-attention case or a routine notice. The trust owns those numbers and edits them for a festival week — the model is never asked to judge when a situation is serious." },
          { kind: "agent", title: "Draft an advisory for the trust to consider", detail: "An Agent node writes a short factual draft: the currently reported wait, which gates the published procedure directs to, and the trust's own published guidance for a heavy day. It drafts; it makes no operational recommendation about closing, holding or diverting." },
          { kind: "parallel", title: "Prepare every language at once", detail: "A Parallel node prepares the draft in the languages the pilgrim base actually uses via Sarvam, so approving once approves a complete, fact-identical bundle — instead of starting a hunt for three translators at the worst possible moment." },
          { kind: "approval", title: "The trust's officer decides — always", detail: "An Approval node holds everything. A crowd-safety judgement and any public advisory belong to the trust's officers and the civil authorities, never to an agent. The workflow's job is to have the message ready the instant a human decides to send one." },
          { kind: "output", title: "Publish only what was approved", detail: "On approval an Output node sends the exact approved text to pilgrims on WhatsApp and SMS and to the trust's Telegram channel, in each language — reaching the people already inside the complex, which the mic never does." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS / Twilio", "Telegram", "Sarvam (Indian languages)", "Tool (MCP → queue/slot + accommodation systems)", "Approval / HITL"],
        outcome:
          "When pressure builds, the trust's officer has a factual, multilingual advisory already drafted and ready to release — and the decision about whether to release it, and what it says, stays entirely theirs.",
        impact:
          "The mechanism is removing the drafting and translation delay from the critical path, not removing the human from it: on a heavy day the bottleneck is producing an accurate message in six languages while also managing the rope line, so the workflow does that part in advance and the channels reach people the mic cannot. Every safety judgement remains with the trust's officers and the civil authorities — the agent only surfaces and escalates, and the threshold that decides what is serious is a number the trust wrote.",
        build: {
          agents: 2,
          agentsNote: "1 drafting agent (once per signal) + 1 translation agent run once per language through the Parallel node. The threshold signal, the systems read, the pressure-band formula, the officer's approval and the publish are workflow nodes and tools.",
          pattern: "Threshold signal → grounded draft → parallel translate → trust officer decides → publish only the approved text",
          prompt:
            "You draft administrative crowd advisories for {{trust}}, for an officer of the trust to review. From the supplied queue/slot and accommodation data plus the trust's published heavy-day guidance in Knowledge, write a factual draft of at most 60 words: the currently reported waiting time, the gate or entry the published procedure directs to, and the trust's own published guidance. Use ONLY the supplied readings and the published guidance — never estimate a wait, never predict how a crowd will behave, and never invent a gate or a timing. You must NOT recommend closing an entry, holding a queue, diverting pilgrims, stopping darshan, or any other operational or safety action; if the data looks serious, add a single line flagging that it needs an officer's attention and stop. Include no religious or ritual content of any kind. This is a DRAFT — you never publish, and you never imply the trust has decided anything.",
          agentRoles: [
            { role: "Advisory drafter (once per signal)", model: "Standard tier — it must stay strictly inside the supplied readings and refuse to make operational recommendations under pressure, which is exactly where a weaker model drifts; a fallback model on a second provider matters here because a heavy day is when you cannot wait out an outage", prompt: "Write a ≤60-word factual draft from the supplied queue/accommodation readings and the trust's published heavy-day guidance. No estimated waits, no crowd predictions, no invented gates, no operational or safety recommendation, no religious content. Flag seriousness for an officer and stop." },
            { role: "Translator (once per language, via Parallel)", model: "Economy tier — a constrained, fact-locked rewrite through Sarvam; it runs once per language so it is the multiplier", prompt: "Translate the officer-bound draft with waiting times, gate names and guidance byte-identical to the source. Use the official published form of every gate or facility name from Knowledge. Add nothing, soften nothing, drop nothing." },
          ],
          mcp: [
            "Queue / darshan slot system — via Tool (MCP): read current occupancy, reported waiting time and throughput per entry; the threshold crossing itself arrives inbound via Webhook",
            "Accommodation allotment system — via Tool (MCP): read check-in load, since arrivals staying overnight change the shape of the day",
            "Trust broadcast subscriber list — via Tool (MCP): resolve which pilgrims are subscribed and in which language, so one approval covers the right audience",
            "WhatsApp / SMS / Telegram — first-party pilgrim channels, reaching people already inside the complex",
          ],
          tools: [
            "user-defined tool `pressure_band` (SpEL): input.waitMinutes >= input.redMins ? 'OFFICER_NOW' : (input.waitMinutes >= input.amberMins ? 'DRAFT_ADVISORY' : 'LOG_ONLY') — the trust's own thresholds, raised or lowered by the trust for a festival week without a developer, so the model never decides what counts as serious",
            "user-defined tool `advisory_stem` (SpEL): input.gate + ': current reported wait ' + input.waitMinutes + ' minutes (as at ' + input.readingTime + ')' — the trust's exact house phrasing, identical across every translation",
            "knowledge_search (the trust's published heavy-day guidance, gate and entry procedure, official facility names)",
            "http_request (read current slot occupancy, queue status and accommodation check-ins)",
          ],
          guardrails: [
            "An agent NEVER makes a crowd-safety call and never publishes — closing an entry, holding a queue, diverting or stopping darshan are decisions for the trust's officers and the civil authorities; the workflow surfaces and escalates, nothing more",
            "The Approval node is mandatory and covers the English draft and every translation together, so an officer approves the complete bundle that will actually go out",
            "The seriousness threshold is the trust's own SpEL formula against numbers it set, not a model judgement — that keeps the safety boundary in the trust's hands",
            "The draft contains only what the trust's own systems reported and what the trust has published — no estimated waiting times, no predictions of crowd behaviour, no invented gates or timings",
            "The agent is prohibited from making any operational recommendation; where the readings look serious it flags that an officer's attention is needed and stops there",
            "No religious, ritual or doctrinal content appears in an advisory — these are administrative notices about queues, gates and timings only",
            "Translations are fact-locked to the approved source and use the official published names of gates and facilities, so approving once cannot let a softened or drifted version reach a different language group",
            "Idempotent per signal id, so a threshold that flickers does not produce repeated advisory blasts to pilgrims",
          ],
          cost:
            "Fires only when the trust's own threshold is crossed, so volume is low and bursty — the sizing question is the per-signal bundle, not a steady rate. One standard-tier draft plus L economy-tier translations is the right shape: translation is the multiplier and is fact-locked rewriting, while the single draft is where a drift into recommending an action would actually matter. Putting the threshold in a SpEL tool rather than asking a model to judge seriousness keeps both the cost and the safety boundary in the right place.",
          output:
            "A queue/slot threshold Webhook or a duty-officer raise → a Tool (MCP) node reads current occupancy, queue status and check-ins → `pressure_band` applies the trust's own thresholds → the standard-tier agent drafts a ≤60-word factual advisory with no operational recommendation → a Parallel node prepares every language via Sarvam, fact-locked → an officer of the trust approves or rejects the complete bundle at the Approval node → an Output node publishes only the approved wording to pilgrims on WhatsApp and SMS and to the trust's Telegram channel.",
        },
      },
    ],
  },
];
