import type { Industry } from "./recipes-types";
import { Briefcase, Factory, LifeBuoy, Receipt, Scale, Sprout, Umbrella, Users } from "lucide-react";

// Core pattern pack, part c: HR, finance, legal, IT, manufacturing, insurance, professional services, agriculture. Content unchanged; split out of recipes-core-patterns.tsx.
export const INDUSTRIES_CORE_C: Industry[] = [
  // ================================================= HR & RECRUITING
  {
    id: "hr-recruiting",
    name: "HR & recruiting",
    icon: Users,
    tagline: "Kill the manual screening, scheduling and onboarding paperwork.",
    featured: true,
    recipes: [
      {
        id: "resume-screening",
        title: "Résumé screening & shortlisting (manual → minutes)",
        scenario:
          "A recruiter opens every CV by hand, reads it against the JD, and copies notes into a sheet — hours per role, and the 200th CV gets less attention than the first.",
        triggerLabel: "Webhook (new application) or Email (careers inbox)",
        steps: [
          { kind: "trigger", title: "Application lands", detail: "A Webhook (from your ATS/careers form) or an Email trigger on the careers inbox starts the flow per candidate." },
          { kind: "knowledge", title: "Score against the JD", detail: "A Knowledge node holds the job description + must-haves; the résumé is compared against it, not a keyword guess." },
          { kind: "agent", title: "Summarize + rate", detail: "An Agent node writes a 3-line summary, a fit score, and the gaps — consistently, for every candidate." },
          { kind: "condition", title: "Auto-route by fit", detail: "A Condition node splits strong / maybe / reject so recruiters only read the shortlist." },
          { kind: "approval", title: "Recruiter confirms shortlist", detail: "An Approval node keeps the human as the decision-maker — the agent ranks, a person decides." },
          { kind: "tool", title: "Write back to the ATS", detail: "A Tool (MCP) node updates the candidate stage and notes in your ATS." },
        ],
        connectors: ["Webhook", "Email", "Knowledge base", "Approval / HITL", "Tool (MCP → ATS)"],
        outcome: "Every applicant is read and scored the same way within minutes; recruiters spend their time only on the shortlist and the human decision.",
        impact: "Removes the fatigue bias of manual screening and the copy-paste into sheets, while the hire/reject call stays with a person.",
        build: {
          agents: 1,
          agentsNote: "1 screening agent per applicant; routing + ATS write-back are workflow nodes.",
          prompt:
            "You are a recruiting screener. Given a résumé and the job's must-haves (from Knowledge), output strict JSON {summary (max 3 lines), fit_score 0-100, matched[], gaps[], recommendation: STRONG|MAYBE|REJECT}. Score only on job-relevant evidence in the résumé. Do NOT use name, gender, age, religion, caste, or photo as signals — ignore them entirely. If a must-have can't be confirmed, list it in gaps, don't assume it.",
          mcp: ["ATS (Greenhouse/Lever/etc.) — via a Tool (MCP) node", "careers inbox — via Email trigger"],
          tools: ["knowledge_search (JD + must-haves)", "file parsing (résumé PDF/DOCX)"],
          guardrails: [
            "Bias guardrail: protected attributes are explicitly excluded from scoring in the prompt",
            "The agent ranks; it never rejects — the Approval node (recruiter) makes every advance/reject decision",
            "Same rubric for every candidate — deterministic Knowledge-grounded criteria, not vibes",
          ],
          output:
            "Per-candidate JSON → Condition buckets STRONG/MAYBE/REJECT → recruiter reviews the shortlist at the Approval node → a Tool node writes the stage + notes back to the ATS via MCP.",
        },
      },
      {
        id: "interview-scheduling",
        title: "Interview scheduling without the email ping-pong",
        scenario: "Coordinators trade a dozen emails to find a slot across a candidate and 3 interviewers.",
        triggerLabel: "Webhook (candidate advanced to interview)",
        steps: [
          { kind: "trigger", title: "Candidate moves to interview", detail: "A Webhook trigger fires when the ATS stage changes." },
          { kind: "agent", title: "Propose times", detail: "An Agent node reads panel availability and drafts options." },
          { kind: "output", title: "Offer slots on WhatsApp/Email", detail: "An Output node lets the candidate pick in one tap." },
          { kind: "tool", title: "Book it everywhere", detail: "A Tool (MCP) node writes the invite to the panel's calendars." },
        ],
        connectors: ["Webhook", "WhatsApp", "Email", "Tool (MCP → calendar)"],
        outcome: "A confirmed slot on everyone's calendar with zero back-and-forth.",
        impact: "Collapses days of coordination into a single self-serve pick.",
        build: {
          agents: 1,
          agentsNote: "1 scheduling agent; the calendar write is a workflow Tool node.",
          prompt:
            "You are an interview-scheduling coordinator. Given a candidate advanced to interview and the panel {interviewers[], durations, timezone}, read each interviewer's availability and propose 3–4 non-conflicting slots that fit everyone. Offer them to the candidate to pick in one tap. Only propose times that are actually free on every required panel member's calendar — never double-book. Confirm the final choice back to all parties.",
          mcp: ["Calendar (Google/Microsoft 365) — via a Tool (MCP) node", "ATS — inbound via Webhook trigger", "WhatsApp / Email — candidate channel"],
          tools: ["http_request (read panel availability)"],
          guardrails: [
            "Proposed slots must be free on every required panel member's calendar — no double-booking",
            "The invite is written by the Tool node only after the candidate picks — no speculative holds",
            "The timezone is respected for both candidate and panel",
          ],
          output:
            "The agent proposes conflict-free slots, the candidate picks one on WhatsApp/Email, and a Tool node writes the confirmed invite to every panel member's calendar.",
        },
      },
      {
        id: "onboarding",
        title: "New-hire onboarding checklist that runs itself",
        scenario: "IT access, documents, and welcome steps are chased manually across three teams for every joiner.",
        triggerLabel: "Webhook (offer accepted)",
        steps: [
          { kind: "trigger", title: "Offer accepted", detail: "A Webhook trigger starts onboarding the moment HR marks the hire." },
          { kind: "parallel", title: "Kick off every track at once", detail: "A Parallel node fans out to IT provisioning, document collection and payroll setup simultaneously." },
          { kind: "tool", title: "Provision + request docs", detail: "Tool (MCP) nodes raise the IT tickets and pull the paperwork tasks in your systems." },
          { kind: "output", title: "Guide the joiner", detail: "An Output node walks the new hire through each step on WhatsApp/Email and nudges what's pending." },
        ],
        connectors: ["Webhook", "Parallel", "Tool (MCP → IT/HRIS)", "WhatsApp", "Email"],
        outcome: "Day-one readiness is tracked automatically; nothing falls through the cracks between teams.",
        impact: "Replaces the manual chase across IT/HR/payroll with a single orchestrated flow.",
        build: {
          agents: 1,
          agentsNote: "1 concierge agent guides the joiner; the Parallel fan-out and the provisioning Tool nodes are workflow steps.",
          prompt:
            "You are a new-hire onboarding concierge for {{company}}. Given a joiner {name, role, start_date, tracks[]}, guide them through each onboarding step (IT access, documents, payroll, welcome) in a friendly WhatsApp/Email conversation. Track what's done vs pending across all tracks and send timely nudges for anything outstanding before the start date. Answer common joiner questions from the onboarding Knowledge base. Never ask for sensitive documents over an insecure channel — link to the secure upload instead.",
          mcp: ["IT provisioning / ITSM — via a Tool (MCP) node", "HRIS / payroll — via a Tool (MCP) node", "WhatsApp / Email — joiner channel"],
          tools: ["knowledge_search (onboarding policies/FAQs)"],
          guardrails: [
            "Sensitive documents are collected only via the secure upload link, never over chat",
            "Provisioning tickets are raised by Tool nodes in your systems — the agent tracks status, it never grants access itself",
            "Each track's status is tracked so nothing is skipped between IT, HR and payroll",
          ],
          output:
            "A Parallel node fans out IT/document/payroll tracks; Tool nodes raise the tickets and pull the tasks, while the agent guides the joiner step-by-step on WhatsApp/Email and nudges whatever's still pending.",
        },
      },
    ],
  },

  // ============================================== FINANCE & ACCOUNTING
  {
    id: "finance-ops",
    name: "Finance & accounting ops",
    icon: Receipt,
    tagline: "Automate invoice intake, approvals and chasing overdue payments.",
    featured: true,
    recipes: [
      {
        id: "invoice-intake",
        title: "Invoice intake: PDF → extracted → posted",
        scenario:
          "AP clerks open each supplier invoice PDF, type the vendor, amount, GST and line items into the ERP, then file it — slow, and error-prone at month-end volume.",
        triggerLabel: "Email (AP inbox) or Webhook (upload)",
        steps: [
          { kind: "trigger", title: "Invoice arrives", detail: "An Email trigger on the AP inbox (or an upload Webhook) catches each invoice with its PDF attachment." },
          { kind: "agent", title: "Extract the fields", detail: "An Agent node reads the document and pulls vendor, invoice no., date, amounts, tax and line items into structured data." },
          { kind: "condition", title: "Validate + match PO", detail: "A Condition node checks totals, tax and 3-way match against the PO; clean ones flow on, mismatches branch to review." },
          { kind: "approval", title: "Approver signs off exceptions", detail: "An Approval node routes anything over a threshold or with a mismatch to the right approver." },
          { kind: "tool", title: "Post to the ERP", detail: "A Tool (MCP) node creates the AP entry in your accounting system." },
        ],
        connectors: ["Email", "Approval / HITL", "Tool (MCP → ERP)", "Webhook"],
        outcome: "Invoices are captured, validated and posted with humans touching only the exceptions.",
        impact: "Eliminates manual data entry and the transcription errors that cause payment disputes; approvals become the only human step.",
        build: {
          agents: 1,
          agentsNote: "1 extraction agent; PO-match, approval routing and the ERP post are workflow nodes.",
          prompt:
            "You are an accounts-payable clerk. From the invoice document, extract strict JSON {vendor, invoice_no, invoice_date, currency, subtotal, tax, total, line_items[{desc, qty, amount}], po_number?}. Copy values exactly — never compute or 'fix' a total; if it doesn't add up, set needs_review=true with the discrepancy. Do not guess a PO number.",
          mcp: ["ERP / accounting system (Tally, Zoho, SAP…) — via a Tool (MCP) node", "AP inbox — via Email trigger"],
          tools: ["document parsing (PDF/scan → text)", "http_request (3-way PO match)"],
          guardrails: [
            "No computed totals — the agent only transcribes; any mismatch forces needs_review (stops silent errors)",
            "Amount/mismatch over threshold → Approval node (the right approver) before posting",
            "Duplicate-invoice check (vendor + invoice_no) before the ERP write — never post the same bill twice",
          ],
          output:
            "Extracted JSON → Condition validates totals + 3-way PO match → exceptions go to the Approval node → clean invoices are posted to the ERP by a Tool node via MCP, with the source PDF linked.",
        },
      },
      {
        id: "ar-dunning",
        title: "Accounts-receivable follow-up (dunning) on autopilot",
        scenario: "Overdue invoices are chased manually and inconsistently — some customers never get a reminder, cash sits uncollected.",
        triggerLabel: "Webhook (scheduled, daily aging run)",
        steps: [
          { kind: "trigger", title: "Aging run", detail: "A scheduled Webhook trigger evaluates open invoices each morning." },
          { kind: "loop", title: "Per overdue account", detail: "A Loop node iterates the overdue list." },
          { kind: "agent", title: "Write a polite, escalating nudge", detail: "An Agent node tailors tone to how overdue it is and includes the exact invoice + a UPI/pay link." },
          { kind: "output", title: "Reach out on WhatsApp/Email", detail: "An Output node sends the reminder on the channel the customer responds to." },
          { kind: "condition", title: "Escalate the silent ones", detail: "A Condition node flags accounts past a threshold to a human for a call." },
        ],
        connectors: ["Webhook", "Loop", "WhatsApp", "Email", "Approval / HITL"],
        outcome: "Every overdue invoice gets a timely, professional nudge; only the stubborn ones reach a human.",
        impact: "Consistent follow-up is what pulls cash in — the manual process skips exactly the accounts that need it most.",
        build: {
          agents: 1,
          agentsNote: "1 dunning agent runs inside the Loop; the escalation is a workflow Condition node.",
          prompt:
            "You are an accounts-receivable dunning agent. For each overdue account {customer, invoice_no, amount_due, days_overdue, pay_link}, write a professional reminder whose tone escalates with how overdue it is — polite at first, firmer later — including the exact invoice number, amount and a UPI/pay link. Never threaten, never invent late fees or legal consequences that aren't in policy, and never misstate the amount owed. Keep it courteous and specific.",
          mcp: ["WhatsApp / Email — send channel", "Accounting/ERP aging data — inbound via Webhook trigger", "Payment link (Razorpay UPI) — via a Tool (MCP) node"],
          tools: ["knowledge_search (dunning policy / escalation tone by aging bucket)"],
          guardrails: [
            "No invented late fees, threats or legal language — tone escalates within policy only",
            "The exact invoice number and amount come from the aging data — never misstated",
            "Accounts past the threshold escalate to a human for a call via the Condition node, not more automated messages",
          ],
          output:
            "A Loop node iterates each overdue account; the agent writes a bucket-appropriate reminder sent on WhatsApp/Email with the invoice and pay link, and the Condition node escalates the stubborn accounts to a human.",
        },
      },
    ],
  },

  // ================================================= LEGAL & CONTRACTS
  {
    id: "legal",
    name: "Legal & contracts",
    icon: Scale,
    tagline: "Triage NDAs and contracts against your own playbook — first pass in minutes.",
    recipes: [
      {
        id: "contract-review",
        title: "First-pass contract review against your playbook",
        scenario:
          "Every incoming contract waits in a queue for a lawyer to read it end-to-end and flag risky clauses by hand — a bottleneck for sales and procurement.",
        triggerLabel: "Email or Webhook (contract uploaded)",
        steps: [
          { kind: "trigger", title: "Contract submitted", detail: "An Email/Webhook trigger receives the document." },
          { kind: "knowledge", title: "Compare to your playbook", detail: "A Knowledge node holds your clause standards and fallback positions; the contract is checked against them — with citations." },
          { kind: "agent", title: "Flag deviations + suggest redlines", detail: "An Agent node lists non-standard clauses (liability, indemnity, term, jurisdiction) and proposes edits." },
          { kind: "approval", title: "Lawyer reviews the flags", detail: "An Approval node puts the qualified opinion where it belongs — the agent surfaces, counsel decides." },
          { kind: "output", title: "Return the marked-up summary", detail: "An Output node sends the risk summary back to the requester." },
        ],
        connectors: ["Email", "Webhook", "Knowledge base", "Approval / HITL"],
        outcome: "Routine contracts get a consistent first-pass in minutes; lawyers start from a flagged summary instead of a blank page.",
        impact: "Cuts the review bottleneck without removing the lawyer — the machine reads and flags, the human judges.",
        build: {
          agents: 1,
          agentsNote: "1 review agent; the lawyer sign-off is an Approval node.",
          prompt:
            "You are a contract first-pass reviewer. Compare the incoming contract against the clause standards and fallback positions in the playbook (Knowledge). Identify non-standard or missing clauses — liability, indemnity, term, termination, jurisdiction, IP, confidentiality — cite the playbook position each deviates from, and propose specific redline edits. Output a structured risk summary {clause, issue, playbook_position, suggested_edit, severity}. This is a first pass for a lawyer to review — never present it as legal advice or a final position.",
          mcp: ["Document intake (email/upload) — via Email/Webhook trigger", "Contract/DMS store — via a Tool (MCP) node"],
          tools: ["document parsing (contract PDF/DOCX)", "knowledge_search (clause playbook + fallbacks)"],
          guardrails: [
            "First-pass only — every flag is reviewed by a lawyer at the Approval node before anything goes back",
            "Deviations cite the specific playbook position, not a generic opinion — no ungrounded legal claims",
            "The agent flags and suggests; it never sends redlines to the counterparty itself",
          ],
          output:
            "The agent produces a cited, clause-by-clause risk summary with suggested redlines; a lawyer reviews it at the Approval node, then an Output node returns the marked-up summary to the requester.",
        },
      },
    ],
  },

  // ================================================= IT SERVICE DESK
  {
    id: "it-servicedesk",
    name: "IT service desk",
    icon: LifeBuoy,
    tagline: "Deflect the repetitive tickets — access, resets, how-tos — and triage the rest.",
    featured: true,
    recipes: [
      {
        id: "access-requests",
        title: "Self-serve access & password requests",
        scenario:
          "Half the ticket queue is 'reset my password' and 'give me access to X' — each handled manually by an engineer who'd rather be fixing real problems.",
        triggerLabel: "Slack / WhatsApp / Webhook (ticket created)",
        steps: [
          { kind: "trigger", title: "Request comes in", detail: "A Slack, WhatsApp or Webhook trigger catches the request." },
          { kind: "agent", title: "Understand + verify", detail: "An Agent node confirms identity and what's actually being asked." },
          { kind: "knowledge", title: "Check policy", detail: "A Knowledge node grounds the answer in your access policy / runbooks." },
          { kind: "approval", title: "Manager approves privileged access", detail: "An Approval node gates anything sensitive behind the right owner — routine resets can auto-proceed, privileged grants can't." },
          { kind: "tool", title: "Execute the change", detail: "A Tool (MCP) node performs the reset / grant in your IdP or ITSM." },
          { kind: "output", title: "Close the loop", detail: "An Output node confirms to the requester and logs it." },
        ],
        connectors: ["Slack", "WhatsApp", "Webhook", "Knowledge base", "Approval / HITL", "Tool (MCP → IdP/ITSM)"],
        outcome: "Common requests resolve in seconds with the right approvals; engineers keep the queue for real incidents.",
        impact: "Removes the highest-volume, lowest-value tickets while keeping privileged actions approval-gated and audited.",
        build: {
          agents: 1,
          agentsNote: "1 service-desk agent; the approval gate and the execute step are workflow nodes.",
          prompt:
            "You are an IT service-desk agent. Classify the request as {password_reset | group_access | software_access | other}, confirm the requester's identity, and check it against the access policy in Knowledge. Output JSON {type, target, requires_manager_approval: boolean, policy_citation}. Set requires_manager_approval=true for any privileged/admin/production access. Never reveal secrets, tokens, or another user's data.",
          mcp: ["Identity provider (Okta/Entra/Google) — via a Tool (MCP) node", "ITSM (Jira/ServiceNow) — via a Tool (MCP) node"],
          tools: ["knowledge_search (access policy + runbooks)"],
          guardrails: [
            "Least-privilege: privileged/prod access ALWAYS sets requires_manager_approval → Approval node before execution",
            "Identity verification before any change; routine self-service resets can auto-proceed, grants cannot",
            "Every action is logged with who-asked / who-approved / what-changed for audit",
          ],
          output:
            "Agent classifies + policy-checks → Condition auto-resolves routine resets, routes privileged grants to the manager Approval node → on approval a Tool node performs the change in the IdP/ITSM via MCP → Output confirms and logs it.",
        },
      },
      {
        id: "incident-triage",
        title: "Incident triage & first-response",
        scenario: "Alerts and tickets pile up unclassified; on-call wastes minutes just working out severity and owner.",
        triggerLabel: "Webhook (alert / new incident)",
        steps: [
          { kind: "trigger", title: "Alert fires", detail: "A Webhook trigger receives the alert/incident payload." },
          { kind: "agent", title: "Classify + summarize", detail: "An Agent node sets likely severity, component and a plain-English summary." },
          { kind: "knowledge", title: "Attach the runbook", detail: "A Knowledge node surfaces the matching runbook and recent similar incidents." },
          { kind: "output", title: "Page the right channel", detail: "An Output node posts to the correct Slack/on-call channel with everything on-call needs." },
        ],
        connectors: ["Webhook", "Knowledge base", "Slack"],
        outcome: "On-call opens a fully-triaged incident with the runbook attached, instead of a raw alert.",
        impact: "Compresses the manual 'what is this and who owns it' step that eats the first minutes of every incident.",
        build: {
          agents: 1,
          agentsNote: "1 triage agent; the runbook attach is Knowledge and the page-out is an Output node.",
          prompt:
            "You are an incident-triage agent. Given an alert/incident payload {source, message, metrics, service}, set a likely severity (SEV1–SEV4), the affected component and owning team, and write a plain-English summary of what's happening and its impact. Use Knowledge to attach the matching runbook and recent similar incidents. Base severity on the evidence in the payload — don't inflate or downplay. If it's ambiguous, say so and default to the more cautious severity so on-call can adjust.",
          mcp: ["Monitoring/alerting source — inbound via Webhook trigger", "Slack — on-call channel", "Incident tool (PagerDuty/Opsgenie) — via a Tool (MCP) node"],
          tools: ["knowledge_search (runbooks + incident history)"],
          guardrails: [
            "Severity is evidence-based; ambiguous cases default to the more cautious level for a human to adjust",
            "The agent triages and pages — it does not take remediation actions on systems",
            "Every triage posts the runbook + similar incidents so on-call starts informed",
          ],
          output:
            "The agent classifies severity/component/owner and attaches the runbook, then an Output node pages the correct Slack/on-call channel with the full triaged summary.",
        },
      },
    ],
  },

  // ========================================= MANUFACTURING & FIELD SERVICE
  {
    id: "manufacturing",
    name: "Manufacturing & field service",
    icon: Factory,
    tagline: "Turn machine signals and complaints into dispatched work — before things break.",
    recipes: [
      {
        id: "predictive-maintenance",
        title: "Sensor alert → maintenance work order",
        scenario:
          "A machine's readings drift out of range; today an operator notices late (or after a breakdown), then phones maintenance and fills a work order by hand.",
        triggerLabel: "Webhook (IoT/SCADA sensor threshold)",
        steps: [
          { kind: "trigger", title: "Sensor crosses a threshold", detail: "A Webhook trigger receives the telemetry event from your IoT/SCADA layer." },
          { kind: "condition", title: "Confirm it's real", detail: "A Condition node filters noise (sustained vs momentary) so you don't cry wolf." },
          { kind: "agent", title: "Diagnose + prioritize", detail: "An Agent node correlates the reading with the asset history and sets urgency." },
          { kind: "device", title: "Safe-state the equipment", detail: "A Device Control node can trigger a safe stop / mode change where that's wired up." },
          { kind: "output", title: "Dispatch the technician", detail: "An Output node raises the work order and messages the on-shift technician with the fault and part." },
        ],
        connectors: ["Webhook (IoT/SCADA)", "Device Control", "WhatsApp", "Tool (MCP → CMMS)"],
        outcome: "Drifting equipment is caught and dispatched automatically, often before failure.",
        impact: "Replaces late human noticing + manual work-order entry with an instant, prioritized dispatch — the core of predictive maintenance.",
        build: {
          agents: 1,
          agentsNote: "1 diagnosis agent; noise filtering is a Condition node and the safe-stop is a Device Control node.",
          prompt:
            "You are a maintenance-diagnosis agent. Given a confirmed sensor event {asset_id, metric, value, threshold, duration} and the asset's history, assess the likely fault, set urgency (low/medium/high/critical), and identify the probable part or check needed. Recommend whether the equipment should be safe-stopped. Base the diagnosis only on the telemetry and maintenance history provided — never fabricate a fault code or part number. Output {asset_id, likely_fault, urgency, recommended_action, part?}.",
          mcp: ["IoT/SCADA telemetry — inbound via Webhook trigger", "CMMS (work orders) — via a Tool (MCP) node", "WhatsApp — technician channel"],
          tools: ["knowledge_search (asset manuals + maintenance history)"],
          guardrails: [
            "The Condition node filters momentary noise — only sustained, confirmed threshold breaches reach the agent",
            "Safe-stop via the Device Control node only where that interlock is explicitly wired — otherwise it advises, never forces",
            "Diagnoses use real telemetry + history — no fabricated fault codes or parts",
          ],
          output:
            "On a confirmed breach, the agent diagnoses and prioritizes, a Device Control node can safe-state the equipment where wired, and an Output node raises the work order and messages the on-shift technician with the fault and part.",
        },
      },
      {
        id: "quality-complaint",
        title: "Field quality complaint → RCA intake",
        scenario: "Customer quality complaints arrive by call/email and are logged inconsistently, delaying root-cause analysis.",
        triggerLabel: "WhatsApp / Email / Call",
        steps: [
          { kind: "trigger", title: "Complaint received", detail: "A WhatsApp/Email/Call trigger captures it in the customer's words." },
          { kind: "agent", title: "Structure the report", detail: "An Agent node extracts product, batch, defect and severity into a standard RCA intake." },
          { kind: "tool", title: "Log to quality system", detail: "A Tool (MCP) node files it in your QMS and links the batch." },
          { kind: "approval", title: "Quality engineer triages", detail: "An Approval node routes to the right engineer for disposition." },
        ],
        connectors: ["WhatsApp", "Email", "Voice / Call", "Tool (MCP → QMS)", "Approval / HITL"],
        outcome: "Every complaint becomes a structured, batch-linked record the moment it arrives.",
        impact: "Removes inconsistent manual logging that slows recalls and root-cause analysis.",
        build: {
          agents: 1,
          agentsNote: "1 intake agent; the QMS write is a Tool node and the triage is an Approval node.",
          prompt:
            "You are a quality-complaint intake agent. From a customer complaint (voice or text, any language), extract a standard RCA intake {product, batch_or_lot, defect_description, severity, date_noticed, customer, quantity_affected}. Capture the customer's own description faithfully — don't diagnose the root cause yourself or assign blame. If the batch/lot is missing, ask for it. Flag safety-critical defects as high severity for immediate routing.",
          mcp: ["QMS (quality management system) — via a Tool (MCP) node", "WhatsApp / Email / Voice — intake channel", "Sarvam — Indian-language support"],
          tools: ["knowledge_search (product/batch catalog for validation)"],
          guardrails: [
            "The agent captures and structures — root-cause and disposition are decided by the quality engineer at the Approval node",
            "Safety-critical defects are flagged high-severity for immediate human routing",
            "Every complaint is logged to the QMS and linked to its batch/lot before triage",
          ],
          output:
            "The agent structures the complaint into a standard RCA record, a Tool node files it in the QMS linked to the batch, and the Approval node routes it to the right quality engineer for disposition.",
        },
      },
    ],
  },

  // ==================================================== INSURANCE
  {
    id: "insurance",
    name: "Insurance",
    icon: Umbrella,
    tagline: "Automate FNOL intake and document collection — adjusters decide, not chase.",
    recipes: [
      {
        id: "fnol-intake",
        title: "First-notice-of-loss (FNOL) intake & document collection",
        scenario:
          "A claimant calls or messages to report a loss; an agent manually captures details, then emails back and forth for photos and documents over days.",
        triggerLabel: "WhatsApp / Call / Webhook",
        steps: [
          { kind: "trigger", title: "Loss reported", detail: "A WhatsApp, Call or Webhook trigger opens the claim in the claimant's own words/language." },
          { kind: "agent", title: "Capture structured FNOL", detail: "An Agent node collects policy no., date, description and severity into a clean record." },
          { kind: "output", title: "Request exactly what's needed", detail: "An Output node asks for the specific photos/documents and accepts them right in the chat." },
          { kind: "condition", title: "Completeness check", detail: "A Condition node won't advance the claim until the mandatory documents are in." },
          { kind: "approval", title: "Adjuster reviews", detail: "An Approval node hands a complete, structured claim to the adjuster for the decision." },
          { kind: "tool", title: "Create the claim record", detail: "A Tool (MCP) node writes it into the core claims system." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Sarvam (multilingual)", "Approval / HITL", "Tool (MCP → claims)"],
        outcome: "Claims arrive complete and structured; adjusters spend time deciding, not collecting.",
        impact: "Removes the days of manual document chasing from the front of every claim, while the assessment stays with a licensed human.",
        build: {
          agents: 1,
          agentsNote: "1 FNOL intake agent; completeness is a Condition node and the assessment is an Approval node.",
          prompt:
            "You are a first-notice-of-loss (FNOL) intake agent. In the claimant's own language, capture a structured claim {policy_no, loss_date, loss_type, description, severity, location, injuries: boolean}. Then request exactly the documents/photos required for this loss type and accept them in the chat. Capture what the claimant reports — never assess fault, coverage, or claim value; that is the adjuster's decision. Verify the policy number format but don't approve or deny anything.",
          mcp: ["Core claims system — via a Tool (MCP) node", "WhatsApp / Voice — intake channel", "Sarvam — multilingual support"],
          tools: ["knowledge_search (required documents by loss type)", "file parsing (uploaded photos/documents)"],
          guardrails: [
            "The Condition node blocks the claim from advancing until all mandatory documents are in",
            "The agent captures only — coverage, fault and value are decided by a licensed adjuster at the Approval node",
            "The policy number is validated for format; no coverage determination is made or implied",
          ],
          output:
            "The agent captures a structured FNOL and collects the required documents in-chat; the Condition node enforces completeness, the adjuster reviews at the Approval node, and a Tool node writes the claim into the core claims system.",
        },
      },
    ],
  },

  // ========================================= PROFESSIONAL SERVICES / SALES OPS
  {
    id: "professional-services",
    name: "Professional services & sales ops",
    icon: Briefcase,
    tagline: "Draft proposals and keep the CRM clean without the manual busywork.",
    recipes: [
      {
        id: "rfp-draft",
        title: "RFP / proposal first draft from your own material",
        scenario:
          "Every RFP is answered by hand — a consultant hunts through old proposals and case studies, copies boilerplate, and rewrites the same answers for the tenth time.",
        triggerLabel: "Email / Webhook (RFP received)",
        steps: [
          { kind: "trigger", title: "RFP arrives", detail: "An Email/Webhook trigger receives the RFP document." },
          { kind: "knowledge", title: "Draw from your library", detail: "A Knowledge node holds past proposals, case studies and standard answers to ground the draft." },
          { kind: "agent", title: "Draft tailored responses", detail: "An Agent node answers each requirement in your voice, reusing what fits and flagging what needs input." },
          { kind: "approval", title: "Lead reviews before it goes out", detail: "An Approval node keeps the human as the author of record." },
          { kind: "output", title: "Return the draft", detail: "An Output node delivers the draft to the owner to finish." },
        ],
        connectors: ["Email", "Webhook", "Knowledge base", "Approval / HITL"],
        outcome: "A tailored first draft in minutes, grounded in your real material — the consultant edits instead of starting cold.",
        impact: "Removes the copy-hunt-rewrite cycle that makes proposals slow, while keeping a human owning the final response.",
        build: {
          agents: 1,
          agentsNote: "1 drafting agent; the lead's sign-off is an Approval node.",
          prompt:
            "You are a proposal-drafting agent for {{company}}. For each requirement in the RFP, draft a tailored response in the company's voice, reusing relevant past proposals, case studies and standard answers from Knowledge. Ground claims (credentials, references, metrics) in the library material — never invent a client, certification, or capability the firm doesn't have. Where a requirement needs a subject-matter input you can't source, flag it clearly as [NEEDS INPUT] instead of guessing.",
          mcp: ["RFP intake (email/upload) — via Email/Webhook trigger", "Proposal library / DMS — via a Tool (MCP) node"],
          tools: ["document parsing (RFP document)", "knowledge_search (past proposals, case studies, standard answers)"],
          guardrails: [
            "Claims are grounded in the library — no invented clients, certifications or capabilities",
            "Gaps are flagged [NEEDS INPUT] for a human, never bluffed",
            "The proposal lead reviews and owns the final draft at the Approval node before anything is sent",
          ],
          output:
            "The agent produces a tailored first draft grounded in your library with gaps flagged; the lead reviews it at the Approval node, and an Output node returns the draft to the owner to finish.",
        },
      },
      {
        id: "meeting-notes-crm",
        title: "Meeting notes → CRM, automatically",
        scenario: "Reps skip updating the CRM after calls; pipeline data rots and follow-ups slip.",
        triggerLabel: "Webhook (call/meeting ended)",
        steps: [
          { kind: "trigger", title: "Meeting ends", detail: "A Webhook trigger fires with the transcript/notes reference." },
          { kind: "agent", title: "Extract the CRM fields", detail: "An Agent node pulls next steps, stage, and action items." },
          { kind: "tool", title: "Update the opportunity", detail: "A Tool (MCP) node writes it straight to the CRM." },
          { kind: "output", title: "Send the recap", detail: "An Output node emails the client a clean summary + next steps." },
        ],
        connectors: ["Webhook", "Tool (MCP → CRM)", "Email"],
        outcome: "The CRM stays current and clients get a recap — with zero manual data entry from the rep.",
        impact: "Fixes the 'reps don't update the CRM' problem by removing the manual step entirely.",
        build: {
          agents: 1,
          agentsNote: "1 notes agent; the CRM update and recap send are workflow nodes.",
          prompt:
            "You are a sales-notes agent. From a meeting transcript, extract CRM updates {stage, next_steps[], action_items[{owner, task, due}], sentiment, key_points}. Capture only what was actually discussed — never fabricate a commitment, number, or next step that wasn't said. Then draft a clean, client-facing recap email summarizing decisions and next steps. Keep the recap professional and concise.",
          mcp: ["CRM (Salesforce/HubSpot/etc.) — via a Tool (MCP) node", "Meeting/transcript source — inbound via Webhook trigger", "Email — recap channel"],
          tools: ["http_request (fetch transcript by reference)"],
          guardrails: [
            "Only what was actually discussed is recorded — no fabricated commitments or figures",
            "The CRM write is keyed to the opportunity/meeting id — idempotent, no duplicate updates",
            "The client recap restates only agreed next steps — nothing promised that wasn't discussed",
          ],
          output:
            "The agent extracts the CRM fields and a Tool node writes them to the opportunity; an Output node emails the client a clean recap of decisions and next steps.",
        },
      },
    ],
  },

  // =============================================== AGRICULTURE / AGRITECH
  {
    id: "agritech",
    name: "Agriculture & agritech",
    icon: Sprout,
    tagline: "Multilingual advisories and procurement updates for farmers, on WhatsApp and voice.",
    recipes: [
      {
        id: "farmer-advisory",
        title: "Weather / advisory alerts in the farmer's language",
        scenario:
          "Agronomy advice and mandi/price updates are pushed manually, in one language, and rarely reach smallholders on time.",
        triggerLabel: "Webhook (weather/price feed) — scheduled",
        steps: [
          { kind: "trigger", title: "Advisory event", detail: "A scheduled Webhook trigger fires on a weather change or price update for a region/crop." },
          { kind: "agent", title: "Turn data into plain advice", detail: "An Agent node converts the raw feed into a specific, actionable tip for that crop and stage." },
          { kind: "output", title: "Reach farmers in-language", detail: "An Output node delivers it on WhatsApp or a voice call, in the local language (Sarvam covers 22+)." },
          { kind: "condition", title: "Escalate real problems", detail: "A Condition node routes pest/disease reports to a human agronomist." },
        ],
        connectors: ["Webhook", "WhatsApp", "Voice / Call", "Sarvam (translate and speech tools)", "Knowledge base"],
        outcome: "Timely, crop-specific advice reaches every farmer in a language they speak — by message or voice.",
        impact: "Replaces slow, one-language manual outreach with instant multilingual delivery at the moment it's useful.",
        build: {
          agents: 1,
          agentsNote: "1 advisory agent; the agronomist escalation is a workflow Condition node.",
          prompt:
            "You are an agri-advisory agent. Given a weather or price event for a region and crop {crop, stage, region, event_type, values}, convert the raw feed into one specific, actionable tip for that crop at its current stage — e.g. irrigation timing, a spray window, or a mandi price movement worth acting on. Deliver it in the farmer's local language (Sarvam covers 22+). Never give unsafe pesticide or dosage instructions beyond the label guidance in Knowledge; for suspected pest/disease outbreaks, route to a human agronomist.",
          mcp: ["Weather/mandi price feed — inbound via Webhook trigger", "WhatsApp / Voice — farmer channel", "Sarvam — 22+ Indian languages"],
          tools: ["knowledge_search (crop calendars, label-safe practices)"],
          guardrails: [
            "No unsafe dosage or pesticide instructions — chemical guidance stays within the label limits in Knowledge",
            "Pest/disease reports route to a human agronomist via the Condition node",
            "Advice is specific to the crop and growth stage, delivered in the farmer's own language",
          ],
          output:
            "The agent turns the raw feed into a crop- and stage-specific tip delivered on WhatsApp or a voice call in the local language, while the Condition node routes pest/disease problems to a human agronomist.",
        },
      },
    ],
  },
];
