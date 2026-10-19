import type { Industry } from "./recipes-data";
import { TestTube, HeartHandshake } from "lucide-react";

// ---------------------------------------------------------------------------
// Diagnostics-lab/pathology & Elder-care/home-nursing workflow recipes — same
// "inline copy, no i18n keys" precedent and honesty rules as recipes-data.tsx.
// Every recipe is expressed ONLY in VegaDūta's real building blocks (Trigger /
// Agent / Knowledge / Condition / Approval / Output / Tool(MCP) / Voice /
// Device / Loop / Code / Parallel / Supervisor / Sub-workflow), so each maps
// 1:1 to something you can actually build in the Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — the LIMS, the HIS/EMR, the radiology
// RIS/PACS, the insurance/TPA pre-auth portal, the home-care rostering app, the
// billing system, BP cuffs / glucometers / fall sensors — are reached the real
// way, via an inbound Webhook trigger and/or a Tool (MCP) node, and each mcp[]
// entry names the ACTUAL system and the ACTUAL call. Only real channels are
// used (Webhook, WhatsApp, SMS/Twilio, Voice/Call, Email, Slack, Telegram,
// ElevenLabs, Sarvam, Razorpay UPI).
//
// User-defined tools, described honestly: in this platform a tenant-authored
// tool is a SpEL expression evaluated against the tool's JSON input. It is
// sandboxed by construction — no method calls, no constructors, no bean or type
// references, and the grammar contains no loop — so it is a data-transform /
// formula tool, NOT a scripting engine. It cannot call an API or run arbitrary
// code. What it IS good for is exactly what a lab or a care agency needs: pull
// a field out of the tool input, format a string, do arithmetic, branch with a
// ternary or elvis. That lets the tenant encode THEIR OWN critical limits,
// acuity slabs and re-collect rules deterministically, without a developer and
// without a new deployment — and, because it is deterministic, it is a far
// better home for a threshold than a model prompt.
//
// Clinical-safety rule, stricter than the general one and applying to every
// recipe below: an agent NEVER diagnoses, never interprets a result for a
// patient, and never adds, stops or changes a medication or a dose. It triages,
// summarises, routes, chases and escalates — and it PROPOSES. A qualified human
// (pathologist, clinician, nurse-in-charge, prescriber) owns every consequential
// act: releasing a clinical result, escalating a critical value, changing a care
// plan, dispatching a nurse, cancelling a test, and any money movement.
// Impact lines describe the mechanism (why it helps), never invented ROI
// numbers, and no agent count is inflated.
// ---------------------------------------------------------------------------

