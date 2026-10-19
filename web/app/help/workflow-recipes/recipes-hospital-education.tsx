import type { Industry } from "./recipes-data";
import { Hospital, School, GraduationCap } from "lucide-react";

// ---------------------------------------------------------------------------
// Additional industry workflow recipes — Hospitals & health systems, K-12
// schools, and Colleges & universities. Same rules as recipes-data.tsx: every
// recipe is expressed ONLY in VegaDūta's real building blocks (Trigger, Agent,
// Knowledge, Condition, Approval, Output, Tool/MCP, Voice, Device, Loop, Code,
// Parallel), on channels/connectors that actually exist (Webhook, WhatsApp,
// SMS, Voice/Call, Email, plus ElevenLabs, Sarvam for 22+ Indian languages,
// Razorpay for UPI, and a grounded Knowledge base).
//
// Honesty rule (verbatim intent): systems VegaDūta does NOT ship a first-party
// connector for (HIS/EHR, LIS/lab systems, SIS/school ERP, fee/LMS platforms)
// are reached the real way — an inbound Webhook trigger and/or a Tool (MCP)
// node — and are labelled "(MCP)" / "via Webhook", never implying native
// integration. Every clinical, eligibility or financial decision passes an
// Approval step — the agent never diagnoses and never rules on money or
// eligibility on its own. Impact lines describe the mechanism (why it helps),
// never invented ROI numbers.
// ---------------------------------------------------------------------------

