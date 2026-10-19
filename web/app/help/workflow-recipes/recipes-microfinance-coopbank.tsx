import type { Industry } from "./recipes-data";
import { HandCoins, Banknote } from "lucide-react";

// ---------------------------------------------------------------------------
// Microfinance / SHG-JLG group lending & Cooperative-bank / credit-society
// workflow recipes — same "inline copy, no i18n keys" precedent and honesty
// rules as recipes-data.tsx. Every recipe is expressed ONLY in VegaDūta's real
// building blocks (Trigger / Agent / Knowledge / Condition / Approval / Output
// / Tool(MCP) / Voice / Device / Loop / Code / Parallel / Supervisor /
// Sub-workflow), so each maps 1:1 to something you can actually build in the
// Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — the core banking system, the LOS/LMS
// (loan origination / loan management), credit bureaus, KYC/eKYC providers,
// NACH / e-mandate rails and regulatory-reporting systems — are reached the
// real way, via an inbound Webhook trigger and/or a Tool (MCP) node, and are
// LABELLED as such, naming what the call actually fetches or writes.
//
// User-defined tools, described honestly: a tenant-authored tool in VegaDūta
// is a SpEL expression evaluated against the tool's JSON input. It is
// sandboxed by construction — no method calls, no constructors, no bean or
// type references, and the grammar has no loop — so it is a data-transform /
// formula tool, NOT a scripting engine and NOT a way to call an API. Used
// below exactly where that fits: pulling a field out, formatting a line,
// simple arithmetic, and ternary/elvis branching. It lets a society encode ITS
// OWN arrears buckets, SLA labels, receipt formats and authority matrix
// without a developer or a redeploy — and it may only CLASSIFY or FORMAT, it
// never decides credit.
//
// Regulated-lending rule, on top of the usual approval posture and stated in
// the guardrails of EVERY recipe below: an agent NEVER makes a credit
// decision, NEVER approves or rejects a loan, NEVER sets or quotes an interest
// rate, and NEVER moves money. It prepares, summarises, checks completeness,
// verifies and routes — a named human decides at an Approval node.
//
// Collections posture, also non-negotiable and in every guardrail list that
// touches a borrower: respectful contact only, no threats or harassment, hard
// contact-hour windows, per-borrower frequency caps, and opt-out honoured
// immediately. This is a real regulatory requirement, not a nicety.
//
// This sector's borrowers are largely rural and often low-literacy, so the
// channel mix leans honestly on WhatsApp voice notes, plain Voice/Call and
// Sarvam for Indian-language understanding and speech — not on a web portal.
// Cost routing is explicit per agent (economy on the volume step, standard
// only on the one hard step) with a fallback model configured for provider
// outages. Impact lines describe the mechanism (why it helps), never invented
// ROI numbers, and no agent count is inflated.
// ---------------------------------------------------------------------------

