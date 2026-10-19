import type { Industry } from "./recipes-data";
import { Shirt, Palette } from "lucide-react";

// ---------------------------------------------------------------------------
// Textile & apparel manufacturing and Handicraft / artisan-MSME export workflow
// recipes — same "inline copy, no i18n keys" precedent and honesty rules as
// recipes-data.tsx. Every recipe is expressed ONLY in VegaDūta's real building
// blocks (Trigger / Agent / Knowledge / Condition / Approval / Output /
// Tool(MCP) / Voice / Device / Loop / Code / Parallel / Supervisor /
// Sub-workflow), so each maps 1:1 to something you can actually build in the
// Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — the apparel ERP/PLM, the buyer's
// vendor portal, the compliance-audit platform, the forwarder/CHA system,
// customs filing, the marketplace back-end — are reached the real way, via an
// inbound Webhook trigger and/or a Tool (MCP) node, and each mcp[] entry says
// what the call actually does. Only real channels are used (Webhook, WhatsApp,
// SMS, Voice/Call, Email, Slack, Telegram, ElevenLabs, Sarvam, Razorpay UPI).
// Every consequential decision — committing a delivery date or a price to a
// buyer, accepting an order change, rejecting a production lot, releasing a
// shipment, releasing a payout — goes through an Approval (human-in-the-loop)
// step. Impact lines describe the mechanism, never invented ROI numbers.
//
// User-defined tools, described honestly: a tenant-authored tool here is a
// SpEL expression evaluated against the tool's JSON input. Sandboxed by
// construction — no method calls, no constructors, no bean or type references,
// and the grammar has no loop. So it is a data-transform / formula tool: pull a
// field out of the input, format a string, do arithmetic, branch with a
// ternary or elvis. It cannot call an API and cannot iterate a list. That
// limitation is exactly what makes it safe to hand to a factory: the AQL
// limits, commission slabs and tolerance bands below are authored by the
// business, per buyer, without a developer and without a deployment. Anything
// needing iteration (summing invoice line items) is a Code node instead, and
// the recipes say which is which.
//
// The WhatsApp + Sarvam pairing carries real weight in both sectors: line
// supervisors, cluster leads and artisans work in Hindi, Tamil, Bangla,
// Gujarati or Odia on WhatsApp — usually by voice note — not in English on
// email. Recipes use that as the primary field channel, with English
// structured output for the office side and the audit trail.
// ---------------------------------------------------------------------------