export const INDUSTRIES_INSTITUTIONS: Industry[] = [
  // ================================================================ HOSPITAL
  {
    id: "hospital",
    name: "Hospitals & health systems",
    icon: Hospital,
    tagline: "Automate OPD scheduling, discharge follow-up and multilingual patient helpdesk — with clinicians deciding anything clinical.",
    recipes: [
      {
        id: "hospital-opd",
        title: "OPD appointment booking with pre-visit instructions",
        scenario:
          "Patients call the front desk to book an OPD slot, and staff manually check the doctor's calendar, book the visit, then read out fasting/document instructions — a queue that only moves in office hours and only in one language.",
        triggerLabel: "WhatsApp / Call (patient booking request)",
        steps: [
          { kind: "trigger", title: "Patient requests a slot", detail: "A WhatsApp or Call trigger starts on any inbound booking request, in whatever language the patient speaks." },
          { kind: "agent", title: "Understand the need + find a slot", detail: "An Agent node captures the department, doctor and preferred time, working in the patient's language via Sarvam." },
          { kind: "tool", title: "Book in the HIS", detail: "A Tool (MCP) node reserves the slot in your HIS / scheduling system. There's no first-party connector — the booking is pushed via MCP (or queued to the front desk)." },
          { kind: "knowledge", title: "Attach the right prep instructions", detail: "A Knowledge node pulls the correct pre-visit instructions (fasting, documents, arrival time) from your hospital's own protocol documents — grounded, with citations, never invented." },
          { kind: "output", title: "Confirm + remind", detail: "An Output node sends the confirmed slot and prep checklist on WhatsApp/SMS, then a day-before reminder to cut no-shows." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Sarvam (translate and speech tools)", "Knowledge base", "SMS", "Tool (MCP → HIS/scheduler)"],
        outcome: "Patients book an OPD slot and receive the exact pre-visit prep in their own language, day or night, without tying up the front desk.",
        impact: "Replaces the manual calendar-check and read-out-the-instructions call with an always-on, multilingual booking flow — grounded prep comes from your own protocols, not a guess.",
        build: {
          agents: 1,
          agentsNote: "1 booking agent; the HIS booking, the prep-instruction Knowledge pull and the reminders are workflow nodes.",
          prompt:
            "You are an OPD booking assistant for {{hospital_name}}. Working in the patient's language, capture the department, doctor and preferred time and confirm the details. Offer only slots the scheduling system returns as real. Attach pre-visit instructions ONLY from the hospital's own protocol documents in Knowledge, with citations. You do NOT give medical advice, interpret symptoms, triage urgency, or suggest which doctor is clinically right — if the patient describes an emergency, tell them to call emergency services or go to the ER. Don't collect clinical history beyond what booking needs.",
          mcp: ["HIS / scheduling system — via a Tool (MCP) node (no first-party connector)", "WhatsApp + Telephony — via the WhatsApp/Voice channels", "Sarvam — 22+ Indian languages"],
          tools: ["knowledge_search (pre-visit protocol documents)", "http_request (check live slot availability)"],
          guardrails: [
            "No clinical advice, symptom interpretation, or urgency triage — an emergency is redirected to emergency services, never assessed by the agent",
            "Prep instructions come only from the hospital's own protocol documents with a citation — never invented",
            "Only books slots the HIS confirms as available (anything it can't book is queued to the front desk), and only booking-necessary details are collected",
          ],
          output:
            "The agent captures the request in the patient's language → a Tool node reserves the slot in the HIS via MCP (or queues it to the front desk) → a Knowledge node attaches the grounded prep checklist → an Output node sends the confirmation and a day-before reminder on WhatsApp/SMS.",
        },
      },
      {
        id: "hospital-discharge-followup",
        title: "Discharge follow-up & medication-adherence check-ins",
        scenario:
          "After discharge, a nurse is supposed to call each patient to check they're taking medication and recovering — but at ward volume most calls never happen, and warning signs are missed until a readmission.",
        triggerLabel: "Webhook (discharge event) — scheduled check-ins",
        steps: [
          { kind: "trigger", title: "Patient is discharged", detail: "A Webhook trigger (from your HIS/EHR) opens a follow-up plan the moment discharge is recorded — no first-party connector, the event arrives via Webhook." },
          { kind: "loop", title: "Scheduled check-ins over recovery window", detail: "A Loop node paces check-ins across the recovery period (e.g. day 2, day 7, day 14) rather than a single message." },
          { kind: "agent", title: "Ask about meds + recovery", detail: "An Agent node checks medication adherence and recovery against the discharge plan, in the patient's language — it asks and listens, it does not diagnose." },
          { kind: "condition", title: "Flag anything concerning", detail: "A Condition node detects reported red-flag symptoms, missed doses or distress and branches those cases out of the routine track." },
          { kind: "approval", title: "Clinician reviews flagged cases", detail: "An Approval node routes any clinical concern to a nurse or doctor for the judgement call — the agent never assesses or advises on the condition itself." },
          { kind: "output", title: "Log + reassure routine cases", detail: "An Output node records the check-in against the patient and sends routine adherence reminders on WhatsApp/SMS." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS", "Sarvam (multilingual)", "Approval / HITL", "Tool (MCP → HIS/EHR)"],
        outcome: "Every discharged patient gets consistent, in-language check-ins and any warning sign reaches a clinician fast — instead of follow-up calls that skip most of the ward.",
        impact: "Removes the manual per-patient follow-up that doesn't scale, while every clinical judgement stays with a licensed human — the agent surfaces, the clinician decides.",
        build: {
          agents: 1,
          agentsNote: "1 check-in agent; the paced Loop, the red-flag Condition and the clinician Approval are workflow nodes.",
          prompt:
            "You are a post-discharge check-in assistant. In the patient's language, ask about medication adherence and recovery against their discharge plan, and record their answers as {doses_taken, symptoms_reported[], concerns, needs_clinician: boolean}. You ASK and LISTEN — you do NOT diagnose, interpret symptoms, change a dose, or advise on the condition. Set needs_clinician=true and stop the routine track on any reported red-flag symptom, missed-dose pattern, or distress. If the patient describes an emergency, tell them to call emergency services or return to hospital immediately.",
          mcp: ["HIS / EHR — discharge event inbound via Webhook, records written via a Tool (MCP) node (no first-party connector)", "WhatsApp + SMS — via the messaging channels", "Sarvam — multilingual"],
          tools: ["knowledge_search (discharge plan + red-flag criteria)", "transfer_to_human"],
          guardrails: [
            "Never diagnoses, advises on the condition, or changes medication — it asks, listens, and records; every clinical judgement is the clinician's at the Approval node",
            "Any red-flag symptom, missed-dose pattern, or distress branches out of the routine track to a clinician; an emergency is redirected to emergency services",
            "Check-ins are logged against the patient record; clinical detail is handled as confidential and not shared beyond the care flow",
          ],
          output:
            "A Loop node paces check-ins across the recovery window → the agent asks and records adherence/recovery in the patient's language → the Condition node flags anything concerning → a clinician reviews flagged cases at the Approval node, while routine cases are logged and get adherence reminders on WhatsApp/SMS.",
        },
      },
    ],
  },

  // ============================================================== K-12 SCHOOLS
  {
    id: "k12-schools",
    name: "Schools (K-12)",
    icon: School,
    tagline: "Automate admission enquiries, fee reminders, attendance alerts and parent communication — the front-office chasing, gone.",
    recipes: [
      {
        id: "k12-admissions",
        title: "Admission enquiry handling with grounded answers",
        scenario:
          "Parents ask the same admission questions — age criteria, documents, fee structure, dates — by call and WhatsApp at all hours, and the front office answers each one by hand from memory, often inconsistently.",
        triggerLabel: "Webhook (enquiry form) or WhatsApp",
        steps: [
          { kind: "trigger", title: "Enquiry arrives", detail: "A Webhook (from the school's enquiry form) or a WhatsApp message starts the flow, in the parent's language." },
          { kind: "knowledge", title: "Answer from the prospectus", detail: "A Knowledge node answers eligibility, documents, fees and dates strictly from the school's own admission documents — grounded, with citations, no guessing." },
          { kind: "agent", title: "Guide + capture interest", detail: "An Agent node keeps the conversation going, collects the child's grade and details, and notes genuine intent." },
          { kind: "output", title: "Send next steps + book a visit", detail: "An Output node shares the form/checklist and offers school-visit or counsellor-call slots on WhatsApp." },
          { kind: "tool", title: "Log the enquiry", detail: "A Tool (MCP) node records the lead in your school SIS/ERP. There's no first-party connector — it's written via MCP." },
        ],
        connectors: ["Webhook", "WhatsApp", "Sarvam (translate and speech tools)", "Knowledge base", "Tool (MCP → SIS/ERP)"],
        outcome: "Every admission enquiry gets accurate, consistent answers instantly in the parent's language, with genuine interest captured for the office to follow up.",
        impact: "Replaces inconsistent from-memory answers and after-hours silence with grounded responses from the school's own prospectus — the front office only handles the warm leads.",
        build: {
          agents: 1,
          agentsNote: "1 admissions agent; the grounded Q&A and the SIS/ERP lead-logging are workflow nodes.",
          prompt:
            "You are an admissions enquiry assistant for {{school_name}}. Answer age criteria, documents, fee structure and dates STRICTLY from the school's admission documents in Knowledge, with citations — never quote a fee, date, or criterion you can't cite, and never guess. In the parent's language, keep the conversation going, collect the child's grade and the parent's details, and note genuine interest. You do not decide or promise admission; you share the process and offer a visit or counsellor call.",
          mcp: ["School SIS / ERP — via a Tool (MCP) node (no first-party connector)", "WhatsApp — via the messaging channel", "Sarvam — 22+ Indian languages"],
          tools: ["knowledge_search (prospectus + admission criteria)"],
          guardrails: [
            "Grounded-only: fees, dates, eligibility and documents come from the admission documents with a citation, never invented",
            "Never decides or promises admission — it explains the process and captures interest for the office to follow up",
            "Only the details needed to follow up are collected; a child's information is handled with care, logged to the enquiry, and not shared",
          ],
          output:
            "The agent answers from the prospectus Knowledge in the parent's language and captures interest → an Output node shares the form/checklist and offers visit or counsellor-call slots on WhatsApp → a Tool node logs the lead in the school SIS/ERP via MCP.",
        },
      },
      {
        id: "k12-fees",
        title: "Fee-due reminders and daily absence alerts to parents",
        scenario:
          "Fee reminders go out late and unevenly, and when a child is absent a parent may not hear until the day ends — both chased manually by staff who can't reach everyone.",
        triggerLabel: "Webhook (scheduled fee-aging & daily attendance)",
        steps: [
          { kind: "trigger", title: "Daily fee & attendance run", detail: "A scheduled Webhook trigger evaluates due fees and the day's attendance from your SIS/ERP each morning — the data arrives via Webhook, there's no native connector." },
          { kind: "parallel", title: "Fee track and absence track at once", detail: "A Parallel node fans out to the fee-reminder path and the absence-alert path simultaneously." },
          { kind: "loop", title: "Per due account / absent student", detail: "A Loop node iterates the overdue-fee list and the absentee list to message each parent individually." },
          { kind: "agent", title: "Draft the right message", detail: "An Agent node writes a clear fee reminder (with the exact amount and a Razorpay UPI pay link) or a same-day absence alert, in the parent's language." },
          { kind: "output", title: "Notify parents", detail: "An Output node sends each message on WhatsApp/SMS on the channel the parent reads." },
        ],
        connectors: ["Webhook", "WhatsApp", "SMS", "Razorpay UPI", "Sarvam (multilingual)", "Tool (MCP → SIS/ERP)"],
        outcome: "Fees are reminded promptly with a one-tap UPI link and parents hear about an absence the same morning — without staff phoning down a list.",
        impact: "Turns two manual, uneven chases into consistent same-day outreach; the UPI link makes paying frictionless and every parent gets reached, not just the ones staff had time for.",
        build: {
          agents: 1,
          agentsNote: "1 messaging agent; the parallel fee/absence split and the per-parent Loop are workflow nodes.",
          prompt:
            "You are a school parent-communication assistant. Depending on the record, write either a fee reminder stating the EXACT outstanding amount and due date with a Razorpay UPI pay link, or a same-day absence alert naming the student and date — in the parent's language. Use only the amounts, dates and attendance the SIS run supplies; never invent, estimate, or round a fee figure. Keep messages factual and courteous, and address only the correct parent for that student.",
          mcp: ["School SIS / ERP — fee-aging and attendance fed inbound via Webhook (no first-party connector)", "Razorpay UPI — pay-link for fees", "WhatsApp + SMS — via the messaging channels", "Sarvam — multilingual"],
          tools: ["http_request (confirm current fee balance before sending)"],
          guardrails: [
            "Fee amounts and dates come only from the SIS run — never invented, estimated, or rounded",
            "Each message goes only to the correct parent for that student; a child's fee/attendance status isn't disclosed to anyone else",
            "Payment is collected only through the Razorpay UPI link — the agent never handles card or account details",
          ],
          output:
            "A Parallel node runs the fee and absence tracks at once → a Loop node iterates due accounts and absentees → the agent drafts the exact-amount fee reminder (with UPI link) or the same-day absence alert in the parent's language → an Output node sends each on WhatsApp/SMS.",
        },
      },
    ],
  },

  // =========================================================== HIGHER EDUCATION
  {
    id: "higher-ed",
    name: "Colleges & universities",
    icon: GraduationCap,
    tagline: "Automate application-status Q&A, financial-aid guidance and student-services helpdesk — eligibility and money calls stay human.",
    recipes: [
      {
        id: "college-admissions",
        title: "Application status Q&A and scholarship guidance",
        scenario:
          "Applicants flood the admissions office asking where their application stands and whether they qualify for a scholarship, and staff look each one up by hand and repeat the same eligibility rules dozens of times a day.",
        triggerLabel: "WhatsApp / Webhook (applicant enquiry)",
        steps: [
          { kind: "trigger", title: "Applicant asks", detail: "A WhatsApp or Webhook trigger starts on any status or scholarship question, in the applicant's language." },
          { kind: "agent", title: "Identify the applicant", detail: "An Agent node verifies identity and the application reference before anything is looked up." },
          { kind: "tool", title: "Fetch live application status", detail: "A Tool (MCP) node reads the current stage from your SIS/admissions system. There's no first-party connector — the status is fetched via MCP." },
          { kind: "knowledge", title: "Explain scholarship criteria", detail: "A Knowledge node answers financial-aid and scholarship questions strictly from the official criteria documents — grounded, with citations." },
          { kind: "approval", title: "Eligibility rulings go to an officer", detail: "An Approval node routes any actual eligibility or award decision to a financial-aid officer — the agent explains the rules, a human decides who qualifies." },
          { kind: "output", title: "Reply + record", detail: "An Output node sends the status and grounded guidance on WhatsApp and logs the interaction." },
        ],
        connectors: ["WhatsApp", "Webhook", "Sarvam (translate and speech tools)", "Knowledge base", "Approval / HITL", "Tool (MCP → SIS/admissions)"],
        outcome: "Applicants get their real status and accurate scholarship guidance instantly, while any eligibility or award decision stays with a human officer.",
        impact: "Removes the manual per-applicant lookup and the hundredth repeat of the same criteria, while keeping every money/eligibility call with a person — the agent informs, it never rules.",
        build: {
          agents: 1,
          agentsNote: "1 applicant-facing agent; the status lookup, the eligibility Approval and the reply/log are workflow nodes.",
          prompt:
            "You are an admissions assistant for {{institution_name}}. Verify the applicant's identity and application reference before looking anything up. Report the application status the system returns, and answer scholarship and financial-aid questions STRICTLY from the official criteria documents in Knowledge, with citations. You EXPLAIN the criteria — you do NOT decide eligibility, confirm an award, or tell an applicant they qualify; any actual eligibility or award decision is a financial-aid officer's at the approval step. Reply in the applicant's language and never reveal another applicant's data.",
          mcp: ["SIS / admissions system — via a Tool (MCP) node (no first-party connector)", "WhatsApp — via the messaging channel", "Sarvam — 22+ Indian languages"],
          tools: ["knowledge_search (scholarship + financial-aid criteria)"],
          guardrails: [
            "Never rules on eligibility or confirms an award — it explains the criteria; the financial-aid officer decides who qualifies at the Approval node",
            "Identity + application reference are verified before any status lookup; an applicant only ever sees their own data",
            "Scholarship guidance is grounded in the official criteria with a citation, never an assumed or invented figure",
          ],
          output:
            "The agent verifies identity → a Tool node reads the live application stage from the SIS via MCP → a Knowledge node grounds scholarship guidance → any eligibility/award call goes to the financial-aid officer at the Approval node → an Output node sends the status and guidance on WhatsApp and logs the interaction.",
        },
      },
      {
        id: "college-student-services",
        title: "Student-services helpdesk (hostel, exams, transcripts)",
        scenario:
          "Students queue at counters and flood inboxes over hostel, exam and transcript queries, and staff answer the same routine questions by hand while genuine exceptions wait behind them.",
        triggerLabel: "WhatsApp / Webhook (student query)",
        steps: [
          { kind: "trigger", title: "Student query comes in", detail: "A WhatsApp or Webhook trigger catches the request, in the student's language." },
          { kind: "knowledge", title: "Answer from the handbook", detail: "A Knowledge node grounds routine answers (hostel rules, exam schedule, transcript process, deadlines) in the university's own handbook and circulars — with citations." },
          { kind: "agent", title: "Handle or raise the request", detail: "An Agent node resolves routine questions directly and, for actionable requests like a transcript, structures the request cleanly." },
          { kind: "condition", title: "Separate routine from exceptions", detail: "A Condition node splits self-serve answers from anything needing staff judgement (fee disputes, special cases)." },
          { kind: "tool", title: "Raise the request in the system", detail: "A Tool (MCP) node files a transcript or hostel request in your student system for processing." },
          { kind: "output", title: "Confirm + track", detail: "An Output node confirms what's happening and the expected timeline on WhatsApp." },
        ],
        connectors: ["WhatsApp", "Webhook", "Sarvam (multilingual)", "Knowledge base", "Tool (MCP → student system)"],
        outcome: "Routine hostel/exam/transcript questions answer themselves from the handbook and actionable requests get logged, so staff spend their time on real exceptions.",
        impact: "Deflects the high-volume, repetitive counter and inbox questions with grounded answers, while genuine exceptions reach a human faster because the routine load is gone.",
        build: {
          agents: 1,
          agentsNote: "1 helpdesk agent; the routine/exception split and the request-raising Tool call are workflow nodes.",
          prompt:
            "You are a student-services helpdesk assistant for {{institution_name}}. Answer routine hostel, exam, transcript and deadline questions STRICTLY from the university's handbook and circulars in Knowledge, with citations. For an actionable request like a transcript or hostel change, structure it cleanly as {student, request_type, details} for the system. Anything needing staff judgement — a fee dispute, a special case, an exception to policy — you do NOT decide; route it to staff. Reply in the student's language and only ever access the requesting student's own records.",
          mcp: ["Student information system — via a Tool (MCP) node (no first-party connector)", "WhatsApp — via the messaging channel", "Sarvam — multilingual"],
          tools: ["knowledge_search (student handbook + circulars)"],
          guardrails: [
            "Grounded-only: routine answers come from the handbook/circulars with a citation, never a guess",
            "Fee disputes, special cases and any policy exception are never decided by the agent — they route to staff judgement",
            "A student only ever sees their own records; a raised request is logged and tracked, not actioned beyond filing it",
          ],
          output:
            "The agent answers routine questions from the handbook Knowledge → the Condition node separates self-serve answers from exceptions → a Tool node files a transcript/hostel request in the student system via MCP → an Output node confirms what's happening and the expected timeline on WhatsApp.",
        },
      },
    ],
  },
];