export const INDUSTRIES_DIAGELDER: Industry[] = [
  // ============================================== DIAGNOSTICS LABS & PATHOLOGY
  {
    id: "diagnostics",
    name: "Diagnostics labs & pathology",
    icon: TestTube,
    tagline:
      "Chase critical values until a clinician actually acknowledges, turn the rejection whiteboard into re-collects, and deliver reports in the patient's language — with a pathologist owning every clinical release.",
    recipes: [
      {
        id: "diag-critical-value",
        title: "Critical-value escalation that doesn't stop until a clinician acknowledges",
        scenario:
          "02:40, one tech on the night bench. The analyser posts a potassium of 7.1. She notices it because she is scrolling the LIMS worklist between runs — there is no alarm. She opens the hard-bound 'Critical Value Callback Register' kept next to the centrifuge, rules the columns by hand, then looks up the ordering doctor in a printed on-call list taped to the wall (last revised in March). She dials from the landline. It rings out. She writes 'NR' in the register and goes back to her run, meaning to try again. At 06:00 handover the day tech sees 'NR' in yesterday's ink and nobody is sure whether anyone ever called. That register — plus a WhatsApp group where the night staff sometimes post a photo of the result — is the entire escalation system.",
        triggerLabel: "Webhook (LIMS result verified / analyser middleware autoverify)",
        steps: [
          { kind: "trigger", title: "Result verifies in the LIMS", detail: "A Webhook fires on every verified result — no worklist scrolling, no dependence on someone looking up. The LIMS/analyser middleware has no first-party connector: results arrive via inbound Webhook, and anything written back goes through a Tool (MCP) node." },
          { kind: "knowledge", title: "Load YOUR critical-value table", detail: "A scoped Knowledge collection holds this lab's own critical/panic limits, delta-check rules and per-analyte callback SOP — age- and sex-banded exactly as your medical director signed them. General medical knowledge is out of bounds; only the signed table is consulted." },
          { kind: "tool", title: "Deterministic threshold check (user-defined SpEL tool)", detail: "A tenant-authored tool `critical_flag` (a sandboxed SpEL expression over the tool's JSON input, e.g. input.value > input.upperLimit ? 'CRITICAL' : (input.value < input.lowerLimit ? 'LOW' : 'NORMAL')) does the actual numeric comparison. A threshold belongs in a deterministic formula the lab can edit itself, not in a prompt — and your medical director can revise it without a developer or a redeploy." },
          { kind: "agent", title: "Assemble the callable fact (no interpretation)", detail: "An Agent node takes the deterministic flag and returns strict JSON {critical, analyte, value, limit_breached, ordering_clinician, patient_ref}. It resolves who ordered it and formats the callable fact — it does NOT interpret, diagnose or suggest treatment, and it never overrules the SpEL flag." },
          { kind: "condition", title: "Critical, routine, or unresolvable?", detail: "A Condition node branches: routine results fall to the normal report flow; a flagged critical enters the callback ladder; an unmapped analyte or unresolvable clinician goes to a human instead of being dropped." },
          { kind: "approval", title: "Duty pathologist owns the call", detail: "An Approval node holds the escalation for the on-duty pathologist or lab director — the judgement that this value is real and callable is a clinician's, and the trail records who made it. A hard timeout escalates UP the human ladder; it is never a bypass that lets the agent call alone." },
          { kind: "voice", title: "Escalation ladder with real retries", detail: "On approval a Voice/Call node (ElevenLabs, Sarvam-localised for Indian-language recipients) calls the ordering clinician with analyte, value, reference limit and a callback reference, backed by SMS and WhatsApp. It reads out facts and asks for a read-back; it never offers advice." },
          { kind: "loop", title: "Keep going until acknowledged", detail: "A Loop node re-runs the documented ladder — ordering clinician, covering doctor, ward/nursing station — at the SOP's own intervals until an explicit acknowledgement is captured or the ladder is exhausted. This is the 'NR in the register' failure, closed." },
          { kind: "output", title: "Write the read-back back", detail: "A Tool (MCP) node writes the callback record — who was reached, when, and the read-back confirmation — into the LIMS against the result id. An exhausted ladder raises a supervisor alert rather than closing itself." },
        ],
        connectors: ["Webhook", "Knowledge base", "Voice / Call", "ElevenLabs", "Sarvam", "SMS / Twilio", "WhatsApp", "Approval / HITL", "Tool (MCP → LIMS / HIS)"],
        outcome:
          "Every verified result is checked against your own signed table the moment it posts, and flagged ones ride a persistent, documented callback ladder — replacing the hand-ruled register, the taped-up on-call list and 'NR'.",
        impact:
          "The mechanism is persistence plus proof. Critical-value reporting rarely fails at detection; it fails at the unfinished chase and the missing read-back record. A Loop that keeps escalating on the SOP's own intervals removes the first, and a Tool node that writes who acknowledged and when removes the second — while the decision to call stays with the pathologist and the threshold itself stays in a formula the lab controls.",
        build: {
          agents: 2,
          agentsNote:
            "1 fact-assembly agent (runs on every verified result — the high-volume step) + 1 escalation-scripting agent (runs only on the flagged minority). The numeric threshold check is a deterministic user-defined SpEL tool, not an agent; the ladder, the branch and the approval are workflow nodes.",
          pattern: "Deterministic SpEL threshold → cheap per-result agent → Condition → pathologist approval → Voice/SMS ladder in a retry Loop",
          prompt:
            "You prepare ONE verified laboratory result for critical-value callback. The CRITICAL/LOW/NORMAL flag has already been computed deterministically by the `critical_flag` tool against this lab's signed table — take it as given and NEVER overrule it. Return strict JSON {critical, analyte, value, units, limit_breached, age_band, patient_ref, ordering_clinician, callback_ref}. You must NOT interpret the result, suggest a diagnosis, suggest treatment, or comment on the patient's condition. If units are ambiguous, the analyte is unmapped, or the ordering clinician cannot be resolved from the HIS, set needs_human=true with a reason — never guess a clinician and never assume a unit.",
          agentRoles: [
            { role: "Fact assembler (per verified result)", model: "Economy tier, with a configured fallback model for provider outages — runs on EVERY result, so this choice sets the whole bill; a lab cannot have callbacks stop because one provider is down", prompt: "Take the deterministic SpEL flag as authoritative, resolve the ordering clinician from the HIS, emit strict JSON. No interpretation, no diagnosis, no treatment comment. Unmapped analyte or unresolvable clinician ⇒ needs_human=true." },
            { role: "Escalation scripter (per flagged value)", model: "Standard tier with fallback — runs only on the flagged minority, where wording accuracy on a phone call matters", prompt: "Compose the callback script and SMS/WhatsApp backup for an APPROVED critical value: analyte, value, units, reference limit, patient identifier, callback reference, and the explicit read-back question. Facts only — no advice, no interpretation, no reassurance. Localise via Sarvam when the recipient's registered language is not English." },
          ],
          mcp: [
            "LIMS — via Tool (MCP): read the verified result payload and write the callback record (who was reached, when, read-back confirmation) against the result id",
            "HIS / EMR — via Tool (MCP): resolve ordering clinician, department and current on-call cover for the patient encounter",
            "Hospital on-call roster — via Tool (MCP): fetch tonight's covering doctor when the orderer is off-shift, replacing the printed list taped to the wall",
          ],
          tools: [
            "user-defined tool `critical_flag` (SpEL): input.value > input.upperLimit ? 'CRITICAL' : (input.value < input.lowerLimit ? 'LOW' : 'NORMAL') — the lab's own limits, edited by the medical director in the tool editor, no developer and no redeploy",
            "user-defined tool `callback_ref` (SpEL): input.sampleId + '-' + input.analyte + '-' + input.verifiedAt — a stable, idempotent callback key so a re-posted result can never open a second ladder",
            "knowledge_search (signed critical-value limits, delta checks and callback SOP)",
            "http_request (resolve ordering clinician and on-call cover; write the callback record to the LIMS)",
          ],
          guardrails: [
            "The agent never diagnoses, never interprets a value, never suggests treatment — it reports only which documented limit was crossed",
            "The threshold decision is deterministic (the `critical_flag` SpEL tool over your signed table), so it cannot drift with a model change or a prompt edit; the agent is explicitly forbidden from overruling it",
            "The escalation is released by the on-duty pathologist at the Approval node — the agent proposes the call, a clinician owns it",
            "The approval timeout escalates UP the human ladder; there is no path in which the workflow calls a clinician without a human having released it",
            "Unmapped analyte, ambiguous units or unresolvable clinician ⇒ needs_human=true and routed to a person — no silent drop, no guessed recipient",
            "An exhausted ladder alerts a supervisor; the workflow can never close a critical value as 'done' by itself",
            "Every attempt and the read-back are written back keyed to `callback_ref` — auditable, and idempotent against a re-verified result",
          ],
          cost:
            "Volume sits entirely on the fact assembler, which touches every verified result — economy tier, hard-capped, with a fallback model configured so a provider outage degrades to a second provider rather than to the paper register. The standard-tier scripter runs only on the flagged subset, so its price barely registers monthly. Note that moving the threshold comparison out of the model and into the SpEL tool also removes tokens from the highest-volume step: the cheapest inference is the one you don't make. Retries in the Loop replay the composed script, so they cost telephony minutes, not tokens.",
          output:
            "Every verified result hits the `critical_flag` SpEL tool for a deterministic verdict → a cheap agent resolves the clinician and assembles the callable fact → the Condition node splits routine from critical from unresolvable → the duty pathologist approves at the Approval node → an ElevenLabs Voice call plus SMS/WhatsApp backup runs the documented ladder inside a retry Loop until an explicit read-back is captured → a Tool (MCP) node writes the callback record into the LIMS, and an exhausted ladder alerts a supervisor instead of closing.",
        },
      },
      {
        id: "diag-sample-rejection",
        title: "The rejection whiteboard → re-collects, batched (map-reduce, 2 agents)",
        scenario:
          "In accessioning there is a whiteboard headed REJECTED with today's date, and a marker on a string. Haemolysed, clotted, QNS, wrong tube, unlabelled, transport time exceeded — the tech writes barcode numbers under each heading through the shift. At around 4pm someone photographs the board into the 'Collection Boys' WhatsApp group. The front desk keeps a parallel Google Sheet called REDRAW_pending with a column nobody fills in reliably, and decides case by case whether the patient is charged for the re-draw. Most patients only learn their sample failed when they ring three days later asking where the report is — and then the front desk has to find the barcode on a photo of a whiteboard that was wiped clean on Tuesday.",
        triggerLabel: "Webhook (LIMS rejection batch — per run / end of shift)",
        steps: [
          { kind: "trigger", title: "Rejection batch lands", detail: "A scheduled Webhook fires with the run's rejection list straight from the LIMS — the board and the photo disappear because the source of truth is the system that rejected the sample." },
          { kind: "loop", title: "Map: one pass per rejected sample", detail: "A Loop node fans a cheap mapper across each rejection, isolated context per sample, so a 200-line batch behaves exactly like a 6-line one." },
          { kind: "tool", title: "Chargeability by formula (user-defined SpEL tool)", detail: "A tenant-authored tool `recollect_charge` (SpEL over the tool input, e.g. input.reasonCode == 'QNS' || input.reasonCode == 'CLOTTED' ? 'LAB_BEARS' : (input.collectedAt == 'HOME' ? 'VISIT_FEE' : 'NO_CHARGE')) applies the lab's own re-collect policy deterministically. Whether the patient pays is a policy question, not a language question — it belongs in an expression the lab's own manager can edit, and it will answer identically every time." },
          { kind: "agent", title: "Mapper classifies + proposes an action", detail: "Strict JSON per sample: reason code (HAEMOLYSED | CLOTTED | QNS | WRONG_TUBE | UNLABELLED | TRANSPORT_TIME), the collector and route, the chargeability verdict from the SpEL tool, and a proposed action — RECOLLECT_HOME / RECOLLECT_CENTRE / CANCEL / CLARIFY. It proposes; it never cancels a test." },
          { kind: "code", title: "De-duplicate and key the batch", detail: "A Code node normalises barcodes and drops duplicates from a re-sent export, so re-running a batch cannot produce two re-collect orders for one sample — the failure mode a photographed whiteboard guarantees." },
          { kind: "agent", title: "Reduce: roll the run up", detail: "A reducer aggregates validated items into a run summary: reason-code counts, which collector, centre, route or tube lot recurs, the re-collect worklist, and the items needing a decision. Pre-analytical failures cluster — the whiteboard makes the cluster invisible." },
          { kind: "approval", title: "Lab in-charge approves", detail: "An Approval node holds every cancellation, every chargeable re-collect and every patient-facing message — cancelling an ordered test and re-billing a patient are both consequential, so a human clicks." },
          { kind: "output", title: "Tell the patient in their language", detail: "On approval, an Output node messages the patient on WhatsApp/SMS (Sarvam-localised): what must be repeated, in plain non-clinical terms, and how to rebook. The message never states or implies a clinical finding." },
          { kind: "tool", title: "Write the outcome back", detail: "A Tool (MCP) node posts approved cancellations, re-collect orders and charge flags to the LIMS and billing, keyed to the normalised barcode." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS / Twilio", "Sarvam", "Approval / HITL", "Tool (MCP → LIMS / billing / home-collection app)", "Email"],
        outcome:
          "The whole rejection batch is triaged into a decision-ready worklist each run, patients hear from you the same day in a language they read, and every cancellation or charge is a human click — with the whiteboard and the parallel Google Sheet both retired.",
        impact:
          "Two mechanisms. First, the patient learns about a failed sample from the lab rather than from a missing report, because the trigger is the rejection itself rather than someone remembering to photograph a board. Second, the reducer surfaces the clustering — one collector, one tube lot, one long evening route — that a per-sample manual read structurally cannot reveal. Fixing the cluster is what actually reduces repeat rejections; the batch pass is what makes the cluster visible.",
        build: {
          agents: 2,
          agentsNote:
            "1 mapper (once per rejected sample via the Loop node) + 1 reducer (once per batch). Chargeability is a deterministic SpEL tool; de-duplication is a Code node; the approval, the patient message and the write-back are workflow nodes.",
          pattern: "Map-reduce: Loop (map per sample, SpEL for policy) → Code de-dupe → reducer (one synthesis) → human approves cancellations, charges and messages",
          prompt:
            "(Mapper) Triage ONE rejected specimen record. Return strict JSON {sample_id, test_ordered, reason_code (HAEMOLYSED|CLOTTED|QNS|WRONG_TUBE|UNLABELLED|TRANSPORT_TIME|OTHER), collector_ref, centre_ref, route_ref, charge_verdict (copied verbatim from the `recollect_charge` tool — never your own opinion), proposed_action (RECOLLECT_HOME|RECOLLECT_CENTRE|CANCEL|CLARIFY), patient_message_points[]}. Judge only from the record's own fields and the rejection-criteria SOP in Knowledge. Never infer a clinical meaning from a rejection, never state or hint at any result, and never mark a test cancelled — CANCEL is a recommendation a human approves. Malformed or ambiguous ⇒ proposed_action=CLARIFY.",
          agentRoles: [
            { role: "Mapper (per rejected sample)", model: "Economy tier with a fallback model — runs N times per batch, so it is the dominant cost term and must survive a provider blip mid-batch", prompt: "Classify ONE rejection into schema-valid JSON with a proposed action and plain-language patient message points; copy the chargeability verdict from the SpEL tool verbatim. No clinical inference, no result disclosure, no autonomous cancellation. Ambiguous ⇒ CLARIFY." },
            { role: "Reducer (once per batch)", model: "Standard tier with fallback — pattern-finding across the run, and the only output a manager reads", prompt: "Aggregate validated per-sample results into a run summary: reason-code distribution, recurring collector/centre/route/tube-lot clusters, the re-collect worklist, and items needing a human decision. Use only supplied items and cite real counts — never estimate a rate, never invent a trend." },
          ],
          mcp: [
            "LIMS — via Tool (MCP): pull the run's rejection export, then post approved cancellations and re-collect orders against the normalised barcode",
            "Billing system — via Tool (MCP): set or clear the re-collect charge flag on the patient's account once a human has approved it",
            "Home-collection / phlebotomy scheduling app — via Tool (MCP): create the approved re-draw visit and read the collector's route for that day",
          ],
          tools: [
            "user-defined tool `recollect_charge` (SpEL): input.reasonCode == 'QNS' || input.reasonCode == 'CLOTTED' ? 'LAB_BEARS' : (input.collectedAt == 'HOME' ? 'VISIT_FEE' : 'NO_CHARGE') — the lab's own re-collect policy as a formula the operations manager edits directly, so who pays is decided identically every time instead of case by case at the front desk",
            "user-defined tool `patient_ref_mask` (SpEL): input.name.substring(0,1) + '*** / ' + input.barcode — a masked identifier for internal worklists and the WhatsApp/SMS trail, so a batch summary never carries a full patient name",
            "knowledge_search (specimen rejection criteria + re-collect/re-billing policy)",
            "http_request (pull the rejection batch; post approved cancellations, re-collect orders and charge flags)",
          ],
          guardrails: [
            "The agents only propose — every cancellation, every chargeable re-collect and every patient-facing message passes the Approval node before anything is written or sent",
            "Patient messages are strictly operational (what to repeat, how to rebook). No result, no clinical interpretation, no reassurance about what a rejection 'means'",
            "Chargeability is decided by the `recollect_charge` SpEL formula, not by the model — the agent copies the verdict verbatim and is forbidden from forming its own opinion about who pays",
            "Reason codes come only from your rejection-criteria SOP in Knowledge — no invented policy",
            "Schema validation per mapper output; a malformed record becomes CLARIFY and is queued for a human, never silently cancelled",
            "The Code node normalises and de-duplicates barcodes, and write-backs are idempotent per sample id — a re-sent export cannot double-cancel or double-charge",
          ],
          cost:
            "Linear and forecastable: N × (economy-tier, hard-capped mapper) + ONE standard-tier reduce. The mapper runs across the whole batch so it must stay on the economy tier — that single choice sets the bill; the reducer's one call is where the reasoning budget belongs. Pushing the chargeability rule into SpEL removes the most token-hungry ambiguity from the per-sample step. Run the map in Parallel batches for wall-clock speed at unchanged per-sample cost, and configure a fallback model so a provider blip does not strand a half-processed run.",
          output:
            "The Loop maps a cheap agent over every rejected sample, with the `recollect_charge` SpEL tool deciding who pays → a Code node normalises barcodes and drops duplicates → the reducer produces one run summary with the recurring clusters → the lab in-charge approves cancellations, charges and messages at the Approval node → an Output node notifies patients on WhatsApp/SMS in their own language (Sarvam), operational detail only → a Tool (MCP) node posts approved outcomes to the LIMS, billing and the home-collection app, idempotently.",
        },
      },
      {
        id: "diag-report-delivery",
        title: "Report delivery in the patient's language — with a hard no-interpretation boundary",
        scenario:
          "The pathologist signs the report at 11:20. A front-desk executive then opens the LIMS in one window and the shared Gmail inbox (reports@) in another, downloads the PDF, copies the patient's email from the LIMS demographics tab, attaches, types 'PFA report', and sends — one patient at a time, roughly two hundred of them, from a single browser tab. A peon carries the hard copies to the collection centres in a plastic folder. Then the phone starts: 'sir, is this bad?' — asked in Kannada or Hindi to whoever picks up, who is a receptionist, and who answers anyway because the caller is frightened and the queue is long.",
        triggerLabel: "Webhook (report authorised/signed in the LIMS)",
        steps: [
          { kind: "trigger", title: "Report is authorised", detail: "A Webhook fires ONLY when the pathologist has signed the report in the LIMS — a preliminary or unverified result can never enter this workflow. That single gate is the difference between automation and a medico-legal incident." },
          { kind: "condition", title: "Deliverable by bot, or by a human?", detail: "A Condition node applies your release policy: reports your policy requires be given in person or by a clinician are routed to a human and never auto-sent." },
          { kind: "tool", title: "Secure link + language pick (user-defined SpEL tool)", detail: "A tenant-authored tool `delivery_channel` (SpEL, e.g. input.consent.whatsapp ? 'WHATSAPP' : (input.consent.sms ? 'SMS' : 'EMAIL')) resolves the consented channel from the patient record, and a second expression formats the localised subject line. Consent routing is a rule the lab must be able to change on a Tuesday afternoon without a release — so it lives in an expression, not a prompt." },
          { kind: "agent", title: "Compose the covering message only", detail: "An Agent node writes the covering message — patient name, panel, collection date, secure expiring link, and how to book a consultation — in the patient's registered language via Sarvam. It transmits the report; it does not summarise, quote or explain a single value." },
          { kind: "approval", title: "Policy-gated releases are signed off", detail: "An Approval node gates any release the policy branch flagged — a human releases the clinical document, and the release is stamped with who did it." },
          { kind: "output", title: "Deliver on the consented channel", detail: "An Output node sends on WhatsApp / SMS / Email per the SpEL-resolved consent, carrying the secure link rather than clinical content in the message body." },
          { kind: "agent", title: "Answer only operational questions", detail: "A second Agent node handles replies inside a hard boundary: link expired, name spelt wrong, hard copy, appointment booking. For ANY question about what a result means it refuses to interpret and hands off — the receptionist's impossible position, removed by design." },
          { kind: "approval", title: "Clinical questions reach a clinician", detail: "An Approval/hand-off node routes every interpretation request to a qualified human with the patient's context attached, in the same conversation thread the patient is already in." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS / Twilio", "Email", "Sarvam", "Approval / HITL", "Tool (MCP → LIMS / patient portal / appointments)"],
        outcome:
          "Signed reports reach patients on a channel they use, in a language they read, without a front-desk executive attaching PDFs one at a time — and every 'is this bad?' lands with a qualified human instead of with whoever picked up.",
        impact:
          "Two mechanisms. Language: a report a patient cannot read is a report that generates a phone call, and a Sarvam-localised covering message removes that call at source. Safety: making refusal-and-handoff an explicit, tested path means the interpretation question gets routed rather than improvised — which is precisely the risk an untrained front-desk answer carries today.",
        build: {
          agents: 2,
          agentsNote:
            "1 delivery composer (once per authorised report) + 1 reply handler bounded to operational Q&A. Channel/consent resolution is a deterministic SpEL tool; the sensitivity branch, the release approval and the clinician handoff are workflow nodes.",
          pattern: "Signed-only trigger → policy branch → SpEL consent routing → localised delivery → bounded reply agent with mandatory clinical handoff",
          prompt:
            "You deliver an ALREADY-AUTHORISED laboratory report to the patient it belongs to. Compose a covering message containing ONLY: patient name, test/panel name, collection date, the secure report link, the link's validity, and how to book a consultation. Localise into the patient's registered language. You must NOT summarise, quote, interpret, grade or comment on ANY value in the report — not even to say the results look normal. If the patient asks what a result means, whether it is serious, what to do, or anything about medication, reply that a qualified clinician will answer and hand the conversation off immediately with the patient's context attached. Never diagnose, never reassure, never advise.",
          agentRoles: [
            { role: "Delivery composer (per report)", model: "Economy tier with a fallback model — one short templated localised message per report; volume tracks report count and delivery must not stall on a provider outage", prompt: "Compose the localised covering message: identity, panel, collection date, secure link, booking route. Zero clinical content — no values, no summary, not even 'looks normal'." },
            { role: "Bounded reply handler", model: "Premium/standard tier with fallback — sees few messages but must refuse reliably under emotional pressure, which is exactly what you are paying for", prompt: "Answer ONLY operational questions (link, format, hard copy, name correction, appointment booking). Classify any clinical or medication question as OUT_OF_SCOPE and hand off to a clinician with context — never attempt an answer, never soften it into general advice, never reassure." },
          ],
          mcp: [
            "LIMS — via Tool (MCP): confirm the report is authorised and mint a secure, expiring report link for this patient and accession",
            "Patient portal / demographics — via Tool (MCP): read registered language, consented channels and contact numbers; log the delivery against the report id",
            "HIS / appointment system — via Tool (MCP): fetch the next available consultation slot so the covering message can offer a real booking route",
          ],
          tools: [
            "user-defined tool `delivery_channel` (SpEL): input.consent.whatsapp ? 'WHATSAPP' : (input.consent.sms ? 'SMS' : 'EMAIL') — consent-first channel routing as a formula the lab's compliance owner edits directly, so a change in consent policy is a one-line edit, not a release",
            "user-defined tool `link_validity_line` (SpEL): 'Valid till ' + input.expiresOn + ' · Ref ' + input.accession — one consistently formatted line across every language template",
            "knowledge_search (report-release policy, consent and channel rules, the sensitive-report list)",
            "http_request (mint the expiring link; log delivery against the report id)",
            "send_email (the Email arm of the consented-channel fan-out)",
          ],
          guardrails: [
            "The workflow triggers ONLY on a pathologist-authorised report — preliminary results are structurally unable to enter it",
            "Hard no-interpretation boundary: the agent transmits and never summarises, quotes, grades or explains a value — including refusing to say results look normal",
            "Any clinical, prognostic or medication question is classified OUT_OF_SCOPE and handed to a qualified human with context; refusal-and-route is the tested path, not a fallback",
            "Policy-flagged sensitive reports bypass automated delivery entirely and route to a human at the Approval node",
            "Clinical content never appears in the message body — only an expiring secure link, on the SpEL-resolved consented channel; a patient with no consent on file receives nothing",
            "Every delivery is logged against the report id, with the releasing human stamped wherever an approval applied",
          ],
          cost:
            "The per-report composer is short, templated and localised — economy tier is its right home, and it is the term that scales with report volume. The reply handler sees only the fraction of patients who write back, so paying the higher tier there buys the thing that actually matters: dependable refusal and clean handoff on a clinical question. Do not economise on the reply handler to save on a small call count. Both roles carry a fallback model, because report delivery stopping for an afternoon is a queue at the collection centre.",
          output:
            "A signed-report Webhook starts the flow → the Condition node diverts policy-flagged sensitive reports to a human → the `delivery_channel` SpEL tool resolves the consented channel and language → an economy-tier agent composes a Sarvam-localised covering message with an expiring secure link → the Output node delivers on WhatsApp/SMS/Email → replies hit a bounded handler that answers logistics and routes every clinical question, via the Approval/handoff node, to a qualified clinician with full context.",
        },
      },
      {
        id: "diag-preauth",
        title: "Insurance / TPA pre-authorisation chased to a decision",
        scenario:
          "The insurance desk is one executive with an Excel file called PREAUTH_TRACKER_final_v3.xlsx and a column headed 'follow up date' that is colour-filled yellow by hand. Each morning she logs into three different TPA portals with three different passwords kept on a sticky note under the keyboard, checks case numbers one by one, and re-uploads whatever document was queried — usually a referring note that was scanned crooked. A file goes quiet for three days because nobody refreshed that portal. On the day of the scan the patient is sitting in the waiting room, the MRI slot is held, and the case is still 'query raised'.",
        triggerLabel: "Webhook (order flagged pre-auth-required in HIS/LIMS)",
        steps: [
          { kind: "trigger", title: "Order flags pre-auth", detail: "A Webhook fires the moment an order is flagged as requiring pre-authorisation — the case starts when the order does, not when the executive reaches that row in the sheet." },
          { kind: "agent", title: "Assemble + completeness-check the pack", detail: "An Agent node assembles the pack against that payer's documented checklist in Knowledge — policy details, referring note, indication, prior reports, tariff estimate — and returns {complete: false, missing[]} rather than submitting a file it already knows will bounce." },
          { kind: "condition", title: "Complete enough to submit?", detail: "A Condition node routes an incomplete pack back for exactly the missing items — one specific request to the referring clinic or the patient, instead of a submission that fails a day later." },
          { kind: "approval", title: "Insurance desk approves submission", detail: "An Approval node holds the submission — a pre-auth request commits clinical justification and a financial claim in the patient's name, so a human reviews and submits." },
          { kind: "tool", title: "Lodge it in the TPA portal", detail: "A Tool (MCP) node lodges the approved pack in the payer/TPA portal, keyed to the order id so a retry cannot open a duplicate case — the exact failure the manual re-upload habit produces." },
          { kind: "loop", title: "Chase to a decision", detail: "A Loop node polls case status on your SLA cadence, replacing the colour-filled follow-up column. On a payer query it re-runs the completeness check for just the newly requested document rather than restarting the pack." },
          { kind: "parallel", title: "Watch every payer at once", detail: "A Parallel node polls all open cases across all three payers in the same pass — the three-portals-three-passwords morning round, collapsed into one scheduled run." },
          { kind: "output", title: "Keep patient and clinic informed", detail: "An Output node updates the patient (WhatsApp/SMS, Sarvam-localised) and the referring clinic (Email) on approval, query or denial — status and next step only, never a clinical opinion and never a coverage promise the payer has not given." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS / Twilio", "Email", "Sarvam", "Approval / HITL", "Tool (MCP → TPA portal / HIS / billing)"],
        outcome:
          "Pre-auth packs go out complete on the first attempt and are then actively chased to an approval, query or denial — with the patient and the referring clinic told the status before the appointment day rather than in the waiting room.",
        impact:
          "The mechanism is the completeness gate plus the poll. Most pre-auth delay is a bounced file for one missing document followed by silence; checking against the payer's own checklist before submission removes the first, and a Loop polling on an SLA cadence removes the second — including on the days the executive is on leave, which is when the tracker stops being updated at all. The financial and clinical commitment still belongs to the human who submits.",
        build: {
          agents: 2,
          agentsNote:
            "1 assembly/completeness agent (per case) + 1 query-response agent (only when a payer raises a query). The submission approval, the portal write and the multi-payer poll are workflow nodes.",
          pattern: "Completeness gate before submission → human approval → Tool(MCP) submit → Parallel polling Loop with query re-entry",
          prompt:
            "You assemble an insurance pre-authorisation pack for a diagnostic order. Using THIS payer's checklist in Knowledge, gather {policy_no, payer, patient_ref, procedure/panel, indication_text, referring_clinician, prior_reports[], estimated_cost}. If ANY mandatory item is missing or unreadable, do NOT assemble a submission — return {complete: false, missing[]} so the workflow makes one specific request to the right party. Copy the clinical justification VERBATIM from the referring clinician's note; NEVER write, embellish or infer an indication yourself, and never state a diagnosis. Costs come only from the documented tariff via the `tariff_estimate` tool — no invented amounts. The pack is a draft: a human at the insurance desk reviews and submits it.",
          agentRoles: [
            { role: "Pack assembler / completeness checker (per case)", model: "Standard tier with a fallback model — payer checklists differ and a bounced file costs a day of turnaround, which dwarfs the token difference", prompt: "Assemble against the payer's own checklist; return {complete, missing[]} rather than a knowingly-incomplete submission. Clinical justification quoted verbatim from the referring note — never authored. Amounts only from the tariff tool." },
            { role: "Query responder (only on a payer query)", model: "Economy tier with fallback — narrow single-document turnarounds, many small runs across open cases", prompt: "Map the payer's query to the exact missing artefact, request that one item from the right party, and re-enter the completeness check. Do not re-open or re-argue the clinical justification, and never add new clinical text." },
          ],
          mcp: [
            "Insurance / TPA pre-auth portal — via Tool (MCP): lodge the approved pack, poll case status, and upload the single queried document against the existing case number",
            "HIS / LIMS — via Tool (MCP): read the order, the referring clinician's note and prior reports to attach to the pack",
            "Billing system — via Tool (MCP): pull the current tariff for the procedure and post the approved/denied outcome onto the patient's estimate",
          ],
          tools: [
            "user-defined tool `tariff_estimate` (SpEL): input.baseRate * input.units + (input.urgent ? input.urgentSurcharge : 0) — the lab's own tariff arithmetic as a formula, so the estimate on a pre-auth is computed identically every time and updated by the billing manager when rates change, with no developer involved",
            "user-defined tool `case_key` (SpEL): input.payerCode + '/' + input.orderId — a stable idempotency key so a retry or a re-triggered Webhook can never open a second case on the same order",
            "knowledge_search (per-payer pre-auth checklist, documentation rules, tariff notes)",
            "http_request (lodge the pack; poll case status; fetch prior reports)",
            "send_email (the referring-clinic status update)",
          ],
          guardrails: [
            "The agent never authors clinical justification — the indication is quoted verbatim from the referring clinician's note, and the agent never states a diagnosis",
            "Nothing is lodged with the payer until the insurance desk approves at the Approval node — the submission is a financial and clinical commitment in the patient's name",
            "Incomplete packs are never submitted; the completeness gate forces one specific request for the missing item",
            "Amounts come only from the `tariff_estimate` SpEL tool over documented rates — no invented figures, and no coverage promise the payer has not given",
            "Portal writes are keyed to `case_key`, so a retry or duplicate Webhook cannot open a second case",
            "Patient-facing updates state status and next step only — never a clinical opinion, never an assurance of approval",
          ],
          cost:
            "Volume is low (only flagged orders) but per-case stakes are high, so the assembler earns standard tier — a bounced file costs a day of turnaround. The query responder handles narrow single-document turnarounds and belongs on economy tier. The chase is polling, not inference: the Parallel/Loop pass costs HTTP calls and only re-invokes a model when a payer actually raises a query. Fallback models matter here because a stalled provider means a missed SLA window on someone else's portal.",
          output:
            "A pre-auth flag opens the case → a standard-tier agent assembles against that payer's checklist with `tariff_estimate` supplying the amount, and the Condition node blocks anything incomplete → the insurance desk approves at the Approval node → a Tool (MCP) node lodges the pack keyed to `case_key` → a Parallel polling Loop chases every open case across every payer to a decision, re-entering the completeness check on a query → an Output node keeps the patient (Sarvam-localised WhatsApp/SMS) and the referring clinic (Email) on status, with no clinical opinion and no coverage promise.",
        },
      },
    ],
  },

  // ============================================== ELDER CARE & HOME NURSING
  {
    id: "eldercare",
    name: "Elder care & home nursing",
    icon: HeartHandshake,
    tagline:
      "Cover dropped home visits, triage device readings against the care plan, and keep adult children updated in their own language — with a nurse-in-charge owning every dispatch and every care-plan change.",
    recipes: [
      {
        id: "elder-roster-gap",
        title: "Dropped home visit → covered, with the dispatch human-approved",
        scenario:
          "06:20, and a nurse messages the coordinator: fever, can't come in. Her six visits today include a dressing change, an insulin administration and two frail clients who cannot be left. The coordinator opens the roster — a Google Sheet printed every Sunday and stuck on a whiteboard with magnets — and starts down a WhatsApp group of forty carers, typing 'anyone free in Jayanagar 11am?' Some reply, some don't, some are already double-booked on a row of the sheet she hasn't scrolled to. She phones four people. By 09:00 half the day is covered, three families have been told, and she genuinely cannot remember which three.",
        triggerLabel: "WhatsApp from the carer, or Webhook (rostering app marks unavailable)",
        steps: [
          { kind: "trigger", title: "Carer reports unavailability", detail: "The carer's own WhatsApp/SMS message, or a Webhook from the rostering app, opens the gap — the same message she already sends, now starting a workflow instead of a scroll." },
          { kind: "knowledge", title: "Load acuity and skill rules", detail: "A scoped Knowledge collection supplies YOUR documented client-acuity tiers, the skill/qualification matrix and the travel and shift-hour rules — the things currently held in the coordinator's head." },
          { kind: "agent", title: "Rank the affected visits by care need", detail: "An Agent node ranks the affected visits: which are clinically unskippable, which are skill-gated (injection, catheter, wound), which can shift by hours. It applies the written rules and makes no clinical judgement of its own." },
          { kind: "parallel", title: "Check the whole pool at once", detail: "A Parallel node evaluates candidate carers simultaneously — qualification match, travel feasibility from their previous visit, shift-hour limits — replacing the serial phone round and the 'anyone free?' broadcast that reaches forty people and answers nobody." },
          { kind: "tool", title: "Hard skill gate (user-defined SpEL tool)", detail: "A tenant-authored tool `skill_gate` (SpEL, e.g. input.visitSkills.contains('INJECTION') ? input.carerQualifications.contains('RN') : true) enforces qualification matching deterministically. A skill gate must never be a model's judgement call — as an expression it is auditable, testable, and editable by the nursing lead when a qualification rule changes." },
          { kind: "condition", title: "Anything still uncovered?", detail: "A Condition node splits covered from uncovered. An unskippable visit with no qualified carer escalates to the nurse-in-charge instead of being quietly rescheduled to tomorrow." },
          { kind: "approval", title: "Nurse-in-charge dispatches", detail: "An Approval node holds every reassignment — sending a specific carer to a specific frail client is a clinical staffing decision with a duty of care attached, so a qualified human confirms each dispatch and each skill match." },
          { kind: "output", title: "Tell carers, clients and families", detail: "On approval, an Output node messages assigned carers with the visit detail, and clients/families on WhatsApp or an ElevenLabs Voice call (Sarvam-localised) with who is coming and when — the step that silently fails today." },
          { kind: "tool", title: "Write the roster and any extra-visit charge", detail: "A Tool (MCP) node updates the rostering system; where cover triggers a chargeable out-of-cycle visit, an approved Razorpay UPI payment link goes to the family. Nothing is auto-debited, ever." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS / Twilio", "Voice / Call", "ElevenLabs", "Sarvam", "Razorpay (UPI)", "Approval / HITL", "Tool (MCP → rostering / billing)"],
        outcome:
          "A dropped shift becomes a ranked, skill-matched cover proposal within minutes, and once the nurse-in-charge dispatches it, every carer, client and family is told automatically — no whiteboard, no forty-person broadcast, no trying to remember who was called.",
        impact:
          "The mechanism is parallel search plus guaranteed notification. The coordinator's slow step is a serial phone round against a printed sheet; evaluating the pool in parallel against documented skill and travel rules collapses that to a single decision. And the step that fails most often — telling the family who is now coming — becomes an automatic consequence of the dispatch rather than a task someone has to remember at 09:00 on a bad morning.",
        build: {
          agents: 2,
          agentsNote:
            "1 acuity/priority agent (once per gap) + 1 cover-matching agent invoked across the pool by the Parallel node. Qualification matching is a deterministic SpEL tool; the dispatch approval, the notifications and the roster write are workflow nodes.",
          pattern: "Priority ranking → Parallel candidate matching with a SpEL skill gate → human dispatch approval → guaranteed multi-party notification",
          prompt:
            "You are a home-nursing roster assistant for {{provider}}. Given the affected visits of an unavailable carer, rank them using ONLY the client-acuity tiers and skill rules in Knowledge. Return strict JSON per visit {visit_id, client_ref, acuity_tier, required_skills[], skippable, latest_safe_time, candidate_carers[{carer_ref, skill_gate_result (copied from the `skill_gate` tool), travel_feasible, within_shift_limits}]}. You propose cover — you never dispatch, never message a client, and never mark a visit skippable unless the documented rules say it is. You must NOT make any clinical judgement about a client's condition, and must NOT alter the care plan or medication. If no qualified carer exists for an unskippable visit, return escalate=true for the nurse-in-charge.",
          agentRoles: [
            { role: "Acuity ranker (per gap)", model: "Standard tier with a fallback model — this ranking decides which frail client is seen first, and a 06:20 outage cannot mean no ranking at all", prompt: "Rank affected visits strictly by documented acuity tiers and skill gates. Never infer clinical need beyond the written rules; unskippable-with-no-cover ⇒ escalate=true." },
            { role: "Cover matcher (per candidate carer, run in Parallel)", model: "Economy tier with fallback — many short mechanical checks per gap", prompt: "For ONE candidate carer, take the `skill_gate` verdict as authoritative, then check travel feasibility from their prior visit and shift-hour limits, returning a structured verdict with reasons. Roster facts only — never assume willingness or availability." },
          ],
          mcp: [
            "Rostering / home-care management system — via Tool (MCP): read today's roster and carer availability, then write the approved reassignment against the visit id",
            "Carer HR / qualification register — via Tool (MCP): fetch each candidate's current qualifications and expiry dates to feed the skill gate",
            "Billing system — via Tool (MCP): raise the out-of-cycle visit charge once approved, and attach the Razorpay UPI payment reference to the client's account",
          ],
          tools: [
            "user-defined tool `skill_gate` (SpEL): input.visitSkills.contains('INJECTION') ? input.carerQualifications.contains('RN') : true — the agency's own qualification rule as a deterministic expression, so a skill gate is never a model's opinion and the nursing lead can update it the day a qualification policy changes",
            "user-defined tool `visit_fee` (SpEL): input.outOfCycle ? (input.baseFee + (input.afterHours ? input.nightSurcharge : 0)) : 0 — the extra-visit charge computed by formula, so the family sees the same number the agency's price list says",
            "knowledge_search (client-acuity tiers, skill/qualification matrix, travel and shift-limit rules)",
            "http_request (read roster and availability; write approved reassignments; issue an approved payment link)",
          ],
          guardrails: [
            "No dispatch happens without the nurse-in-charge's approval — the agent proposes cover, a qualified human sends a carer to a client",
            "The agent makes no clinical judgement about a client and never touches the care plan or medication; it applies only the documented acuity and skill rules",
            "Skill gates are enforced by the `skill_gate` SpEL expression as hard filters, never soft preferences a model can trade away against travel time",
            "A visit is skippable only if the written rules say so — an unskippable visit with no qualified carer escalates rather than being rescheduled",
            "Money movement is gated twice: a human approves the charge, and the family receives a Razorpay UPI link — nothing is ever auto-debited",
            "Roster writes are keyed to the visit id, so a re-trigger cannot double-assign a carer or double-charge a family",
          ],
          cost:
            "The matcher is the multiplied term — once per candidate carer per gap — so it stays on the economy tier and hard-capped; the Parallel node buys wall-clock speed, not extra cost per check. The acuity ranker runs once per gap and is where standard tier earns its keep, because that single ranking decides which frail client is seen first. Both carry a fallback model: a 06:20 provider outage is exactly the morning you cannot afford to be back on the whiteboard.",
          output:
            "The gap opens by the carer's own WhatsApp message or a Webhook → a standard-tier agent ranks affected visits on documented acuity rules → a Parallel node runs cheap per-carer checks across the pool with the `skill_gate` SpEL tool as a hard filter → the Condition node escalates any uncovered unskippable visit → the nurse-in-charge dispatches at the Approval node → an Output node notifies carers, clients and families (WhatsApp and ElevenLabs Voice, Sarvam-localised) → a Tool (MCP) node writes the roster and, only if approved, issues a Razorpay UPI link for a chargeable extra visit.",
        },
      },
      {
        id: "elder-vitals-triage",
        title: "Home device readings triaged against the care plan — escalated, never diagnosed",
        scenario:
          "The family bought a BP monitor, a glucometer and a fall sensor. Every evening the daughter photographs the cuff's display and posts it into a WhatsApp group called 'Amma Care' with the agency's day nurse in it. The nurse reads the group when she can, which is not after 18:00 and not on Sundays. Some readings go into a spiral notebook kept on the client's shelf, in the carer's handwriting, in whatever units she remembered. A drift builds over four days across a photo, a notebook page and nobody's memory. When the fall sensor fires at 23:10, it fires into an app on the son's phone in another city, and the first the agency hears is his call at 23:22.",
        triggerLabel: "Device / Webhook (BP, SpO2, glucose, weight, fall sensor)",
        steps: [
          { kind: "device", title: "Reading arrives", detail: "A Device or Webhook trigger receives the reading from the home device or its vendor cloud — no photograph, no group, no transcription into a notebook. Consumer health devices have no first-party connector: Webhook in, vendor cloud read via a Tool (MCP) node." },
          { kind: "knowledge", title: "Load THIS client's care-plan bands", detail: "A scoped Knowledge collection supplies the per-client parameters set by that client's own clinician — escalation bands, trend rules and the named on-call ladder. Population norms are never used." },
          { kind: "tool", title: "Band check by formula (user-defined SpEL tool)", detail: "A tenant-authored tool `band_check` (SpEL, e.g. input.systolic >= input.plan.systolicEscalate ? 'ESCALATE' : (input.systolic >= input.plan.systolicWatch ? 'WATCH' : 'WITHIN_PLAN')) performs the comparison deterministically against that client's plan values. A clinician-set threshold must behave identically at 23:10 and at 11:00 — an expression guarantees that, and the nursing lead can update a client's bands after a review without waiting on a developer." },
          { kind: "agent", title: "Frame the fact (no diagnosis)", detail: "An Agent node turns the deterministic verdict into strict JSON {status, parameter, value, units, band_breached, plan_escalation_route, note} for the ladder. It never names a condition, never explains a cause, never suggests an action beyond the plan's own route, and never overrules the formula." },
          { kind: "condition", title: "Reading, drift, emergency, or broken cuff?", detail: "A Condition node separates in-band readings (logged), watch-level drift (nurse review queue), plan-defined escalation or a fall alert (immediate route), and device faults — an implausible value or an error code goes to a human to check the equipment, not to a clinical alarm." },
          { kind: "approval", title: "On-call nurse owns the escalation", detail: "An Approval node holds the escalation for the on-call nurse. For plan-defined emergencies the node runs the SOP's fast path with a hard timeout that auto-escalates UP the human ladder — never a bypass that lets the agent act alone." },
          { kind: "voice", title: "Reach the named ladder", detail: "A Voice/Call node (ElevenLabs; Sarvam for Indian languages) calls the on-call nurse, then the named clinician, with SMS/WhatsApp backup — reading out the parameter, the value and the band crossed as facts." },
          { kind: "loop", title: "Retry until someone acknowledges", detail: "A Loop node re-runs the documented ladder at the SOP's intervals until an explicit human acknowledgement is captured; an exhausted ladder raises a supervisor alert. This is the 23:10 gap, closed." },
          { kind: "output", title: "Log it against the care record", detail: "A Tool (MCP) node writes the reading, the classification and the acknowledgement trail to the care record — so the four-day drift is visible next time as data, not reconstructed from a photo and a notebook." },
        ],
        connectors: ["Webhook", "Voice / Call", "ElevenLabs", "Sarvam", "SMS / Twilio", "WhatsApp", "Knowledge base", "Approval / HITL", "Tool (MCP → care record / device cloud)"],
        outcome:
          "Every reading is checked against that client's own clinician-set bands as it arrives, drift becomes a reviewable queue instead of a surprise, and a plan-defined escalation reaches a named human with retries and a written trail — at 23:10 and on Sundays.",
        impact:
          "The mechanism is continuous comparison against a per-client plan plus a persistent contact ladder. Home telemetry does not fail because readings are missing; it fails because nobody is watching the WhatsApp group at 23:10 and nobody records what happened next. Comparison is cheap and constant, and the retry Loop plus the acknowledgement trail supply exactly the two things a family later asks about: was anyone told, and when.",
        build: {
          agents: 2,
          agentsNote:
            "1 framing agent (per reading — the high-volume step) + 1 trend agent (scheduled, over a client's recent window). The band comparison is a deterministic user-defined SpEL tool; the escalation approval, the ladder and the record write are workflow nodes.",
          pattern: "Deterministic SpEL band check → cheap per-reading framing → Condition split → human-owned escalation with timeout-escalates-UP → retry ladder",
          prompt:
            "You frame ONE home-monitoring reading for THIS client's care team. The WITHIN_PLAN / WATCH / ESCALATE verdict has already been computed deterministically by the `band_check` tool against this client's own care-plan values — take it as given and NEVER overrule it. Return strict JSON {status, parameter, value, units, band_breached, plan_escalation_route, note}. Use only this client's documented bands — never population norms, never general medical knowledge. You must NOT name or suggest any medical condition, must NOT explain what the reading might be caused by, must NOT recommend or change any medication or dose, and must NOT advise the client or family what to do. If the value is implausible, the units are wrong, or the device reports an error, return DEVICE_FAULT so a human checks the equipment rather than raising a clinical alarm.",
          agentRoles: [
            { role: "Reading framer (per reading)", model: "Economy tier with a fallback model — fires on every device reading, so it sets the entire cost curve, and a provider outage must never silence a fall sensor", prompt: "Take the SpEL band verdict as authoritative and emit strict JSON for the ladder. No condition names, no causes, no advice, no medication commentary. Implausible value or device error ⇒ DEVICE_FAULT to a human." },
            { role: "Trend reviewer (scheduled, per client window)", model: "Standard tier with fallback — runs infrequently over a window of readings, and is what a nurse actually reads", prompt: "Summarise the recent reading window factually for the nurse's review queue: direction, magnitude, missed readings, and which documented WATCH rules were met. Cite actual values and actual counts — never estimate, never interpret clinically, never suggest a diagnosis or a plan change." },
          ],
          mcp: [
            "Home device vendor cloud (BP cuff, glucometer, SpO2, weight scale, fall sensor) — via Tool (MCP): pull the reading payload and the device's own error/battery status, and backfill missed readings after a connectivity gap",
            "Care record / home-care system — via Tool (MCP): append the reading, its classification and the full acknowledgement trail against the client id",
            "On-call roster — via Tool (MCP): resolve who is on call for this client right now before the ladder starts dialling",
          ],
          tools: [
            "user-defined tool `band_check` (SpEL): input.systolic >= input.plan.systolicEscalate ? 'ESCALATE' : (input.systolic >= input.plan.systolicWatch ? 'WATCH' : 'WITHIN_PLAN') — this client's clinician-set bands as a deterministic expression, updated by the nursing lead after a care review without a developer or a deployment, and behaving identically at 23:10 as at 11:00",
            "user-defined tool `reading_line` (SpEL): input.parameter + ' ' + input.value + ' ' + input.units + ' (plan band ' + input.plan.label + ')' — one consistently formatted line for the voice script, the SMS and the care-record entry, so all three say exactly the same thing",
            "knowledge_search (this client's care-plan thresholds, trend rules and named escalation ladder)",
            "http_request (fetch device history; write the reading, classification and acknowledgement to the care record)",
          ],
          guardrails: [
            "The agent never diagnoses, never names a condition, never explains a cause and never touches medication or dose — it reports only which documented band was crossed",
            "The band decision is deterministic (`band_check` over this client's own plan values), so it cannot drift with a model change; the agent is explicitly forbidden from overruling it",
            "Thresholds are per-client and clinician-set; population norms and general medical knowledge are out of bounds",
            "Every escalation is owned by the on-call nurse at the Approval node; the emergency fast path has a hard timeout that escalates UP the human ladder and never lets the agent act alone",
            "Implausible values, wrong units and device errors return DEVICE_FAULT and route to a human — a broken cuff must not raise a clinical alarm, and a flat battery must not read as a silent client",
            "Client- and family-facing messages state facts and next steps only, never reassurance or interpretation",
            "An exhausted ladder alerts a supervisor; the workflow cannot close an escalation by itself, and every attempt is written to the care record",
          ],
          cost:
            "This is the highest-volume workflow in the pack — several readings per client per day, every day — so the framer must be economy tier and hard-capped, with a fallback model configured because a provider outage here means a fall alert with nowhere to go. Moving the comparison into the `band_check` SpEL tool also removes the reasoning burden from that highest-volume step, which is the cheapest optimisation available. The trend reviewer runs once per client per window, not per reading, which is exactly where a standard-tier model is affordable. The ladder consumes telephony minutes rather than tokens, since retries replay the composed script.",
          output:
            "A Device/Webhook reading lands → a scoped Knowledge collection supplies that client's clinician-set bands and the `band_check` SpEL tool returns a deterministic verdict → an economy-tier agent frames it as strict JSON with no clinical interpretation → the Condition node logs, queues, escalates or flags a device fault → the on-call nurse owns the escalation at the Approval node (timeout escalates UP the human ladder) → ElevenLabs Voice plus SMS/WhatsApp run the named ladder inside a retry Loop → a Tool (MCP) node writes the reading, classification and acknowledgement trail to the care record.",
        },
      },
      {
        id: "elder-family-update",
        title: "Daily family update in the family's own language",
        scenario:
          "The carer writes the visit note in a paper file kept in a drawer at the client's house — three lines of shorthand, 'BP ok, took lunch half, walked to gate w/ support'. Nothing leaves that house. The son lives in Pune and works nights; the daughter-in-law reads Kannada, not English. So the son rings the agency office at odd hours, and whoever picks up walks to the coordinator's desk, who checks the roster sheet for which carer went, then rings the carer, then rings the son back. Every retelling is slightly different, and the daughter-in-law — who is the person actually at the house each morning — is told nothing at all.",
        triggerLabel: "Webhook (visit completed / carer note submitted) or scheduled daily digest",
        steps: [
          { kind: "trigger", title: "Visit note is submitted", detail: "A Webhook fires when the carer completes the visit in the app (or a scheduled digest collects the day's visits) — the note leaves the drawer and becomes an event." },
          { kind: "agent", title: "Turn shorthand into plain, factual language", detail: "An Agent node rewrites the carer's note for the family: what was done, meals and fluids, mobility, mood, and what is planned next. It reports only what the carer wrote — no assessment, no prognosis, no reassurance." },
          { kind: "condition", title: "Anything the carer flagged for a nurse?", detail: "A Condition node checks for carer-flagged concerns and plan-defined trigger words; those never go to the family as a routine update — they divert into the clinical review queue first." },
          { kind: "approval", title: "Nurse reviews anything flagged", detail: "An Approval node holds any flagged update for the nurse-in-charge, who decides what is communicated and by whom before the family hears it." },
          { kind: "tool", title: "Per-contact channel and language (user-defined SpEL tool)", detail: "A tenant-authored tool `contact_route` (SpEL, e.g. input.contact.prefersVoice ? 'VOICE' : (input.contact.whatsapp ? 'WHATSAPP' : 'SMS')) resolves each registered contact's channel and language from the care record. Family communication preferences change constantly — a formula the coordinator edits herself keeps that current without a ticket." },
          { kind: "output", title: "Send in each person's own language", detail: "An Output node sends on each consented contact's own channel — WhatsApp, SMS, Telegram or Email — Sarvam-localised, so the Kannada-reading daughter-in-law finally gets the same update as the English-reading son." },
          { kind: "voice", title: "Voice for those who prefer it", detail: "For an elderly spouse or a contact who does not read comfortably, a Voice/Call node with ElevenLabs delivers the identical factual update by phone in the same language." },
          { kind: "agent", title: "Answer questions within a hard boundary", detail: "A bounded reply agent handles logistics — who is visiting tomorrow, at what time, how to change a visit, invoice questions. Any clinical question ('should we take her to hospital?', 'can we stop the tablet?') is refused and routed to the nurse-in-charge with context." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS / Twilio", "Telegram", "Email", "Voice / Call", "ElevenLabs", "Sarvam", "Approval / HITL", "Tool (MCP → care record / rostering)"],
        outcome:
          "Every consented family contact gets the same factual daily update on their own channel in their own language, and the calls that still come in are logistics — because clinical questions are routed to a nurse by design rather than by whoever answers the phone.",
        impact:
          "Two mechanisms. Consistency: an update generated from the carer's actual note says the same thing to every contact, unlike a chain of retellings through the office, the coordinator and the carer. Access: a Kannada- or Hindi-reading relative who currently receives nothing gets exactly the same information — which is what removes the anxious 22:00 call, not any promise of reassurance.",
        build: {
          agents: 2,
          agentsNote:
            "1 note-to-plain-language agent per visit + 1 bounded reply handler for family questions. Channel/language routing is a deterministic SpEL tool; the flag check, the nurse review and the per-contact fan-out are workflow nodes.",
          pattern: "Note rewrite → clinical-flag diversion → SpEL per-contact routing → localised fan-out → bounded replies with mandatory nurse handoff",
          prompt:
            "You rewrite a home-care visit note into a short, plain-language update for the client's registered family contacts. Include ONLY what the carer actually recorded: care tasks completed, meals and fluids, mobility, mood, and the next scheduled visit. You must NOT add any assessment of how the client is doing, any prognosis, any reassurance ('she's doing well'), any concern the carer did not write, any medical terminology absent from the note, and NOTHING about medication beyond whether a scheduled dose was recorded as given. Never diagnose, never advise, never suggest a care-plan change. If the note contains a carer-flagged concern or a plan-defined trigger word, do NOT produce a family update — return {flagged: true, quote} so a nurse reviews first. Localise into each contact's registered language.",
          agentRoles: [
            { role: "Note rewriter (per visit)", model: "Economy tier with a fallback model — short bounded rewriting, once per visit per day across the whole client book", prompt: "Rewrite the carer's note into plain factual language and localise it. Content strictly limited to what was recorded; no assessment, no reassurance, no added medical terms. Flagged concern ⇒ stop and return {flagged:true} for nurse review." },
            { role: "Bounded family reply handler", model: "Premium/standard tier with fallback — few messages, but it must refuse cleanly and route reliably when a frightened relative pushes hard for a clinical answer", prompt: "Answer ONLY logistics: visit schedule, carer identity, rescheduling, invoices and payment. Classify any clinical, medication, hospital-or-not or end-of-life question as OUT_OF_SCOPE and hand off to the nurse-in-charge with context — never answer, never soften into general advice, never reassure." },
          ],
          mcp: [
            "Home-care / rostering app — via Tool (MCP): read the submitted visit note and the client's next scheduled visit and assigned carer",
            "Care record — via Tool (MCP): read the consent list, each contact's registered language and channel, then log what was sent to whom against the visit id",
            "Billing system — via Tool (MCP): fetch the current invoice status so the reply handler can answer a payment question factually",
          ],
          tools: [
            "user-defined tool `contact_route` (SpEL): input.contact.prefersVoice ? 'VOICE' : (input.contact.whatsapp ? 'WHATSAPP' : 'SMS') — per-contact channel selection as a formula, because family preferences change weekly and the coordinator should be able to change them herself",
            "user-defined tool `next_visit_line` (SpEL): 'Next visit: ' + input.nextVisit.date + ' at ' + input.nextVisit.time + ' with ' + input.nextVisit.carerFirstName — the single line families ask about most, formatted identically in every language template",
            "knowledge_search (family-communication policy, consent rules, plan-defined trigger words)",
            "http_request (fetch the visit note and contact preferences; log what was sent to whom)",
            "send_email (the Email arm of the fan-out, for contacts who prefer it)",
          ],
          guardrails: [
            "The update contains only what the carer recorded — no assessment, no prognosis, no reassurance, no added clinical terminology, and nothing about medication beyond 'scheduled dose recorded as given'",
            "A carer-flagged concern or plan-defined trigger word blocks the family update entirely and diverts to the nurse-in-charge at the Approval node",
            "The reply handler answers logistics only; every clinical, medication or hospital-decision question is refused and routed to a nurse with context",
            "Recipients, channels and languages come from the consent list on the care record via `contact_route` — a relative who is not on the consent list receives nothing, however insistent",
            "The agent never proposes or communicates a care-plan change; that path lives in the handover recipe behind a clinician approval",
            "Every send is logged against the visit id, so the agency can show exactly what each contact was told and when",
          ],
          cost:
            "The rewriter runs once per visit across the entire client book — that is the volume term and it belongs on economy tier, since bounded rewriting plus localisation needs nothing more. Spend the higher tier on the reply handler, which sees far fewer messages but must hold the line when an anxious family member pushes for a clinical answer at midnight. Voice delivery costs telephony minutes and should be enabled per contact via `contact_route`, not by default.",
          output:
            "A completed-visit Webhook (or the daily digest) starts the flow → an economy-tier agent rewrites the carer's note into plain, localised language with no added assessment → the Condition node diverts anything carer-flagged to the nurse-in-charge at the Approval node → the `contact_route` SpEL tool resolves each consented contact's channel and language → an Output node fans the identical update out on WhatsApp/SMS/Telegram/Email (Sarvam-localised), with an ElevenLabs Voice call for contacts who prefer it → replies hit a bounded handler that answers logistics and routes every clinical question to the nurse with full context.",
        },
      },
      {
        id: "elder-handover-careplan",
        title: "Shift handover + care-plan change PROPOSALS, clinician-approved",
        scenario:
          "Three carers, two shifts and a weekend relief cover the same client. Handover is a message in a WhatsApp group at shift change — 'all ok, dressing done' — plus a spiral notebook on the client's shelf that only whoever is standing in the room can read. Standing observations live in separate places and separate weeks: she has refused the evening meal four times, the dressing is soaking through sooner than it did, she has stopped using the walker indoors. No single carer has seen all three facts together, the notebook is not scanned anywhere, and the care plan gets reviewed properly when the family complains or when something goes wrong — whichever comes first.",
        triggerLabel: "Scheduled (end of shift / weekly plan review) + Webhook (visit notes)",
        steps: [
          { kind: "trigger", title: "Shift closes or review falls due", detail: "A scheduled trigger fires at handover or on the weekly review date, and a Webhook supplies every visit note since the last handover — the whole window, not the last message in the group." },
          { kind: "loop", title: "Map: read each visit note", detail: "A Loop node runs a cheap extractor over each note in the window — one focused pass per note, so a busy week costs proportionally and nothing is skimmed at 07:00." },
          { kind: "agent", title: "Extract observations verbatim", detail: "The extractor returns strict JSON per note: tasks done and missed, intake, mobility, skin/wound observations, mood, refusals, and any carer-flagged concern — each with the carer's own words quoted. It adds no interpretation." },
          { kind: "code", title: "Count the patterns deterministically", detail: "A Code node counts occurrences across the window (refusals per meal, dressing changes per week, missed tasks by carer) so frequencies are computed, not estimated by a model — '4 of 6 visits' is arithmetic, and it should be done by arithmetic." },
          { kind: "agent", title: "Reduce: handover + labelled proposals", detail: "A reducer produces the handover summary and, separately, a clearly-labelled list of PROPOSED care-plan review points — 'evening meal refused on 4 of 6 visits' with the quotes attached. Proposals cite evidence; they never assert a cause." },
          { kind: "supervisor", title: "Route by who must decide", detail: "A Supervisor node splits the output: operational items to the coordinator, anything touching the clinical care plan to the client's nurse/clinician, and anything about medication ONLY to the prescriber's queue." },
          { kind: "approval", title: "Clinician approves any plan change", detail: "An Approval node holds every proposed care-plan change for the responsible clinician. The agent may surface a pattern with evidence; only a qualified human changes a care plan, and medication stays entirely with the prescriber." },
          { kind: "subworkflow", title: "Approved change flows onward", detail: "An approved plan change invokes a Sub-workflow that updates the care plan, refreshes the per-client bands used by the vitals-triage recipe's `band_check` tool, and re-briefs the assigned carers — so an approved decision actually reaches the people and the thresholds it affects." },
          { kind: "output", title: "Publish the handover, record the decisions", detail: "An Output node posts the handover to the incoming shift (WhatsApp/Slack/Email) and a Tool (MCP) node writes approved changes to the care record, stamped with the approving clinician." },
        ],
        connectors: ["Webhook", "WhatsApp", "Slack", "Email", "Knowledge base", "Approval / HITL", "Tool (MCP → care record / rostering)"],
        outcome:
          "Each shift starts with an evidence-backed handover assembled from every note in the window, and slow-moving changes surface as clinician-reviewable proposals with quotes and real counts attached — instead of after an incident or a complaint.",
        impact:
          "The mechanism is aggregation across notes that no single carer ever sees together. A refusal pattern or a change in dressing frequency is visible only when six notes are read side by side, which is exactly the read nobody has time for at a 07:00 handover in a WhatsApp group. Attaching the carer's own words and a counted frequency to each proposal is what makes it reviewable in seconds by the clinician who has to decide.",
        build: {
          agents: 2,
          agentsNote:
            "1 note extractor (once per visit note via the Loop node) + 1 reducer producing the handover and the proposal list. Frequency counting is a Code node, routing is a Supervisor node, and the approved change fans out through a Sub-workflow.",
          pattern: "Map-reduce over the note window → Code-counted frequencies → Supervisor routes by decision-owner → clinician approves → Sub-workflow applies",
          prompt:
            "(Extractor) Read ONE home-care visit note and return strict JSON {visit_id, date, carer_ref, tasks_completed[], tasks_missed[], intake_note, mobility_note, skin_wound_note, mood_note, refusals[], carer_flags[], quotes[]}. Quote the carer's OWN words for anything that could matter clinically. Do not interpret, do not infer a cause, do not name a condition, and do not comment on medication beyond whether a scheduled dose was recorded as given. If the note is empty or unreadable, return needs_review=true rather than inferring what probably happened.",
          agentRoles: [
            { role: "Note extractor (per visit note)", model: "Economy tier with a fallback model — runs once per note across the window, so it dominates this workflow's cost", prompt: "Structured extraction with verbatim quotes. Zero interpretation, zero inference of cause, zero medication commentary. Unreadable ⇒ needs_review=true." },
            { role: "Handover reducer + proposal writer (once)", model: "Standard tier with fallback — pattern-finding across notes, and the one output a clinician actually reads", prompt: "Produce (a) the shift handover summary and (b) a separate, clearly-labelled PROPOSED care-plan review list, each item citing the supporting notes and quotes and using the counts supplied by the Code node. Never compute or estimate a frequency yourself, never assert a cause, and never propose a medication change — medication items are routed to the prescriber as observations only." },
          ],
          mcp: [
            "Home-care app / care record — via Tool (MCP): read every visit note in the window, then write approved plan changes stamped with the approving clinician",
            "Rostering system — via Tool (MCP): read which carers cover the incoming shift so the handover reaches the right people, and re-brief them after an approved change",
            "Prescriber / clinic queue — via Tool (MCP): file medication-related observations into the prescriber's own queue, never into the operational one",
          ],
          tools: [
            "user-defined tool `frequency_line` (SpEL): input.occurrences + ' of ' + input.visits + ' visits since ' + input.windowStart — the counted evidence line rendered identically on every proposal, so a clinician reads the same format every week and a model never gets to phrase a frequency loosely",
            "user-defined tool `review_due` (SpEL): input.daysSinceReview >= input.plan.reviewIntervalDays ? 'DUE' : 'NOT_DUE' — the agency's own review cadence as a formula, so the weekly review is triggered by policy rather than by someone remembering",
            "knowledge_search (this client's current care plan, handover template, escalation policy)",
            "http_request (read the note window; write approved plan changes with the approving clinician stamped)",
          ],
          guardrails: [
            "The agent PROPOSES care-plan review points and never changes a care plan — every change passes the responsible clinician's Approval node and is stamped with who approved it",
            "Medication is out of scope entirely: the agent may report that a scheduled dose was or was not recorded as given, and routes anything else to the prescriber's queue as an observation — it never proposes a drug, a dose or a stop",
            "No diagnosis and no causal claim: proposals cite the carer's verbatim quotes and Code-counted frequencies, never an explanation of why something is happening",
            "Frequencies are computed by the Code node and rendered by the `frequency_line` SpEL tool — a model never phrases a rate, and an uncounted percentage can never appear",
            "The Supervisor node routes by decision-owner, so a clinical item cannot land in an operational queue and be actioned by someone unqualified",
            "An empty or unreadable note becomes needs_review rather than an inferred entry — the handover never contains invented care history",
            "The Sub-workflow only runs on an approved change, so per-client vitals bands and carer briefings can never be updated by an unapproved proposal",
          ],
          cost:
            "Classic map-reduce economics: N notes × (economy-tier extractor) + ONE standard-tier reduce. The extractor is the multiplied term and stays cheap and hard-capped; the reducer is the single call a clinician actually reads, so that is where the reasoning budget goes. Counting in a Code node rather than the model removes the most error-prone and token-heavy part of the reduce. A weekly review over a busy client costs one reduce regardless of note count, the map can run in Parallel batches for speed at unchanged per-note cost, and fallback models keep a Monday-morning handover from depending on one provider's uptime.",
          output:
            "A scheduled handover or a `review_due` trigger collects the note window → a Loop maps a cheap extractor over every note, quoting carers verbatim → a Code node counts the real frequencies → a standard-tier reducer builds the handover summary plus a separate, evidence-cited list of PROPOSED care-plan review points → a Supervisor node routes operational, clinical and medication items to their proper owners → the responsible clinician approves any change at the Approval node → a Sub-workflow applies the approved change, refreshes the per-client bands and re-briefs the carers → an Output node publishes the handover to the incoming shift and a Tool (MCP) node writes the change to the care record, stamped with the approving clinician.",
        },
      },
    ],
  },
];