export const INDUSTRIES_TEXTILECRAFT: Industry[] = [
  // ============================================ TEXTILE & APPAREL MANUFACTURING
  {
    id: "textile-apparel",
    name: "Textile & apparel manufacturing",
    icon: Shirt,
    tagline:
      "Techpack revisions, sampling round-trips, lot decisions and audit packs — chased and assembled automatically, with your own AQL limits encoded as tenant-authored formula tools and every buyer commitment human-approved.",
    recipes: [
      {
        id: "tex-techpack-change",
        title: "Buyer techpack revision → impact assessment before anything is accepted",
        scenario:
          "Rev C arrives at 11:40pm as an attachment called STYLE4471_TECHPACK_revC_FINAL_v2.xlsx. Rev B is somewhere in the shared drive, in a folder named 'latest', next to a folder named 'latest FINAL'. The merchandiser opens both on a laptop at the dining table and scrolls the measurement tab column by column looking for the cells that moved, then messages the fabric store on WhatsApp to ask whether the interlining has already been issued, then guesses at a date because the buyer's mail says 'please confirm by tomorrow'. Cutting starts at 8am and nobody has told them anything.",
        triggerLabel: "Email / Webhook (revised techpack from the buyer or the vendor portal)",
        steps: [
          { kind: "trigger", title: "Revised techpack arrives", detail: "An Email trigger on the merchandising inbox catches the buyer's revision; the same workflow accepts a Webhook push from the buyer's vendor portal (no first-party connector — the portal reaches you via Webhook)." },
          { kind: "knowledge", title: "Pull the in-production spec", detail: "A Knowledge node queries the style-master collection — approved techpack, BOM and trim card, scoped to this style only — so the comparison is against what the floor is actually building, not against whichever file was attached last." },
          { kind: "agent", title: "Diff the revision", detail: "An Agent node produces a field-level change list — measurement points, fabric/GSM, trims, print placement, packing — each marked CHANGED / ADDED / REMOVED with the old and new value quoted verbatim from the two documents." },
          { kind: "tool", title: "Score each change with your own rule", detail: "A user-defined tool (a SpEL formula authored by merchandising, not code) turns each change row into a severity band using this buyer's own thresholds — so 'a 0.5cm chest change is absorbable, 1cm is not' is a business setting, editable per buyer, not something a developer ships." },
          { kind: "parallel", title: "Assess the impact three ways at once", detail: "A Parallel node fans the scored change list to three assessors — materials, production and costing — so one revision is evaluated on all three axes in a single pass rather than three sequential email threads." },
          { kind: "supervisor", title: "Merchandiser-supervisor consolidates", detail: "A Supervisor agent merges the three assessments into one buyer-facing position: what is absorbable, what moves the ship date, what carries a cost, what is simply not feasible at this stage of the order." },
          { kind: "approval", title: "Merchandising manager owns the answer", detail: "An Approval node holds the whole position — accepting the change, any revised delivery date, any surcharge. A date and a price to a buyer are binding commitments; the agent never sends one." },
          { kind: "subworkflow", title: "Trigger re-sampling if the change demands it", detail: "If the approved position requires a fresh PP sample, a Sub-workflow node hands off to the sampling recipe below with the style and the accepted change list — one shared sampling workflow, not a copy pasted into every trigger." },
          { kind: "output", title: "Reply to the buyer + brief the floor", detail: "An Output node sends the consolidated response on the buyer's email thread, and a second Output posts the accepted changes to the production floor group on WhatsApp in the line's own language via Sarvam." },
        ],
        connectors: ["Email", "Webhook", "WhatsApp", "Sarvam (Indian languages)", "Knowledge base", "Approval / HITL", "Tool (MCP → ERP/PLM)"],
        outcome: "Every techpack revision comes back as a quoted, field-level change list scored against this buyer's own tolerance rules, with a materials/production/costing position attached — and no date, cost or acceptance leaves the building without a merchandising manager's click.",
        impact: "The mechanism is grounding plus fan-out plus tenant-owned rules: the diff is quoted from the two documents rather than remembered, the three impact views are produced in one parallel pass instead of three sequential threads, and the 'is this absorbable' threshold lives in a formula merchandising can edit per buyer — so the manager decides on a complete picture on day one instead of assembling it by day three.",
        build: {
          agents: 5,
          agentsNote: "1 diff agent + 3 parallel impact assessors (materials / production / costing) + 1 supervisor that consolidates. Parallel, Approval, Knowledge and Sub-workflow are workflow nodes, not agents.",
          pattern: "Diff → tenant-authored severity scoring → Parallel fan-out (3 assessors) → Supervisor consolidation → human approval → optional sampling sub-workflow",
          prompt:
            "(Diff agent) Compare the incoming techpack revision against the approved in-production techpack + BOM in Knowledge for style {{style}}. Return strict JSON {changes[{area (MEASUREMENT|FABRIC|TRIM|PRINT|PACKING|LABEL), field, old_value, new_value, delta_cm, status (CHANGED|ADDED|REMOVED), source_quote}], unchanged_confirmed: bool}. Quote old_value and new_value verbatim from the documents — if a value is not stated in one of the two documents, set it null and flag it; NEVER infer or normalise a measurement, and never unit-convert. You produce a change list only: you do not accept the revision, do not estimate a date, and do not price anything.",
          agentRoles: [
            { role: "Techpack diff agent", model: "Premium tier — the hard step; a missed measurement change becomes cut fabric. Configure an economy fallback model so a provider outage degrades to a slower, flagged diff rather than a stalled buyer thread", prompt: "Field-level diff of revised vs approved techpack/BOM. Verbatim quoted old/new values, null (not guessed) for anything absent, explicit status per field. No acceptance, no dates, no costing." },
            { role: "Materials assessor (parallel)", model: "Standard tier", prompt: "Given the scored change list, state materials impact only: which fabric/trim POs are affected, what is already issued or in transit, what must be re-sourced, and the lead time each supplier document actually states. Cite the document; never invent a lead time." },
            { role: "Production assessor (parallel)", model: "Standard tier", prompt: "Given the scored change list, state production impact only: which cut/sew/finishing operations change, whether cut fabric is affected, whether a new PP sample or re-approval is required, and which line-plan slots move. No dates to the buyer — internal feasibility only." },
            { role: "Costing assessor (parallel)", model: "Standard tier", prompt: "Given the scored change list, state cost impact only, computed from the rate/BOM data supplied — per-unit material delta, extra operations, rework or write-off of committed material. Show the arithmetic. Never round into a 'ballpark' and never present this as a quoted price." },
            { role: "Merchandiser supervisor", model: "Premium tier — one call, and it produces everything the buyer will read", prompt: "Merge the three assessments into one draft buyer position: absorbable changes, changes that move the ship date, changes that carry a cost, changes not feasible at this order stage. Flag every conflict between assessors rather than resolving it silently. Output is a DRAFT for the merchandising manager to approve or edit — never phrase it as a confirmed commitment." },
          ],
          mcp: [
            "Apparel ERP / PLM — via Tool (MCP): fetch the style master, BOM and trim card, read which fabric/trim POs are already issued or in transit, and read the current line-plan slot for the style",
            "Apparel ERP / PLM — via Tool (MCP): attach the approved change note to the style and stamp the accepted revision number (write happens only after Approval)",
            "Buyer vendor portal — inbound via Webhook (no first-party connector): receives the revision push and its attachment reference",
            "Email — the buyer's own revision thread, replied to in place",
            "WhatsApp + Sarvam — floor briefing to the line group in the line's language",
          ],
          tools: [
            "knowledge_search (style-scoped collection: approved techpack, BOM, trim card, prior revisions for this style only)",
            "http_request (read line plan and issued POs from the ERP/PLM; attach the approved change note back)",
            "send_email (reply on the buyer's existing revision thread after approval)",
            "user-defined tool `change_severity` (SpEL): input.area == 'MEASUREMENT' ? (input.deltaCm > input.buyerToleranceCm ? 'BLOCKING' : 'ABSORBABLE') : (input.area == 'FABRIC' or input.area == 'TRIM' ? 'BLOCKING' : 'REVIEW') — merchandising authors and edits this per buyer in the tool editor; a fussy account gets a tighter tolerance the same afternoon, with no developer and no deployment. It is a formula over the change row only: it reads fields, compares numbers and returns a band. It cannot call the ERP or loop over the change list — the workflow applies it row by row.",
          ],
          guardrails: [
            "The workflow never accepts a revision, commits a delivery date or quotes a surcharge — all three sit behind the Approval node for the merchandising manager",
            "Every diff row carries a verbatim source quote; a value absent from either document is returned null and flagged, never inferred — measurements are never normalised or unit-converted silently",
            "Severity comes from the tenant-authored `change_severity` formula, so the threshold is auditable, per-buyer and editable by the business — the model never decides what counts as absorbable",
            "The supervisor surfaces assessor disagreement instead of resolving it — a costing/production conflict reaches the human as a conflict",
            "The floor is briefed and the sampling sub-workflow is triggered only AFTER approval, so no line changes on the strength of an unaccepted revision",
          ],
          cost:
            "One revision costs: 1 premium diff + 3 standard assessors in parallel + 1 premium consolidation. Parallel buys wall-clock time here, not money — the three assessors cost the same run sequentially or together. Keep the assessors on the standard tier and spend on the two premium calls that bracket them: the diff (where a miss is expensive fabric) and the consolidation (which is what the buyer actually reads). Set an economy fallback model on both premium roles so a provider outage produces a slower flagged answer instead of a dead buyer thread. Revisions are low-volume and high-stakes — the wrong place to economise.",
          output:
            "A quoted field-level change list → each row scored by the tenant's own `change_severity` formula → three parallel impact assessments → one consolidated draft buyer position → the merchandising manager approves or edits at the Approval node → the sampling sub-workflow is triggered if a fresh PP sample is required → an Output node replies on the buyer's email thread while a second Output briefs the floor on WhatsApp via Sarvam, and a Tool (MCP) node attaches the approved change note to the style in the ERP/PLM.",
        },
      },
      {
        id: "tex-sampling",
        title: "Sampling round-trip: buyer comments mapped to actions, with a completeness gate",
        scenario:
          "Fit comments come back as a phone photo of the sample with red-pen circles, plus 40 lines of email under a subject line that has been forwarded six times. The sampling merchant prints it, walks it to the pattern master, translates the comments out loud in Hindi, and writes the actions in a spiral notebook. Two comments contradict each other, one refers to 'the point we discussed on the call', and nobody is sure whether the buyer approved the collar or just did not mention it. The next sample is cut anyway, because the courier pickup is at 6pm.",
        triggerLabel: "Email / Webhook (buyer comments on a submitted sample)",
        steps: [
          { kind: "trigger", title: "Comments come back", detail: "An Email trigger on the sampling inbox (or a Webhook from the buyer's portal) catches the comment sheet for a specific sample submission." },
          { kind: "agent", title: "Comments → structured actions", detail: "An Agent node turns free-text comments into discrete actions: measurement point + revision, trim swap, construction change, or 'approved as submitted' — each tagged mandatory-before-next-round or advisory, with the buyer's own words quoted." },
          { kind: "condition", title: "Completeness gate", detail: "A Condition node checks that every comment maps to exactly one action and that no mandatory point is left ambiguous — an unmapped or contradictory comment is never silently dropped; it branches to one consolidated clarification back to the buyer." },
          { kind: "loop", title: "One clarification round, not five", detail: "A Loop node holds the outstanding-clarification list and re-asks only what is still unanswered, so the buyer receives one consolidated question set per round rather than a trickle of separate mails." },
          { kind: "agent", title: "Draft the next-round plan", detail: "A second Agent node assembles the sample plan: the revised spec sheet for the sample room, pattern and trim actions in execution order, and an internal readiness estimate explicitly marked internal-only." },
          { kind: "tool", title: "Compute the internal ready date your way", detail: "A user-defined tool (SpEL) turns the action count and the round type into an internal working-day estimate using the sample room's own documented lead times — a formula the sampling head can retune when the room's capacity changes, without touching the workflow." },
          { kind: "approval", title: "Sampling head confirms the dispatch date", detail: "An Approval node holds the buyer-facing sample dispatch date. A committed sample date drives the buyer's own calendar — the agent proposes it, the sampling head commits it." },
          { kind: "output", title: "Issue to the sample room + confirm to the buyer", detail: "An Output node pushes the action list to the sample room group on WhatsApp in their own language via Sarvam, and confirms the approved dispatch date on the buyer's email thread." },
        ],
        connectors: ["Email", "Webhook", "WhatsApp", "Sarvam (Indian languages)", "Knowledge base", "Approval / HITL", "Tool (MCP → ERP/PLM sample tracker)"],
        outcome: "Every buyer comment ends up as exactly one traceable action with its source quote attached, ambiguity is resolved in one consolidated round instead of five, and only a human commits the next dispatch date.",
        impact: "The mechanism is the completeness gate: 'every comment is accounted for' becomes a checked fact instead of an assumption, so the next sample is not built against a comment somebody skimmed at 5:40pm — and the clarification loop collapses a trickle of questions into a single round-trip the buyer can answer once.",
        build: {
          agents: 2,
          agentsNote: "1 comment-mapping agent + 1 sample-plan agent. The completeness gate, the clarification loop, the date formula and the approval are workflow nodes and tools.",
          pattern: "Extract → completeness gate → bounded clarification loop → plan → human commits the date",
          prompt:
            "(Comment mapper) You map buyer sample comments to actions for {{style}} round {{round}}. Return strict JSON {actions[{comment_quote, area (MEASUREMENT|FIT|TRIM|CONSTRUCTION|FABRIC|PACKING), action, spec_point, priority (MANDATORY|ADVISORY), confidence}], unmapped[], contradictions[]}. Every action must carry the buyer's own words in comment_quote. If a comment is ambiguous, contradicts another comment, or you cannot tell which measurement point it refers to, put it in unmapped[] or contradictions[] — do NOT guess a spec point and do NOT drop it. 'Approved' is recorded only where the buyer explicitly approves; silence on a point is never approval.",
          agentRoles: [
            { role: "Comment mapper", model: "Premium tier — the accuracy-critical step; a mis-mapped measurement comment costs an entire sampling round and a week of calendar. Economy fallback model configured for outages", prompt: "Free-text buyer comments → one discrete action each, with verbatim quote, area, spec point and MANDATORY/ADVISORY priority. Ambiguous or contradictory comments go to unmapped[]/contradictions[], never to a guess. Silence is never approval." },
            { role: "Sample-plan agent", model: "Standard tier", prompt: "From the confirmed action list, produce the sample-room work order: revised spec points, pattern/trim actions in execution order, materials to be pulled, and an INTERNAL readiness estimate taken from the lead-time formula. Label the estimate internal-only — you never state a buyer-facing dispatch date." },
          ],
          mcp: [
            "ERP / PLM sample tracker — via Tool (MCP): read the submission record for this style and round (what was sent, when, against which spec version)",
            "ERP / PLM sample tracker — via Tool (MCP): write the round outcome, the action list and the approved dispatch date back against the submission",
            "Buyer vendor portal — inbound via Webhook (no first-party connector): receives comment sheets posted on the portal rather than emailed",
            "Email — the buyer's comment thread, for both the clarification round and the confirmation",
            "WhatsApp + Sarvam — the sample-room work order, in the room's own language",
          ],
          tools: [
            "knowledge_search (style-scoped collection: approved spec, measurement chart, every prior round's comments for this style)",
            "http_request (read and update the sample submission record in the ERP/PLM)",
            "send_email (consolidated clarification and the approved dispatch confirmation, on the buyer's existing thread)",
            "user-defined tool `sample_ready_days` (SpEL): input.round == 'PP' ? input.baseDays + input.actionCount * input.perActionDays + 2 : input.baseDays + input.actionCount * input.perActionDays — the sampling head owns this formula: base days, per-action days and the PP buffer are the room's own numbers, editable when the room's capacity changes, with no developer involved. It is arithmetic over the plan's own fields; it does not read the ERP and cannot iterate the action list — the workflow passes it a count.",
          ],
          guardrails: [
            "The buyer-facing dispatch date passes the Approval node — the formula's estimate is explicitly marked internal-only and is never sent as-is",
            "Completeness gate: every comment maps to exactly one action or lands in unmapped[]/contradictions[], and the round does not proceed — nothing is silently dropped",
            "Every action carries the buyer's verbatim comment, so the pattern master can always see what was actually asked for rather than someone's paraphrase",
            "Explicit approval only — a point the buyer did not comment on is never recorded as approved",
            "Clarifications are batched per round by the Loop node, so the buyer is never drip-fed questions across a week",
          ],
          cost:
            "Per round: 1 premium mapping call + 1 standard planning call, plus a cheap re-run of the mapper on each clarification reply. The mapper earns the premium tier because it is accuracy-critical and runs a handful of times per style — not per unit. The plan agent is assembly work and sits on the standard tier; drop the clarification re-mapping passes to economy once the action list is largely settled. The date formula costs nothing — it is a SpEL expression, not a model call, which is precisely why the lead-time policy belongs there rather than in a prompt.",
          output:
            "Comments become quoted, prioritised actions → the completeness gate blocks on anything unmapped or contradictory → one consolidated clarification round per cycle → a sample-room work order with an internal readiness estimate from the tenant's own formula → the sampling head approves the buyer-facing dispatch date → an Output node issues the work order on WhatsApp via Sarvam and confirms the date on the buyer's email thread, with the round written back to the ERP/PLM sample tracker.",
        },
      },
      {
        id: "tex-lot-rejection",
        title: "Inline QC lot call in the supervisor's own language (WhatsApp + Sarvam)",
        scenario:
          "The end-line checker keeps a register on the table — style, bundle, defect, tally marks in pencil. She finds shading across a bundle at 2:15pm, tells the floor in-charge, who sends a 40-second Hindi voice note to a WhatsApp group with 60 people in it. The QA manager is in a buyer meeting on the first floor with his phone face-down. The line keeps running. By the time somebody plays the voice note, two more bundles have gone through, and the decision that had to be made at 2:15 gets made at 5:00 on a bigger, more expensive lot.",
        triggerLabel: "WhatsApp (line supervisor's defect report — text or voice note)",
        steps: [
          { kind: "trigger", title: "Supervisor reports the defect", detail: "A WhatsApp trigger catches the report from the line group — typed or as a voice note, in whatever language the supervisor actually speaks." },
          { kind: "voice", title: "Transcribe + normalise the language", detail: "A Voice node transcribes with Sarvam so Hindi, Tamil, Bangla, Gujarati and Odia are all first-class inputs; nobody has to compose an English message standing at a sewing line." },
          { kind: "agent", title: "Classify the defect against the AQL plan", detail: "An Agent node maps the report to a defect class and severity against the buyer's documented AQL sampling plan and defect glossary in a buyer-scoped Knowledge collection, and asks back for sample size and defect count if the report did not include them." },
          { kind: "tool", title: "Apply YOUR AQL rule, not the model's opinion", detail: "A user-defined tool (SpEL) compares the defect rate against this buyer's AQL limit and returns PASS / REVIEW / REJECT_REWORK. QA authors the expression themselves and keeps a different one per buyer — the disposition rule is a business setting with an audit trail, never a sentence buried in a prompt." },
          { kind: "condition", title: "Within tolerance, or a real call?", detail: "A Condition node branches on the formula's band: PASS is logged as an advisory; REVIEW and REJECT_REWORK escalate as a lot decision. The branch is arithmetic against the plan, not a judgement call." },
          { kind: "agent", title: "Propose accept / rework / reject", detail: "The proposal agent drafts the case with the numbers shown: sample size, defects found, the AQL limit being applied, the affected bundle range, and the specific rework operation if rework is the recommendation." },
          { kind: "device", title: "Flag the line, if a line device is registered", detail: "Optional: where a line-side tablet or andon indicator is registered in Device Control, a Device node marks the line as 'lot under QC review' the moment a REJECT_REWORK band is hit — so the floor sees it without waiting for a person to walk over." },
          { kind: "approval", title: "QA head decides the lot", detail: "An Approval node holds the decision. Rejecting or reworking a lot stops a line and costs money — the agent shows the arithmetic, the QA head makes the call, on their phone, from the buyer meeting." },
          { kind: "output", title: "Decision back to the floor, in-language", detail: "An Output node sends the decision to the line group on WhatsApp in the supervisor's own language via Sarvam, with the bundle range and the exact rework instruction; a Tool (MCP) node logs it against the lot in the ERP quality module." },
        ],
        connectors: ["WhatsApp", "Sarvam (Indian languages)", "Voice", "Device control", "Knowledge base", "Approval / HITL", "Tool (MCP → ERP quality log)"],
        outcome: "A voice note from the floor becomes an AQL-grounded, numbers-shown lot proposal in the QA head's approval queue within minutes, banded by the factory's own rule — and the decision comes back to the line in the language the line speaks.",
        impact: "The mechanism removes the language barrier and the availability barrier at the same time: the supervisor reports in their own language by voice, the disposition band comes from the factory's own auditable formula rather than a model's opinion, and the QA head receives a fully worked comparison instead of a forwarded voice note — so the decision happens while the line can still be stopped cheaply.",
        build: {
          agents: 2,
          agentsNote: "1 defect-classification agent (every report) + 1 proposal agent (only reports that breach the band). The disposition rule is a user-defined SpEL tool, not an agent — deliberately, so it is auditable and editable per buyer.",
          pattern: "Voice/WhatsApp intake → in-language transcription → tenant-authored AQL formula → human lot decision → in-language response",
          prompt:
            "(Classifier) You triage inline QC reports from a garment line. From the transcribed report, return strict JSON {defect_class, severity (CRITICAL|MAJOR|MINOR), lot_ref, bundle_range, sample_size, defects_found, missing_fields[]}. Classify ONLY against the buyer's defect glossary and AQL plan in Knowledge. If sample_size or defects_found is not stated, put it in missing_fields[] and stop — do NOT assume a sample size and never estimate a defect rate. Ask the supervisor for the missing numbers in THEIR language. You never accept, rework or reject a lot; the disposition band comes from the factory's own rule and the decision belongs to the QA head.",
          agentRoles: [
            { role: "Defect classifier", model: "Economy tier — runs on every floor report, including the majority that turn out to be within tolerance", prompt: "Transcribed report → defect class, severity, lot/bundle refs and the two numbers that matter (sample size, defects found). Missing numbers are asked back in-language, never assumed. No lot decision." },
            { role: "Lot-proposal agent", model: "Standard tier — runs only on reports the AQL formula bands as REVIEW or REJECT_REWORK. Economy fallback model so a provider outage still produces a flagged, human-readable case", prompt: "Draft the lot proposal with the arithmetic visible: sample size, defects found, the documented AQL limit applied, affected bundle range, recommended action and the specific rework operation. Cite the AQL clause. Present as a recommendation awaiting the QA head." },
          ],
          mcp: [
            "ERP / quality module — via Tool (MCP): resolve the lot and bundle references the supervisor named, and read which order and buyer the lot belongs to",
            "ERP / quality module — via Tool (MCP): write the approved disposition, the defect class and the sample arithmetic against the lot, so the buyer's own inspector can audit it later",
            "WhatsApp — the line group, for both intake and the in-language decision",
            "Sarvam — transcription of voice notes and in-language reply generation for Indian languages",
            "Device Control — optional: set a registered line-side tablet or andon indicator to 'lot under QC review' when the band is breached",
          ],
          tools: [
            "knowledge_search (buyer-scoped collection: that buyer's AQL sampling plan, defect glossary and rework SOP — one buyer's plan never answers another buyer's lot)",
            "http_request (resolve lot/bundle refs; log the approved decision against the lot)",
            "user-defined tool `lot_disposition` (SpEL): input.defectPct > input.aqlLimit ? 'REJECT_REWORK' : (input.defectPct > input.aqlLimit * 0.7 ? 'REVIEW' : 'PASS') — QA writes this in the tool editor and keeps a variant per buyer, because a 2.5 AQL account and a 1.0 AQL account are not the same factory. Changing the review band is a business edit with a version history, not a code change and not a re-prompt. It reads the numbers off the tool input and returns a band; it cannot call the ERP, cannot loop over bundles, and cannot reject anything itself.",
          ],
          guardrails: [
            "No lot is ever accepted, reworked or rejected by the workflow — the QA head's Approval is the only path to a disposition, and the line is told only after that",
            "The disposition band comes from the tenant-authored `lot_disposition` formula against the buyer's documented AQL limit — the model never sets the threshold, and the rule that was applied is recorded with the decision",
            "Missing sample size or defect count blocks the proposal and is asked back to the supervisor in their own language, rather than being assumed",
            "Both the inbound transcription and the outbound decision stay in the supervisor's language — the English structured record exists for the office and the audit log, not for the floor",
            "Knowledge is scoped per buyer, so one buyer's AQL plan can never be applied to another buyer's lot",
            "Every decision is written back against the lot id, keeping the quality log auditable for the buyer's own inspection",
          ],
          cost:
            "This is the one recipe here with real per-event volume, so tiering carries weight: the classifier runs on every floor report and belongs on the economy tier, while the standard-tier proposal agent runs only on the minority of reports the formula bands as REVIEW or REJECT_REWORK — the SpEL tool plus the Condition node is what keeps that ratio honest, and it costs nothing to evaluate. Transcription is billed per voice note and is the entire reason the floor participates at all.",
          output:
            "A WhatsApp voice note in any supported Indian language → Sarvam transcription → economy-tier classification against that buyer's AQL plan → the tenant's own `lot_disposition` formula bands it → PASS is logged as an advisory, REVIEW/REJECT_REWORK escalate (and optionally flag a registered line device) → a numbers-shown proposal → the QA head approves on their phone → the decision returns to the line group in the supervisor's language and is logged against the lot in the ERP.",
        },
      },
      {
        id: "tex-audit-pack",
        title: "Compliance-audit document pack: assembled, expiry-checked, gap-listed",
        scenario:
          "The buyer mails on a Friday that a social audit is booked for the 22nd. The compliance officer opens a shared drive folder called AUDIT DOCS FINAL, which contains sub-folders by year, one called MISC, and one called MISC 2. The fire NOC is a phone photo of a photocopy. The ESI challans are in the accountant's email. The wage register for the required month is in a different building. Machine calibration certificates are in a lever-arch file on top of a cupboard. Everyone finds out which ones expired last quarter when the auditor asks for them, at the table, in front of the buyer's representative.",
        triggerLabel: "Webhook / Email (audit announcement from the buyer or the audit platform)",
        steps: [
          { kind: "trigger", title: "Audit is announced", detail: "A Webhook from the buyer's audit platform (no first-party connector) or an Email announcement starts the pack, carrying the audit standard and the visit window." },
          { kind: "knowledge", title: "Load the checklist for that standard", detail: "A Knowledge node pulls the document checklist for the specific standard and version being applied, from a compliance-scoped collection — so the pack is built against that auditor's requirement list rather than a generic one." },
          { kind: "loop", title: "Map: check every required document", detail: "A Loop node runs a cheap checker once per checklist line — present, current version, inside its validity window on the audit date, covering the required period." },
          { kind: "agent", title: "Per-document verdict", detail: "The checker returns strict JSON per line: PRESENT_VALID / PRESENT_EXPIRED / WRONG_PERIOD / MISSING / UNREADABLE, with the document reference, its printed validity date and the owning department." },
          { kind: "tool", title: "Renewal urgency, on your own thresholds", detail: "A user-defined tool (SpEL) converts days-to-expiry versus days-to-audit into an urgency band — the compliance head sets what counts as CRITICAL, because a fire NOC renewal and a calibration certificate do not have the same runway in every state." },
          { kind: "agent", title: "Reduce: readiness report + owner-wise gap list", detail: "A reducer agent rolls the verdicts into a readiness report — ready, expiring before the audit date, missing, unreadable — split by owning department with the lead time each renewal SOP actually states." },
          { kind: "output", title: "Chase the owners where they actually are", detail: "An Output node sends each department owner their own gap list — WhatsApp in-language via Sarvam for floor, admin and maintenance owners; Email for HR and finance — instead of one broadcast that nobody owns." },
          { kind: "approval", title: "Compliance head releases the pack", detail: "An Approval node holds the assembled pack before anything is submitted to the buyer or uploaded to the audit platform — a submitted pack is a formal representation about the factory, so a named human signs it off." },
        ],
        connectors: ["Webhook", "Email", "WhatsApp", "Sarvam (Indian languages)", "Knowledge base", "Approval / HITL", "Tool (MCP → document store, audit platform)"],
        outcome: "By the day after the announcement there is a per-document verdict, an owner-wise gap list already in the right people's hands with an urgency band on each item, and a pack nobody submits until the compliance head releases it.",
        impact: "The mechanism is exhaustive per-line checking plus routing: every checklist line gets its own cheap pass — including the validity-date arithmetic against the audit date that a Friday-evening manual review always skips — and each gap goes to the person who can actually close it, in the channel they read, banded by the factory's own idea of what is urgent.",
        build: {
          agents: 2,
          agentsNote: "1 document checker (once per checklist line via the Loop) + 1 reducer that writes the readiness report. Loop, Knowledge and Approval are workflow nodes; the urgency band is a SpEL tool.",
          pattern: "Map-reduce over the checklist → tenant-authored urgency banding → owner-wise routed chase → human releases the pack",
          prompt:
            "(Checker) Verify ONE audit-checklist line for an audit on {{audit_date}} under standard {{standard}}. Return strict JSON {checklist_item, status (PRESENT_VALID|PRESENT_EXPIRED|WRONG_PERIOD|MISSING|UNREADABLE), doc_ref, valid_until, covers_period, owner_dept, note}. Compare valid_until against {{audit_date}} — a document expiring before the audit date is PRESENT_EXPIRED even if it is valid today. If a date is unreadable or the file is a scan you cannot parse, return UNREADABLE; never assume a document is valid because it exists, and never infer a validity period that is not printed on it.",
          agentRoles: [
            { role: "Document checker (per checklist line)", model: "Economy tier — runs once per line across the whole checklist, so its tier sets the cost of the run", prompt: "One checklist line → presence, version, validity-vs-audit-date and period coverage, as strict JSON with the document reference and owning department. UNREADABLE rather than assumed-valid. No pack-level judgement." },
            { role: "Readiness reducer", model: "Standard tier — reasoning. Economy fallback model so the report still lands during a provider outage, flagged as degraded", prompt: "Aggregate the per-line verdicts into a readiness report: ready, expiring before the audit date, missing, unreadable. Split gaps by owning department, attach the renewal lead time each documented SOP states, and flag any gap whose lead time exceeds the days remaining. Use only the verdicts supplied — never assert a document exists that no verdict covers." },
          ],
          mcp: [
            "Compliance / document store — via Tool (MCP): list the documents filed against each checklist line and fetch the file for parsing",
            "Compliance / document store — via Tool (MCP): file the released pack and its manifest against the audit record",
            "Buyer audit platform — inbound announcement via Webhook, and submission via Tool (MCP): upload the released pack against the scheduled audit (no first-party connector, and the upload happens only after Approval)",
            "HRMS / statutory-filings system — via Tool (MCP): read the latest ESI/PF challan references and wage-register periods so the checker is comparing against what was actually filed",
            "WhatsApp + Sarvam / Email — owner-wise gap chasing in each owner's channel and language",
          ],
          tools: [
            "knowledge_search (compliance-scoped collection: the checklist for this standard and version, renewal SOPs and documented lead times)",
            "http_request (list and fetch documents from the store; upload the released pack)",
            "web_search (optional: check whether the published standard has a newer revision than the one in Knowledge — anything found is raised to the compliance head as a lead to verify, never treated as fact or used to alter the checklist automatically)",
            "send_email (owner-wise gap lists to HR and finance, on their existing threads)",
            "user-defined tool `renewal_urgency` (SpEL): input.daysToExpiry < 0 ? 'EXPIRED' : (input.daysToExpiry < input.leadTimeDays ? 'CRITICAL' : (input.daysToExpiry < input.daysToAudit ? 'EXPIRES_BEFORE_AUDIT' : 'OK')) — the compliance head owns the thresholds, because a fire NOC and a calibration certificate have different runways in different states, and the same factory may face two standards with different tolerance. Pure arithmetic over the verdict row; it cannot fetch a document or iterate the checklist.",
          ],
          guardrails: [
            "Nothing is submitted to the buyer or uploaded to the audit platform before the compliance head's Approval — the pack is a formal representation about the factory",
            "Validity is arithmetic against the audit date, not against today; a certificate expiring mid-window is reported as expired, and the urgency band comes from the tenant's own formula",
            "A document that cannot be parsed is UNREADABLE and gets chased — never counted as present",
            "The reducer may only report on lines a checker actually returned; an absent verdict is a gap, never an assumption of compliance",
            "web_search results are treated as leads for a human to verify, never as an authority that changes the checklist — the checklist of record stays in Knowledge",
            "Gap lists are routed per owning department in that owner's own channel and language, so accountability is individual rather than a group broadcast",
          ],
          cost:
            "Cost is linear: N checklist lines × economy-tier checker + ONE standard-tier reduce. The checker's tier is the dominant lever because it runs across the whole checklist — and its job is deliberately narrow (presence, version, two date comparisons) precisely so it can stay there. The urgency banding is a SpEL expression and costs nothing, which is the right home for a policy that changes per standard. Run the map in Parallel batches to compress wall-clock time without changing per-line cost.",
          output:
            "The checklist for that standard is mapped line by line by a cheap checker → each verdict banded by the tenant's own `renewal_urgency` formula → a reducer produces the readiness report and an owner-wise gap list with lead times → each owner is chased on WhatsApp via Sarvam or Email → the compliance head releases the assembled pack at the Approval node → only then does a Tool (MCP) node upload it to the buyer's audit platform.",
        },
      },
    ],
  },

  // ============================================== HANDICRAFTS & ARTISAN MSME EXPORT
  {
    id: "handicraft-export",
    name: "Handicrafts & artisan MSME export",
    icon: Palette,
    tagline:
      "Run an artisan cluster on WhatsApp in the language it actually speaks — allocation, payment status, export documents and season planning, with your own commission and tolerance rules as tenant-authored formulas and every commitment human-approved.",
    recipes: [
      {
        id: "craft-cluster-allocation",
        title: "Export order → artisan cluster allocation, confirmed in-language on WhatsApp",
        scenario:
          "A buyer PO lands for 900 hand-block pieces across four designs. There is one WhatsApp group per cluster — 'Bagru Team', 'Sanganer New', 'Barmer Ladies Group' — and the export manager works down them one at a time, calling the lead, asking how many they can take, and pencilling numbers into a diary page with the clusters listed down the left margin. Three leads do not pick up. One says 'ho jayega' without a number. The earliest cluster starts cutting cloth against a quantity nobody has written down anywhere, and the buyer is waiting on a ship date.",
        triggerLabel: "Email / Webhook (buyer purchase order)",
        steps: [
          { kind: "trigger", title: "PO arrives", detail: "An Email trigger on the export desk (or a Webhook from the buyer's portal — no first-party connector) catches the purchase order with quantities, designs and the requested ship window." },
          { kind: "agent", title: "Draft an allocation proposal", detail: "An Agent node splits the order across clusters using the craft/skill mapping, historical per-cluster throughput and current open commitments in Knowledge — producing a proposal with a stated reason per cluster, not a round-robin." },
          { kind: "tool", title: "Cap every cluster with your own rule", detail: "A user-defined tool (SpEL) computes each cluster's remaining headroom from its documented throughput minus open commitments, minus the safety margin the export manager sets — so 'never load Barmer past 80% in a festival month' is a business setting, not a line in a prompt." },
          { kind: "loop", title: "Poll each cluster lead, in their language", detail: "A Loop node asks every proposed cluster lead on WhatsApp — Sarvam handling Hindi, Bangla, Gujarati or Odia — to confirm the quantity and the date they can actually commit to, with voice-note replies accepted." },
          { kind: "voice", title: "Accept voice replies", detail: "A Voice node transcribes voice-note confirmations, so a cluster lead who does not type still answers in the medium they use every day rather than finding someone's nephew to text for them." },
          { kind: "condition", title: "Does the confirmed capacity cover the order?", detail: "A Condition node compares the sum of confirmed capacity against the PO. A shortfall is never quietly absorbed — it branches to the export manager with the exact gap and the clusters that still have headroom." },
          { kind: "approval", title: "Export manager commits the allocation", detail: "An Approval node holds the final allocation and any buyer-facing ship date. Allocating work commits an artisan family's month and a date to a buyer — the agent proposes, the manager commits." },
          { kind: "output", title: "Confirm both ways", detail: "An Output node sends each cluster its confirmed quantity, design and date on WhatsApp in-language, and the consolidated allocation to the buyer thread on Email; a Tool (MCP) node records it against the PO in the export ERP." },
        ],
        connectors: ["Email", "Webhook", "WhatsApp", "Sarvam (Indian languages)", "Voice", "Knowledge base", "Approval / HITL", "Tool (MCP → export ERP)"],
        outcome: "An allocation proposal grounded in real per-cluster throughput and capped by the manager's own loading rule, confirmed by each lead in their own language, with any shortfall stated as a number — and nothing committed to a buyer or a cluster without the export manager.",
        impact: "The mechanism is parallel in-language polling replacing a serial afternoon of phone calls: every cluster lead is asked at once, in the language and medium they use, their answers land as structured capacity instead of pencil marks, and the shortfall arithmetic is done before a date is promised rather than discovered after.",
        build: {
          agents: 2,
          agentsNote: "1 allocation-proposal agent + 1 cheap reply-interpreter that runs once per cluster reply inside the Loop. Voice transcription, the headroom formula, the coverage branch and the approval are workflow nodes and tools.",
          pattern: "Proposal → tenant-authored headroom cap → in-language fan-out poll (Loop) → coverage gate → human commits allocation and date",
          prompt:
            "(Allocator) Propose a split of a buyer PO across artisan clusters for {{exporter}}. Use ONLY the craft/skill mapping, historical monthly throughput and current open commitments in Knowledge, and respect the headroom returned by the capacity tool. Return strict JSON {allocations[{cluster_id, design, quantity, proposed_ready_date, reason, headroom_note}], unallocated_quantity, assumptions[]}. Never allocate a design to a cluster whose skill mapping does not cover that craft, and never propose a quantity above the tool's headroom — if the order exceeds total capacity, leave the remainder in unallocated_quantity rather than spreading it thin. Proposed dates are internal until a cluster lead confirms and the export manager approves.",
          agentRoles: [
            { role: "Allocation proposer", model: "Standard tier — runs once per PO", prompt: "PO → per-cluster allocation with a stated reason, bounded by documented skill mapping and the headroom formula. Remainder goes to unallocated_quantity; capacity is never invented to make the order fit." },
            { role: "Cluster-reply interpreter (per reply)", model: "Economy tier — one call per cluster reply, including voice-note transcripts", prompt: "Turn one cluster lead's in-language reply into {confirmed_quantity, confirmed_date, conditions[], unclear[]}. A partial yes, or a date with no quantity, is marked unclear and gets one short follow-up in their language — never round a hesitant 'ho jayega' up into a confirmation." },
          ],
          mcp: [
            "Export ERP / order book — via Tool (MCP): read the PO lines, and read every cluster's currently open commitments so headroom is computed against reality, not last month's plan",
            "Export ERP / order book — via Tool (MCP): write the approved allocation against the PO, cluster by cluster, after the manager signs off",
            "Buyer portal — inbound via Webhook (no first-party connector): receives POs issued on the portal instead of by email",
            "WhatsApp + Sarvam — cluster-lead polling and confirmation in Hindi, Bangla, Gujarati or Odia, voice notes included",
            "Email — the buyer's confirmation thread",
          ],
          tools: [
            "knowledge_search (cluster-scoped collection: craft/skill mapping, historical monthly throughput, cluster working patterns)",
            "http_request (read the PO and the open order book; write the approved allocation back)",
            "send_email (consolidated allocation confirmation to the buyer, after approval)",
            "user-defined tool `cluster_headroom` (SpEL): (input.monthlyThroughput * input.safetyFactor) - input.openCommitments — the export manager owns safetyFactor and can hold a cluster at 0.8 through a festival month or push a reliable one to 1.0, per cluster, in the tool editor. It is arithmetic over the numbers the workflow hands it; it does not read the ERP itself and cannot loop over clusters — the workflow calls it once per cluster.",
          ],
          guardrails: [
            "No allocation reaches a cluster and no ship date reaches the buyer before the export manager's Approval — a commitment binds an artisan family's month",
            "Proposals are bounded by the `cluster_headroom` formula and the documented skill mapping; capacity is never invented to make an order fit",
            "A shortfall is surfaced as an explicit unallocated quantity with the exact gap — never silently spread across clusters",
            "A partial or hesitant cluster reply is marked unclear and followed up in the lead's own language, never rounded up into a confirmation",
            "Cluster leads are asked and answered entirely in their own language, by voice note if that is how they work — the English record exists for the office and the buyer, not for them",
          ],
          cost:
            "One standard-tier proposal per PO, plus one economy-tier interpretation per cluster reply — reply volume scales with cluster count, not order value, which is exactly why the interpreter stays on the economy tier. The headroom formula is SpEL and costs nothing, which is also why the loading policy belongs there rather than in a prompt that would need re-testing every time it changed. Voice transcription is per note, and it is the price of the clusters participating at all.",
          output:
            "A grounded allocation proposal capped by the tenant's own headroom formula → every cluster lead polled on WhatsApp in-language with voice replies accepted → confirmations interpreted into structured capacity → the Condition node exposes any shortfall against the PO → the export manager commits the allocation and the ship date at the Approval node → clusters are confirmed in-language, the buyer on Email, and the allocation written against the PO in the export ERP.",
        },
      },
      {
        id: "craft-payment-status",
        title: "Artisan payment status in their own language, payouts still human-released",
        scenario:
          "Voice notes arrive at 9:15pm: 'bhaiya, pichhle consignment ka paisa kab aayega'. The accounts assistant keeps a file called ARTISAN PAYMENT NEW FINAL.xlsx with a sheet per month, and answers whichever messages she sees before she leaves at 6. The rest ask again on Thursday, and again the following Tuesday. Some artisans stop asking and assume they have been short-paid on a quality deduction nobody explained to them in Hindi. A question with a factual, written-down answer becomes a trust problem.",
        triggerLabel: "WhatsApp (artisan or cluster lead asks about a payment — text or voice)",
        steps: [
          { kind: "trigger", title: "Artisan asks", detail: "A WhatsApp trigger catches the question from the artisan's own number, at whatever hour it arrives, in whatever language they write or speak." },
          { kind: "voice", title: "Transcribe the voice note", detail: "A Voice node transcribes with Sarvam, so an artisan who does not type is a first-class user of the system rather than someone who needs a relative to text on their behalf." },
          { kind: "agent", title: "Identify the artisan and the consignment", detail: "An Agent node resolves who is asking and which delivery they mean, then reads the payment ledger through a Tool (MCP) node — never from memory, never from a guess." },
          { kind: "tool", title: "Deductions and commission, on your own slabs", detail: "A user-defined tool (SpEL) computes the net payable from the passed quantity, the rate, the quality-deduction percentage and the cluster-lead commission slab — the exporter authors the slabs and can vary them per cluster, so the number quoted to an artisan is always the number the policy actually produces." },
          { kind: "condition", title: "Answerable, or does a human own it?", detail: "A Condition node branches: a clear, ledger-backed status is answered directly; a dispute, a quantity disagreement, a contested quality deduction or a request to release money early routes to the accounts owner." },
          { kind: "output", title: "Answer in their language", detail: "An Output node replies on WhatsApp in the artisan's own language via Sarvam — what was received, what was passed, what is on hold and why, and the documented next payment date, in short plain sentences." },
          { kind: "approval", title: "Any payout is released by a human", detail: "An Approval node holds every actual disbursement — milestone payout, advance, deduction reversal. The agent may report a payment's status; it never moves money." },
          { kind: "output", title: "Release on approval, confirm in-language", detail: "On approval the workflow executes a Razorpay UPI payout and the artisan gets an in-language confirmation with the reference; the accounts owner sees the same trail." },
        ],
        connectors: ["WhatsApp", "Sarvam (Indian languages)", "Voice", "Razorpay (UPI)", "Approval / HITL", "Tool (MCP → accounting / payment ledger)"],
        outcome: "Every artisan gets the same accurate, ledger-backed answer in their own language within minutes of asking, at any hour — with deductions explained from the exporter's own published slab, and every rupee that actually moves released by a named human.",
        impact: "The mechanism is language plus availability plus grounding: the question is answered from the ledger rather than from someone's recollection, in the artisan's own language, without needing office hours or an intermediary — while the money stays behind a human approval, so answering faster never means paying looser.",
        build: {
          agents: 1,
          agentsNote: "1 payment-status agent. Transcription, the net-payable formula, the dispute branch, the payout approval and the UPI disbursement are workflow nodes and tools.",
          pattern: "In-language intake → ledger-grounded answer with tenant-authored deduction maths → dispute branch → human-released UPI payout",
          prompt:
            "You answer artisan payment questions for {{exporter}}. Resolve the artisan and the consignment they mean, then read the payment ledger via the ledger tool and answer ONLY from what it returns: {quantity_received, quantity_passed, rate, deduction_pct, amount_paid, on_hold_amount, hold_reason, next_scheduled_date}, using the net-payable tool for the arithmetic. If the ledger has no record, or the artisan disputes a quantity or a deduction, or they ask for money early or out of schedule, do NOT answer with a number — hand it to the accounts owner. Never promise a payment date the ledger does not state, never estimate an amount yourself, and never say a payment has been made unless the ledger shows it. You cannot initiate, approve or release any payment. Reply in the artisan's own language, in short plain sentences.",
          agentRoles: [
            { role: "Payment-status agent", model: "Economy tier — high message volume, and the answer is a grounded lookup plus a formula, not a reasoning problem. Configure a fallback model so an outage does not leave artisans unanswered overnight", prompt: "Resolve artisan + consignment, read the ledger, compute net payable with the tool, answer only from those fields, in the artisan's language. Disputes, missing records and early-money requests go straight to the accounts owner. Never states a date the ledger does not state; never moves money." },
          ],
          mcp: [
            "Accounting / payment ledger — via Tool (MCP): resolve the artisan from their WhatsApp number, and read the consignment receipts, passed quantities, deductions and scheduled payment dates for that artisan only",
            "Accounting / payment ledger — via Tool (MCP): record the released payout and its Razorpay reference against the consignment, after Approval",
            "WhatsApp + Sarvam — the artisan-facing channel: text and voice, in Indian languages, at any hour",
            "Razorpay UPI — executes the approved payout to the artisan's registered VPA",
          ],
          tools: [
            "knowledge_search (policy-scoped collection: payment terms, quality-hold rules, what a deduction means in plain language)",
            "http_request (read the artisan payment ledger; record the released payout reference)",
            "user-defined tool `net_payable` (SpEL): (input.qtyPassed * input.rate) * (1 - input.deductionPct / 100) - (input.qtyPassed * input.rate * input.leadCommissionPct / 100) — accounts authors the slab and can hold a different commission percentage per cluster or per craft. Because it is one auditable expression rather than a prompt instruction, the number an artisan is told and the number the ledger pays are computed the same way every time. It is arithmetic over the fields handed to it: no ledger access, no iteration over consignments, no ability to release anything.",
          ],
          guardrails: [
            "The agent reports status only — every disbursement, advance and deduction reversal passes the Approval node before any Razorpay UPI payout executes",
            "Answers are strictly ledger-backed and the arithmetic comes from the `net_payable` formula: no estimated amounts, no promised dates the ledger does not state, no 'it has been paid' without a ledger record",
            "Disputes over quantity or quality deductions, and any request for early money, route straight to the accounts owner — the agent does not negotiate on the exporter's behalf",
            "Identity is resolved from the registered number before any figure is disclosed, so one artisan's payables are never read out to another",
            "Every reply is in the artisan's own language, including the reason for a hold; the English audit record is for the office side",
          ],
          cost:
            "This is the highest-volume workflow in the set — many short, repeated questions — so the answering agent belongs on the economy tier and nowhere else; the accuracy comes from the ledger tool and the SpEL formula, not from model size. Transcription is per voice note. The only expensive path is escalation, and that is a human's time spent on genuine disputes, which is exactly where it should go.",
          output:
            "An artisan's WhatsApp message or voice note in any supported Indian language → Sarvam transcription → identity and consignment resolved → net payable computed by the exporter's own formula → a ledger-backed status answered in their own language, or a dispute routed to accounts → any actual payout waits at the Approval node → on release, a Razorpay UPI disbursement executes and is confirmed to the artisan in-language with its reference, with the same trail visible to the accounts owner.",
        },
      },
      {
        id: "craft-export-docs",
        title: "Export document completeness check before the consignment is released",
        scenario:
          "The consignment is packed and the forwarder wants it on Thursday. The export executive works down a photocopied checklist on a clipboard in the packing hall at 10pm, ticking in blue pen: commercial invoice, packing list, certificate of origin, EPCH paperwork, the buyer's labelling declaration, insurance, the shipping-bill data set for the CHA. On the last tick he notices the invoice total and the packing-list weights disagree, because the packer added two cartons in the afternoon and told the invoice clerk verbally. The booking slips a week and the buyer's Christmas window moves.",
        triggerLabel: "Webhook (consignment marked ready in the export ERP) / Email",
        steps: [
          { kind: "trigger", title: "Consignment is ready to ship", detail: "A Webhook from the export ERP (no first-party connector) fires when the consignment is marked ready, carrying the shipment reference, destination and incoterm." },
          { kind: "knowledge", title: "Load the document set for this lane", detail: "A Knowledge node pulls the required document list for that destination, incoterm and product category — the set for an EU buyer is not the set for a US one, and the workflow checks against the right list rather than one photocopied master." },
          { kind: "loop", title: "Map: one check per required document", detail: "A Loop node runs a cheap checker per document — present, current, signed where a signature is required, carrying the fields the destination requires." },
          { kind: "code", title: "Cross-document consistency", detail: "A Code node does the arithmetic the eye skips at 10pm: invoice total against the sum of its line items, packing-list carton count and gross/net weights against the invoice, HS codes consistent across invoice, packing list and certificate of origin. This is a Code node precisely because it must iterate line items — the SpEL formula tools deliberately cannot loop." },
          { kind: "tool", title: "Tolerance band, set by your export desk", detail: "A user-defined tool (SpEL) decides whether a weight or value difference is a rounding artefact or a real mismatch, using the desk's own tolerance — so a 0.2kg gross-weight difference does not hold a container while a 20kg one always does." },
          { kind: "agent", title: "Reduce: release-readiness report", detail: "A reducer agent produces one report — documents ready, documents missing, and every mismatch stated as the two conflicting values side by side with their source documents named." },
          { kind: "approval", title: "Export head releases the shipment", detail: "An Approval node holds the release. Handing a consignment to the forwarder starts customs filing and a container booking — the agent reports readiness, the export head releases." },
          { kind: "output", title: "Hand over + chase what is open", detail: "On release, a Tool (MCP) node pushes the pack to the forwarder/CHA system and the shipping-bill data set to the filing agent — neither has a first-party connector, and the workflow never files with customs itself; open items go back to their owners by Email or in-language WhatsApp." },
        ],
        connectors: ["Webhook", "Email", "WhatsApp", "Sarvam (Indian languages)", "Knowledge base", "Approval / HITL", "Tool (MCP → export ERP, forwarder/CHA, filing agent)"],
        outcome: "Every consignment gets a documented release-readiness report with mismatches quoted value-against-value and banded by the desk's own tolerance — produced when the consignment is marked ready, not on a clipboard the night before the cut-off.",
        impact: "The mechanism is putting arithmetic where arithmetic belongs: totals, weights, carton counts and HS-code consistency are computed deterministically in a Code node rather than by a tired reader or a language model, the tolerance is the export desk's own setting, and the agent is left to do only what it is good at — explaining the gaps and naming who must close them.",
        build: {
          agents: 2,
          agentsNote: "1 per-document checker (once per required document via the Loop) + 1 reducer that writes the readiness report. The cross-document arithmetic is a Code node and the tolerance band is a SpEL tool — deliberately, neither is an agent.",
          pattern: "Map over the document set → deterministic Code-node cross-checks → tenant-authored tolerance band → reduce → human releases the shipment",
          prompt:
            "(Checker) Verify ONE required export document for shipment {{shipment_ref}} to {{destination}} on incoterm {{incoterm}}. Return strict JSON {doc_type, status (PRESENT_COMPLETE|PRESENT_INCOMPLETE|MISSING|UNSIGNED|UNREADABLE), missing_fields[], extracted{invoice_total, currency, carton_count, gross_weight, net_weight, hs_codes[]}, owner}. Extract values EXACTLY as printed — do not convert currencies, do not convert weight units, do not reformat an HS code. If a field is absent, return null and list it in missing_fields[]. You do not compare documents against each other; the workflow's Code node does that arithmetic. You never declare a shipment ready.",
          agentRoles: [
            { role: "Document checker (per document)", model: "Economy tier — runs once per required document on every consignment, so it sets the cost of the run", prompt: "One document → presence, completeness, signature status and verbatim extracted fields as strict JSON. No unit or currency conversion, no reformatting, null for absent fields. No cross-document comparison, no release judgement." },
            { role: "Readiness reducer", model: "Standard tier — reasoning. Economy fallback model so a provider outage still yields a usable, flagged report before the cut-off", prompt: "Combine the per-document verdicts with the Code node's cross-check results and the tolerance bands into one release-readiness report. State every mismatch as the two conflicting values with their source documents named. Name the owner and the single next action for each open item. Report only what the verdicts and checks contain — never assert a document is fine because nobody flagged it." },
          ],
          mcp: [
            "Export ERP / document store — via Tool (MCP): read the shipment record (destination, incoterm, product category) and fetch each filed document for parsing",
            "Forwarder / CHA system — via Tool (MCP): push the released document pack against the booking (no first-party connector; only after Approval)",
            "Customs-filing agent — via Tool (MCP): hand over the shipping-bill data set for the agent to file. The workflow never files with customs itself and never signs a declaration",
            "Buyer portal — via Tool (MCP): upload the shipping documents the buyer requires against the PO, where the buyer works that way",
            "Email / WhatsApp + Sarvam — chasing open items with the owner who can close them, in their own language",
          ],
          tools: [
            "knowledge_search (lane-scoped collection: the destination + incoterm + product-category document matrix, labelling and origin requirements)",
            "http_request (fetch documents; push the released pack to the forwarder and the data set to the filing agent)",
            "send_email (open-item chase to office owners, and the release note to the forwarder)",
            "user-defined tool `doc_tolerance` (SpEL): input.diff <= input.toleranceAbs ? 'WITHIN_TOLERANCE' : (input.diff <= input.toleranceAbs * 3 ? 'REVIEW' : 'MISMATCH') — the export desk authors this and passes a different toleranceAbs per field, so 0.2kg on a gross weight is a rounding artefact and 20kg is never one. It bands a single already-computed difference; the Code node did the summing, because a formula tool has no loop and is not pretending to. No method calls and no type references either — the grammar simply does not have them, which is exactly why it is safe to hand the expression to the export desk to edit.",
          ],
          guardrails: [
            "The consignment is released only at the Approval node — the workflow never hands over to the forwarder, never pushes filing data and never signs a declaration on its own judgement",
            "Cross-document arithmetic (line-item totals, carton counts, gross/net weights, HS-code consistency) is done deterministically in a Code node — a language model is never the arbiter of whether two numbers match",
            "The tolerance band is the export desk's own SpEL expression, so what counts as a rounding artefact is an auditable business setting rather than a model's discretion",
            "Extraction is verbatim: no currency conversion, no unit conversion, no HS-code reformatting; an absent field is null and gets chased, never inferred",
            "The required-document list is resolved per destination, incoterm and product category — one generic checklist is never applied to every lane",
            "Mismatches are always reported as both conflicting values with their source documents named, so the human resolves a fact rather than adjudicating a summary",
          ],
          cost:
            "Per consignment: N documents × economy-tier checker + one deterministic Code node (effectively free) + one SpEL banding call (free) + one standard-tier reduce. Moving the cross-checks into code is a cost decision as much as a correctness one — arithmetic in code costs nothing and never hallucinates, which lets the per-document checker stay narrow and cheap. Parallel batches compress wall-clock time without changing per-document cost.",
          output:
            "Each required document is checked once by a cheap agent → a Code node runs the cross-document arithmetic deterministically → each difference is banded by the desk's own tolerance expression → a reducer writes the release-readiness report with every mismatch quoted value-against-value → the export head releases at the Approval node → a Tool (MCP) node pushes the pack to the forwarder/CHA and the shipping-bill data set to the filing agent, while open items go back to their owners on Email or in-language WhatsApp.",
        },
      },
      {
        id: "craft-season-capacity",
        title: "Festival-season capacity plan before the dates are promised",
        scenario:
          "Diwali and Christmas orders land in the same eight-week window, and the same clusters are absent for harvest, for a village wedding season, and for the local fair. The export manager plans it on a whiteboard with cluster names in one column and week numbers across the top, half-rubbed-out, plus a diary where last year's actual dispatches are recorded but never added up. Dates get promised from feel during buyer calls, and the season is spent apologising to whichever buyer is furthest from the truth.",
        triggerLabel: "Webhook / scheduled (season planning cycle opens, or the enquiry pipeline crosses a threshold)",
        steps: [
          { kind: "trigger", title: "Season planning opens", detail: "A scheduled trigger (or a Webhook from the export ERP when the enquiry pipeline crosses a threshold) starts the planning cycle for the coming season window." },
          { kind: "knowledge", title: "Load last season's actuals", detail: "A Knowledge node pulls the previous comparable season's ACTUAL per-cluster output, the local festival and harvest calendar, and each cluster's documented working-day pattern — so the plan starts from what happened, not from nominal capacity." },
          { kind: "loop", title: "Ask every cluster what it can really do", detail: "A Loop node polls each cluster lead on WhatsApp in-language: available artisan count for the window, known absences, and the crafts they can run — voice replies accepted and transcribed via Sarvam." },
          { kind: "agent", title: "Build the season capacity picture", detail: "An Agent node converts the replies plus last season's actuals into a week-by-week capacity view per cluster and craft, with each week's available working days already netted of the festival and harvest calendar." },
          { kind: "tool", title: "Week capacity, from your own per-day rates", detail: "A user-defined tool (SpEL) turns available artisans, available days and last season's per-artisan-day output into an indicative weekly figure — the planner owns the per-day rates per craft, which is exactly the number that should never be buried in a prompt." },
          { kind: "agent", title: "Test the pipeline against it", detail: "A second Agent node lays the open enquiry and order pipeline over the capacity view and marks each enquiry comfortably servable, servable only with a later date, or not servable in the window — naming the binding constraint each time." },
          { kind: "approval", title: "Manager approves the plan and any dates", detail: "An Approval node holds the season plan and, critically, any buyer-facing date derived from it. The plan is a proposal; a promised festival-season date is a commitment the manager makes." },
          { kind: "output", title: "Publish it both ways", detail: "An Output node sends each cluster its indicative week-by-week load in-language on WhatsApp — labelled indicative, not a work order — and the approved position to the sales/export desk, so quoting and the floor work from the same plan." },
        ],
        connectors: ["Webhook", "WhatsApp", "Sarvam (Indian languages)", "Voice", "Email", "Knowledge base", "Approval / HITL", "Tool (MCP → export ERP pipeline)"],
        outcome: "A week-by-week, cluster-by-cluster capacity picture built from confirmed availability and last season's actuals, with every open enquiry marked servable or not and the binding constraint named — before a single festival-season date is promised on a call.",
        impact: "The mechanism replaces feel with two grounded inputs and one owned formula: what each cluster says it can actually staff, what it genuinely produced last comparable season, netted against the local festival and harvest calendar and converted at the planner's own per-day rates — so the manager promises dates against arithmetic and knows in advance which enquiry is the one that breaks the plan.",
        build: {
          agents: 3,
          agentsNote: "1 economy-tier reply interpreter (per cluster reply, inside the Loop) + 1 capacity-model agent + 1 pipeline-fit agent. Voice transcription, the weekly-capacity formula and the plan approval are workflow nodes and tools.",
          pattern: "In-language capacity poll (Loop) → tenant-authored weekly capacity formula → capacity model → pipeline fit → human approves the plan and the dates",
          prompt:
            "(Capacity model) Build a week-by-week capacity view for the season window {{window}} per cluster and craft. Inputs: each cluster lead's confirmed available artisan count and stated absences, last comparable season's ACTUAL output from Knowledge, and the local festival/harvest calendar. Use the weekly-capacity tool for every indicative figure. Return strict JSON {weeks[{week, cluster_id, craft, available_days, indicative_units, basis}]}. Never use nominal or theoretical capacity, and never take a cluster's optimistic self-estimate alone as the basis. Net out every calendar absence. Where last season has no comparable actual, set basis='no_baseline' and leave indicative_units null rather than guessing.",
          agentRoles: [
            { role: "Cluster-reply interpreter (per reply)", model: "Economy tier — one call per cluster reply, including transcribed voice notes", prompt: "One cluster lead's in-language reply → {available_artisans, absence_periods[], crafts_available[], unclear[]}. One short in-language follow-up on anything unclear; never convert a vague answer into a firm number." },
            { role: "Capacity-model agent", model: "Standard tier — runs once per planning cycle", prompt: "Confirmed availability + last season's actuals + festival/harvest calendar → week-by-week indicative capacity per cluster and craft, every figure from the weekly-capacity tool, with the basis stated per row. Null where there is no baseline; every calendar absence netted out." },
            { role: "Pipeline-fit agent", model: "Premium tier — the hard step, and its output is what dates get promised from. Economy fallback model configured, with any degraded run flagged in the plan header", prompt: "Lay the open enquiry and order pipeline over the capacity view. Mark each enquiry SERVABLE | SERVABLE_LATER (with the earliest defensible date and its basis) | NOT_SERVABLE, and name the binding constraint for every non-clean case — cluster, craft, week or material. Flag every enquiry that depends on a null/no-baseline capacity row. These are proposals for the manager, never commitments." },
          ],
          mcp: [
            "Export ERP / CRM pipeline — via Tool (MCP): read the open enquiries and confirmed orders for the season window, with their requested ship dates and crafts",
            "Export ERP — via Tool (MCP): read last season's actual dispatch records per cluster and craft, so the baseline is measured rather than remembered",
            "Export ERP — via Tool (MCP): attach the approved season plan to the season record, after the manager signs off",
            "WhatsApp + Sarvam — cluster capacity polling in Indian languages, voice notes accepted",
            "Email — publication of the approved position to the sales/export desk",
          ],
          tools: [
            "knowledge_search (season-scoped collection: last comparable season's actual output, the local festival and harvest calendar, cluster working-day patterns)",
            "http_request (read the enquiry/order pipeline and last season's dispatches; attach the approved plan to the season record)",
            "user-defined tool `week_capacity` (SpEL): input.availableArtisans * input.availableDays * input.perArtisanDayUnits * (input.isFestivalWeek ? input.festivalFactor : 1) — the planner owns perArtisanDayUnits per craft and the festival factor, and edits them as the season teaches them something. Keeping the rates in one auditable expression means the plan, the quote and the cluster's own indicative load are all computed from the same numbers. Pure arithmetic over the row handed to it: no ERP access, no iteration over weeks — the workflow calls it per week per cluster.",
          ],
          guardrails: [
            "No buyer-facing festival-season date leaves the workflow — every date in the plan is a proposal until the export manager approves it at the Approval node",
            "Capacity is derived from last comparable season's ACTUAL output through the `week_capacity` formula, never from nominal capacity or a cluster's optimistic self-estimate alone",
            "Where no comparable baseline exists, capacity is null with basis='no_baseline' and every enquiry depending on it is flagged — the plan never fills a gap with a guess",
            "Festival and harvest absences are netted out of available days before any indicative number is produced",
            "Every non-servable or later-date verdict names its binding constraint, so the manager can act on the constraint rather than argue with a conclusion",
            "Clusters receive an indicative load, explicitly labelled as such, in their own language — an indicative plan is never sent as a confirmed work order",
          ],
          cost:
            "A planning cycle costs: one economy-tier interpretation per cluster reply + one standard-tier capacity model + one premium-tier pipeline fit. The premium tier is justified exactly once, on the step whose output becomes promised dates; polling and modelling stay cheap, and the weekly-capacity arithmetic is a free SpEL evaluation. Because the cycle runs a few times a season rather than per order, total spend is small — what is being protected is the season's credibility with buyers, not a per-transaction margin.",
          output:
            "Every cluster lead polled in-language with voice replies accepted → a week-by-week capacity view computed by the planner's own formula from last season's actuals and netted of the festival/harvest calendar → each open enquiry marked servable, servable-later or not servable with its binding constraint named → the export manager approves the plan and any buyer-facing dates at the Approval node → clusters receive their indicative weekly load in-language and the sales desk receives the approved position, with the plan attached to the season record in the ERP.",
        },
      },
    ],
  },
];
