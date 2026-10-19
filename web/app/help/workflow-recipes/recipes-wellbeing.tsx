import type { Industry } from "./recipes-data";
import { Brain, HandHeart } from "lucide-react";

// ---------------------------------------------------------------------------
// Mental wellbeing & healthy routines / Caring for someone & support circles.
// Same "inline copy, no i18n keys" precedent and honesty rules as
// recipes-data.tsx: real building blocks only (Trigger / Agent / Knowledge /
// Condition / Approval / Output / Tool(MCP) / Voice / Device / Loop / Code /
// Parallel / Supervisor / Sub-workflow), real channels only (Webhook, WhatsApp,
// SMS/Twilio, Voice/Call, Email, Telegram, ElevenLabs, Sarvam), and anything
// without a first-party connector — a clinic system, an EHR, a booking portal —
// arrives as an inbound Webhook and/or a Tool (MCP) node, LABELLED as such.
//
// READ THIS BEFORE EDITING THIS FILE.
//
// VegaDūta is NOT a therapist, NOT a counsellor and NOT a medical device, and
// nothing in this section may be written as if it were. These recipes are
// logistics and reflection aids: reminders, organisation, journalling prompts
// the user wrote for themselves, and preparation for real appointments with
// real professionals. They support professional care; they never replace it and
// must never delay it.
//
// Hard lines, enforced on every recipe below:
//   - No diagnosis, no therapy, no counselling, no advice on medication or
//     dosage — ever. No assessment or interpretation of anyone's mental state,
//     and never an analysis of a THIRD party's mental health from someone
//     else's description.
//   - Nothing produced here is a clinical assessment, and the user-facing copy
//     (outcome / impact) says so, not just the guardrails.
//   - Crisis wording is never counselled, de-escalated or graded — it is routed
//     immediately to official help and to a real human the user chose in
//     advance. That path is never behind an Approval node and never rate- or
//     cost-limited.
//   - Crisis helpline numbers are NEVER hardcoded here or in any prompt. They
//     live in the tenant's own Knowledge base, sourced from the region's
//     official current publication, because numbers differ by country and
//     change over time — a wrong number is worse than no number.
//   - This is among the most sensitive data a person has: it stays in the
//     user's own tenant, is never used for marketing, and is never shared with
//     anyone without an explicit Approval step. Never monitor another adult:
//     a support workflow requires the supported person's own agreement.
//   - No invented statistics, prevalence figures, efficacy claims or ROI. The
//     impact lines describe the mechanism only.
// ---------------------------------------------------------------------------

