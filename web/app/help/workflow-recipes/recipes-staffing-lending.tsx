import type { Industry } from "./recipes-data";
import { UsersRound, Banknote } from "lucide-react";

// ---------------------------------------------------------------------------
// Staffing & Lending workflow recipes — same "inline copy, no i18n keys"
// precedent and honesty rules as recipes-data.tsx. Every recipe is expressed
// ONLY in VegaDūta's real building blocks (Trigger / Agent / Knowledge /
// Condition / Approval / Output / Tool(MCP) / Voice / Device / Loop / Code /
// Parallel), so each maps 1:1 to something you can actually build in the
// Workflow designer.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta does
// NOT ship a first-party connector for — ATS/VMS, LOS/core-banking, credit
// bureau, payroll — are reached the real way, via an inbound Webhook trigger
// and/or a Tool (MCP) node, and are LABELLED as such. Every consequential
// decision (candidate placement, credit/disbursement) goes through an Approval
// (human-in-the-loop) step. Impact lines describe the mechanism (why it helps),
// never invented ROI numbers.
// ---------------------------------------------------------------------------

export const INDUSTRIES_STAFFFIN: Industry[] = [
  // ================================================= STAFFING & RECRUITMENT
  {
    id: "staffing",
    name: "Staffing & recruitment agencies",
    icon: UsersRound,
    tagline: "Source and screen at scale, chase timesheets, and keep the bench warm — without a recruiter tied to a spreadsheet.",
    recipes: [
      {
        id: "staffing-screen",
        title: "Bulk sourcing & screening against a client req",
        scenario:
          "A client sends a role and a stack of applicants floods in from job boards. A recruiter opens each CV, reads it against the req, and copies notes into a tracker — hours per role, and the applicant screened at 6pm gets less attention than the one at 9am.",
        triggerLabel: "Webhook (new applicants) or Email (job-board inbox)",
        steps: [
          { kind: "trigger", title: "Applicants land", detail: "A Webhook (from your ATS/job board) or an Email trigger on the applications inbox starts the flow — no first-party ATS connector, the board pushes in via Webhook." },
          { kind: "knowledge", title: "Load the client req", detail: "A Knowledge node holds the client's job spec + must-haves; every applicant is compared against it, not a keyword guess." },
          { kind: "loop", title: "Screen every applicant the same way", detail: "A Loop node iterates the full applicant batch so the hundredth CV is read as carefully as the first." },
          { kind: "agent", title: "Summarize + score fit", detail: "An Agent node writes a 3-line summary, a fit score against the req, and the gaps — consistently, for every candidate." },
          { kind: "condition", title: "Auto-route by fit", detail: "A Condition node splits strong / maybe / reject so recruiters only read the shortlist." },
          { kind: "approval", title: "Recruiter confirms the submittals", detail: "An Approval node keeps the human as the decision-maker — the agent ranks, a recruiter decides who goes to the client. This is the placement gate." },
          { kind: "tool", title: "Write back to the ATS/VMS", detail: "A Tool (MCP) node updates each candidate's stage and notes in your ATS or the client's VMS." },
        ],
        connectors: ["Webhook", "Email", "Loop", "Knowledge base", "Approval / HITL", "Tool (MCP → ATS/VMS)"],
        outcome: "Every applicant in the batch is read and scored against the client req within minutes; recruiters spend their time only on the shortlist and the submittal decision.",
        impact: "Removes the fatigue bias of screening a big batch by hand and the copy-paste into trackers, while the who-to-submit call stays with a recruiter.",
        build: {
          agents: 1,
          agentsNote: "1 screening agent per applicant; the per-applicant Loop, the fit routing and the ATS/VMS write-back are workflow nodes.",
          prompt:
            "You are a staffing screener working a client requisition. Given a résumé and the client's must-haves (from Knowledge), output strict JSON {summary (max 3 lines), fit_score 0-100, matched[], gaps[], recommendation: STRONG|MAYBE|REJECT}. Score only on job-relevant evidence in the résumé. Do NOT use name, gender, age, religion, caste, marital status, or photo as signals — ignore them entirely. If a must-have can't be confirmed, put it in gaps rather than assuming it. You rank candidates; you never decide who is submitted to the client.",
          mcp: ["ATS / client VMS — via a Tool (MCP) node (no first-party connector)", "Job board — applicants pushed inbound via Webhook", "Applications inbox — via Email trigger"],
          tools: ["knowledge_search (client req + must-haves)", "file parsing (résumé PDF/DOCX)"],
          guardrails: [
            "Bias guardrail: protected attributes are explicitly excluded from scoring in the prompt",
            "The agent ranks; it never rejects or submits — the recruiter makes every submittal decision at the Approval node",
            "Same Knowledge-grounded rubric for every applicant in the batch — the hundredth CV is scored like the first, not on fatigue",
          ],
          output:
            "A Loop node runs the agent per applicant → per-candidate JSON → the Condition node buckets STRONG/MAYBE/REJECT → the recruiter confirms the submittals at the Approval node → a Tool node writes each candidate's stage + notes back to the ATS/VMS via MCP.",
        },
      },
      {
        id: "staffing-bench",
        title: "Bench availability check-ins & redeployment",
        scenario:
          "Contractors roll off assignments and the bench sits idle because nobody has time to call round and check who's free, when, and for what — so the agency re-sources roles a benched consultant could have filled.",
        triggerLabel: "Webhook (scheduled, bench/availability run)",
        steps: [
          { kind: "trigger", title: "Availability run", detail: "A scheduled Webhook trigger evaluates the bench and contractors nearing roll-off each week (fed from your ATS/VMS via Webhook)." },
          { kind: "loop", title: "Per benched consultant", detail: "A Loop node iterates the list so each consultant gets a personal check-in." },
          { kind: "agent", title: "Draft a tailored check-in", detail: "An Agent node references the consultant's skills and last assignment, asks about availability, notice and rate expectations." },
          { kind: "output", title: "Reach out on their channel", detail: "An Output node sends the check-in on WhatsApp/SMS/Email and captures the reply." },
          { kind: "knowledge", title: "Match to open reqs", detail: "A Knowledge node searches current open client requirements for genuine skill matches to redeploy them into." },
          { kind: "approval", title: "Recruiter approves the redeployment", detail: "An Approval node routes each proposed match to a recruiter — the agent surfaces the fit, a person decides the placement." },
        ],
        connectors: ["Webhook", "Loop", "WhatsApp", "SMS", "Email", "Knowledge base", "Approval / HITL"],
        outcome: "The bench is kept warm with real availability data, and free consultants are matched to open reqs before the agency re-sources from scratch.",
        impact: "Consistent, timely check-ins are what surface who's actually available — the manual process skips exactly the consultants who quietly roll off, while the placement decision stays with a recruiter.",
        build: {
          agents: 1,
          agentsNote: "1 outreach agent per consultant; the per-consultant Loop, the req-matching Knowledge search and the recruiter Approval are workflow nodes.",
          prompt:
            "You are a bench-management assistant for a staffing agency. For each benched or rolling-off consultant {name, skills[], last_assignment, roll_off_date}, write a personal check-in referencing their skills and last role, and ask about current availability, notice period and rate expectations. Capture their reply as {available_from, notice, rate_expectation, interested: boolean, notes}. Be respectful of their time, honour opt-outs, and don't imply a specific role is confirmed — matching and placement are decided by a recruiter.",
          mcp: ["ATS / VMS — bench and roll-off list fed inbound via Webhook (no first-party connector)", "WhatsApp + SMS + Email — via the messaging channels"],
          tools: ["knowledge_search (current open client requirements)"],
          guardrails: [
            "The agent surfaces skill matches to open reqs; the recruiter decides every redeployment at the Approval node — no placement is the agent's call",
            "Never implies a role is confirmed or negotiates a rate — availability, notice and rate are captured, not committed",
            "Honours opt-outs and check-in frequency so a consultant isn't over-messaged between assignments",
          ],
          output:
            "A Loop node runs the agent per benched consultant → an Output node sends the check-in and captures the reply → a Knowledge search matches them to open client reqs → each proposed match goes to the recruiter at the Approval node for the placement decision.",
        },
      },
      {
        id: "staffing-timesheet",
        title: "Timesheet collection & chasing for placed contractors",
        scenario:
          "Every week placed contractors must submit timesheets before payroll and client invoicing can run; today a coordinator chases missing ones by hand over email, and late sheets hold up both pay and billing.",
        triggerLabel: "Webhook (scheduled, weekly timesheet run)",
        steps: [
          { kind: "trigger", title: "Timesheet run", detail: "A scheduled Webhook trigger fires at the weekly cut-off for all active placements (contractor list fed from your ATS/VMS via Webhook)." },
          { kind: "loop", title: "Per active contractor", detail: "A Loop node iterates active placements so each contractor gets their own request." },
          { kind: "output", title: "Request the timesheet", detail: "An Output node messages each contractor on WhatsApp/Email with a one-tap submit link and the hours to confirm." },
          { kind: "condition", title: "Chase only the missing ones", detail: "A Condition node tracks who has submitted and escalates reminders only to the ones still outstanding as the deadline nears." },
          { kind: "approval", title: "Client manager approves the hours", detail: "An Approval node routes submitted hours to the client's approving manager before anything flows to pay or invoice." },
          { kind: "tool", title: "Push to payroll / invoicing", detail: "A Tool (MCP) node writes approved hours into your payroll and invoicing system — no first-party payroll connector, MCP calls yours." },
        ],
        connectors: ["Webhook", "Loop", "WhatsApp", "Email", "Approval / HITL", "Tool (MCP → payroll)"],
        outcome: "Timesheets are requested, chased and approved on schedule, with humans touching only the approval — pay and client invoices stop waiting on missing sheets.",
        impact: "Removes the manual weekly chase that delays payroll and billing, while the hours-approval step stays with the client manager who owns it.",
        build: {
          agents: 1,
          agentsNote: "1 messaging agent; the per-contractor Loop, the missing-only chase Condition, the manager Approval and the payroll write are workflow nodes.",
          prompt:
            "You are a timesheet-collection assistant for placed contractors. For each active placement, message the contractor a clear request for this week's hours with a one-tap submit link, stating the period and any expected hours to confirm. If they reply with hours, capture {contractor, period, hours, notes} exactly as submitted — never edit, round, or estimate hours on their behalf. Keep reminders polite and send them only to contractors who haven't yet submitted as the deadline nears.",
          mcp: ["Payroll / invoicing system — via a Tool (MCP) node (no first-party connector)", "ATS / VMS — active-placement list fed inbound via Webhook", "WhatsApp + Email — via the messaging channels"],
          tools: ["http_request (check submission status)"],
          guardrails: [
            "Submitted hours are captured verbatim — the agent never edits, rounds, or invents hours",
            "Hours go to the client's approving manager at the Approval node before anything flows to payroll or invoicing",
            "Only outstanding contractors are chased; already-submitted ones aren't re-messaged",
          ],
          output:
            "A Loop node runs per active contractor → an Output node requests the timesheet with a submit link → the Condition node chases only those still missing → the client manager approves the hours at the Approval node → a Tool node writes approved hours to payroll/invoicing via MCP.",
        },
      },
    ],
  },

  // ================================================= LENDING & FINANCIAL SERVICES
  {
    id: "lending",
    name: "Lending & financial services",
    icon: Banknote,
    tagline: "Take applications, collect KYC, answer eligibility and chase repayments — with every credit decision gated to a human underwriter.",
    recipes: [
      {
        id: "lending-apply",
        title: "Loan application intake + KYC with a completeness gate",
        scenario:
          "A borrower applies and an officer manually captures details, then emails back and forth for KYC documents, income proof and bank statements over days — incomplete files bounce repeatedly and stall the pipeline.",
        triggerLabel: "WhatsApp / Webhook (loan enquiry)",
        steps: [
          { kind: "trigger", title: "Application opens", detail: "A WhatsApp trigger (or a Webhook from your website/LOS) opens the application in the borrower's own language." },
          { kind: "agent", title: "Capture the application", detail: "An Agent node collects loan type, amount, tenure, income and purpose into a clean, structured record." },
          { kind: "output", title: "Request exactly the right documents", detail: "An Output node asks for the specific KYC / income / bank documents and accepts them right in the chat, guiding on format and clarity." },
          { kind: "condition", title: "Completeness gate", detail: "A Condition node won't advance the file until every mandatory KYC and income document is present and legible." },
          { kind: "approval", title: "Underwriter makes the credit decision", detail: "An Approval node hands a complete, structured file to a human underwriter — the credit decision is never automated. This is the non-negotiable control." },
          { kind: "tool", title: "Write to the LOS / core banking", detail: "A Tool (MCP) node records the application and the decision in your loan-origination or core-banking system — reached via MCP, not a native connector." },
        ],
        connectors: ["WhatsApp", "Webhook", "Sarvam (multilingual)", "Approval / HITL", "Tool (MCP → LOS/core-banking)"],
        outcome: "Applications arrive complete and structured with KYC in hand; underwriters spend time deciding, not chasing documents — and every credit decision is a logged human approval.",
        impact: "Removes the days of manual document chasing from the front of every loan, while the credit decision stays with a licensed underwriter.",
        build: {
          agents: 1,
          agentsNote: "1 intake agent; the completeness gate, the underwriter Approval and the LOS write are workflow nodes.",
          prompt:
            "You are a loan-application intake assistant. Capture the application as strict JSON {loan_type, amount, tenure, monthly_income, employment, purpose, applicant_details} in the borrower's language, and request the specific KYC, income and bank-statement documents needed, guiding on format and legibility. You do NOT assess creditworthiness, quote an interest rate, or say whether the loan will be approved — the credit decision is a human underwriter's at the approval step. Never promise approval or a disbursal date. Handle identity and financial documents as confidential.",
          mcp: ["Loan-origination system / core banking — via a Tool (MCP) node (no first-party connector)", "WhatsApp — via the messaging channel", "Website/LOS — inbound via Webhook"],
          tools: ["document parsing (KYC / income / bank-statement PDFs)", "knowledge_search (required-document checklist by loan type)"],
          guardrails: [
            "Never makes or implies a credit decision — completeness only; the underwriter decides at the Approval node, and every decision is logged",
            "The file can't advance until every mandatory KYC and income document is present and legible (Condition gate)",
            "KYC and financial documents are handled as confidential — captured to the application, never echoed back or shared",
          ],
          output:
            "The agent captures the application and collects documents in-chat → the Condition node blocks anything incomplete → the underwriter makes the credit decision at the Approval node → a Tool node records the application and decision in the LOS/core-banking system via MCP.",
        },
      },
      {
        id: "lending-preapproval",
        title: "Pre-approval eligibility Q&A (indicative only, gated)",
        scenario:
          "Prospects ask 'will I qualify, and for how much?' at all hours; officers answer the same eligibility questions by hand, and inconsistent replies create false expectations.",
        triggerLabel: "WhatsApp / Webhook (eligibility enquiry)",
        steps: [
          { kind: "trigger", title: "Eligibility question arrives", detail: "A WhatsApp or Webhook trigger starts the flow in the prospect's own language." },
          { kind: "knowledge", title: "Ground in the product & policy rules", detail: "A Knowledge node answers strictly from your current loan products, rate cards and eligibility criteria — with citations, no guessing." },
          { kind: "agent", title: "Explain indicative eligibility", detail: "An Agent node walks the prospect through the criteria and gives an indicative-only view, clearly flagged as not an offer of credit." },
          { kind: "condition", title: "Route the ready ones forward", detail: "A Condition node identifies prospects who look eligible and want to proceed to a real application." },
          { kind: "approval", title: "Officer confirms any pre-approval", detail: "An Approval node routes anything that looks like a firm pre-approval to a human officer — no credit commitment is made by the agent." },
          { kind: "output", title: "Hand off or invite to apply", detail: "An Output node either books an officer callback or sends the borrower into the full application flow." },
        ],
        connectors: ["WhatsApp", "Webhook", "Sarvam (translate and speech tools)", "Knowledge base", "Approval / HITL"],
        outcome: "Prospects get accurate, in-language eligibility answers instantly; officers spend time only on those ready to apply, and no credit commitment is ever made without a human.",
        impact: "Consistent, grounded answers replace ad-hoc manual replies that set false expectations, while any actual pre-approval stays a human decision.",
        build: {
          agents: 1,
          agentsNote: "1 eligibility agent; the ready-to-proceed Condition, the officer Approval and the hand-off are workflow nodes.",
          prompt:
            "You are a loan eligibility assistant. Answer 'will I qualify and for how much' questions strictly from the current products, rate cards and eligibility criteria in Knowledge, with citations. Walk the prospect through the criteria and give an INDICATIVE view only, stated clearly as 'not an offer of credit and subject to verification and approval'. Never confirm a pre-approval, commit an amount or rate, or say they are approved — anything that looks like a firm pre-approval is routed to a human officer. If the rules don't cover their case, say so and offer a callback.",
          mcp: ["WhatsApp — via the messaging channel", "Website — inbound via Webhook"],
          tools: ["knowledge_search (loan products + rate cards + eligibility criteria)"],
          guardrails: [
            "Indicative only: every eligibility answer is flagged as not an offer of credit and subject to approval — the agent makes no credit commitment",
            "Anything resembling a firm pre-approval is routed to a human officer at the Approval node",
            "Grounded-only: answers come strictly from the current products/rate-cards/criteria with a citation, never an assumed number",
          ],
          output:
            "The agent explains indicative eligibility grounded in the product/policy Knowledge → the Condition node identifies prospects ready to proceed → any firm pre-approval is confirmed by an officer at the Approval node → an Output node books an officer callback or sends the prospect into the full application flow.",
        },
      },
      {
        id: "lending-repayments",
        title: "EMI reminders & soft collections outreach",
        scenario:
          "Upcoming and overdue EMIs are chased manually and inconsistently — some borrowers never get a reminder, cash sits uncollected, and the tone is the same whether they're one day or ninety days late.",
        triggerLabel: "Webhook (scheduled, daily repayment run)",
        steps: [
          { kind: "trigger", title: "Repayment run", detail: "A scheduled Webhook trigger evaluates upcoming and overdue EMIs each morning (schedule fed from your LOS/core-banking via Webhook)." },
          { kind: "loop", title: "Per due / overdue account", detail: "A Loop node iterates the list so each borrower gets a tailored message." },
          { kind: "agent", title: "Write a tone-matched nudge", detail: "An Agent node tailors tone to how overdue it is — a gentle upcoming-due reminder versus a firmer, still-respectful soft-collections message — and includes the exact amount and a Razorpay UPI pay link." },
          { kind: "output", title: "Reach out on their channel", detail: "An Output node sends the reminder on WhatsApp/SMS/Email in the borrower's language and offers to pay in one tap." },
          { kind: "condition", title: "Escalate the persistent ones", detail: "A Condition node flags accounts past a delinquency threshold for human handling — hard collections and any restructuring are never automated." },
          { kind: "approval", title: "Collections officer takes over", detail: "An Approval node routes escalated, seriously delinquent accounts to a human officer for the call and any decision on the account." },
        ],
        connectors: ["Webhook", "Loop", "WhatsApp", "SMS", "Email", "Razorpay UPI", "Approval / HITL"],
        outcome: "Every due and overdue EMI gets a timely, tone-appropriate nudge with a one-tap pay link; only the seriously delinquent accounts reach a human, and no account action is automated.",
        impact: "Consistent, respectful follow-up is what pulls repayments in — the manual process skips exactly the accounts that need it, while any real account decision stays with a collections officer.",
        build: {
          agents: 1,
          agentsNote: "1 messaging agent per account; the per-account Loop, the escalation Condition and the collections-officer Approval are workflow nodes.",
          prompt:
            "You are an EMI-reminder assistant. For each account {borrower, emi_amount, due_date, days_overdue, language}, write a message whose tone matches the stage — a gentle upcoming-due reminder, or a firmer but always respectful soft-collections note — stating the exact amount and including a Razorpay UPI pay link. Follow fair-collections conduct: never threaten, harass, shame, or contact at unreasonable hours, and never disclose the debt to anyone but the borrower. You do NOT restructure, waive, settle, or make any decision on the account — seriously delinquent accounts go to a human officer.",
          mcp: ["Loan-origination system / core banking — repayment schedule fed inbound via Webhook (no first-party connector)", "Razorpay UPI — pay-link for one-tap repayment", "WhatsApp + SMS + Email — via the messaging channels"],
          tools: ["knowledge_search (collections communication + conduct policy)"],
          guardrails: [
            "Fair-conduct: respectful tone only, no threats/harassment/shaming, no unreasonable-hour contact, and the debt is disclosed to no one but the borrower",
            "Hard collections, restructuring, settlement and any account action are never automated — seriously delinquent accounts escalate to the collections officer at the Approval node",
            "Payment is only ever collected through the Razorpay UPI link; the agent never handles card or account details",
          ],
          output:
            "A Loop node runs the agent per due/overdue account → an Output node sends the tone-matched reminder with a UPI pay link in the borrower's language → the Condition node flags accounts past the delinquency threshold → those go to a collections officer at the Approval node for the call and any account decision.",
        },
      },
    ],
  },
];