export const INDUSTRIES_MFICOOP: Industry[] = [
  // ================================== MICROFINANCE & SHG/JLG GROUP LENDING
  {
    id: "microfinance",
    name: "Microfinance & SHG group lending",
    icon: HandCoins,
    tagline:
      "Run centre meetings, collection reconciliation and repayment-stress early warning in the borrower's own language — with every credit decision, waiver and posting left to a human.",
    recipes: [
      {
        id: "mfi-centre-meeting",
        title: "Centre / SHG meeting reminders and attendance capture, in local language",
        scenario:
          "Today: the field officer sits down at 8pm the night before with the branch register and her own phone, and calls or WhatsApps eight group leaders one at a time — some pick up, some don't, and she has no record of which. At the meeting she ticks names on a printed attendance sheet on a clipboard, folds it into her bag, and rides to the next centre. That evening (or Friday, if the week is bad) a branch assistant types twelve of those sheets into an Excel file, guessing at two names that smudged in the rain. Nobody notices that the Tuesday centre has not actually met for three weeks until the month-end MIS is pulled.",
        triggerLabel: "Webhook (meeting calendar from the LMS) / scheduled run",
        steps: [
          { kind: "trigger", title: "Tomorrow's centre meetings are listed", detail: "A scheduled Webhook trigger brings in the next day's centre/SHG meeting schedule. The LMS has no first-party connector — a Tool (MCP) node fetches the meeting calendar, the centre roster and each group's recorded language." },
          { kind: "loop", title: "Fan out per centre", detail: "A Loop node iterates each centre so one cheap agent pass drafts that centre's reminder — a 40-centre branch is handled exactly like a 4-centre one, and the officer's evening of phone calls disappears." },
          { kind: "agent", title: "Draft the reminder in the group's language", detail: "An economy-tier agent composes a short reminder — centre name, meeting place, time, what to bring (passbook, savings) — in the group's recorded language, grounded in the meeting SOP in a scoped Knowledge collection. It states facts only; it never mentions amounts due, penalties or any consequence of non-attendance." },
          { kind: "output", title: "Send on WhatsApp + voice note", detail: "An Output node sends the text to the group leader on WhatsApp and, for low-literacy groups, an ElevenLabs/Sarvam voice note in the local language on the same thread — inside the permitted contact-hour window only, and logged per centre so 'did she get the reminder?' is answerable." },
          { kind: "voice", title: "Capture attendance by voice at the centre", detail: "A Voice node lets the field officer dictate attendance and meeting notes in her own language, standing at the centre; Sarvam transcribes and the agent maps spoken names to member ids, marking anything uncertain rather than guessing. The clipboard sheet stops being the system of record." },
          { kind: "code", title: "Band the centre's attendance", detail: "A Code node calls the society's own user-defined SpEL tool to label the meeting HEALTHY / WATCH / CRITICAL from the roster and present counts — deterministic arithmetic, no model involved, and the thresholds are the branch's own." },
          { kind: "approval", title: "Officer confirms the register", detail: "An Approval node shows the mapped attendance register and its band back to the field officer for confirmation — the attendance record is an official document, so a human signs it off before anything is written." },
          { kind: "output", title: "File the register + flag silent centres", detail: "On confirmation a Tool (MCP) node posts the register to the LMS, and centres that missed their meeting are listed for the branch manager the same day — not chased automatically, and not discovered at month-end." },
        ],
        connectors: ["Webhook", "WhatsApp", "Voice / Call", "ElevenLabs (voice)", "Sarvam (Indian languages)", "Knowledge base", "Approval / HITL", "Tool (MCP → LMS)"],
        outcome:
          "Every centre gets a same-language reminder the evening before without the officer making a single call, and the attendance register exists as structured, banded data before she gets home — instead of a clipboard sheet typed up on Friday.",
        impact:
          "The mechanism is timing and language, not persuasion: reminders reach the group leader on the channel they already use, in the language they actually speak, and attendance is captured by voice while the officer is still standing at the centre — so a centre that has stopped meeting surfaces within a day rather than in the month-end MIS.",
        build: {
          agents: 2,
          agentsNote: "1 reminder drafter (runs once per centre via the Loop node) + 1 attendance mapper (runs once per meeting transcript). Loop / Voice / Code / Approval are workflow nodes.",
          pattern: "Loop fan-out for reminders → voice capture → mapper → SpEL banding in a Code node → human confirms the register",
          prompt:
            "(Reminder drafter) Write ONE short meeting reminder for a microfinance centre/SHG group. You are given {centre_name, meeting_place, meeting_time, group_language, items_to_bring[]}. Write in group_language, at a primary-school reading level, under 40 words. State ONLY: which centre, where, when, what to bring. You must NOT mention any amount due, arrears, penalty, interest, or any consequence of not attending — this is a meeting reminder, not a collection message. Do not add greetings that name individual members. Return {text, tts_text} where tts_text is the same message written to be read aloud naturally.",
          agentRoles: [
            { role: "Reminder drafter (per centre)", model: "Economy tier, with a fallback model configured for provider outages — runs once per centre every meeting day, so this routing choice dominates total cost", prompt: "Compose one short, factual, local-language meeting reminder from the given fields. No amounts, no arrears, no consequences, no invented details. Return text + a read-aloud variant." },
            { role: "Attendance mapper (per meeting)", model: "Standard tier, fallback configured — matching real names out of noisy field-recorded speech is the one step that needs the reasoning", prompt: "From a Sarvam transcript of the officer's spoken attendance, map each spoken name to a member id in the supplied centre roster. Return {present[], absent[], unmatched[]}. If a spoken name is ambiguous or not clearly on the roster, put it in unmatched with the raw text — never guess a member id, and never mark someone present who was not clearly named." },
          ],
          mcp: [
            "LMS / loan-management system — via Tool (MCP): fetch tomorrow's centre meeting calendar, the centre roster and each group's recorded language",
            "LMS / loan-management system — via Tool (MCP): post the officer-confirmed attendance register, keyed to (centre id, meeting date)",
            "WhatsApp — deliver the reminder text and the voice note to the group leader's existing thread",
            "Sarvam — transcribe the officer's spoken attendance and render the reminder in the group's language",
          ],
          tools: [
            "knowledge_search (centre-meeting SOP, group roster and recorded language — scoped Knowledge collection)",
            "http_request (read the meeting schedule; post the confirmed register)",
            "user-defined tool `centre_attendance_band` (SpEL): input.rosterSize == 0 ? 'NO_ROSTER' : (input.presentCount * 100 / input.rosterSize >= 80 ? 'HEALTHY' : (input.presentCount * 100 / input.rosterSize >= 50 ? 'WATCH' : 'CRITICAL')) — a tenant-authored formula tool, not a script: it does arithmetic on the tool input and returns a label. The branch encodes ITS OWN attendance thresholds (a 12-member SHG and a 30-member JLG centre are judged differently) without a developer or a redeploy, and because it is deterministic the same counts always produce the same band. It only labels a meeting — it never touches a member's loan.",
          ],
          guardrails: [
            "No credit decision, ever: this workflow schedules and records attendance only — it never approves, rejects, re-prices or disburses anything, and any credit judgement belongs to a human at an Approval node",
            "Reminders are meeting notices, not collection messages — the drafter is forbidden from mentioning dues, arrears, penalties or consequences, and outbound sends respect the permitted contact-hour window, a per-group frequency cap and any opt-out already recorded",
            "The attendance register is written only after the field officer confirms it at the Approval node; unmatched names stay unmatched rather than being guessed onto a member id",
            "The `centre_attendance_band` SpEL tool classifies a meeting only — a CRITICAL band is a prompt for a human to look, never an action against a group or a member",
            "The register post is idempotent per (centre id, meeting date) so a re-run never duplicates or overwrites a confirmed register",
          ],
          cost:
            "Cost is N centres × (economy-tier drafter, hard token cap) + one standard-tier mapper per meeting actually held. The drafter is the high-volume step and must stay on the economy tier — a 40-word factual reminder from structured fields needs no reasoning budget — and a fallback model is configured so a provider outage degrades the tier rather than stopping tomorrow's reminders. The mapper is the genuinely hard step (noisy speech, real names) and is the only place the standard tier is justified. The SpEL banding costs nothing at all: it is arithmetic in a Code node, not a model call. Run the reminder loop in Parallel batches for wall-clock speed without changing per-centre cost.",
          output:
            "Loop drafts one cheap local-language reminder per centre → an Output node delivers it on WhatsApp as text plus an ElevenLabs/Sarvam voice note inside the contact-hour window → after the meeting a Voice node captures spoken attendance → the mapper matches names to member ids → a Code node applies the society's `centre_attendance_band` SpEL tool → the field officer confirms at the Approval node → a Tool (MCP) node files the register in the LMS and lists silent centres for the branch manager the same day.",
        },
      },
      {
        id: "mfi-collection-recon",
        title: "Centre-collection reconciliation — variances prepared, never posted",
        scenario:
          "Today: field officers come back to the branch between 5 and 7pm, each with a cash bag and a carbon-copy collection sheet. The cashier counts the cash, the operations assistant sits with three things open — the paper sheets, the LMS demand report printed that morning, and a calculator — and ticks lines until the totals agree. When ₹40 refuses to reconcile at 8:30pm, it gets 'adjusted' with a note in the margin, and the officer is told to check with the member tomorrow (which nobody tracks). The margin notes surface a year later in an internal audit as a finding.",
        triggerLabel: "Webhook (collection sheet + LMS demand file, end of collection day)",
        steps: [
          { kind: "trigger", title: "Day's collections close", detail: "A scheduled Webhook trigger fires when the day's centre collection sheets and the LMS demand/receipt file are available. Neither the LMS nor the core banking system has a first-party connector — both are read via Tool (MCP) nodes." },
          { kind: "code", title: "Deterministic match first", detail: "A Code node does the arithmetic — per-centre and per-member match of demand vs collected vs cash-remitted — in plain code, replacing the calculator and the ticking. Money must never be totalled by a language model; the agent only explains what the code found." },
          { kind: "code", title: "Band the variance with the branch's own rule", detail: "The same Code node applies the society's user-defined SpEL tool to label each unmatched line MATCHED / MINOR / ESCALATE against ITS OWN tolerance — the tolerance that used to live in the operations assistant's head is now an editable, auditable expression." },
          { kind: "loop", title: "Explain each unmatched line", detail: "A Loop node runs a cheap agent over only the lines the Code node could not match — matched lines never touch a model at all." },
          { kind: "agent", title: "Classify the variance", detail: "The agent returns strict JSON per unmatched line — likely cause (SHORT_COLLECTION / ADVANCE_PAID / WRONG_MEMBER_CREDITED / SHEET_ENTRY_ERROR / CASH_IN_TRANSIT / UNKNOWN), the evidence it used and what a human should check. It proposes; it never adjusts. This is the margin note, made structured and assigned an owner." },
          { kind: "agent", title: "Roll up the day", detail: "A reducer agent produces the branch's evening reconciliation summary — total matched, total in variance, the centres driving it, and the check-list for tomorrow's field visit." },
          { kind: "approval", title: "Branch manager approves every adjustment", detail: "An Approval node holds each proposed adjustment, waiver or re-credit individually for the branch manager — nothing touches a member's ledger, and no money moves, without an explicit human decision that is recorded against their name." },
          { kind: "output", title: "Post approved entries + file the summary", detail: "A Tool (MCP) node posts only the approved entries to the LMS/core banking system and send_email files the signed reconciliation summary to the branch manager and internal audit; everything else stays a tracked open item instead of a margin note." },
        ],
        connectors: ["Webhook", "Knowledge base", "Approval / HITL", "Tool (MCP → LMS / core banking)", "Email", "WhatsApp"],
        outcome:
          "Every centre reconciles the same evening without a calculator and three printouts, each unmatched rupee has a named likely cause and an owner, and no ledger entry exists without a branch manager's recorded approval.",
        impact:
          "The mechanism is separation of duties made cheap: deterministic code does the arithmetic the assistant used to do by hand, a tenant-authored tolerance rule bands the exceptions, the model only explains them, and the human decision moves from 'reconcile forty centres' to 'approve the handful of variances' — which is what makes same-day reconciliation survivable and leaves an audit trail where the margin note used to be.",
        build: {
          agents: 2,
          agentsNote: "1 variance explainer (runs once per unmatched line via the Loop node) + 1 reducer (runs once per branch-day). The matching arithmetic and the tolerance banding are Code nodes, not agents.",
          pattern: "Deterministic Code match + SpEL banding → map cheap agent over exceptions only → reduce → human approves each adjustment",
          prompt:
            "(Variance explainer) Explain ONE unmatched collection line. You are given {centre_id, member_id, demand_amount, collected_amount, remitted_amount, variance_band, sheet_notes, recent_ledger_lines[]}. Return strict JSON {likely_cause (SHORT_COLLECTION|ADVANCE_PAID|WRONG_MEMBER_CREDITED|SHEET_ENTRY_ERROR|CASH_IN_TRANSIT|UNKNOWN), evidence, suggested_check, confidence (LOW|MED|HIGH)}. Do NOT compute or restate totals — the amounts given to you are authoritative and were computed in code. Do NOT propose a waiver, a write-off, an interest change or a ledger entry: your output is a hypothesis for a human to check. If the evidence does not support a cause, return UNKNOWN with confidence LOW rather than a plausible guess.",
          agentRoles: [
            { role: "Variance explainer (per unmatched line)", model: "Economy tier with a configured fallback model — runs once per exception, the volume step and therefore the cost lever", prompt: "Classify one unmatched line into schema-valid JSON with a likely cause, the evidence and a suggested human check. Never recompute money. Never propose a waiver or ledger entry. UNKNOWN beats a plausible guess." },
            { role: "Reducer (once per branch-day)", model: "Standard tier, fallback configured — one call a day, where synthesis and prioritisation actually earn the spend", prompt: "Aggregate the classified variances into an evening reconciliation summary: totals as supplied by the Code node, the centres driving the variance, the items needing a field check tomorrow, and anything repeating across days. Use only the supplied figures; never estimate or extrapolate." },
          ],
          mcp: [
            "LMS / loan-management system — via Tool (MCP): fetch the day's demand and receipt file per centre",
            "Core banking system — via Tool (MCP): fetch the branch cash-remittance entries for the collection date",
            "LMS / core banking — via Tool (MCP): post ONLY the branch-manager-approved adjustment entries, keyed per line id",
            "Email — deliver the signed reconciliation summary to the branch manager and internal audit",
          ],
          tools: [
            "knowledge_search (reconciliation SOP, variance-tolerance and escalation rules — scoped Knowledge collection)",
            "http_request (pull demand/receipt/remittance files; post approved entries)",
            "send_email (file the signed evening reconciliation summary for audit)",
            "user-defined tool `variance_band` (SpEL): (input.variance < 0 ? -input.variance : input.variance) == 0 ? 'MATCHED' : ((input.variance < 0 ? -input.variance : input.variance) > input.escalateAbove ? 'ESCALATE' : 'MINOR') — a tenant-authored formula tool: it takes the absolute variance from the tool input and compares it to the branch's own threshold, also passed in. No API call, no loop, no code execution — just arithmetic and a ternary. The society changes its own tolerance in the tool definition rather than filing a change request, and because the same expression runs on every line the tolerance is applied identically at every branch. It labels a line; a human still approves any adjustment.",
          ],
          guardrails: [
            "No credit decision and no money movement: the agents classify and summarise only — every adjustment, waiver, re-credit or write-off is approved individually by the branch manager at the Approval node before any ledger or banking system is touched",
            "All arithmetic is done in a Code node (plus the deterministic `variance_band` SpEL tool); the model is explicitly forbidden from computing or restating amounts, so no total in this workflow was ever produced by a language model",
            "The SpEL tool only bands a variance — an ESCALATE label routes a line to a human faster, it never authorises an adjustment, a waiver or a posting",
            "Schema validation on every explainer output — a malformed or low-confidence line is escalated as an open item, never auto-classified into an adjustment",
            "Posting is idempotent per (branch, collection date, line id) so a re-run cannot double-post an approved entry, and the full proposal-to-approval trail is retained for audit",
            "Nothing in this workflow contacts a borrower — variance follow-up is a field-officer task; any borrower contact happens through the early-warning recipe under its contact-hour, frequency and opt-out rules",
          ],
          cost:
            "Only unmatched lines reach a model, so the bill scales with the exception rate, not with book size: matched lines cost zero tokens, and the SpEL banding costs nothing because it is an expression, not a call. The explainer runs at exception volume and must be economy tier with a hard cap and a fallback model; exactly one standard-tier reduce runs per branch-day and is where the reasoning budget goes. If exception volume spikes, batch the explainer through a Parallel node — same per-line cost, better wall-clock.",
          output:
            "A Code node matches demand vs collected vs remitted deterministically and applies the branch's `variance_band` SpEL tool → the Loop maps a cheap explainer over unmatched lines only → the reducer produces one branch-day reconciliation summary → the branch manager approves each proposed adjustment individually at the Approval node → a Tool (MCP) node posts only approved entries to the LMS/core banking system and send_email files the summary for audit, leaving everything else as a tracked open item.",
        },
      },
      {
        id: "mfi-doc-precheck",
        title: "Loan-file document completeness pre-check before the credit officer's visit",
        scenario:
          "Today: the group leader collects photocopies from ten members into a plastic folder over a week — Aadhaar, ration card, the group resolution, savings passbooks. The credit officer rides 40km on a two-wheeler for the appraisal visit, opens the folder in someone's courtyard, and finds one photocopy is a black rectangle, one member's address proof is her husband's, and the resolution page is unsigned. She notes it in a diary, rides back, and the group is told to try again next fortnight. Two of the ten members have found money elsewhere by then.",
        triggerLabel: "WhatsApp / Webhook (documents submitted for a group loan application)",
        steps: [
          { kind: "trigger", title: "Documents come in", detail: "A WhatsApp trigger catches photos of documents sent by the group leader or field officer — the same phone camera that already takes them — and a Webhook covers files pushed from the LOS. The LOS and eKYC provider have no first-party connector; they are reached via Webhook in and Tool (MCP) out." },
          { kind: "knowledge", title: "Load the checklist for this product", detail: "A Knowledge node scoped to the lender's product policy supplies the exact document checklist for this loan product and group type — per member and per group — so completeness is judged against documented policy, not the model's memory or the officer's recall." },
          { kind: "loop", title: "Check each submitted document", detail: "A Loop node runs a cheap vision-capable agent once per photo: is it the right document type, is it legible, is it signed/dated where required, does the name on it match the member record? The black-rectangle photocopy is caught the day it is sent, not in the courtyard." },
          { kind: "agent", title: "Assemble the gap list", detail: "A second agent compares what was received against the checklist and produces a per-member gap list — missing, illegible, unsigned, name-mismatch — with a plain-language ask for each gap." },
          { kind: "code", title: "Label the file's readiness", detail: "A Code node applies the lender's user-defined SpEL tool to turn the gap counts into a single readiness label the branch can sort on — the lender's own definition of 'ready enough to travel for', editable without a deployment." },
          { kind: "condition", title: "File complete?", detail: "A Condition node branches: a complete file goes forward for the officer's visit; an incomplete one goes back for exactly the missing items — one clear request, not the fishing expedition a diary note turns into." },
          { kind: "output", title: "Ask for the gaps in local language", detail: "An Output node sends the specific outstanding items to the group leader on WhatsApp, with a Sarvam/ElevenLabs voice note version so a low-literacy leader can act on it without finding someone to read it to her." },
          { kind: "approval", title: "Officer confirms the file is visit-ready", detail: "An Approval node puts the completeness verdict in front of the credit officer — the agent asserts only that documents are present and legible; whether the file is fit to appraise, and every credit judgement that follows, is the human's call." },
        ],
        connectors: ["WhatsApp", "Webhook", "Knowledge base", "Sarvam (Indian languages)", "ElevenLabs (voice)", "Approval / HITL", "Tool (MCP → LOS / eKYC)"],
        outcome:
          "The officer travels only to files that are actually complete, and the group gets one precise, understandable request for what is missing instead of a fortnight's delay and a second trip.",
        impact:
          "The mechanism is moving the completeness check off the field visit and into the week before it: presence, legibility and signature are objective, checkable facts a model can verify against a documented checklist — so the scarce, expensive thing (a 40km ride and the officer's credit judgement) is spent only where it can succeed.",
        build: {
          agents: 2,
          agentsNote: "1 per-document checker (runs once per submitted photo via the Loop node) + 1 gap-list assembler (runs once per application). The checklist lives in a scoped Knowledge collection; the readiness label, the branch and the sign-off are workflow nodes.",
          pattern: "Loop map over documents → assemble gaps → SpEL readiness label → clarify once → human confirms visit-readiness",
          prompt:
            "(Document checker) Inspect ONE submitted document image for a group-loan application. You are given the expected checklist entry {doc_type, must_be_signed, must_show_name, member_name} and the image. Return strict JSON {matches_expected_type (bool), legible (bool), signed_where_required (bool|null), name_on_document, name_matches (bool|null), issues[]}. Judge ONLY what is visible. Never transcribe or store a full identity number — report the last 4 characters at most. If the image is unclear, set legible=false with the reason; do not infer content you cannot read. You are checking that a document is present and readable — you are NOT assessing creditworthiness, income, or whether the loan should be granted.",
          agentRoles: [
            { role: "Document checker (per document)", model: "Economy tier with vision, fallback model configured — runs once per photo across every application, so it is the dominant cost line", prompt: "Verify one document image against its expected checklist entry: right type, legible, signed where required, name match. Strict JSON, visible evidence only, never a full identity number, no credit assessment." },
            { role: "Gap-list assembler (per application)", model: "Standard tier, fallback configured — reconciling thirty per-photo results against a product checklist is the one step worth the reasoning", prompt: "Compare the per-document results against the product's documented checklist for this group and produce a per-member gap list {member, missing[], illegible[], unsigned[], name_mismatch[]} plus one plain-language request per gap, written for a low-literacy reader. State completeness as a fact about documents only — never as a recommendation to lend." },
          ],
          mcp: [
            "LOS / loan-origination system — via Tool (MCP): fetch the application, its member list and the product code",
            "Core banking / member master — via Tool (MCP): fetch each member's recorded name and address for the name-match check",
            "KYC / eKYC provider — via Tool (MCP): check whether a submitted identity document already has a valid verification on record",
            "LOS — via Tool (MCP): attach the completeness report and the officer's visit-ready confirmation to the application",
            "WhatsApp — receive document photos and send the single consolidated gap request",
            "Sarvam — phrase the gap request in the group's language and voice it",
          ],
          tools: [
            "knowledge_search (per-product document checklist, group-formation policy — scoped Knowledge collection)",
            "http_request (read application/member records; attach the completeness report to the file)",
            "user-defined tool `file_readiness_label` (SpEL): input.missingCount + input.illegibleCount == 0 ? 'VISIT_READY' : (input.missingCount + input.illegibleCount <= input.oneMoreRoundThreshold ? 'ONE_MORE_ROUND' : 'NOT_READY') — a tenant-authored formula tool: field arithmetic plus a ternary over the tool input, nothing more. The lender sets its own threshold for what is worth travelling to, and branches sort their pipeline on a label everyone defines the same way. It describes a folder's paperwork state — it says nothing about the member and decides nothing about the loan.",
          ],
          guardrails: [
            "No credit decision, ever: this workflow verifies that documents are present, legible and signed — it never scores, rates, approves, rejects, prices or recommends a loan, and the appraisal decision is the credit officer's alone at the Approval node",
            "The `file_readiness_label` SpEL tool classifies paperwork completeness only — VISIT_READY means the folder is complete, never that the group is creditworthy",
            "Identity data minimisation: the checker reports at most the last 4 characters of any identity number, never a full number, and document images are handled under the tenant's retention policy",
            "Gaps are requested exactly once per round through a single consolidated message — no repeat chasing, contact-hour window respected, opt-out honoured, and the group leader can always ask for a human instead",
            "Completeness is asserted only against the checklist in Knowledge — the model may not invent a required document or waive one, and an unclear image is reported as unclear rather than inferred",
          ],
          cost:
            "The per-document checker is the volume step (a ten-member group file is easily thirty photos), so it stays on an economy vision tier with a hard cap, a downscaled image and a fallback model — this single routing choice sets the bill. Exactly one standard-tier assembler runs per application, where reconciling many results against the product checklist actually needs reasoning. Oversized and duplicate images are rejected in a Code node before they reach a model, and the readiness label is free SpEL arithmetic.",
          output:
            "Documents arrive on WhatsApp or via the LOS Webhook → the Loop checks each photo cheaply against the scoped Knowledge checklist → the assembler builds a per-member gap list with plain-language asks → a Code node applies the lender's `file_readiness_label` SpEL tool → the Condition node either sends one consolidated local-language request (text plus voice note) or passes the file forward → the credit officer confirms visit-readiness at the Approval node, and a Tool (MCP) node attaches the completeness report to the application in the LOS.",
        },
      },
      {
        id: "mfi-early-warning",
        title: "Early-warning on repayment stress → respectful, rule-bound outreach",
        scenario:
          "Today: stress is invisible until the arrears report prints on the 1st. Then the branch manager takes the list home, and between 9 and 10pm rings members off his personal phone — because that is when they are home — and the tone hardens with each call. Two officers also call the same member the same evening because nobody logged the first call. A group leader is asked to 'talk to' a defaulting member. There is no record of who was contacted, when, or what was said, and the one member who genuinely wanted to pay ₹300 that week had no way to do it without travelling to the centre.",
        triggerLabel: "Webhook (daily LMS repayment + attendance feed)",
        steps: [
          { kind: "trigger", title: "Daily repayment feed lands", detail: "A scheduled Webhook trigger brings the day's repayment, attendance and centre-efficiency data from the LMS via a Tool (MCP) node — no first-party connector exists for it. Stress is looked at daily, not on the 1st." },
          { kind: "code", title: "Compute the stress signals + bucket in code", detail: "A Code node computes the objective signals — days past due, consecutive missed meetings, trend in centre collection efficiency — deterministically, then applies the lender's user-defined SpEL `arrears_bucket` tool. Thresholds come from the lender's own policy expression, never from a model." },
          { kind: "condition", title: "Contact eligibility gate", detail: "A Condition node filters BEFORE any drafting: is this member inside the permitted contact-hour window, under the per-borrower frequency cap, not opted out, and not in a legal or dispute hold? Anything failing the gate is excluded structurally — this is the node that makes 'two officers ringing at 9pm' impossible, and the model never gets a say in who may be contacted." },
          { kind: "loop", title: "Draft one message per eligible member", detail: "A Loop node runs a cheap agent per eligible member to draft a short, factual, local-language message — the amount and date as supplied, the ways to pay, and an offer to speak to the field officer by name." },
          { kind: "approval", title: "Field supervisor reviews the outreach batch", detail: "An Approval node puts the whole drafted batch in front of the field supervisor before a single message sends — tone, targeting and volume become one person's recorded responsibility instead of nobody's." },
          { kind: "output", title: "Send on WhatsApp / voice, in language", detail: "Approved messages go out on WhatsApp with an ElevenLabs/Sarvam voice note for low-literacy members, and every send is logged with channel, timestamp and approver. A Razorpay UPI payment link is included so the member who wanted to pay ₹300 can, without a trip to the centre." },
          { kind: "supervisor", title: "Escalate the pattern, not the person", detail: "A Supervisor node rolls the week's signals into a portfolio-stress briefing for the branch manager — which centres are drifting and why — as an early-warning input to a human review, never as an automatic action on any borrower." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS / Twilio", "Voice / Call", "ElevenLabs (voice)", "Sarvam (Indian languages)", "Razorpay (UPI)", "Approval / HITL", "Tool (MCP → LMS)"],
        outcome:
          "Stress signals surface days earlier as a reviewed, supervisor-approved outreach batch in the borrower's language, every contact is inside the rules and logged, and the first touch carries a way to pay rather than a 9pm call from a personal phone.",
        impact:
          "The mechanism is earlier, calmer, rule-bound contact: deterministic code and the lender's own bucket rule find the signal, a hard eligibility gate decides who may be contacted at all, and a supervisor approves the batch — so the first touch is a factual reminder with a UPI link, at a permitted hour, once, with a record of it.",
        build: {
          agents: 2,
          agentsNote: "1 message drafter (runs once per eligible member via the Loop node) + 1 portfolio-briefing agent behind the Supervisor node (weekly). Signal computation and bucketing are a Code node; eligibility is a Condition node.",
          pattern: "Code signals + SpEL bucket → hard eligibility gate → cheap per-member drafting → supervisor approves the batch → send + log",
          prompt:
            "(Message drafter) Write ONE short repayment reminder for a microfinance borrower. You are given {member_name, group_name, amount_due, due_date, arrears_bucket, language, officer_name, pay_link}. Write in the member's language, under 45 words, at a primary-school reading level. Include: what is due and when (exactly as supplied — never recompute), that they can pay by the link or at the centre meeting, and that they can call {officer_name} if there is a difficulty. You must NOT: threaten, mention legal action, recovery agents, seizure, the group's liability, credit-bureau reporting, or any consequence; imply urgency beyond stating the date; contact anyone other than the borrower; or name any other member. Do not mention the arrears_bucket to the member — it is for routing only. Do not offer a waiver, a reschedule, a settlement or any change of terms — if the member raises difficulty, the reply is that the field officer will speak to them. Return {text, tts_text}.",
          agentRoles: [
            { role: "Message drafter (per eligible member)", model: "Economy tier with a configured fallback model — the high-volume step; a 45-word factual reminder from supplied fields needs no reasoning budget", prompt: "Draft one short, factual, local-language reminder from the supplied fields. Never recompute an amount. No threats, no consequences, no third parties, no terms changes, no bucket labels shown to the member. Text plus a read-aloud variant." },
            { role: "Portfolio-stress briefing (weekly, behind the Supervisor node)", model: "Standard tier, fallback configured — one call a week, trend synthesis for a human reviewer", prompt: "From the week's computed signals, brief the branch manager on which centres and groups are drifting, what the signals were, and what a human should look at. Report patterns only; never name an action to take against an individual borrower and never propose a credit or recovery decision." },
          ],
          mcp: [
            "LMS — via Tool (MCP): fetch the daily repayment, days-past-due, attendance and centre-collection-efficiency feed",
            "LMS — via Tool (MCP): write back a contact log entry per member (channel, timestamp, approver, message id)",
            "WhatsApp / SMS / Voice — deliver the approved reminder on the member's reachable channel",
            "Sarvam + ElevenLabs — render the reminder in the member's language and voice it for low-literacy members",
            "Razorpay UPI — generate a per-member payment link ONLY; the workflow never initiates a debit and never moves money",
          ],
          tools: [
            "knowledge_search (collections code of conduct, contact-hour and frequency policy, escalation matrix — scoped Knowledge collection)",
            "http_request (pull the daily feed; log every contact attempt)",
            "user-defined tool `arrears_bucket` (SpEL): input.daysPastDue > 90 ? 'NPA_REVIEW' : (input.daysPastDue > 30 ? 'BUCKET_2' : (input.daysPastDue > 0 ? 'BUCKET_1' : 'CURRENT')) — a tenant-authored formula tool: it reads one field off the tool input and returns a label through nested ternaries. No API access, no loops, no code execution. Every lender defines its buckets differently and regulators expect those definitions to be explicit and stable, so encoding them as an expression the lender edits itself — rather than a constant buried in a service — is the honest fit. It CLASSIFIES only: an NPA_REVIEW label puts a file in front of a human, it never marks an account, changes a provision or decides anything about the member.",
          ],
          guardrails: [
            "No credit or recovery decision, ever: the agents draft and summarise only — rescheduling, waivers, settlements, write-offs, bureau reporting, provisioning and any escalation are human decisions taken outside this workflow, and the outreach batch itself is approved by the field supervisor at the Approval node before anything sends",
            "The `arrears_bucket` SpEL tool labels a file for routing and nothing else — it never marks an account NPA, never triggers an action against a borrower, and its output is never shown to the member",
            "Collections conduct is enforced structurally, not by prompt alone: a Condition node blocks contact outside the permitted hour window, above the per-borrower frequency cap, for opted-out members, and for anyone under a legal or dispute hold — the model never decides who may be contacted",
            "The drafter is prohibited from threats, legal or seizure language, consequence framing, third-party or co-member contact, and any mention of another member's dues; every sent message is logged with its timestamp, channel and approver for audit",
            "Amounts and dates are passed through verbatim from the LMS feed — the model may not compute, round or restate a figure, and a Razorpay UPI link is an option offered to the borrower, never an auto-debit or any movement of money by the workflow",
            "Opt-out is honoured immediately and permanently for outreach, and every message states how to reach the named field officer for a human conversation",
          ],
          cost:
            "The drafter runs once per eligible member per cycle and is the whole cost story — keep it economy tier with a hard token cap and a fallback model, since the message is short, factual and template-shaped. Crucially, the eligibility Condition node and the free SpEL bucketing run BEFORE drafting, so ineligible and current members cost nothing at all. One standard-tier briefing per week behind the Supervisor node is the only premium spend; there is no per-borrower reasoning step anywhere in this recipe.",
          output:
            "A Code node computes stress signals deterministically and applies the lender's `arrears_bucket` SpEL tool → the eligibility gate removes anyone who may not be contacted → the Loop drafts one cheap local-language message per remaining member → the field supervisor approves the whole batch at the Approval node → an Output node sends WhatsApp text plus an ElevenLabs/Sarvam voice note with a Razorpay UPI link → every contact is logged back to the LMS, and a weekly Supervisor-node briefing gives the branch manager the portfolio pattern to act on.",
        },
      },
    ],
  },

  // ============================= COOPERATIVE BANKS & CREDIT SOCIETIES
  {
    id: "coop-bank",
    name: "Cooperative banks & credit societies",
    icon: Banknote,
    tagline:
      "Member grievances, KYC re-verification, AGM and dividend notices and loan-committee file prep — handled in members' own languages, with every decision left to the committee.",
    recipes: [
      {
        id: "coop-grievance",
        title: "Member grievance intake in local language, routed and tracked",
        scenario:
          "Today: a member in her seventies comes to the counter because her passbook shows no entry for a deposit her son made three weeks ago. The clerk writes four lines in a hardbound complaint register, promises 'we will check', and gets back to the queue. The grievance officer visits that branch on Thursdays. Nobody starts a clock, the member has no reference number, and when she comes back the following week a different clerk asks her to explain it all again. The statutory turnaround exists on paper in the redressal policy and nowhere in the process.",
        triggerLabel: "Voice / Call, WhatsApp, Email or Webhook (grievance raised)",
        steps: [
          { kind: "trigger", title: "A member raises a grievance", detail: "A Voice/Call, WhatsApp, Email or web-form Webhook trigger opens the case, whichever way the member chose to reach the society — including the counter clerk simply forwarding it from the branch WhatsApp number." },
          { kind: "voice", title: "Listen in the member's language", detail: "A Voice node with Sarvam captures a spoken complaint in the member's own language and transcribes it — the member in her seventies never has to write anything, use an app, or repeat herself to a second clerk." },
          { kind: "agent", title: "Structure and categorise", detail: "An economy-tier agent turns the complaint into a structured case — category (deposit/withdrawal, loan servicing, passbook/statement, staff conduct, charges, other), the account or membership number if given, what the member says happened, and what she wants. It never judges whether the complaint is valid." },
          { kind: "code", title: "Apply the society's own SLA label", detail: "A Code node runs the society's user-defined SpEL tool to attach the redressal-policy SLA label and turnaround days to the case — the clock that used to exist only in the policy document now exists on the record." },
          { kind: "condition", title: "Route by category and severity", detail: "A Condition node routes: staff-conduct and suspected-fraud cases go straight to the grievance officer with no automated drafting at all; routine service cases go to the branch queue — all with the turnaround clock already running." },
          { kind: "output", title: "Acknowledge immediately, in language", detail: "An Output node sends the member an acknowledgement with the case reference and the expected turnaround, on her own channel, as text plus an ElevenLabs/Sarvam voice note. She leaves the counter with a reference number instead of a promise." },
          { kind: "knowledge", title: "Draft a grounded response for the officer", detail: "A Knowledge node scoped to the society's bye-laws, schedule of charges and grievance-redressal policy grounds a suggested response — the agent drafts citing the clause, and never states an outcome that is not in the documented policy." },
          { kind: "approval", title: "Grievance officer decides and replies", detail: "An Approval node holds every response, remedy, charge reversal or compensation for the grievance officer — the resolution is a human decision; the agent only prepared the draft, the transaction evidence and the clause." },
        ],
        connectors: ["Voice / Call", "WhatsApp", "Email", "Webhook", "Sarvam (Indian languages)", "ElevenLabs (voice)", "Knowledge base", "Approval / HITL", "Tool (MCP → core banking)"],
        outcome:
          "Every grievance is logged the moment it is raised, acknowledged in the member's language with a reference number and a running clock, and lands on the grievance officer's desk already structured with the transaction evidence and the relevant bye-law clause attached.",
        impact:
          "The mechanism is capture and clock-start at the point of contact: a spoken complaint in any Indian language becomes a structured, timestamped case with a statutory acknowledgement out immediately — so nothing depends on a hardbound register being read on Thursday, and the officer's time goes to deciding rather than reconstructing what the member said.",
        build: {
          agents: 2,
          agentsNote: "1 intake structurer (runs once per grievance) + 1 response drafter (runs only for routine cases that reach drafting). SLA labelling, routing, acknowledgement and the officer's decision are workflow nodes.",
          pattern: "Multi-channel intake → structure → SpEL SLA label → route by severity → grounded draft → officer decides",
          prompt:
            "(Intake structurer) Turn ONE member grievance into a structured case. You are given a transcript or message and any member context. Return strict JSON {category (DEPOSIT_WITHDRAWAL|LOAN_SERVICING|PASSBOOK_STATEMENT|STAFF_CONDUCT|CHARGES|OTHER), account_or_member_ref, summary (the member's own account, neutrally restated), requested_remedy, severity (ROUTINE|SERIOUS|SUSPECTED_FRAUD), language}. Restate only what the member said — do NOT decide whether the complaint is justified, do NOT assign blame to any named staff member, and do NOT promise any remedy, reversal, refund or timeline beyond the society's published acknowledgement turnaround. If details are missing, list them in missing[]; never fill them in.",
          agentRoles: [
            { role: "Intake structurer (per grievance)", model: "Economy tier with a configured fallback model — every call, message and forwarded counter complaint passes through it, so it is the volume step", prompt: "Structure one grievance into strict JSON: category, reference, neutral summary, requested remedy, severity, language. No validity judgement, no blame, no promises, no invented details." },
            { role: "Response drafter (routine cases only)", model: "Standard tier, fallback configured — must reason over bye-laws and the charges schedule and cite the exact clause, which is the one hard step here", prompt: "Draft a response for the grievance officer, grounded in the bye-laws, schedule of charges and redressal policy in Knowledge, citing the specific clause. Present it explicitly as a draft for approval; never state an outcome the policy does not support, and never commit the society to a remedy." },
          ],
          mcp: [
            "Core banking system — via Tool (MCP): pull the member's transaction history for the disputed date range as evidence for the officer",
            "Core banking system — via Tool (MCP): fetch the charges actually levied on the account when the category is CHARGES",
            "Grievance register / case system — via Tool (MCP): create the case with its reference, and update status, SLA clock and final outcome",
            "Voice, WhatsApp, Email — member intake channels and the acknowledgement/outcome reply",
            "Sarvam + ElevenLabs — transcribe the spoken complaint and voice the acknowledgement back in the member's language",
          ],
          tools: [
            "knowledge_search (bye-laws, schedule of charges, grievance-redressal policy and turnaround norms — scoped Knowledge collection)",
            "http_request (pull the transaction evidence; create and update the case)",
            "send_email (send the officer-approved written response where the member's channel of record is email)",
            "user-defined tool `grievance_sla_label` (SpEL): input.severity == 'SUSPECTED_FRAUD' ? 'OFFICER_IMMEDIATE' : (input.category == 'STAFF_CONDUCT' ? 'OFFICER_1D' : (input.severity == 'SERIOUS' ? 'OFFICER_3D' : 'BRANCH_' + input.routineTurnaroundDays + 'D')) — a tenant-authored formula tool: ternary branching plus string concatenation over the tool input, nothing executable. Each society's redressal policy sets its own turnaround, and it changes when the policy changes; expressing it as an editable expression means the register's clock and the written policy stay the same document. It labels and routes a case — the remedy is always the grievance officer's.",
          ],
          guardrails: [
            "No decision of any kind by the agent: it structures, evidences and drafts — every resolution, charge reversal, compensation or credit-related outcome (including anything touching a member's loan) is decided by the grievance officer at the Approval node, and no agent may approve, reject or re-price anything",
            "The intake agent never judges whether a complaint is justified and never blames a named employee; staff-conduct and suspected-fraud cases bypass automated drafting entirely and go straight to the officer",
            "The `grievance_sla_label` SpEL tool sets a routing label and a clock from the society's published policy — it does not decide the outcome, and it cannot shorten or waive a statutory turnaround",
            "The only thing sent without human review is the acknowledgement itself — reference number, category and the published turnaround; it promises no outcome",
            "Every draft response cites the bye-law or charges-schedule clause it relies on; an outcome not supported by documented policy is escalated rather than drafted",
            "Member contact stays within the permitted hour window and honours opt-out for anything other than the statutory acknowledgement and the case outcome; account and transaction details are pulled only for the member's own case",
          ],
          cost:
            "Intake is the high-volume step and sits on the economy tier with a fallback model — structuring a transcript into a fixed schema is exactly the cheap-model case. The drafter is the hard step (bye-law reasoning, clause citation) and is the only standard-tier call, and it never runs for staff-conduct or fraud cases, which route straight to a human. The SLA labelling is free SpEL. On call intake, voice minutes rather than tokens dominate the bill — cap transcript length in a Code node before it reaches a model.",
          output:
            "A grievance arrives by Voice, WhatsApp, Email or web form → Sarvam transcribes it in the member's language → the cheap structurer produces a schema-valid case and severity → a Code node applies the society's `grievance_sla_label` SpEL tool → the Condition node routes serious and conduct cases straight to the grievance officer → an acknowledgement with case reference and turnaround goes out immediately as text and voice note → for routine cases the standard-tier drafter proposes a clause-cited response → the grievance officer decides and replies at the Approval node, and a Tool (MCP) node updates the case record.",
        },
      },
      {
        id: "coop-kyc-rechase",
        title: "Periodic KYC re-verification chasing, without the branch queue",
        scenario:
          "Today: the branch prints a list of 600 members due for periodic KYC re-verification and posts cyclostyled letters to addresses last updated when the account was opened. Most come back undelivered or unread. The clerk keeps a photocopy folder and a ruled register of who has submitted what, and ticks it by hand. Members find out there is a problem when a withdrawal is refused at the counter — then queue for an hour, having brought a ration card when what was needed was a current address proof, and are told to come again.",
        triggerLabel: "Webhook (KYC-due list from core banking, scheduled)",
        steps: [
          { kind: "trigger", title: "The KYC-due list arrives", detail: "A scheduled Webhook trigger pulls the members due for re-verification. The core banking system and the KYC/eKYC provider have no first-party connectors — both are reached via Tool (MCP) nodes, which also return each member's KYC category and language." },
          { kind: "code", title: "Band the due date with the branch's own rule", detail: "A Code node applies the society's user-defined SpEL tool to label each member OVERDUE / DUE_SOON / SCHEDULED, so the branch works the list in its own defined order rather than alphabetically off a printout." },
          { kind: "condition", title: "Eligibility and quiet-hours gate", detail: "A Condition node filters before any drafting: opted-out members, members already in the branch's manual queue, and anyone outside the permitted contact window are excluded — the model never decides who gets contacted." },
          { kind: "loop", title: "One personalised notice per member", detail: "A Loop node runs a cheap agent per member to draft a short local-language notice — what is due, exactly which documents are needed for their KYC category, and the two ways to submit them. The 'she brought the wrong paper' failure is designed out here." },
          { kind: "output", title: "Deliver on WhatsApp, SMS and voice", detail: "An Output node sends the notice on WhatsApp with an ElevenLabs/Sarvam voice note, falling back to SMS where WhatsApp is not reachable — no portal, no app install, no posted letter." },
          { kind: "loop", title: "Check what comes back", detail: "As members reply with photos, a Loop node runs a vision-capable economy-tier agent per document: right type, legible, unexpired, name matching the member record. The ruled register becomes structured data." },
          { kind: "condition", title: "Complete or still missing?", detail: "A Condition node either advances a complete submission or sends one precise follow-up for exactly the outstanding item — a maximum of the policy-defined number of reminders, then it becomes a named branch task, not an endless automated chase." },
          { kind: "approval", title: "Branch officer verifies and updates", detail: "An Approval node holds the KYC update for a branch officer — the agent only reports that documents look present, legible and unexpired; verifying identity and updating the member's KYC status is a regulated human act." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS / Twilio", "ElevenLabs (voice)", "Sarvam (Indian languages)", "Knowledge base", "Approval / HITL", "Tool (MCP → core banking / KYC provider)"],
        outcome:
          "Members are told what is due in a language and channel they actually use, can submit by phone photo instead of a branch trip, and the officer receives pre-checked submissions to verify rather than an hour-long counter queue of people holding the wrong document.",
        impact:
          "The mechanism is reach plus precision: the notice arrives on WhatsApp with a voice note rather than as a posted letter to a stale address, and it names exactly the documents that member's KYC category needs — so the two common failures (member never knew, member brought the wrong paper) are removed before anyone stands at a counter.",
        build: {
          agents: 2,
          agentsNote: "1 notice drafter (per member) + 1 document checker (per submitted image); both run inside Loop nodes. The due-date banding, the eligibility gate, the reminder cap and the officer's verification are workflow nodes.",
          pattern: "SpEL due-banding → eligibility gate → cheap per-member notice → cheap per-document check → capped follow-up → human verifies",
          prompt:
            "(Notice drafter) Write ONE short KYC re-verification notice for a cooperative-society member. You are given {member_name, language, kyc_category, required_documents[], due_by, due_band, branch_name, whatsapp_number}. Write in the member's language, under 50 words, at a primary-school reading level. Say: that periodic KYC re-verification is due by {due_by}; the exact documents required (list them, no more and no fewer than required_documents); and the two ways to submit — photograph them to this WhatsApp number, or bring them to {branch_name}. Do NOT state or imply any consequence such as account freezing, blocking or closure. Do NOT ask the member to send an identity number as text, a password, an OTP or any banking credential. Return {text, tts_text}.",
          agentRoles: [
            { role: "Notice drafter (per member)", model: "Economy tier with a configured fallback model — one short templated notice per due member across a whole book, the dominant cost line", prompt: "Draft one short local-language KYC notice from supplied fields: what is due, exactly which documents, two ways to submit. No consequences, no credential requests, no invented requirements." },
            { role: "Document checker (per image)", model: "Economy tier with vision, fallback configured — runs once per submitted photo; images downscaled before the call", prompt: "Check one submitted image against the expected document type: right type, legible, unexpired, name matching the member record. Strict JSON, visible evidence only, at most the last 4 characters of any identity number, no identity-verification verdict." },
          ],
          mcp: [
            "Core banking system — via Tool (MCP): fetch the periodic-KYC-due list with each member's KYC category, language and contact preferences",
            "Core banking system — via Tool (MCP): write the officer-approved KYC status update, keyed to (member id, KYC cycle)",
            "KYC / eKYC provider — via Tool (MCP): check an existing verification record before asking the member for a document they have already provided",
            "WhatsApp / SMS — deliver the notice and receive document photos in the same thread",
            "Sarvam + ElevenLabs — render the notice in the member's language and voice it",
          ],
          tools: [
            "knowledge_search (KYC policy by member category, document matrix, reminder-frequency policy — scoped Knowledge collection)",
            "http_request (pull the due list; attach submissions; write the approved status update)",
            "user-defined tool `kyc_due_band` (SpEL): input.daysUntilDue < 0 ? 'OVERDUE' : (input.daysUntilDue <= input.dueSoonDays ? 'DUE_SOON' : 'SCHEDULED') — a tenant-authored formula tool: one comparison and a ternary over the tool input, with the society's own DUE_SOON horizon passed in. It cannot call the KYC provider or run code; it turns a date difference into a label the branch sorts and reports on. The society tunes its own lead time (a cycle that clashes with harvest season gets a longer horizon) without a developer or a release.",
          ],
          guardrails: [
            "No decision by the agent: identity verification and the KYC status update are regulated human acts performed by the branch officer at the Approval node — the agent only reports that a document appears present, legible and unexpired, and it never approves, rejects, freezes or restricts an account, nor makes any credit judgement",
            "The `kyc_due_band` SpEL tool prioritises a worklist only — an OVERDUE label never restricts an account or triggers any action against the member",
            "The notice may never state or imply a consequence (freeze, block, closure) and may never ask for an identity number as text, an OTP, a password or any banking credential — credential-style requests are the exact shape of the fraud this sector suffers from",
            "Contact is capped: an eligibility gate excludes opted-out members and out-of-hours sends before drafting, and follow-ups stop at the policy-defined reminder count, after which the case becomes a named branch task rather than an automated chase",
            "Data minimisation: at most the last 4 characters of any identity number are extracted, images are retained under the tenant's policy, and member records are read only for the member being contacted",
            "The status write is idempotent per (member id, KYC cycle) and stamped with the approving officer, so a re-run cannot re-verify or duplicate a record",
          ],
          cost:
            "Both agents are economy tier by design with fallback models configured, because both steps are high volume and low judgement — a templated notice and a present/legible/unexpired image check. That keeps a whole-book KYC cycle predictable: cost is (eligible members × one short draft) + (submitted images × one capped vision call), with the banding free in SpEL. No standard-tier call exists in the happy path; escalations go to a human, not to a bigger model.",
          output:
            "The due list arrives via Webhook → a Code node applies the society's `kyc_due_band` SpEL tool → the eligibility gate removes opted-out and out-of-window members → the Loop drafts one cheap local-language notice each → an Output node delivers it on WhatsApp with a voice note, falling back to SMS → returning photos are checked one by one by a cheap vision agent → the Condition node sends at most the policy-allowed follow-ups for outstanding items → the branch officer verifies identity and approves at the Approval node, and a Tool (MCP) node writes the KYC status back to core banking.",
        },
      },
      {
        id: "coop-agm-dividend",
        title: "AGM and dividend notices with RSVP and proxy tracking",
        scenario:
          "Today: after the board declares the dividend, the secretary and two clerks spend a week at a table with the share register, a rubber stamp and a stack of pre-printed notices, hand-writing each member's shareholding and dividend entitlement onto the notice, stuffing envelopes and getting them to the post office inside the statutory notice period. Proxy forms drift back in an envelope pile that nobody reconciles against the register until the morning of the meeting, when quorum is worked out at the door with a pen and the register open on the table.",
        triggerLabel: "Webhook (AGM notice + dividend declaration approved by the board)",
        steps: [
          { kind: "trigger", title: "Board approves the notice", detail: "A Webhook trigger fires once the board has approved the AGM notice and dividend declaration — this workflow distributes a decision already made by the board; it never produces one." },
          { kind: "knowledge", title: "Ground the notice in the bye-laws", detail: "A Knowledge node scoped to the society's bye-laws supplies the notice requirements — statutory notice period, mandatory contents, quorum rule, proxy eligibility — so the distributed text carries every required element and the notice-period check is made before anything sends." },
          { kind: "loop", title: "Personalise per member", detail: "A Loop node runs a cheap agent per member to render the approved notice in that member's recorded language, with their shareholding and dividend entitlement inserted verbatim from the share register — the table, the stamp and the hand-writing, replaced. No figure is ever computed by a model." },
          { kind: "code", title: "Format the entitlement line", detail: "A Code node uses the society's user-defined SpEL tool to build the entitlement line in the society's own house format from the register fields — so every notice reads identically and no clerk's handwriting is involved." },
          { kind: "approval", title: "Secretary signs off the distribution", detail: "An Approval node holds the rendered notice set and the distribution list for the secretary — a statutory notice is a legal instrument, so a human approves the exact text and audience before a single send." },
          { kind: "output", title: "Send on WhatsApp, SMS, Email and voice", detail: "An Output node delivers the approved notice on each member's preferred channel, with an ElevenLabs/Sarvam voice note for members who do not read comfortably, and logs delivery per member for the statutory record — which the post office never gave them." },
          { kind: "agent", title: "Collect RSVPs and proxies", detail: "Replies flow back to a light agent that records attending / not attending / sending a proxy, and asks for the named proxy where one is declared — it records what the member said and never accepts a proxy as valid itself." },
          { kind: "approval", title: "Secretary validates proxies + quorum", detail: "An Approval node puts the reconciled proxy list and the projected quorum in front of the secretary the week before, not at the door — proxy eligibility under the bye-laws and the quorum call remain human determinations." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS / Twilio", "Email", "ElevenLabs (voice)", "Sarvam (Indian languages)", "Knowledge base", "Approval / HITL", "Tool (MCP → member register)"],
        outcome:
          "Every member receives a bye-law-compliant AGM and dividend notice in their own language on a channel they use, with per-member delivery logged for the statutory record, and the secretary walks into the meeting with a reconciled RSVP and proxy list instead of an envelope pile.",
        impact:
          "The mechanism is verbatim distribution at scale plus structured reply capture: the board's approved text and each member's register figures are passed through untouched, delivery is logged per member, and RSVPs and proxies land as data — so quorum is a count the secretary validates in advance rather than works out at the door.",
        build: {
          agents: 2,
          agentsNote: "1 notice renderer (runs once per member via the Loop node) + 1 RSVP/proxy recorder (runs per reply). The bye-law grounding, the entitlement-line formatting, both sign-offs and the delivery log are workflow nodes.",
          pattern: "Board-approved text → per-member language rendering + SpEL entitlement line → secretary approves the set → send + log → structured RSVP capture → secretary validates quorum",
          prompt:
            "(Notice renderer) Render the board-approved AGM and dividend notice for ONE member. You are given {member_name, language, shares_held, dividend_amount, entitlement_line, meeting_datetime, venue, agenda_items[], approved_notice_text}. Reproduce every mandatory element of approved_notice_text — you may translate into the member's language and reformat for readability, but you may NOT add, remove, soften or reinterpret any statutory content, agenda item, date or venue. Insert entitlement_line EXACTLY as supplied; never compute, round, convert or estimate a figure. Do not add any statement about the society's performance, future dividends, or advice about what the member should do. Return {text, tts_text}.",
          agentRoles: [
            { role: "Notice renderer (per member)", model: "Economy tier with a configured fallback model — runs once for every member on the register, so it is the entire cost story; a bigger model here would only risk paraphrasing statutory language it must not touch", prompt: "Render the approved notice into one member's language with the supplied entitlement line inserted verbatim. No added or removed statutory content, no computed figures, no commentary or advice." },
            { role: "RSVP / proxy recorder (per reply)", model: "Economy tier, fallback configured — short structured extraction from a free-text or spoken reply", prompt: "Record one member reply as {attending (YES|NO|PROXY|UNCLEAR), proxy_name, notes}. Ask for the proxy's name if PROXY is declared without one. Never assess whether a proxy is eligible and never count or project quorum — that is the secretary's determination." },
          ],
          mcp: [
            "Member / share register — via Tool (MCP): fetch each member's shareholding, dividend entitlement, recorded language and preferred channel",
            "Member / share register — via Tool (MCP): log per-member delivery (channel, timestamp, outcome) for the statutory record",
            "Member / share register — via Tool (MCP): record each RSVP and declared proxy against the member's row for the secretary's reconciliation",
            "WhatsApp / SMS / Email — distribute the notice and receive replies on the member's preferred channel",
            "Sarvam + ElevenLabs — render the notice per member language and voice it for members who do not read comfortably",
          ],
          tools: [
            "knowledge_search (bye-laws: notice period, mandatory contents, quorum and proxy rules — scoped Knowledge collection)",
            "http_request (read the member register; log per-member delivery and RSVP)",
            "send_email (deliver the approved notice to members whose channel of record is email)",
            "user-defined tool `dividend_notice_line` (SpEL): input.memberName + ' — shares held: ' + input.sharesHeld + ' — dividend as declared by the board and recorded in the register: ' + input.dividendAmount + ' (' + input.financialYear + ')' — a tenant-authored formatting tool: it concatenates fields straight off the tool input, with no arithmetic on money at all. This is the honest use of the feature — the society controls the exact wording and order of its own statutory notice line and can amend it when the bye-laws or the house style change, without a developer, and without ever letting a model or a formula recompute a member's entitlement.",
          ],
          guardrails: [
            "No decision by the agent: the board declared the dividend and the secretary approves the notice text, the audience, the proxy validity and the quorum call at Approval nodes — the agent distributes and records only, makes no credit, financial or governance determination, and moves no money",
            "All figures — shareholding, dividend entitlement, dates, venue — are passed through verbatim from the register and the approved text; the model is forbidden from computing, rounding or restating any amount, and the `dividend_notice_line` SpEL tool only concatenates supplied fields, it performs no arithmetic on money",
            "Statutory content is preserved: the renderer may translate and reformat but may not add, drop or reinterpret a mandatory element, and the bye-law notice-period requirement is checked before distribution",
            "Per-member delivery (channel, timestamp, outcome) is logged for the statutory record, sends respect the permitted contact window, and the notice itself is a statutory communication — routine opt-outs apply to follow-ups, not to the notice",
            "Proxies are recorded as declared, never validated by the agent; eligibility under the bye-laws is determined by the secretary at the second Approval node",
          ],
          cost:
            "This is a pure fan-out: one economy-tier render per member on the register, once a year, plus one cheap extraction per reply, with a fallback model so an outage cannot jeopardise a statutory notice window. Because the text is board-approved and the figures are inserted verbatim by a SpEL formatter, there is no reasoning step anywhere — a standard-tier model would add cost and add risk. Cache the translated boilerplate per language and vary only the member fields.",
          output:
            "The board's approved notice arrives via Webhook → the bye-law requirements are loaded from the scoped Knowledge collection → the Loop renders one per-member language version, with the `dividend_notice_line` SpEL tool formatting the register figures verbatim → the secretary approves the exact text and distribution list at the first Approval node → an Output node sends on WhatsApp, SMS or Email with a voice note and logs delivery per member → replies are recorded as structured RSVP/proxy data → the secretary validates proxy eligibility and calls quorum at the second Approval node.",
        },
      },
      {
        id: "coop-loan-file-prep",
        title: "Loan-committee file preparation — the pack, not the verdict",
        scenario:
          "Today: the credit committee meets on alternate Saturdays and works through a stack of physical files a clerk carried up from the branch. Half the sitting goes on discovering what is not in them — the member's share qualification was never checked against the bye-law, nobody pulled his existing borrowings from the other branch, the bureau report is four months old, and one file's guarantor page is missing. Those files go back and wait a fortnight. Members phone the branch in the meantime and are told 'it is with the committee'.",
        triggerLabel: "Webhook (application marked ready for committee) / Email",
        steps: [
          { kind: "trigger", title: "A file is marked committee-ready", detail: "A Webhook from the LOS (or an Email from the branch) marks an application ready. The LOS, core banking system and credit bureau have no first-party connectors — each is reached via a Tool (MCP) node." },
          { kind: "parallel", title: "Gather the file in parallel", detail: "A Parallel node fans out the read-only gathering the clerk used to do serially over days: application and documents from the LOS, membership and share qualification and existing borrowings from core banking, and a current bureau summary — all at once, all read-only." },
          { kind: "subworkflow", title: "Re-use the document completeness check", detail: "A Sub-workflow node invokes the same document-completeness pre-check the field side already uses, so 'the guarantor page is missing' is found before the file is tabled rather than in the sitting." },
          { kind: "agent", title: "Check the file against the checklist", detail: "An economy-tier agent verifies completeness against the product's documented checklist and the society's bye-law eligibility conditions — documents present and legible, member category, share qualification, bureau report currency. It reports facts and gaps only." },
          { kind: "code", title: "Route to the right sanctioning authority", detail: "A Code node applies the society's user-defined SpEL tool to the applied amount to select WHO must decide — branch manager, credit committee or board — straight from the society's own authority matrix. It picks the decider; it never decides." },
          { kind: "condition", title: "Complete enough to table?", detail: "A Condition node holds back an incomplete file with a specific gap list for the branch, so the Saturday agenda contains only files that can actually be decided." },
          { kind: "agent", title: "Write the committee brief", detail: "A standard-tier agent assembles a neutral one-page brief: what is applied for, the member's standing and existing exposure as read from the systems, the documented eligibility position with the bye-law clause cited, and the open questions. It presents no recommendation, no score and no rate." },
          { kind: "approval", title: "The committee decides", detail: "An Approval node routes the brief to the sanctioning authority the SpEL tool selected — approve, reject, or send back with conditions. Nothing in this workflow may sanction, price or disburse anything." },
          { kind: "output", title: "Record the minute + inform the member", detail: "A Tool (MCP) node records the committee's recorded decision against the file, and an Output node informs the member of the outcome in his language on WhatsApp — so 'it is with the committee' stops being the answer he gets by phone." },
        ],
        connectors: ["Webhook", "Email", "WhatsApp", "Knowledge base", "Sarvam (Indian languages)", "Approval / HITL", "Tool (MCP → LOS / core banking / credit bureau)"],
        outcome:
          "The committee receives complete files with a neutral one-page brief and a cited eligibility position, already routed to the correct sanctioning authority — so the sitting is spent on the credit judgement itself rather than on discovering what is missing.",
        impact:
          "The mechanism is separating the clerical from the judgemental: gathering, completeness, bureau currency and bye-law eligibility are objective, checkable work a workflow can do in parallel and in advance, while the decision — which is the committee's statutory function — is untouched and arrives better informed.",
        build: {
          agents: 2,
          agentsNote: "1 completeness/eligibility checker (per file) + 1 brief writer (per committee-ready file). The parallel gathering, the re-used document sub-workflow, the authority routing, the agenda gate and the committee decision are workflow nodes.",
          pattern: "Parallel read-only gather → re-used completeness sub-workflow → cheap eligibility check → SpEL authority routing → gate → neutral brief → committee decides",
          prompt:
            "(Brief writer) Write a NEUTRAL one-page committee brief for a cooperative-society loan file. You are given the application, the completeness-check result, the member's standing and existing borrowings as read from the systems, the bureau summary, and the selected sanctioning authority. Structure it as: (1) what is applied for, verbatim; (2) member standing — membership date, share qualification, existing borrowings and repayment record, all quoted exactly as supplied; (3) documented eligibility position, citing the specific bye-law or product-policy clause from Knowledge; (4) open questions for the committee. You must NOT: recommend approval or rejection; state, score or imply creditworthiness; suggest an amount, tenor, interest rate, security or condition; compute a ratio or an amount not supplied to you; or characterise the member's character or intent. If a figure is missing, say it is missing.",
          agentRoles: [
            { role: "Completeness + eligibility checker (per file)", model: "Economy tier with a configured fallback model — a checklist comparison over structured inputs, run on every file including the ones that never get tabled", prompt: "Compare the gathered file against the product checklist and bye-law eligibility conditions in Knowledge. Return {present[], missing[], illegible[], eligibility_conditions_met[], eligibility_conditions_unmet[], bureau_report_age_days, clause_refs[]}. Report facts against documented rules only — no creditworthiness view, no recommendation, no waiver." },
            { role: "Brief writer (per committee-ready file)", model: "Standard tier, fallback configured — the one genuinely hard step: neutral synthesis with clause citation, and the only place a bigger model earns its cost", prompt: "Assemble the neutral one-page brief exactly as structured in the main prompt, quoting all figures verbatim and citing the bye-law clause. No recommendation, no score, no rate, no computed ratio, no character assessment." },
          ],
          mcp: [
            "LOS / loan-origination system — via Tool (MCP): fetch the application, the submitted documents and the product code",
            "Core banking system — via Tool (MCP): fetch membership date, share qualification, existing borrowings across branches and the repayment record",
            "Credit bureau — via Tool (MCP): pull the enquiry status and the current bureau summary, and return the report date so its currency can be checked",
            "LOS — via Tool (MCP): write the committee's recorded decision and minute reference against the application id",
            "WhatsApp — inform the member of the committee's recorded outcome in his own language",
          ],
          tools: [
            "knowledge_search (product policy, bye-law eligibility conditions, sanctioning-authority matrix — scoped Knowledge collection)",
            "http_request (parallel read-only gathering; write the committee's recorded decision)",
            "user-defined tool `sanctioning_authority` (SpEL): input.appliedAmount > input.boardLimit ? 'BOARD' : (input.appliedAmount > input.committeeLimit ? 'CREDIT_COMMITTEE' : 'BRANCH_MANAGER') — a tenant-authored formula tool: two comparisons and a ternary over the tool input, with the society's own limits supplied as fields. Every society's authority matrix is different and is amended by board resolution, so holding it as an editable expression means the routing follows the resolution the day it passes, with no release. Read the boundary carefully: this selects WHO is empowered to decide, never WHAT is decided — the sanction itself is always the named human's at the Approval node.",
          ],
          guardrails: [
            "ABSOLUTE: no agent makes a credit decision. Nothing here approves, rejects, scores, rates, prices, sets a limit, security or tenor, or disburses — the brief explicitly carries no recommendation, and the sanctioning authority decides at the Approval node",
            "The `sanctioning_authority` SpEL tool routes a file to the correct decision-maker under the society's own authority matrix — it selects who decides, it never decides, and it can never lower the authority a board resolution requires",
            "Every figure — exposure, repayment record, share qualification, bureau data — is quoted verbatim from the source system; the model may not compute a ratio or an amount, and a missing figure is reported as missing rather than inferred",
            "The eligibility position is stated only against the bye-law or product-policy clause it cites; the model may not waive a condition, invent one, or characterise the member's intent or character",
            "All system access in the gathering step is read-only; the single write is the committee's recorded decision, keyed to the application id and stamped with the deciding authority, so the workflow can never sanction by side effect",
            "The member is informed of the committee's recorded outcome only — the message states a human decision and never explains it as the agent's, and it carries no advice, no rate and no commitment",
          ],
          cost:
            "Only two model calls per file, and only one of them is standard tier: the completeness check is a checklist comparison and belongs on the economy tier, while the brief is the single hard step where neutral synthesis and clause citation justify the spend; both have fallback models so a provider outage cannot cost the committee a fortnight. Gathering runs in a Parallel node, which cuts wall-clock without adding tokens, and the authority routing is free SpEL. Files stopped by the completeness gate never reach the standard-tier brief at all, so incomplete files cost almost nothing.",
          output:
            "A Parallel node gathers the application, the member's standing and a current bureau summary read-only via Tool (MCP) nodes → a Sub-workflow node re-uses the document-completeness pre-check → the cheap checker verifies bye-law eligibility with clause references → a Code node applies the society's `sanctioning_authority` SpEL tool to route the file → the Condition node keeps incomplete files off the agenda and returns a specific gap list to the branch → the standard-tier writer produces a neutral, recommendation-free one-page brief → the named sanctioning authority decides at the Approval node → a Tool (MCP) node records the decision against the file and an Output node informs the member on WhatsApp in his own language.",
        },
      },
    ],
  },
];