export const INDUSTRIES_WELLBEING: Industry[] = [
  // ============================================ MENTAL WELLBEING & ROUTINES
  {
    id: "wellbeing",
    name: "Mental wellbeing & healthy routines",
    icon: Brain,
    tagline:
      "Reminders, your own journalling prompts, and better-prepared appointments — a logistics aid that supports professional care and never pretends to be it.",
    recipes: [
      {
        id: "wb-evening-reflection",
        title: "End-of-day reflection, in your own words, kept to yourself",
        scenario:
          "You jot a line about how the day went into a notes app, or into a chat with yourself, and then never look at it again. The prompts you meant to answer are in a book on a shelf; the app you downloaded wants a subscription and a mood score out of ten. By the time you next see a professional, three weeks have blurred together and you can't remember what you wanted to say.",
        triggerLabel: "Scheduled (evening) → WhatsApp / Telegram / SMS",
        steps: [
          {
            kind: "trigger",
            title: "A scheduled nudge, at a time you set",
            detail:
              "A scheduled trigger fires on the days and at the hour you chose, on the channel you chose (WhatsApp, Telegram or SMS). You can pause or stop it from the same thread — this is a reminder you own, not a streak you owe anyone.",
          },
          {
            kind: "knowledge",
            title: "Your own prompt set",
            detail:
              "A Knowledge node holds the reflection prompts YOU wrote or pasted in — for example the homework a therapist actually gave you. The agent asks your prompts. It does not generate therapeutic exercises of its own.",
          },
          {
            kind: "agent",
            title: "Asks the prompts, records the answer verbatim",
            detail:
              "An Agent node asks one or two of your prompts in plain language and writes your reply down as you said it. It does not score, rate, interpret or reply with an assessment of how you seem — it is taking dictation for you, nothing more.",
          },
          {
            kind: "condition",
            title: "Crisis wording → hand off immediately",
            detail:
              "A Condition node checks for wording that suggests risk of self-harm, suicide or abuse. On any match it stops the reflection flow at once and hands to the crisis sub-workflow. It never continues the journalling conversation and never tries to talk you through it.",
          },
          {
            kind: "subworkflow",
            title: "Crisis path (see the escalation recipe)",
            detail:
              "A Sub-workflow node calls the safe-escalation route. That path is never gated by an Approval node and never rate- or cost-limited — it always runs.",
          },
          {
            kind: "output",
            title: "Filed privately, in your tenant",
            detail:
              "An Output node stores the entry in your own tenant, visible only to you. Nothing is shared with a family member, a clinician or anyone else unless you send it yourself through an explicit Approval step.",
          },
        ],
        connectors: ["WhatsApp", "Telegram", "SMS / Twilio", "Knowledge base", "Sarvam (Indian languages)"],
        outcome:
          "A short, private record in your own words that you can actually find later and take to an appointment. It is a diary, not a clinical assessment, and no part of it is a diagnosis or an opinion about your mental health.",
        impact:
          "The mechanism is memory and friction, not treatment: the prompt arrives on a channel you already read, the answer is captured before the day blurs, and it is stored where you can retrieve it. Whether that is useful to you is between you and a professional.",
        build: {
          agents: 1,
          agentsNote:
            "1 reflection agent that asks your prompts and records the answer. The crisis check is a Condition node; the escalation is a Sub-workflow node; the schedule and the private write are workflow nodes.",
          pattern: "Scheduled prompt → verbatim capture → hard crisis branch → private store",
          prompt:
            "You help {{user}} keep a private end-of-day note. Ask ONE or TWO of the reflection prompts stored in Knowledge — these are the user's own prompts; never invent a therapeutic exercise, never substitute your own. Record the answer VERBATIM in the field 'entry'. You must NOT diagnose, interpret, score, rate, or comment on the user's mental state, mood or progress. Do not analyse anyone else the user mentions. Do not discuss medication, dosage or whether to take anything. If the user asks you for therapy, counselling or a clinical opinion, say plainly that you cannot give one, that you are a note-keeping tool, and that a professional is the right place for it. If any wording suggests risk of self-harm, suicide or abuse, STOP: do not counsel, do not reassure, do not ask follow-up questions to gauge how serious it is — hand straight to the crisis sub-workflow.",
          agentRoles: [
            {
              role: "Reflection scribe (once per evening)",
              model: "Economy tier — this is a short, structured capture, not a reasoning task",
              prompt:
                "Ask the user's own stored prompts; write the reply down word for word; add no interpretation, no summary judgement, no score. Never diagnose, never counsel, never mention medication. On any crisis wording, stop and hand off.",
            },
          ],
          mcp: [
            "None. This runs on first-party channels only (WhatsApp / Telegram / SMS) and the tenant's own Knowledge base — a private journal is not a good place to add third-party systems.",
          ],
          tools: [
            "knowledge_search (the user's own reflection prompts, and nothing generated by the platform)",
          ],
          guardrails: [
            "NOT A THERAPY PRODUCT: the agent does not diagnose, does not provide therapy or counselling, and gives no advice on medication or dosage — ever, under any phrasing of the request",
            "It does not assess or interpret the user's mental state, assign a mood score, or judge progress; and it never analyses a THIRD party's mental health from the user's description of them",
            "It is a logistics and reflection aid — reminders, organisation, journalling prompts the user chose, and preparation for real appointments. It supports professional care; it never replaces it and must never be used to delay it",
            "Nothing it produces is a clinical assessment, and the entry is labelled as a personal note wherever it is shown",
            "Crisis wording is never counselled, de-escalated or graded here — the Condition node hands straight to the crisis sub-workflow, which is never behind an Approval node and never rate- or cost-limited",
            "PRIVACY: this is among the most sensitive data a person has. Entries stay in the user's own tenant, are never used for marketing or model training, and are never shared with a family member or clinician without an explicit Approval step the user takes themselves",
            "CONSENT: the schedule is the user's own and can be paused or deleted from the same thread. No other adult is monitored, notified or reported to by this workflow",
          ],
          cost:
            "Economy tier is the right and honest choice: one short call per evening, no batch, no reasoning burden. Deliberately keep the crisis Condition node OUTSIDE any cost or rate cap — the escalation path is the one place where budget controls must not apply.",
          output:
            "The scheduled trigger sends your own prompts on your channel → the agent records your answer verbatim → the Condition node hands any crisis wording straight to the escalation sub-workflow → an Output node files the entry privately in your tenant, shareable only by you, through an explicit Approval step.",
        },
      },

      {
        id: "wb-routines",
        title: "Sleep, hydration, movement and medication REMINDERS (reminder only)",
        scenario:
          "The pharmacist's advice is on a folded slip in a drawer. The physio's exercises were done twice. You set three phone alarms, learned to swipe them away without reading them, and turned them off in week two. Nobody is tracking whether the water bottle got refilled, and the tablet you take at night is remembered or not depending on how the evening went.",
        triggerLabel: "Scheduled (per routine) → WhatsApp / SMS / Voice",
        steps: [
          {
            kind: "trigger",
            title: "Each routine on its own schedule",
            detail:
              "Scheduled triggers fire per routine — a wind-down nudge, a water reminder, the physio set, the evening tablet reminder — each at the time you set, on the channel you picked.",
          },
          {
            kind: "knowledge",
            title: "Your reminder text, exactly as you entered it",
            detail:
              "A Knowledge node holds the reminder wording YOU entered (or copied from a prescription label or a physio sheet). The agent repeats that text. It never composes medical instructions and never restates a dose in its own words.",
          },
          {
            kind: "voice",
            title: "Optional voice or local-language delivery",
            detail:
              "For a reminder that gets missed as text, an ElevenLabs voice call or a Sarvam-rendered message in your own language can carry it instead. Same words, different channel — no added advice.",
          },
          {
            kind: "tool",
            title: "User-defined tool: check-in streak band (neutral)",
            detail:
              "A tenant-authored user-defined tool — a SpEL expression over the tool's JSON input, e.g. checkin_streak_band: input.daysLogged >= 7 ? 'STEADY' : 'PATCHY'. It is a data-transform/formula tool, sandboxed by construction (no method calls, no constructors, no bean or type references, no loop in the grammar) — not a scripting engine. It counts logged days. It must NEVER be used to score severity, risk or symptoms.",
          },
          {
            kind: "condition",
            title: "A medication QUESTION is not a reminder",
            detail:
              "A Condition node catches anything that is a question rather than a tick — 'should I double up?', 'can I skip tonight?', 'is this dose right?'. The agent answers none of them. It says so plainly and points to the prescriber or pharmacist.",
          },
          {
            kind: "output",
            title: "A tick, and a private log you can read back",
            detail:
              "An Output node records a simple done / not-done against the routine, in your own tenant. That log is yours to read — and yours to take to a professional if you want to.",
          },
        ],
        connectors: ["WhatsApp", "SMS / Twilio", "Voice / Call", "ElevenLabs (voice)", "Sarvam (Indian languages)", "Knowledge base"],
        outcome:
          "The reminders you meant to keep actually arrive, in your words, on a channel you read — plus a plain done / not-done log. It is a reminder and a tick-list, not treatment, not monitoring, and not a clinical assessment.",
        impact:
          "The mechanism is delivery and record-keeping: the nudge reaches a channel you already use rather than a dismissed alarm, and the tick is captured at the moment rather than reconstructed later. It makes no claim about health outcomes, because we cannot make one.",
        build: {
          agents: 1,
          agentsNote:
            "1 reminder agent. The schedules, the medication-question gate, the streak-band formula tool and the private log are workflow nodes and tenant configuration.",
          pattern: "Scheduled reminder → verbatim delivery → question gate → private tick log",
          prompt:
            "You deliver {{user}}'s own routine reminders. Send the reminder text EXACTLY as stored in Knowledge — you may add nothing to it. For a medication reminder you may only say that it is time for the item as the user labelled it; you must NEVER state, confirm, convert, adjust or discuss a dose, a frequency, an interaction, a side effect, or whether to take, skip or double anything. If the user asks any of that, reply that you cannot advise on medication and that their prescriber or pharmacist is the right person, then stop. Do not diagnose, do not interpret how the user is doing, do not comment on their mental state, and do not analyse anyone else. Record only done / not-done and any free-text note the user chooses to add, verbatim. If any wording suggests risk of self-harm, suicide or abuse, hand straight to the crisis sub-workflow — do not counsel and do not assess.",
          mcp: [
            "None required. If a clinic or pharmacy system holds the schedule, it reaches the workflow as an inbound Webhook and/or a Tool (MCP) node — there is no first-party connector for it, and it is labelled as such wherever it appears.",
          ],
          tools: [
            "knowledge_search (the user's own reminder wording — never platform-generated medical text)",
            "user-defined tool checkin_streak_band (SpEL): input.daysLogged >= 7 ? 'STEADY' : 'PATCHY' — a neutral count of logged days, never a severity, risk or symptom score",
          ],
          guardrails: [
            "NOT A THERAPY PRODUCT: the agent does not diagnose, does not provide therapy or counselling, and gives no advice on medication or dosage — ever. It reminds; it never instructs",
            "It does not assess or interpret the user's mental state, and it never analyses a THIRD party's mental health from someone else's description",
            "MEDICATION IS REMINDER-ONLY: the agent repeats the user's own stored wording and may never state, convert or adjust a dose, discuss frequency, interactions or side effects, or answer whether to take, skip or double anything. Every such question is refused and routed to the prescriber or pharmacist",
            "The user-defined SpEL tool is a data-transform/formula only (no method calls, no constructors, no bean or type refs, no loop in the grammar). It bands a count of logged days. It must NEVER be used to score severity, risk or symptoms — that would be a clinical judgement dressed as arithmetic",
            "It is a logistics aid supporting professional care; nothing it produces is a clinical assessment, and it must never be used to delay seeing someone",
            "Crisis wording is never counselled or graded — it hands straight to the escalation sub-workflow, which is never behind an Approval node and never rate- or cost-limited",
            "PRIVACY: the routine log is among the most sensitive data a person has. It stays in the user's own tenant, is never used for marketing, and is never shared with family, employer or clinician without an explicit Approval step",
            "CONSENT: this is the user's own routine, set up by them and stoppable by them. It never reports adherence to another adult, and no other adult is monitored by it",
          ],
          cost:
            "Economy tier throughout — these are short, templated deliveries, and volume is a handful of calls a day. Voice (ElevenLabs) costs more per delivery, so reserve it for the one or two reminders that genuinely get missed as text. The crisis branch sits outside every cost cap and rate limit by design.",
          output:
            "Each routine fires on its own schedule → the agent delivers your own wording on WhatsApp, SMS or voice → any medication question is refused and pointed at your prescriber → the tick is written to a private log in your tenant, with a neutral streak band from the SpEL tool and no clinical interpretation attached.",
        },
      },

      {
        id: "wb-appointment-prep",
        title: "Before and after an appointment: your questions, and what was agreed",
        scenario:
          "The appointment is at 3pm and you remember it at 2:40. You had three things you wanted to raise and you leave having raised one. A week later you cannot recall whether the next review was six weeks or eight, or what you agreed to try in the meantime — so you do neither, and the next appointment starts from scratch.",
        triggerLabel: "Scheduled (before appointment) + Webhook (clinic/booking system, labelled) / Email",
        steps: [
          {
            kind: "trigger",
            title: "Appointment is coming up",
            detail:
              "A scheduled trigger fires ahead of the appointment. If the date comes from a clinic or booking system, it arrives as an inbound Webhook and/or is read via a Tool (MCP) node — there is no first-party connector for those systems, and it is labelled as such.",
          },
          {
            kind: "knowledge",
            title: "Your own notes since last time",
            detail:
              "A Knowledge node retrieves what YOU logged — your reflection entries, your routine ticks, the things you noted to raise. Only your own material; nothing inferred about you and nothing about anyone else.",
          },
          {
            kind: "agent",
            title: "Draft a question list — your words, tidied",
            detail:
              "An Agent node assembles your own noted points into a short list you can take in with you. It groups and orders them; it does not add clinical questions of its own, does not rank them by importance, and does not suggest what the answer might be.",
          },
          {
            kind: "output",
            title: "Facts you logged, stated as facts",
            detail:
              "Alongside the questions, an Output node can surface your own logged patterns plainly — for example 'on 6 of the 9 days you marked hard, you had also logged under 6 hours of sleep'. That is a count of what you wrote down. It is information for YOU to take to a professional, explicitly not an interpretation, a correlation claim or a diagnosis.",
          },
          {
            kind: "output",
            title: "After: what was agreed, in your words",
            detail:
              "After the appointment the agent asks what was agreed and records your answer verbatim — the next review date, anything you said you would try. It does not evaluate the plan, does not restate a dose, and does not offer an opinion on it.",
          },
          {
            kind: "condition",
            title: "Follow-ups scheduled from your answer",
            detail:
              "A Condition node turns a stated next-review date into a reminder, and anything left open into a single follow-up nudge. If nothing was agreed, nothing is invented.",
          },
        ],
        connectors: ["Webhook (clinic / booking system — no first-party connector)", "Tool (MCP → clinic / EHR / booking system)", "Email", "WhatsApp", "Knowledge base", "Sarvam (Indian languages)"],
        outcome:
          "You walk in with the list you meant to bring and walk out with the next date and what you said you would try, both in your own words. None of it is a clinical assessment — it is your notes, organised, for a professional to work from.",
        impact:
          "The mechanism is recall, not insight: the questions were yours and are simply in front of you at the right moment, and what was agreed is captured while you still remember it. The counts we show back are counts of what you logged — nothing is inferred, and any meaning in them is for a professional to judge.",
        build: {
          agents: 1,
          agentsNote:
            "1 preparation agent, run twice — once before the appointment, once after. The retrieval, the follow-up scheduling and the private store are workflow nodes.",
          pattern: "Pre-appointment assembly → post-appointment verbatim capture → follow-up scheduling",
          prompt:
            "You help {{user}} prepare for and record a real appointment with a professional. BEFORE: assemble the points the user themselves logged into a short, plain question list, grouped and ordered for a short appointment. You may reorder and de-duplicate the user's own words; you may NOT add clinical questions of your own, rank the items by importance, or hint at what an answer might be. You may also state counts drawn strictly from the user's own logs (e.g. 'on 6 of the 9 days you marked hard, you had also logged under 6 hours of sleep'), labelled clearly as a count of what they wrote, NOT a finding, a correlation or an explanation. Draw no conclusion from it. AFTER: ask what was agreed and record the answer VERBATIM; capture a next-review date if one was given. Never evaluate, endorse or question the plan; never restate a dose or discuss medication; never diagnose or interpret the user's mental state; never analyse anyone else. If any wording suggests risk of self-harm, suicide or abuse, hand straight to the crisis sub-workflow without counselling or assessing.",
          agentRoles: [
            {
              role: "Preparation agent (pre-visit)",
              model: "Standard tier — it is grouping and ordering the user's own text, and the failure mode to avoid (adding content of its own) is worth the better instruction-following",
              prompt:
                "Group and order only what the user logged. Add nothing clinical. State counts as counts, never as findings. No ranking, no interpretation, no suggested answers.",
            },
            {
              role: "Capture agent (post-visit)",
              model: "Economy tier — verbatim capture plus a date",
              prompt:
                "Record what the user says was agreed, word for word, and extract a next-review date if stated. Do not comment on the plan, do not restate any dose, do not summarise it into advice.",
            },
          ],
          mcp: [
            "Clinic / EHR / booking system (appointment date, and only if the user has connected it) — via inbound Webhook and/or a Tool (MCP) node; no first-party connector exists and it is labelled as such",
            "Email / WhatsApp — the reminder and question-list channel",
          ],
          tools: [
            "knowledge_search (the user's own logged notes and reminder history — their material only)",
            "http_request (read the appointment date from a connected clinic/booking system, if the user set one up)",
          ],
          guardrails: [
            "NOT A THERAPY PRODUCT: the agent does not diagnose, does not provide therapy or counselling, and gives no advice on medication or dosage — ever. It prepares you to talk to someone who can",
            "It does not assess or interpret the user's mental state. Patterns are shown as literal counts of what the user logged, explicitly not as findings, correlations, causes or a diagnosis — they are information to take TO a professional, and the copy says so",
            "It never analyses a THIRD party's mental health from the user's description, including a clinician, a family member or anyone discussed in the appointment",
            "The question list contains the user's own points only — the agent may reorder and de-duplicate, never add clinical questions, rank them by importance, or suggest what the answer will be",
            "What was agreed is recorded verbatim and never evaluated, endorsed, questioned or converted into instructions. No dose is ever restated by the agent",
            "It supports professional care and must never be used to replace or delay it; nothing it produces is a clinical assessment",
            "Crisis wording is never counselled or graded — it hands straight to the escalation sub-workflow, which is never behind an Approval node and never rate- or cost-limited",
            "PRIVACY: appointment notes are among the most sensitive data a person has. They stay in the user's own tenant, are never used for marketing, and are never sent to a clinic, an insurer, an employer or a family member without an explicit Approval step",
            "CONSENT: this workflow is run by and for the person attending the appointment. It never monitors another adult and never reports attendance or content to anyone",
          ],
          cost:
            "Two short runs per appointment — a standard-tier assembly pass and an economy-tier capture pass. There is no batch and no multiplier, so total spend is trivial; put the reasoning budget in the pre-visit pass, where adding content that was not the user's is the real risk. The crisis branch is outside all cost caps.",
          output:
            "A short question list in your own words before you go in, an optional plain count of what you logged (labelled as a count, not a finding), a verbatim record of what was agreed afterwards, and a reminder for the next review — all stored privately in your own tenant.",
        },
      },

      {
        id: "wb-crisis-route",
        title: "Crisis wording → official help and a real human, immediately",
        scenario:
          "Someone types something frightening into a chat at 1am. Today, whatever is on the other end either keeps talking to them — which is the wrong thing for software to do — or does nothing at all. The number for the national helpline is somewhere in a browser tab from six months ago, and the friend who said 'call me any time, seriously' has never actually been called.",
        triggerLabel: "Any inbound message on any configured channel (always on, never gated)",
        steps: [
          {
            kind: "trigger",
            title: "Any inbound message, on any channel",
            detail:
              "This path sits in front of every wellbeing workflow — the evening reflection, the routine reminders, the appointment prep — and on any channel the tenant has configured (WhatsApp, SMS, Telegram, Voice, Email).",
          },
          {
            kind: "condition",
            title: "Wording match → route. No grading, no judgement call",
            detail:
              "A Condition node routes on wording that suggests risk of self-harm, suicide or abuse. It does NOT rate severity, estimate intent or decide whether it is 'serious enough'. Any match routes, full stop. False positives are expected and acceptable — showing someone a helpline they did not need costs nothing.",
          },
          {
            kind: "knowledge",
            title: "The tenant's own official crisis resources",
            detail:
              "A Knowledge node returns the crisis resources the tenant stored in advance for the user's region — taken from the official current publication of that country's health authority or recognised helpline body. Many countries operate a national mental-health helpline; the tenant configures theirs. NO NUMBER IS HARDCODED in this guide, in the prompt or in the model, and the agent may never recall one from memory: numbers vary by country and change over time, and a wrong number is worse than none. Set this up while well, and review it on a schedule.",
          },
          {
            kind: "output",
            title: "Surface the official resources, verbatim, at once",
            detail:
              "An Output node returns the stored resources exactly as written, immediately, in the user's language (Sarvam for Indian languages). The message says plainly that this is software, that it cannot help with this, and who can. It does not ask a follow-up question.",
          },
          {
            kind: "output",
            title: "Reach the human the user chose in advance",
            detail:
              "In parallel, an Output node contacts the trusted person or professional service the user nominated while well — by SMS, a voice call, or both. They are told the user asked for them now; the content of the message is not forwarded unless the user set it up that way.",
          },
          {
            kind: "output",
            title: "Tell the user who was contacted, then stop",
            detail:
              "The user is told plainly who has been notified. The agent then stops talking. It does not counsel, does not de-escalate, does not attempt to keep the person talking, and does not follow up with questions — every one of those is unsafe and out of scope for software.",
          },
        ],
        connectors: ["WhatsApp", "SMS / Twilio", "Voice / Call", "Telegram", "Email", "Knowledge base (tenant's own official crisis resources)", "Sarvam (Indian languages)"],
        outcome:
          "Official, tenant-verified help is on screen within seconds and a real human the user chose is contacted straight away. This is a routing step, not care and not a clinical assessment — the help comes from the helpline and the person, never from the agent.",
        impact:
          "The mechanism is speed and pre-commitment: the decisions that are hard at 1am — which number, who to call — were made in advance while well, and the software only executes them. It makes no claim to help anyone through a crisis, because software cannot and must not try.",
        build: {
          agents: 1,
          agentsNote:
            "1 minimal agent whose only job is to deliver stored text and trigger the notifications. It is deliberately the least capable agent in this section — there is no reasoning task here, and reasoning is exactly what must not happen.",
          pattern: "Always-on wording match → stored official resources + notify pre-chosen human → stop. No approval gate anywhere in this path.",
          prompt:
            "You are a routing step, not a helper. If this path has fired, output the crisis resources retrieved from Knowledge EXACTLY as written, with no edits, no additions and no reordering, and state plainly: that you are software, that you cannot help with this, and that the listed service and the user's chosen contact can. You must NEVER counsel, reassure, de-escalate, empathise at length, ask how serious it is, ask what happened, or try to keep the user talking — all of these are unsafe and out of scope. You must NEVER assess risk or severity, and you must NEVER output a helpline number that is not in the retrieved Knowledge result; if Knowledge returns nothing, say the resources are not configured, urge the user to contact local emergency services or a trusted person now, and notify the configured contact anyway. Say who has been contacted. Then stop.",
          mcp: [
            "None. Nothing in this path may traverse a third-party system: no external logging, no analytics, no CRM, no MCP tool. It stays in the user's own tenant and goes only to the people the user chose.",
          ],
          tools: [
            "knowledge_search (the tenant's own official, current, region-specific crisis resources — the ONLY permitted source of a number)",
            "Notification to the pre-configured trusted contact / professional service over SMS, Voice or both",
          ],
          guardrails: [
            "NO COUNSELLING, EVER: on any crisis wording the agent must NOT counsel, de-escalate, assess severity, ask what happened, or try to keep the person talking. Any such attempt is unsafe and out of scope. It surfaces help and routes to a human, then stops",
            "NOT A THERAPY PRODUCT: no diagnosis, no therapy or counselling, no advice on medication or dosage — ever. It does not assess or interpret anyone's mental state, and it never analyses a THIRD party's mental health from someone else's description",
            "It is a logistics aid — it delivers pre-stored information and contacts a pre-chosen person. It supports professional care and never replaces or delays it; nothing here is a clinical assessment, and the user-facing message says so in plain words",
            "NO HARDCODED NUMBERS: helpline details come ONLY from the tenant's own Knowledge base, sourced from the region's official current publication and reviewed on a schedule. The model may never recall a number from memory, and none is written into this guide or the prompt — numbers vary by country and change over time, and a wrong number is worse than none",
            "NEVER GATED: this path must not sit behind an Approval node, a rate limit, a quota, a cost cap or a plan tier. It always runs. This is the one place where 'add a human in the loop later' is the wrong design — the human here is the person being called, not an approver standing between the user and help",
            "NO THIRD PARTIES: crisis content is never logged to, transmitted to or analysed by any external system, analytics tool, MCP connector or model-training pipeline. It stays in the user's own tenant and reaches only the contacts the user chose",
            "The wording match routes; it does not grade. False positives are by design — the failure mode to avoid is missing one, not showing a helpline to someone who did not need it",
            "PRIVACY: this is the most sensitive data the platform will ever hold. The trusted contact is told the user asked for them, not the content of the message, unless the user configured otherwise while well. Nothing is shared with anyone else without an explicit Approval step, and it is never used for marketing",
            "CONSENT: the trusted contact and the escalation route are chosen by the user in advance, while well, and can be changed or removed by them at any time. No other adult is monitored, and no one is added to this path without the user's own agreement",
          ],
          cost:
            "This path is NEVER cost-optimised and NEVER rate-limited. Economy tier is right for routine reminders elsewhere in this section; here, model choice, spend caps, quotas and plan tiers are all irrelevant by policy — the path runs regardless of budget state, and any cost control that could block it is a misconfiguration.",
          output:
            "Wording match → the tenant's own official crisis resources returned verbatim in the user's language, immediately → the pre-chosen trusted contact or professional service notified by SMS and/or voice → the user told who was contacted → the agent stops. No approval gate, no rate limit, no third-party system anywhere in the path.",
        },
      },
    ],
  },

  // ================================== CARING FOR SOMEONE & SUPPORT CIRCLES
  {
    id: "care-support",
    name: "Caring for someone & support circles",
    icon: HandHeart,
    tagline:
      "The logistics of caring for someone — rotas, appointments, handovers, and time off for the carer — coordinated with the agreement of the person being cared for.",
    recipes: [
      {
        id: "care-rota",
        title: "Family rota and shared handover note, with mum's agreement",
        scenario:
          "Four siblings, one group chat, and nobody is sure who is visiting mum this week. Two turn up on Thursday, nobody on Sunday. What the district nurse said on Tuesday is in one person's head, so the next visitor asks the same questions again. Someone eventually makes a spreadsheet; it is out of date within a fortnight.",
        triggerLabel: "Scheduled (weekly rota) + WhatsApp / Telegram group thread",
        steps: [
          {
            kind: "approval",
            title: "Setup: the person being cared for agrees first",
            detail:
              "Before anything runs, an Approval node records the agreement of the person being cared for (or their legally appointed representative) to the circle and to what is shared with it. They can see the members, and they can have someone removed. Without that recorded agreement the workflow does not start — this is coordination WITH someone, never surveillance OF them.",
          },
          {
            kind: "trigger",
            title: "Weekly rota goes out",
            detail:
              "A scheduled trigger posts next week's rota to the family thread on WhatsApp or Telegram, showing who has which slot and which slots are still open.",
          },
          {
            kind: "tool",
            title: "User-defined tool: next in rotation",
            detail:
              "A tenant-authored user-defined tool — a SpEL expression over the tool's JSON input, e.g. next_in_rota: input.members[(input.weekIndex + 1) % input.memberCount]. A data-transform/formula tool, sandboxed by construction (no method calls, no constructors, no bean or type refs, no loop in the grammar) — it fairly rotates a list of names. Nothing about anyone's health goes near it.",
          },
          {
            kind: "agent",
            title: "Collect swaps, keep the rota straight",
            detail:
              "An Agent node reads replies in the thread ('I can't do Thursday, I'll take Sunday'), updates the rota, and states the open slots plainly. It coordinates people and dates. It offers no view on how anyone is coping.",
          },
          {
            kind: "output",
            title: "Shared handover note",
            detail:
              "After each visit, an Output node captures a short handover in the visitor's own words — what was done, what is needed next, what a professional said. Recorded verbatim; never interpreted, never turned into advice, and never used to draw a conclusion about anyone's condition.",
          },
          {
            kind: "condition",
            title: "Clinical questions leave the thread",
            detail:
              "A Condition node catches anything that is a medical question rather than a logistics one. The agent answers none of it and points to the clinician or pharmacist. Medication appears only as a reminder in the person's own stored wording — never a dose, never a change.",
          },
        ],
        connectors: ["WhatsApp", "Telegram", "SMS / Twilio", "Email", "Knowledge base", "Approval / HITL", "Sarvam (Indian languages)"],
        outcome:
          "One rota everybody can see, gaps visible before they happen, and a handover note the next visitor can actually read — all with the agreement of the person being cared for. It is a shared calendar and notebook, not a care plan and not a clinical assessment.",
        impact:
          "The mechanism is a single source of truth and an explicit ask: the rota is in the thread the family already uses, the open slot is named rather than assumed, and what was said on Tuesday is written down instead of held in one person's memory.",
        build: {
          agents: 1,
          agentsNote:
            "1 coordination agent. The consent gate, the rota rotation formula, the medical-question gate and the shared store are workflow nodes and tenant configuration.",
          pattern: "Consent gate first → scheduled rota → thread-based swaps → verbatim handover note",
          prompt:
            "You coordinate a family care rota for {{circle}}. Post the week's slots, read replies, apply swaps, and state clearly which slots are still open and who is asked to fill them. Capture each post-visit handover VERBATIM in the visitor's own words. You handle logistics ONLY: dates, names, transport, who is doing what. You must NEVER diagnose, provide therapy or counselling, or give any advice on medication or dosage. You must NEVER assess or interpret anyone's mental state — not a circle member's, and above all not the mental health of the person being cared for, whom you have never met and are only hearing about second-hand. Do not summarise handover notes into conclusions about how they are doing. If a medical question is asked, say you cannot answer it and point to the clinician or pharmacist. If any wording suggests risk of self-harm, suicide or abuse — to the person being cared for or to a carer — hand straight to the crisis sub-workflow without counselling or assessing.",
          mcp: [
            "None required for the rota itself. If a care agency or clinic system supplies visit slots, it arrives as an inbound Webhook and/or a Tool (MCP) node — no first-party connector exists, and it is labelled as such.",
          ],
          tools: [
            "knowledge_search (the circle's own agreed arrangements and the person's own stored reminder wording)",
            "user-defined tool next_in_rota (SpEL): input.members[(input.weekIndex + 1) % input.memberCount] — fair rotation of a name list, and nothing else; never used to score anyone or anything clinical",
          ],
          guardrails: [
            "CONSENT FIRST: the workflow does not start without the recorded agreement of the person being cared for (or their appointed representative) at the Approval node. They can see who is in the circle and have anyone removed. Never monitor another adult — this is coordination with them, not surveillance of them",
            "NOT A THERAPY PRODUCT: the agent does not diagnose, does not provide therapy or counselling, and gives no advice on medication or dosage — ever. Medication appears only as a reminder in the person's own stored wording; no dose, frequency or change is ever stated by the agent",
            "It does not assess or interpret anyone's mental state, and it explicitly never analyses the mental health of the person being cared for from a family member's second-hand description. Handover notes are stored verbatim and never summarised into a judgement about how anyone is doing",
            "It is a logistics aid — rota, appointments, handovers and reminders. It supports professional care; it never replaces or delays it, and nothing it produces is a clinical assessment",
            "The user-defined SpEL tool is a data-transform/formula only (no method calls, no constructors, no bean or type refs, no loop in the grammar). It rotates names. It must NEVER be used to score severity, risk or symptoms",
            "Crisis wording from anyone in the circle hands straight to the escalation sub-workflow, which is never behind an Approval node and never rate- or cost-limited",
            "PRIVACY: a family's care notes are among the most sensitive data they hold. They stay in the tenant, are never used for marketing, and are never shared outside the agreed circle — including with a wider family group, an employer or an insurer — without an explicit Approval step",
            "Membership changes are themselves gated: adding someone to the circle requires the agreement of the person being cared for, recorded at the Approval node",
          ],
          cost:
            "Economy tier is right — this is scheduling and short thread bookkeeping, a handful of calls a week, with no batch multiplier. The crisis branch is outside every cost cap and rate limit.",
          output:
            "Consent recorded at the Approval node → the weekly rota posts to the family thread with open slots named → swaps applied from replies → each visit's handover captured verbatim → medical questions refused and pointed at the clinician, with everything stored in the tenant and shared only inside the agreed circle.",
        },
      },

      {
        id: "care-appointments",
        title: "Appointment tracking for someone you care for, and what was agreed",
        scenario:
          "Dad's appointments live on three calendars and none of them is his. The letter arrives addressed to him and stays unopened on the side. Whoever takes him has not been told what the last consultant said, so they cannot answer the first question they are asked. Nobody wrote down that the review was moved to eight weeks, so it quietly becomes twelve.",
        triggerLabel: "Webhook (clinic / booking system, labelled) / Email → WhatsApp, SMS, Voice",
        steps: [
          {
            kind: "approval",
            title: "Setup: agreement, and what may be shared",
            detail:
              "An Approval node records the agreement of the person being cared for to the arrangement, including exactly which circle members may see appointment details. Nothing is shared behind their back and they can change the list at any time.",
          },
          {
            kind: "trigger",
            title: "An appointment appears or moves",
            detail:
              "An inbound Webhook from a clinic or booking system, or an Email a family member forwards in, brings the appointment. Those systems have no first-party connector — they reach the workflow via Webhook and/or a Tool (MCP) node, labelled as such.",
          },
          {
            kind: "agent",
            title: "Extract the logistics, nothing more",
            detail:
              "An Agent node pulls out the practical facts: who, when, where, department, what to bring, how long. It does not read clinical content into the appointment, does not speculate on why it was booked, and does not comment on what it might mean.",
          },
          {
            kind: "output",
            title: "Reminder to the right person, on the right channel",
            detail:
              "An Output node reminds the person and whoever is taking them — WhatsApp, SMS, a voice call via ElevenLabs, or Sarvam in the language they actually use — with the transport and timing details attached.",
          },
          {
            kind: "output",
            title: "Questions in, answers out",
            detail:
              "Beforehand, the circle's noted questions go with whoever attends. Afterwards, an Output node records what was agreed in the attendee's own words — the next date, what to arrange. Verbatim, never evaluated, and no dose ever restated.",
          },
          {
            kind: "condition",
            title: "Next review scheduled, or flagged as missing",
            detail:
              "A Condition node turns a stated next-review date into a reminder for the circle. If none was given, it says so plainly rather than inventing one, so someone can ring and ask.",
          },
        ],
        connectors: ["Webhook (clinic / booking system — no first-party connector)", "Tool (MCP → clinic / EHR / booking system)", "Email", "WhatsApp", "SMS / Twilio", "Voice / Call", "ElevenLabs (voice)", "Sarvam (Indian languages)", "Approval / HITL"],
        outcome:
          "Every appointment in one place, the right person reminded on a channel they read, and what was agreed written down where the next visitor will find it. It is an organised diary, not medical guidance and not a clinical assessment.",
        impact:
          "The mechanism is transfer: the details move from an unopened letter and three private calendars into one shared record, and what was said in the room is captured before it is forgotten. It says nothing about whether the care itself is right — that is for the clinicians and the family.",
        build: {
          agents: 1,
          agentsNote:
            "1 appointment agent, run before and after. The consent gate, the review-date branch and the shared store are workflow nodes.",
          pattern: "Consent gate → inbound appointment → logistics extraction → reminder → verbatim post-visit capture",
          prompt:
            "You track appointments for a care circle around {{person}}, with that person's recorded agreement. From a letter, email or booking-system payload, extract ONLY logistics {who, date, time, location, department, duration, what_to_bring, transport_needed}. Do not infer, restate or speculate about clinical content, and never explain why an appointment might have been booked. Send reminders to the attendee and the person, on their configured channel. Afterwards, record what the attendee says was agreed VERBATIM, and extract a next-review date if one was given; if none was, say so rather than inventing one. You must NEVER diagnose, provide therapy or counselling, or give advice on medication or dosage — you may not even restate a dose the attendee mentions as an instruction; record it as their words and leave it there. Never assess or interpret anyone's mental state, and never analyse the mental health of the person being cared for from what a family member tells you. If any wording suggests risk of self-harm, suicide or abuse, hand straight to the crisis sub-workflow without counselling or assessing.",
          mcp: [
            "Clinic / EHR / hospital booking system — via inbound Webhook and/or a Tool (MCP) node; there is no first-party connector and it is labelled as such wherever it appears",
            "Email — for letters and confirmations forwarded in by the family",
          ],
          tools: [
            "knowledge_search (the circle's agreed arrangements, the questions noted for this appointment, transport preferences)",
            "http_request (read appointment details from a connected clinic or booking system, where the family has set one up)",
          ],
          guardrails: [
            "CONSENT FIRST: the person being cared for (or their appointed representative) agrees at the Approval node to the arrangement and to exactly which circle members see appointment details. They can change that list at any time. Never monitor another adult",
            "NOT A THERAPY PRODUCT: the agent does not diagnose, does not provide therapy or counselling, and gives no advice on medication or dosage — ever. It will not even restate a mentioned dose as an instruction; it records the attendee's words and stops there",
            "It does not assess or interpret anyone's mental state, and it never analyses the mental health of the person being cared for from a family member's second-hand account",
            "It is a logistics aid — appointments, reminders, transport and a record of what was agreed. It supports professional care; it never replaces or delays it, and nothing it produces is a clinical assessment",
            "The agent extracts logistics only and never speculates about clinical content or why an appointment was booked. What was agreed is stored verbatim and never evaluated or converted into advice",
            "If no next review was given, that is reported as missing rather than invented — a made-up date is how a review quietly slips",
            "Crisis wording hands straight to the escalation sub-workflow, which is never behind an Approval node and never rate- or cost-limited",
            "PRIVACY: appointment and health-adjacent data is among the most sensitive a family holds. It stays in the tenant, is never used for marketing, and is never sent to anyone outside the agreed circle — including an insurer, an employer or a wider family group — without an explicit Approval step",
          ],
          cost:
            "Economy tier for extraction and reminders; the volume is a few appointments a month, with no batch multiplier. Voice (ElevenLabs) costs more per delivery, so use it for the person who genuinely will not read a text. The crisis branch sits outside all cost caps and rate limits.",
          output:
            "Consent recorded → the appointment arrives by Webhook or forwarded Email → logistics extracted with no clinical inference → reminders sent to the person and the attendee on their own channel → what was agreed captured verbatim afterwards → the next review scheduled, or explicitly flagged as missing.",
        },
      },

      {
        id: "care-respite",
        title: "Respite for the carer: naming the gap and asking for cover",
        scenario:
          "The main carer has not had an evening off in two months. Everyone in the family says 'just tell me when you need a break' and nobody is asked, because asking feels like complaining and because there is never a good moment to work out who could cover. Meanwhile the carer's own dental appointment has been rebooked twice.",
        triggerLabel: "Scheduled (weekly) → WhatsApp / SMS to the carer and the circle",
        steps: [
          {
            kind: "trigger",
            title: "Weekly check of the cover calendar",
            detail:
              "A scheduled trigger looks at the rota the circle already keeps — not at the carer, at the calendar. It asks one question: when did a slot last exist where somebody else was on.",
          },
          {
            kind: "tool",
            title: "User-defined tool: days since last covered break",
            detail:
              "A tenant-authored user-defined tool — a SpEL expression over the tool's JSON input, e.g. cover_gap_band: input.daysSinceCoveredSlot >= 14 ? 'LONG_GAP' : 'RECENT'. A data-transform/formula tool, sandboxed by construction (no method calls, no constructors, no bean or type refs, no loop in the grammar). It counts days between calendar entries. It says nothing about how the carer is, and must NEVER be used to score wellbeing, strain, severity or risk — that would be a clinical judgement dressed as arithmetic.",
          },
          {
            kind: "agent",
            title: "Ask the circle for a specific slot",
            detail:
              "On a long gap, an Agent node posts a concrete ask to the circle — a named evening, not a general appeal — because a specific request is answerable and 'let me know if you need anything' is not. It states the calendar fact. It does not describe the carer's state.",
          },
          {
            kind: "approval",
            title: "The carer decides what the circle is told",
            detail:
              "An Approval node lets the carer confirm the ask before it goes out, and edit it. Their own logs and reflections are never surfaced to the circle; only the slot request is. Nobody is told the carer is struggling — that is theirs to say or not.",
          },
          {
            kind: "output",
            title: "The carer's own appointments, reminded too",
            detail:
              "An Output node reminds the carer of the appointments they keep moving — their own GP, dentist, physio — and books the cover slot into the shared rota so the time is actually protected rather than notional.",
          },
          {
            kind: "condition",
            title: "Crisis wording → escalate, don't reassure",
            detail:
              "A Condition node routes any wording from the carer suggesting risk of self-harm, suicide or abuse straight to the crisis sub-workflow. Carer distress is not something this workflow talks anyone through — it hands to official help and a real human, immediately and ungated.",
          },
        ],
        connectors: ["WhatsApp", "SMS / Twilio", "Telegram", "Email", "Knowledge base", "Approval / HITL", "Sarvam (Indian languages)"],
        outcome:
          "A real, named evening off in the shared rota, asked for on the carer's behalf and on their terms, plus reminders for the carer's own postponed appointments. It is a calendar gap being named and filled — not an assessment of the carer, and not a clinical judgement about anyone.",
        impact:
          "The mechanism is arithmetic and a specific ask: the calendar shows how long it has been since anyone else was on, and the circle gets a concrete slot to accept or decline instead of a vague offer to help. Carer burnout is real; this addresses the logistics of it, and claims nothing beyond that.",
        build: {
          agents: 1,
          agentsNote:
            "1 coordination agent. The gap calculation is a user-defined SpEL formula tool; the carer's confirmation, the rota write and the crisis branch are workflow nodes.",
          pattern: "Scheduled calendar check → neutral gap band → carer-approved specific ask → cover booked into the rota",
          prompt:
            "You help a care circle give its main carer an actual break. Once a week, look at the shared rota and state the calendar fact: how long it has been since a slot was covered by someone else. On a long gap, draft a SHORT, specific request to the circle for one named evening or afternoon. State only the calendar fact and the ask. You must NOT describe, infer or comment on how the carer is doing, feeling or coping — you have no basis for that and it is not yours to say. Never surface the carer's own logs, notes or reflections to the circle. You must NEVER diagnose, provide therapy or counselling, or advise on medication or dosage; never assess or interpret anyone's mental state, including the carer's and including the person being cared for. The carer approves and may edit the message before it is sent. If any wording from the carer suggests risk of self-harm, suicide or abuse, hand straight to the crisis sub-workflow — do not reassure, do not counsel, do not assess.",
          mcp: [
            "None. The rota and the carer's own appointments live in the tenant; nothing about a carer's time off needs to leave it.",
          ],
          tools: [
            "knowledge_search (the shared rota, the circle's members, the carer's own appointment list)",
            "user-defined tool cover_gap_band (SpEL): input.daysSinceCoveredSlot >= 14 ? 'LONG_GAP' : 'RECENT' — a count of days between calendar entries, never a score of wellbeing, strain, severity or risk",
          ],
          guardrails: [
            "NOT A THERAPY PRODUCT: the agent does not diagnose, does not provide therapy or counselling, and gives no advice on medication or dosage — ever, including when a carer describes being exhausted or low",
            "It does not assess or interpret anyone's mental state — not the carer's and not the cared-for person's — and it never analyses a THIRD party's mental health from someone else's description. It reports a calendar gap, which is arithmetic, not an assessment",
            "The user-defined SpEL tool is a data-transform/formula only (no method calls, no constructors, no bean or type refs, no loop in the grammar). It bands a count of days. It must NEVER be used to score wellbeing, strain, severity, risk or symptoms",
            "It is a logistics aid — rota gaps, cover requests and the carer's own appointment reminders. It supports professional care; it never replaces or delays it, and nothing it produces is a clinical assessment",
            "The carer approves and may edit any message before it reaches the circle. Their own notes, logs and reflections are NEVER surfaced to the circle — the workflow shares a slot request, never a description of how they are",
            "Crisis wording from the carer hands straight to the escalation sub-workflow, which is never behind an Approval node and never rate- or cost-limited. Carer distress is routed to real help, not talked through by software",
            "PRIVACY: a carer's own data is theirs. It stays in the tenant, is never used for marketing, and is never shared with the circle, an employer or anyone else without an explicit Approval step",
            "CONSENT: the circle exists with the agreement of the person being cared for, and the carer opts into these prompts themselves. Nobody in this workflow is monitored, scored or reported on",
          ],
          cost:
            "Economy tier — one short weekly run over a rota, no batch, no reasoning burden. Keep the crisis branch outside every cost cap and rate limit; a budget control must never be able to block it.",
          output:
            "A weekly look at the rota → a neutral days-since-cover band from the SpEL tool → a short, specific cover request the carer approves and can edit → the agreed slot written into the shared rota → the carer's own postponed appointments reminded, with nothing about how they are doing shared with anyone.",
        },
      },
    ],
  },
];
