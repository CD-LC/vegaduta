import type { Industry } from "./recipes-data";
import { BriefcaseBusiness, Wallet } from "lucide-react";

// ---------------------------------------------------------------------------
// Job seekers & personal finance / document admin — the INDIVIDUAL's side of
// two categories this catalogue already covers from the institution's side.
// "HR & recruiting" and "Staffing & recruitment agencies" are the employer and
// the agency; these recipes belong to the CANDIDATE. "Lending" and "Payments"
// are the bank and the PSP; these belong to the person managing their own
// money and their own paperwork.
//
// Same inline-copy, no-i18n precedent and the same honesty rules as
// recipes-data.tsx. Every recipe is expressed only in VegaDūta's real building
// blocks (Trigger / Agent / Knowledge / Condition / Approval / Output /
// Tool(MCP) / Voice / Device / Loop / Code / Parallel / Supervisor /
// Sub-workflow), so each maps 1:1 to something buildable in the designer.
//
// Systems VegaDūta ships no first-party connector for — job boards and ATS
// career portals, bank/insurer/utility portals, tax-filing sites — are reached
// the real way, via an inbound Webhook and/or a Tool (MCP) node, and are
// LABELLED as such. Only real channels are used (Webhook, WhatsApp, SMS,
// Email, Voice/Call, Telegram, Slack, ElevenLabs, Sarvam, Razorpay UPI).
//
// Two non-negotiable postures run through every recipe here:
//   1. HONESTY. The agent never fabricates experience, skills, qualifications
//      or dates. It reframes and surfaces what is genuinely true in the
//      person's own Knowledge, and names the gaps out loud. A CV that lies is
//      unethical and is the user's legal exposure, not the agent's.
//   2. NOT ADVICE, NOT A WALLET. The agent organises, reminds, summarises and
//      compares the person's OWN documents. It gives no personalised
//      investment advice and recommends no financial product. It never stores
//      or types a card number, password, OTP or government ID number, and it
//      never auto-submits a financial form — every submission and every
//      payment passes an Approval node and the human clicks.
// Impact lines describe the mechanism. No invented callback rates, salary
// increases or ROI figures appear anywhere in this file.
// ---------------------------------------------------------------------------

export const INDUSTRIES_CAREERMONEY: Industry[] = [
  // ================================================ JOB SEEKERS & CAREER GROWTH
  {
    id: "job-seekers",
    name: "Job seekers & career growth",
    icon: BriefcaseBusiness,
    tagline:
      "Screen postings against your real must-haves, tailor only where it's worth it, keep the tracker honest and practise out loud — without a single invented line on your CV.",
    recipes: [
      {
        id: "job-fit-screen",
        title: "Sixty postings → a ranked shortlist, then a tailored application (map-reduce, 3 agents)",
        scenario:
          "One CV, unchanged, went out to sixty postings. Each application took twenty minutes of scrolling and copy-paste, so the tailoring stopped after the first four and the rest went out generic. Nobody ever read back which sixty they were, or which ones actually matched what the person can genuinely do.",
        triggerLabel: "Webhook (saved-search digest from a job board) / Email (job alerts)",
        steps: [
          { kind: "trigger", title: "The day's postings arrive", detail: "Job-board alert emails hit an Email trigger, or a saved-search digest posts to a Webhook. Job boards and ATS career portals have no first-party connector — postings come in via Webhook and/or are fetched by a Tool (MCP) node." },
          { kind: "knowledge", title: "Your own record, once", detail: "A Knowledge node holds the person's real material: current CV, past project write-ups, certificates, transcripts, reference letters. This is the ONLY source of fact about them — everything downstream is grounded in it." },
          { kind: "loop", title: "Map: one cheap pass per posting", detail: "A Loop node fans a cheap screener agent across every posting — one focused pass each, isolated context — so sixty postings cost the same per item as six." },
          { kind: "agent", title: "Screener scores the fit honestly", detail: "The screener returns strict JSON per posting: must-have requirements found vs. missing, evidence quoted from the person's own Knowledge, and a verdict. A missing must-have is reported as missing, never smoothed over." },
          { kind: "tool", title: "Your must-haves, encoded once", detail: "A user-defined tool turns the screener's counts into a single verdict using YOUR thresholds, not a model's mood — the same rule applied identically to all sixty." },
          { kind: "agent", title: "Reduce: the shortlist", detail: "A reducer agent ranks the STRONG and STRETCH postings into one shortlist with the reason for each, and lists the SKIPs with the specific must-have that was missing — that list is the honest map of the person's real gaps." },
          { kind: "agent", title: "Premium tailoring, shortlist only", detail: "Only for shortlisted roles does a premium-tier writer draft the tailored CV summary and cover letter — re-ordering and re-framing what is already true in Knowledge, adding nothing that isn't." },
          { kind: "approval", title: "You read it before it exists anywhere", detail: "An Approval node holds every draft. The person reads the claims, checks each one is theirs, edits, and only then releases. Nothing is submitted by the workflow — applications are filed by the human on the employer's own portal." },
        ],
        connectors: ["Webhook (job-board digest)", "Email", "Tool (MCP → job board / ATS career portal)", "Knowledge base", "Approval / HITL", "Telegram"],
        outcome:
          "Every posting in the day's batch is screened against the person's actual must-haves, the shortlist arrives ranked with reasons, and only shortlisted roles get a hand-checked tailored draft.",
        impact:
          "The mechanism is cost asymmetry plus grounding: the expensive work (tailoring) is spent only where a cheap, rule-checked screen says it's warranted, and every claim in the draft traces back to a document the person already owns — so tailoring scales without the CV drifting away from the truth.",
        build: {
          agents: 3,
          agentsNote:
            "1 screener (runs once per posting via the Loop node) + 1 reducer (once) + 1 tailoring writer (only for shortlisted roles). The Loop, the user-defined verdict tool and the Approval are workflow nodes.",
          pattern: "Map-reduce with a premium tail: Loop (cheap map) → reducer (rank) → premium writer on the shortlist only → human approves",
          prompt:
            "(Screener) Assess ONE job posting against the candidate's own record in Knowledge. Return strict JSON {title, employer, mustHaveTotal, mustHaveMatches, matched[{requirement, evidence, source_doc}], missing[{requirement, why}], niceToHaveMatches, notes}. EVERY item in matched[] MUST quote real evidence from the candidate's own documents and name the source document — if you cannot find evidence, the requirement goes in missing[], never in matched[]. Do NOT infer a skill from a job title, do NOT round up years of experience, do NOT assume a degree or certificate that isn't in Knowledge. You are scoring fit, not selling the candidate. If the posting is unreadable or a duplicate, return {skip: true, reason}.",
          agentRoles: [
            {
              role: "Screener (per posting)",
              model: "Economy tier — fast and cheap; it runs once per posting, so this single choice dominates the total cost of the whole run. Configure a fallback model so one flaky call doesn't drop a posting from the batch.",
              prompt:
                "Score ONE posting against the candidate's Knowledge into schema-valid JSON. Quote evidence and name its source document for every match; anything without evidence is 'missing'. No inferred skills, no rounded-up years, no assumed credentials.",
            },
            {
              role: "Reducer (once per batch)",
              model: "Standard tier — reasoning over the whole batch",
              prompt:
                "Rank the screened postings into one shortlist (STRONG first, then STRETCH) with a one-line reason each, and list the SKIPs grouped by the must-have that was missing. Use only the provided results — never re-score a posting yourself. Finish with the two or three requirements that blocked the most roles; that is the candidate's real gap list, state it plainly.",
            },
            {
              role: "Tailoring writer (shortlist only)",
              model: "Premium tier — this is the one place the quality is worth paying for, and it runs on a handful of roles, not on sixty",
              prompt:
                "Draft a tailored CV summary and a cover letter for ONE shortlisted role, using ONLY facts present in the candidate's Knowledge. You may re-order, re-frame, re-word and choose which true things to lead with. You may NOT add a skill, tool, employer, responsibility, qualification or date that is not in the source documents, and you may NOT change any date or duration. Where the role asks for something the candidate genuinely lacks, either omit it or address it honestly as a gap they are working on — never imply it is held. Mark every draft as a draft for the candidate to check.",
            },
          ],
          mcp: [
            "Job board / saved search — inbound via Webhook; posting bodies fetched by a Tool (MCP) node (no first-party connector)",
            "ATS career portal — read-only via Tool (MCP) where the employer exposes one; applications are filed by the human, never by the workflow",
            "Email / Telegram — where the shortlist and the drafts are delivered",
          ],
          tools: [
            "knowledge_search (the candidate's own CV, project write-ups, certificates, references)",
            "web_search (employer background for the cover letter — public information only)",
            "http_request (fetch posting bodies from the board)",
            "user-defined tool `role_fit` (SpEL): input.mustHaveMatches >= input.mustHaveTotal ? 'STRONG' : (input.mustHaveMatches >= input.mustHaveTotal - 1 ? 'STRETCH' : 'SKIP') — a tenant-authored SpEL expression over the tool's JSON input. It is a data-transform/formula tool, not a scripting engine: sandboxed by construction, because the grammar allows no method calls, no constructors, no bean or type references and no loop. The benefit is that the person encodes THEIR OWN must-have threshold once — 'one gap is a stretch, two is a no' — and it is applied identically to all sixty postings, deterministically, without a developer and without asking a model to be consistent about arithmetic.",
          ],
          guardrails: [
            "HONESTY, absolute: the agent never fabricates experience, skills, qualifications, employers or dates. It only reframes and highlights what is genuinely true in the candidate's own Knowledge, and it flags missing requirements as missing — a CV that lies is unethical and is the candidate's legal risk to carry",
            "Every 'matched' requirement must quote evidence and name the source document; anything unevidenced is forced into the missing[] list, so the shortlist can't be inflated by a confident model",
            "No draft leaves as an application: the Approval node holds every CV summary and cover letter for the person to read line by line, and the submission itself is done by the human on the employer's portal — the workflow never auto-applies",
            "The screener's verdict comes from the deterministic `role_fit` SpEL rule, not from model discretion, so the same posting scores the same way on every run",
            "Schema validation on each screener output — a malformed posting is skipped with a reason and surfaced, never silently ranked",
          ],
          cost:
            "The whole design is a cost shape: N × economy-tier screener + ONE standard-tier reduce + K × premium-tier tailoring where K is the shortlist, not the batch. Screening sixty postings on the premium tier would cost roughly an order of magnitude more than screening them on the economy tier and paying premium for four cover letters — the Loop is exactly where cost routing earns its keep. Cap the screener per-call, give it a fallback model so a single failure doesn't cost a re-run of the batch, and run the map in Parallel batches for wall-clock speed at unchanged per-item cost.",
          output:
            "The Loop maps the economy screener over every posting → `role_fit` turns counts into STRONG/STRETCH/SKIP deterministically → the reducer emits one ranked shortlist plus an honest gap list → the premium writer drafts only for shortlisted roles → the Approval node puts every claim in front of the person before anything is used, and they file the application themselves.",
        },
      },
      {
        id: "job-pipeline-tracker",
        title: "The tracker that updates itself (and admits when it doesn't know)",
        scenario:
          "Applications went into a spreadsheet. The spreadsheet stopped being updated in week two. Now replies, rejections and interview invites are scattered across an inbox, and it's genuinely unclear which roles are still live, which went quiet three weeks ago, and which one needed a follow-up last Tuesday.",
        triggerLabel: "Email (replies to applications) + scheduled Webhook (daily sweep)",
        steps: [
          { kind: "trigger", title: "A reply lands, or the day rolls over", detail: "An Email trigger catches replies from employers and ATS no-reply addresses; a scheduled Webhook fires a daily sweep for the applications that nobody replied to at all." },
          { kind: "agent", title: "Classify the reply", detail: "An Agent node matches the message to an application and classifies it — acknowledgement, rejection, screening call, interview invite, offer, or ambiguous — extracting any date, time and named contact it actually contains." },
          { kind: "condition", title: "Ambiguous stays ambiguous", detail: "A Condition node routes anything the agent could not confidently match or classify to a human-review lane instead of guessing a stage. A tracker that quietly guesses is exactly how the last one lost trust." },
          { kind: "loop", title: "Sweep the silent ones", detail: "A Loop node walks the still-open applications and computes days since the last contact, so the ones that went quiet surface on their own rather than being remembered." },
          { kind: "tool", title: "Your follow-up window, encoded", detail: "A user-defined tool decides what is due today using the person's own waiting periods — not a generic default, and not a model's opinion of what's polite." },
          { kind: "output", title: "One short daily digest", detail: "An Output node sends a single WhatsApp or Telegram message: what moved, what's due for a nudge, what's ambiguous and needs a human eye. One message a day, not a notification per email." },
          { kind: "approval", title: "Nudges are drafted, never auto-sent", detail: "An Approval node holds each follow-up draft — the person reads it and releases it. Nothing is sent to an employer without a click." },
        ],
        connectors: ["Email", "Webhook (scheduled sweep)", "WhatsApp", "Telegram", "Approval / HITL", "Tool (MCP → application tracker sheet/store)"],
        outcome:
          "The pipeline stays current on its own, silence becomes visible instead of invisible, and follow-ups are drafted at the right moment for the person to send.",
        impact:
          "The mechanism is that the update work moves off the person entirely — a tracker fails because maintaining it is manual, so the fix is not a better spreadsheet but removing the maintenance step, while keeping every outbound message human-released and every uncertain classification human-reviewed.",
        build: {
          agents: 1,
          agentsNote:
            "1 classification agent (per reply and per swept application). The daily sweep Loop, the ambiguity gate, the follow-up SpEL rule and the send Approval are workflow nodes.",
          prompt:
            "You are the candidate's own application tracker. Given an inbound message and the list of open applications, match it to exactly one application and classify the stage as one of {ACK, REJECTED, SCREENING_CALL, INTERVIEW, TASK_ASSIGNMENT, OFFER, UNCLEAR}. Extract only what the message literally contains: {employer, role, date, time, timezone, contact_name, action_required}. Never infer a date, a stage or an outcome that is not written down — if the match or the stage is not obvious, return UNCLEAR with the two best candidate matches and let a human decide. Do not draft or send anything to the employer; you only classify and summarise. Never treat silence as a rejection; report it as days since last contact.",
          agentRoles: [
            {
              role: "Reply classifier (per message)",
              model: "Economy tier — short, structured extraction on a high-volume stream; add a fallback model so a transient failure doesn't lose a reply",
              prompt:
                "Match one message to one open application and return schema-valid JSON with the stage and only literally-stated dates/contacts. UNCLEAR is a valid, preferred answer over a guess.",
            },
            {
              role: "Digest writer (once a day, same agent, second pass)",
              model: "Standard tier — one call per day, so the reasoning budget is affordable here",
              prompt:
                "Write one short daily digest: what moved and to which stage, which applications are due a follow-up today, which items are UNCLEAR and need the person's eye. Plain language, no encouragement filler, no invented statistics about response rates.",
            },
          ],
          mcp: [
            "Application tracker store (sheet / database) — via a Tool (MCP) node; there is no first-party connector for a personal spreadsheet",
            "ATS no-reply mail — inbound via the Email trigger only; ATS portals are not written to by the workflow",
            "WhatsApp / Telegram — the daily digest channel",
          ],
          tools: [
            "knowledge_search (what was actually applied for, and the tailored draft that was sent)",
            "http_request (read and update the tracker store)",
            "send_email (follow-up drafts — released only after Approval)",
            "user-defined tool `follow_up_due` (SpEL): input.daysSinceContact >= input.nudgeAfterDays && input.stage != 'REJECTED' && input.nudgesSent < 2 ? 'NUDGE' : 'WAIT' — a tenant-authored SpEL expression over the tool's JSON input, sandboxed by construction (no method calls, no constructors, no bean/type references, no loop in the grammar). It is a formula tool, not a scripting engine. The benefit: the person sets their own waiting period and their own cap on how many times they will chase a single employer, once, and it is applied the same way to every application forever — no developer, and no model deciding on the day what counts as too soon or too pushy.",
          ],
          guardrails: [
            "HONESTY, absolute: no fabricated experience, skills, qualifications or dates anywhere in a follow-up message — it may reference only what is genuinely in the person's Knowledge and what was actually sent, and it flags gaps honestly rather than papering over them",
            "UNCLEAR is a first-class outcome — an unmatched or ambiguous message goes to the human-review lane and never silently moves an application to a stage it may not be in",
            "Silence is never classified as a rejection; it is reported as days since last contact",
            "Every follow-up is drafted and held at the Approval node — the person releases it; the workflow sends nothing to an employer on its own, and the SpEL nudge cap stops repeat chasing",
            "The tracker write is keyed to the application id and idempotent, so a forwarded or re-delivered email can't create a duplicate row or double-count a stage",
          ],
          cost:
            "Per-reply classification is short structured extraction, so it belongs on the economy tier with a hard per-call cap — over a long search this is the volume that adds up. Exactly one standard-tier call a day writes the digest. The daily sweep does no model work at all: `follow_up_due` is deterministic SpEL, so computing what's due costs nothing and can't drift.",
          output:
            "Each reply is classified and the tracker updated by a Tool (MCP) node → the daily Loop plus the `follow_up_due` SpEL rule marks what's due → one Output node sends a single digest on WhatsApp or Telegram → each follow-up draft waits at the Approval node until the person releases it via send_email.",
        },
      },
      {
        id: "job-interview-practice",
        title: "Interview practice out loud, grounded in the actual job ad",
        scenario:
          "Interview prep is re-reading the job ad on the bus. The first time the answers are said out loud is in the interview itself, and the questions that get asked are the ones about the parts of the CV that are thinnest — which is exactly where the answers were never rehearsed.",
        triggerLabel: "WhatsApp / Voice (\"practise the Tuesday interview\") — or scheduled the evening before",
        steps: [
          { kind: "trigger", title: "Start a practice round", detail: "The person messages on WhatsApp or dials in on Voice/Call. A scheduled trigger can also open a practice slot the evening before a confirmed interview from the tracker." },
          { kind: "knowledge", title: "The ad and the real CV, side by side", detail: "A Knowledge node loads the specific posting and the person's genuine record — so the questions come from the actual requirements of that role, and the feedback is measured against what they have actually done." },
          { kind: "agent", title: "Interviewer agent asks, one at a time", detail: "An Agent node runs a realistic round: role-specific questions, a follow-up probe when an answer is vague, and deliberate coverage of the requirements the fit screen flagged as gaps — because those are the questions that will land." },
          { kind: "voice", title: "Spoken, in the person's language", detail: "A Voice node with ElevenLabs handles the spoken round; Sarvam covers Indian-language practice, so someone interviewing in English can still rehearse and get feedback in the language they think in." },
          { kind: "agent", title: "Feedback on substance, not vibes", detail: "After the round, the agent gives specific feedback: which answers had a concrete example and which didn't, where an answer drifted into a claim the CV doesn't support, and which genuine experience they under-sold." },
          { kind: "condition", title: "Gap or overclaim?", detail: "A Condition node separates two very different notes — 'you have this and didn't mention it' versus 'you said something your record doesn't support' — and the second is always surfaced, never smoothed." },
          { kind: "output", title: "A short written brief", detail: "An Output node sends a compact recap on WhatsApp or Email: the questions, the strongest true examples to reuse, and the honest gaps to be ready to name." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "ElevenLabs", "Sarvam", "Knowledge base", "Email"],
        outcome:
          "The person has said their answers out loud, against the real requirements of the specific role, and knows which of their genuine examples are strongest and which gaps they need an honest answer for.",
        impact:
          "The mechanism is rehearsal against the specific ad rather than generic prep, plus a truthfulness check on the spoken answers themselves — practising a claim the CV doesn't support is worse than not practising, so the agent catches it in the room where it costs nothing.",
        build: {
          agents: 1,
          agentsNote:
            "1 interviewer/coach agent running both the question round and the feedback pass. Voice, the gap/overclaim branch and the delivery are workflow nodes.",
          prompt:
            "You are running interview practice for a candidate on ONE specific role. Load the posting and the candidate's own record from Knowledge. Ask realistic questions for that role, one at a time, and probe once when an answer is vague or has no concrete example. Cover the requirements the fit screen marked as gaps — those are the questions most likely to be asked. Then give specific feedback: which answers carried a real example, which were generic, which under-sold something the candidate genuinely has, and — most importantly — any answer that claimed something their record does not support. NEVER coach the candidate to claim experience, skills, tools, qualifications or dates they do not have; if they lack a requirement, help them say so honestly and describe what they would do about it. Do not invent an interviewer's opinion, a scoring rubric or an outcome prediction. No flattery.",
          agentRoles: [
            {
              role: "Interviewer / coach (whole session)",
              model: "Standard tier for the question round — conversational latency matters more than depth here; a premium-tier fallback is worth configuring for the feedback pass, where the substance judgement actually happens",
              prompt:
                "Ask role-specific questions one at a time with a single probe on vague answers, then give evidence-based feedback. Flag any answer that outruns the candidate's own record. Never coach a false claim.",
            },
          ],
          mcp: [
            "Job posting — pulled in via a Tool (MCP) node or the Webhook digest (no first-party job-board connector)",
            "ElevenLabs — spoken practice round",
            "Sarvam — Indian-language practice and feedback",
            "WhatsApp / Email — the written recap",
          ],
          tools: [
            "knowledge_search (the specific posting + the candidate's real CV, projects and certificates)",
            "web_search (public information about the employer, for context questions only)",
          ],
          guardrails: [
            "HONESTY, absolute: the coach never helps the candidate fabricate experience, skills, qualifications or dates. It flags any spoken answer that outruns their own record, and it coaches an honest 'I haven't done that, here's the closest thing I have' instead — a rehearsed lie is both unethical and the candidate's legal risk in the interview",
            "Feedback is grounded in the candidate's own documents and the actual posting; no invented rubric, no predicted outcome, no invented callback or success rates",
            "The practice session is a rehearsal only — nothing from it is sent to any employer, and no recording or transcript leaves the person's own workspace",
            "No flattery-as-feedback: an answer with no concrete example is named as such, because the point of practising is to find that before the interview does",
          ],
          cost:
            "A conversational round is many short turns, so it runs on the standard tier with a per-session budget cap; the single feedback pass at the end is the one call worth routing to a premium model, with a fallback configured so a dropped call doesn't waste the session. Voice minutes, not tokens, are usually the larger line here — cap the session length rather than the model.",
          output:
            "The Voice node runs the spoken round through ElevenLabs (or Sarvam for an Indian language) → the agent's feedback pass separates under-sold strengths from unsupported claims at the Condition node → an Output node delivers a short written brief on WhatsApp or Email with the strongest true examples and the gaps to answer honestly.",
        },
      },
      {
        id: "job-referral-asks",
        title: "The referral asks that never got sent",
        scenario:
          "There are eleven people who would probably help — an ex-manager, two former colleagues, someone met at a meetup. The messages were never written, because each one needs to be different and writing eleven different honest messages at 11pm after a day of applications is where the energy runs out.",
        triggerLabel: "Webhook (a role is shortlisted) / WhatsApp (\"who do I know at this company?\")",
        steps: [
          { kind: "trigger", title: "A shortlisted role needs a warm route", detail: "A Webhook fires when the fit screen shortlists a role, or the person asks directly on WhatsApp." },
          { kind: "knowledge", title: "Your own contact notes", detail: "A Knowledge node holds the person's own notes on their network — who they worked with, on what, when, and how well they actually know them. Only what the person themselves recorded; nothing scraped, nothing compiled from outside sources." },
          { kind: "loop", title: "One draft per contact, not one template", detail: "A Loop node walks the relevant contacts, so each message is drafted from the real shared history with that specific person instead of a mail-merge." },
          { kind: "agent", title: "Draft an honest, specific ask", detail: "An Agent node writes each message: how they actually know each other, what the person is looking for, what they genuinely bring, and a small, easy, refusable ask. No inflated claims, and no pretending to a closeness that isn't there." },
          { kind: "condition", title: "Thin connection?", detail: "A Condition node separates strong ties from thin ones and drafts them differently — a barely-remembered meetup contact gets a short reintroduction, not a referral request, because pretending otherwise is how these messages get ignored." },
          { kind: "approval", title: "You send it, always", detail: "An Approval node holds every draft. The person reads it, edits the tone, and sends it themselves — a message to someone's real professional network is theirs to send, not an agent's." },
          { kind: "output", title: "Logged so it isn't asked twice", detail: "An Output node records who was asked, for which role and when, so the same contact isn't approached three times in a month." },
        ],
        connectors: ["Webhook", "WhatsApp", "Email", "Slack", "Knowledge base", "Approval / HITL"],
        outcome:
          "Eleven specific, honest, individually-grounded messages exist as drafts within minutes, and the person spends their remaining energy on reading and sending rather than on writing from a blank page.",
        impact:
          "The mechanism is removing the blank-page cost that kills these messages, while keeping the two things that make them work: specificity to the real shared history, and the person's own hand on send.",
        build: {
          agents: 1,
          agentsNote:
            "1 drafting agent, run once per contact via the Loop node. The tie-strength branch, the send Approval and the ask-log are workflow nodes.",
          prompt:
            "Draft ONE outreach message from the candidate to ONE person in their own contact notes, about ONE specific role. Ground it in the real shared history recorded in Knowledge — where and when they worked together, on what. Say plainly what the candidate is looking for and what they genuinely bring, using ONLY what is in the candidate's own record. Make the ask small, specific and easy to decline. NEVER overstate the relationship, never claim a shared project that isn't in the notes, and never inflate the candidate's experience, skills, qualifications or dates. If the recorded connection is thin, write a short honest reintroduction instead of a referral request and say so. This is a draft for the candidate to edit and send themselves — do not write it as if already sent.",
          agentRoles: [
            {
              role: "Outreach drafter (per contact)",
              model: "Standard tier — short, tone-sensitive writing where per-contact specificity is the whole point; economy tier flattens every message into the same voice, which is the failure mode being fixed",
              prompt:
                "Write one honest, specific message grounded in the recorded shared history. Small refusable ask. No overstated relationship, no inflated experience. Draft only.",
            },
          ],
          mcp: [
            "The person's own contact notes — held in Knowledge; no external network is scraped or compiled",
            "Email / WhatsApp / Slack — the channels the person sends from themselves after approving",
          ],
          tools: [
            "knowledge_search (own contact notes, own CV and project history)",
            "send_email (only after the Approval node releases a specific draft)",
          ],
          guardrails: [
            "HONESTY, absolute: no fabricated experience, skills, qualifications or dates, and no fabricated shared history — the message may only reference what the person themselves recorded, and a thin connection is written as a thin connection",
            "Nothing is sent by the workflow: every draft passes the Approval node and the person clicks send, because these are their real relationships",
            "Only the person's own contact notes are used — the workflow does not scrape, enrich or compile information about contacts from outside sources",
            "The ask-log prevents the same contact being approached repeatedly across different roles in a short window",
          ],
          cost:
            "One standard-tier call per contact, bounded by how many people are actually relevant to a given role — typically a handful, so there is no runaway multiplier. If a batch ever gets large, cap it and draft the strong ties first: the thin-tie reintroductions are the ones that can wait, not the ones worth a cheaper model.",
          output:
            "The Loop drafts one grounded message per contact → the tie-strength Condition splits referral asks from reintroductions → every draft waits at the Approval node for the person to edit and send from their own Email, WhatsApp or Slack → an Output node logs who was asked, for what, and when.",
        },
      },
    ],
  },

  // ==================================== PERSONAL FINANCE & DOCUMENT ADMIN
  {
    id: "personal-finance",
    name: "Personal finance & document admin",
    icon: Wallet,
    tagline:
      "Get renewals, warranties, tax papers and forgotten subscriptions out of the Downloads folder and onto a dated timeline — organised and reminded, never advised, and never paid without your click.",
    recipes: [
      {
        id: "money-renewal-radar",
        title: "Every policy, bill and warranty on one dated timeline",
        scenario:
          "The insurance premium is remembered two days late. The extended warranty on the fridge expired without anyone noticing, and the receipt is somewhere in Downloads next to eleven files called document(3).pdf. The rent agreement's renewal date is known by nobody, and the electricity bill is paid when the reminder SMS arrives, which is not always.",
        triggerLabel: "Email (bills, policy documents) / WhatsApp (forward a PDF) + scheduled daily sweep",
        steps: [
          { kind: "trigger", title: "A document arrives, however it arrives", detail: "An Email trigger catches statements and policy documents; forwarding a PDF or a photo of a paper bill on WhatsApp works too. Bank, insurer and utility portals have no first-party connector — anything from them arrives as an emailed document or is fetched by a Tool (MCP) node." },
          { kind: "loop", title: "One pass per document", detail: "A Loop node walks each new document — including the backlog sitting in Downloads if it is imported once — so a hundred old PDFs are processed the same way as tomorrow's one." },
          { kind: "agent", title: "Extract only what's printed", detail: "An Agent node pulls out {type, provider, reference, amount, due or expiry date, renewal terms} into strict JSON — from the document itself. If a date isn't printed, the field is null and the document is flagged for a human glance, never estimated." },
          { kind: "knowledge", title: "Filed where it can be found", detail: "A Knowledge node stores the document with its extracted fields, so 'where is the fridge warranty' is a search rather than an archaeology expedition." },
          { kind: "tool", title: "Your reminder windows, encoded once", detail: "A user-defined tool converts days-to-expiry into an urgency band using the person's own thresholds — the same rule for a policy, a warranty and a rent agreement." },
          { kind: "output", title: "Reminded in time to act, not after", detail: "An Output node sends the urgency band's reminder on WhatsApp or SMS with the document attached and the exact amount and reference printed on it." },
          { kind: "approval", title: "Paying is always a human click", detail: "If the person chooses to pay through the platform, an Approval node holds it and a Razorpay UPI link is generated for them to complete themselves. The workflow never auto-pays, never stores a card number, and never handles an OTP." },
        ],
        connectors: ["Email", "WhatsApp", "SMS / Twilio", "Knowledge base", "Approval / HITL", "Razorpay (UPI)", "Tool (MCP → biller / insurer portal)"],
        outcome:
          "Every recurring obligation the household actually has sits on one dated timeline with its source document attached, and the reminder arrives with enough runway to do something about it.",
        impact:
          "The mechanism is that a due date only works if it is extracted at the moment the document arrives rather than remembered later — turning arrival into filing plus a dated reminder removes the step that was always going to be forgotten. It organises the person's own documents; it does not advise on them.",
        build: {
          agents: 1,
          agentsNote:
            "1 extraction agent, run once per document via the Loop node. The urgency SpEL rule, the reminder Output and the payment Approval are workflow nodes.",
          prompt:
            "Extract the obligation details from ONE document belonging to the user. Return strict JSON {doc_type (POLICY|BILL|WARRANTY|AGREEMENT|SUBSCRIPTION|OTHER), provider, reference, amount, currency, issue_date, due_date, expiry_date, renewal_terms, notes}. Read ONLY what is printed on the document — if a value is not present, return null and set needs_review=true. Never estimate an amount, never infer a due date from a billing cycle, never guess a provider from a logo you are unsure of. Do NOT extract, echo, store or repeat any card number, bank account number, password, OTP or government identity number that appears on the document — mask them and note that a masked value was present. You are filing and dating the document, not interpreting it: give no advice about whether to renew, switch, buy or claim.",
          agentRoles: [
            {
              role: "Document extractor (per document)",
              model: "Economy tier — bounded structured extraction that runs once per document and across the whole imported backlog, so this is where the cost lives; configure a fallback model so one failure doesn't strand a document unfiled",
              prompt:
                "Extract printed obligation fields into schema-valid JSON. null + needs_review for anything not printed. Mask any card, account, OTP or ID number rather than extracting it. No advice, no interpretation.",
            },
          ],
          mcp: [
            "Insurer / utility / biller portals — no first-party connector; documents arrive by Email or WhatsApp, or are fetched read-only by a Tool (MCP) node",
            "Razorpay (UPI) — payment link generation only, completed by the human after Approval",
            "WhatsApp / SMS — the reminder channel",
          ],
          tools: [
            "knowledge_search (the person's own filed policies, bills, warranties and agreements)",
            "http_request (fetch a statement the user has explicitly connected, read-only)",
            "user-defined tool `renewal_alert` (SpEL): input.daysToExpiry <= 7 ? 'ACT_NOW' : (input.daysToExpiry <= 30 ? 'PLAN' : 'OK') — a tenant-authored SpEL expression over the tool's JSON input, sandboxed by construction: the grammar permits no method calls, no constructors, no bean or type references and no loop, so it is a data-transform/formula tool rather than a scripting engine. The benefit is that the person sets their own reminder windows once — someone who needs three weeks to arrange a premium payment sets thirty and seven, someone else sets fourteen and three — and every document from then on is banded by that rule identically, with no developer and no model deciding what counts as urgent today.",
          ],
          guardrails: [
            "NOT FINANCIAL ADVICE, stated plainly: the agent organises, reminds, summarises and compares the user's OWN documents. It does not give personalised investment advice and does not recommend financial products, insurers, funds or lenders — 'your policy expires on the 14th, here is the document' is in scope; 'you should switch to a better policy' is not",
            "SECURITY: card numbers, bank account numbers, passwords, OTPs and government ID numbers are never stored, echoed or typed — they are masked on extraction, and the workflow never enters them anywhere",
            "No financial form is ever auto-submitted and no payment is ever automatic: an Approval node holds every payment, a Razorpay UPI link is generated, and the human completes it themselves",
            "Dates and amounts come only from the printed document — nothing is estimated or inferred from a billing cycle; a missing field is null and flagged for review, because a wrong due date is worse than a missing one",
            "Extraction is idempotent per document hash, so re-forwarding the same bill can't create a duplicate obligation or a second reminder",
          ],
          cost:
            "Extraction is the only model work and it runs once per document — economy tier with a hard per-call cap, which matters most on the initial backlog import where hundreds of old PDFs go through at once. The daily sweep costs nothing at all: `renewal_alert` is deterministic SpEL, so banding every tracked obligation every morning is arithmetic, not inference. Run the backlog import in Parallel batches for wall-clock speed at unchanged per-item cost.",
          output:
            "The Loop extracts each document into strict JSON → it is filed in Knowledge with its fields and its source PDF → the daily sweep runs `renewal_alert` over everything tracked → an Output node sends the ACT_NOW and PLAN reminders on WhatsApp or SMS with the document attached → any payment waits at the Approval node and is completed by the person through a Razorpay UPI link.",
        },
      },
      {
        id: "money-tax-pack",
        title: "The tax-time document pack, assembled all year instead of hunted in April",
        scenario:
          "Every April is the same search: interest certificates in one inbox, rent receipts in a phone gallery, donation receipts in a WhatsApp thread, the investment statement never downloaded at all. Three evenings go into finding documents that all arrived months ago and were all, at the time, just closed and forgotten.",
        triggerLabel: "Email (certificates and statements, year-round) + scheduled Webhook (pre-filing assembly)",
        steps: [
          { kind: "trigger", title: "Catch it when it arrives", detail: "An Email trigger catches interest certificates, statements, receipts and salary documents as they land through the year; a WhatsApp forward covers the paper ones photographed on the spot." },
          { kind: "agent", title: "Tag it to the right year and category", detail: "An Agent node identifies the document type and the financial year it belongs to, from the document itself, and files it. A document that could belong to either year is flagged rather than filed on a guess." },
          { kind: "knowledge", title: "One folder that is actually one folder", detail: "A Knowledge node holds the year's pack, categorised — income, interest, rent, donations, insurance, investment statements — each with its source file." },
          { kind: "subworkflow", title: "Pre-filing assembly", detail: "A scheduled Sub-workflow runs before filing season: it walks the expected categories and produces the pack plus, crucially, the list of what is MISSING and who to request it from." },
          { kind: "condition", title: "Missing is the useful output", detail: "A Condition node separates complete categories from incomplete ones — the incomplete list is the actual value, because it is knowable in February rather than discovered in April." },
          { kind: "output", title: "Pack out, checklist out", detail: "An Output node delivers the categorised pack and the missing-documents checklist by Email, with reminders for the outstanding items on WhatsApp." },
          { kind: "approval", title: "Nothing is filed for you", detail: "An Approval node makes it explicit: the pack goes to the person or their accountant. The workflow does not file a return, does not log into a tax portal, and does not compute a tax position." },
        ],
        connectors: ["Email", "WhatsApp", "Knowledge base", "Approval / HITL", "Tool (MCP → tax portal, read-only where available)"],
        outcome:
          "The filing pack assembles itself through the year, and the list of what is still missing is known months before the deadline instead of the night before it.",
        impact:
          "The mechanism is shifting the work from a single April search to a per-document moment of arrival, and making absence visible — the expensive part of tax admin is not organising what you have, it is discovering in April what you never downloaded.",
        build: {
          agents: 2,
          agentsNote:
            "1 tagging agent (per document, year-round) + 1 assembly agent (once, pre-filing). The missing-category gate and the hand-off Approval are workflow nodes.",
          prompt:
            "(Tagger) Identify ONE document belonging to the user. Return strict JSON {category (SALARY|INTEREST|RENT|DONATION|INSURANCE|INVESTMENT|CAPITAL_GAINS|OTHER), issuer, financial_year, period_from, period_to, amount, reference, needs_review}. Determine the financial year from dates printed on the document only — if the document spans or ambiguously straddles two years, set needs_review=true rather than choosing. Never compute, estimate or total anything; you are categorising and dating, not calculating. Do NOT extract, store or echo any bank account number, card number, password, OTP or government identity number — mask them. Give no opinion on deductions, exemptions, regimes or tax position: that is the user's or their accountant's judgement, not yours.",
          agentRoles: [
            {
              role: "Document tagger (per document, all year)",
              model: "Economy tier — one short structured pass per document, spread across the year; with a fallback model so a certificate that arrives once annually is never lost to a transient failure",
              prompt:
                "Categorise and date one document into schema-valid JSON from printed dates only. Ambiguous year → needs_review. No totals, no tax opinions, sensitive numbers masked.",
            },
            {
              role: "Pack assembler (once, pre-filing)",
              model: "Standard tier — a single call that reasons over the whole year's collection",
              prompt:
                "Assemble the year's categorised pack index and, for each expected category, state whether it is present, partial or missing, naming the issuer to request the missing document from. List only what is actually in the collection — never assume a document exists, never fill a gap with an estimate, and produce no tax computation, no deduction advice and no filing recommendation.",
            },
          ],
          mcp: [
            "Tax-filing portal — no first-party connector; read-only reference via a Tool (MCP) node at most, and never written to or logged into by the workflow",
            "Banks, insurers and employers — documents arrive by Email or WhatsApp forward; portals are not scraped",
            "Email / WhatsApp — pack delivery and missing-document reminders",
          ],
          tools: [
            "knowledge_search (the year's filed documents, by category)",
            "send_email (deliver the pack and checklist to the person or, on approval, their accountant)",
            "user-defined tool `pack_ready` (SpEL): input.presentCategories >= input.expectedCategories ? 'COMPLETE' : (input.daysToDeadline <= 30 ? 'CHASE_NOW' : 'GATHERING') — a tenant-authored SpEL expression over the tool's JSON input, sandboxed by construction (no method calls, no constructors, no bean/type references, no loop in the grammar): a formula tool, not a scripting engine. The benefit is that the person defines which categories they personally expect — a salaried filer's list is not a freelancer's — and how close to the deadline counts as chase-time, once, and the pack is assessed against their own definition every year without a developer.",
          ],
          guardrails: [
            "NOT FINANCIAL OR TAX ADVICE, plainly: the agent organises, reminds, summarises and compares the user's OWN documents. It does not compute a tax position, does not advise on deductions, exemptions or regimes, and does not recommend financial products — the pack goes to the person or their accountant, who decide",
            "SECURITY: no bank account, card, password, OTP or government ID number is stored, echoed or typed; the workflow never logs into a tax portal and never submits a return or any other financial form",
            "Nothing is totalled or estimated by the agent — it categorises and dates printed documents; a straddling or ambiguous financial year is flagged for review rather than assigned",
            "The missing-documents list names only genuinely absent categories against the user's own expected set — a gap is never filled with an assumed or reconstructed document",
            "Hand-off to an accountant passes the Approval node, because sending a year of personal financial documents to a third party is the person's decision to make explicitly",
          ],
          cost:
            "Tagging is one economy-tier call per document amortised across a whole year — negligible per event, and worth a hard cap plus a fallback model because these documents often arrive exactly once. The assembly pass is a single standard-tier call per year. The completeness check itself is deterministic `pack_ready` SpEL, so the pack's status can be recomputed daily in the run-up to the deadline for nothing.",
          output:
            "Each document is tagged and filed into the year's Knowledge pack as it arrives → the pre-filing Sub-workflow assembles the categorised index and runs `pack_ready` → the Condition node surfaces the missing categories → an Output node emails the pack plus the chase-list and sends WhatsApp reminders for outstanding items → the Approval node governs any hand-off to an accountant.",
        },
      },
      {
        id: "money-subscription-sweep",
        title: "The subscriptions nobody cancelled",
        scenario:
          "Somewhere between four and nine small recurring charges leave the account every month. Some are used daily, some are a trial from two years ago that converted, and at least one is a duplicate of something the household already pays for. Nobody has ever sat down with a statement and read every line, because that is a genuinely tedious hour.",
        triggerLabel: "Email (card/bank statement) / WhatsApp (forward the statement PDF) + monthly sweep",
        steps: [
          { kind: "trigger", title: "The statement arrives", detail: "An emailed statement, or a PDF forwarded on WhatsApp. Bank and card portals have no first-party connector — the statement is a document the person supplies, or is fetched read-only by a Tool (MCP) node they explicitly connected." },
          { kind: "loop", title: "Read every line, once", detail: "A Loop node walks the statement lines so the tedious part — actually reading all of them — is done completely rather than skimmed." },
          { kind: "agent", title: "Identify the recurring ones", detail: "An Agent node groups lines into recurring charges by merchant and cadence, and matches them against the person's own filed receipts and confirmation emails in Knowledge to name what each one actually is." },
          { kind: "condition", title: "Known, unknown, or duplicated?", detail: "A Condition node splits the result three ways: recognised and expected; recognised but overlapping with another subscription the household already has; and unrecognised — the third group is the one that matters and it is presented as a question, never as an accusation." },
          { kind: "output", title: "One plain list", detail: "An Output node sends a plain monthly list on WhatsApp or Email: what recurs, how much, how long it has been running, and which ones the person may want to look at." },
          { kind: "approval", title: "Cancelling is yours to do", detail: "An Approval node holds any cancellation draft. The agent can draft the cancellation email; the person sends it, and cancels through the provider themselves. The workflow never submits a form on a provider's site and never touches payment credentials." },
        ],
        connectors: ["Email", "WhatsApp", "Knowledge base", "Approval / HITL", "Tool (MCP → card/bank statement source)"],
        outcome:
          "Every recurring charge on the statement is named, dated and grouped once a month, with duplicates and unrecognised ones surfaced as questions the person can answer in two minutes.",
        impact:
          "The mechanism is completeness where a human skims: the tedium of reading every statement line is precisely what makes forgotten subscriptions survive, and the agent's only real advantage here is that it does not get bored on line forty. It reports what the person's own documents say; it does not recommend what to keep.",
        build: {
          agents: 1,
          agentsNote:
            "1 statement-analysis agent, run per statement with a Loop over the lines. The three-way branch and the cancellation Approval are workflow nodes.",
          prompt:
            "Analyse the user's OWN statement lines. Group them into recurring charges: return strict JSON {merchant, cadence (MONTHLY|QUARTERLY|ANNUAL|IRREGULAR), amount, currency, first_seen, last_seen, occurrences, matched_receipt (from Knowledge or null), status (RECOGNISED|OVERLAPPING|UNRECOGNISED)}. Use ONLY what is printed on the statement and what is in the user's own filed receipts — never guess what an unfamiliar merchant descriptor is, mark it UNRECOGNISED and let the user say. Never state or imply that a charge is fraudulent, wasteful or should be cancelled; present unrecognised and overlapping charges as questions for the user. Do NOT extract, store or echo any card number, bank account number, CVV, password or OTP — mask them. Give no advice about which services to keep, switch to or buy.",
          agentRoles: [
            {
              role: "Statement analyser (per statement)",
              model: "Economy tier — line-level grouping over a bounded document, once a month; a fallback model keeps a month from being skipped entirely on a transient failure",
              prompt:
                "Group the user's own statement lines into recurring charges with schema-valid JSON. UNRECOGNISED for anything not matched to a filed receipt. No judgements, no advice, sensitive numbers masked.",
            },
          ],
          mcp: [
            "Card / bank statement — no first-party connector; supplied by the user as a document, or fetched read-only through a Tool (MCP) node they explicitly connected",
            "Subscription providers — not integrated; cancellation is done by the person on the provider's own site",
            "WhatsApp / Email — the monthly list and the cancellation drafts",
          ],
          tools: [
            "knowledge_search (the person's own receipts, confirmation emails and prior statements)",
            "send_email (cancellation drafts — released only by the Approval node)",
            "user-defined tool `sub_flag` (SpEL): input.matchedReceipt == false ? 'ASK_USER' : (input.overlapsExisting == true ? 'REVIEW' : 'KNOWN') — a tenant-authored SpEL expression over the tool's JSON input, sandboxed by construction: no method calls, no constructors, no bean or type references, no loop in the grammar. A formula tool, not a scripting engine. The benefit is that the triage rule is the person's own and it is deterministic — the same merchant is banded the same way every month, so the monthly list is comparable month to month instead of re-litigated by a model each time.",
          ],
          guardrails: [
            "NOT FINANCIAL ADVICE, plainly: the agent organises, reminds, summarises and compares the user's OWN statements and receipts. It never recommends which services to keep, cancel, switch to or buy, and it gives no personalised investment or product advice",
            "SECURITY: card numbers, account numbers, CVVs, passwords and OTPs are masked and never stored, echoed or typed; the workflow never logs into a provider or a bank and never auto-submits a cancellation or payment form",
            "An unfamiliar merchant descriptor is reported as UNRECOGNISED, not guessed and not labelled fraudulent — the person identifies it",
            "Cancellation emails are drafts held at the Approval node; the person sends them and cancels with the provider themselves",
            "Analysis is idempotent per statement, so re-forwarding a month can't double-count a subscription's occurrence history",
          ],
          cost:
            "One economy-tier pass per statement per month, hard-capped — this is a low-volume, high-tedium job, which is exactly the profile where a cheap model earns its place. The three-way triage runs on deterministic `sub_flag` SpEL rather than a second model call, so month-over-month comparability costs nothing.",
          output:
            "The Loop walks every statement line → the agent groups them into named recurring charges matched against the person's own receipts → `sub_flag` bands each one deterministically → an Output node sends one plain monthly list on WhatsApp or Email → any cancellation draft waits at the Approval node for the person to send and action themselves.",
        },
      },
      {
        id: "money-elder-paperwork",
        title: "A parent's paperwork, explained out loud in their own language",
        scenario:
          "A pension letter, a hospital bill and an insurance renewal notice arrive at a parent's house. They are in English, in small print, and the person who can read them lives four hundred kilometres away and finds out about them on a Sunday call, usually after the date on the letter has passed.",
        triggerLabel: "WhatsApp (photo of the letter) / Voice (\"what does this letter say?\")",
        steps: [
          { kind: "trigger", title: "A photo of the letter", detail: "The parent photographs the letter and sends it on WhatsApp, or calls in on Voice and asks. No app to learn, no portal to log into." },
          { kind: "agent", title: "Read it, say what it says", detail: "An Agent node extracts what the letter actually states — who sent it, what it is about, any amount, any date, and what if anything is being asked for. It summarises; it does not interpret or recommend." },
          { kind: "voice", title: "Answered in their language", detail: "A Voice node with Sarvam replies in the parent's own language — Hindi, Kannada, Tamil, Bengali — read aloud, at length if needed; ElevenLabs handles the spoken delivery. The point is that the explanation reaches them without a translator on a Sunday." },
          { kind: "condition", title: "Does it need someone to act?", detail: "A Condition node separates letters that are informational from letters with a date and an action — only the second kind escalates." },
          { kind: "output", title: "The family member is looped in", detail: "An Output node forwards the summary, the deadline and the photograph to the adult child on WhatsApp or Email — on the day the letter arrives, not on the Sunday call." },
          { kind: "approval", title: "No one's money moves automatically", detail: "An Approval node governs anything with a payment. A Razorpay UPI link is generated for a family member to complete deliberately. The agent never asks for, stores or repeats a card number, a bank password, an OTP or a government ID number — and it says so if a caller offers one." },
        ],
        connectors: ["WhatsApp", "Voice / Call", "Sarvam", "ElevenLabs", "Email", "Approval / HITL", "Razorpay (UPI)"],
        outcome:
          "A letter is understood on the day it arrives, in the language the person actually thinks in, and the family member who needs to act knows about the deadline while there is still time to act on it.",
        impact:
          "The mechanism is closing the gap between a letter arriving and a family member learning about it — that gap, not the letter's difficulty, is what turns a routine renewal into a lapsed one. Summarising a document is not advising on it, and the agent holds that line explicitly.",
        build: {
          agents: 1,
          agentsNote:
            "1 reader/explainer agent handling both the extraction and the spoken reply. Voice, the action branch, the family notification and the payment Approval are workflow nodes.",
          prompt:
            "You are helping an elderly person understand ONE letter or bill they have received, speaking in their own language. Say what the document actually states: who sent it, what it concerns, the amount if any, the date if any, and what is being asked for. Use plain, unhurried language and offer to repeat. Read ONLY what is printed — if something is unclear or missing, say so rather than filling it in. NEVER give financial, insurance, medical, legal or tax advice, never suggest buying, switching, claiming or cancelling anything, and never say whether a charge is fair. NEVER ask for — and never repeat back, store or type — a card number, bank account number, password, OTP or government identity number; if the person starts to read one out, stop them and explain that it is not needed and should not be shared. You do not pay anything and you do not fill in any form: a family member reviews and completes anything that involves money.",
          agentRoles: [
            {
              role: "Reader / explainer (per document)",
              model: "Standard tier — the spoken turns need to be responsive, and the extraction is short; keep a fallback model configured, because the person on the other end is on the phone and a failed call has a real cost",
              prompt:
                "Extract and explain one letter in plain language in the person's own tongue. Printed facts only. No advice of any kind. Never solicit or repeat card numbers, passwords, OTPs or ID numbers; stop the caller if they begin to.",
            },
          ],
          mcp: [
            "Sarvam — Indian-language understanding and reply",
            "ElevenLabs — spoken delivery on the Voice channel",
            "Razorpay (UPI) — payment link only, generated after Approval and completed by a family member",
            "Insurer / hospital / pension portals — no first-party connector and deliberately not integrated; the letter itself is the input",
          ],
          tools: [
            "knowledge_search (the family's own filed policies and prior letters, so a renewal can be matched to the policy it belongs to)",
            "send_email (forward the summary and the photograph to the family member)",
          ],
          guardrails: [
            "NOT FINANCIAL ADVICE, plainly and out loud: the agent organises, reminds, summarises and compares the family's OWN documents. It gives no personalised investment advice, recommends no financial product, and offers no opinion on whether to renew, claim, switch or pay",
            "SECURITY, spoken as well as stored: the agent never asks for, repeats, stores or types a card number, bank account number, password, OTP or government ID number, and it actively interrupts and warns if the caller starts to read one out — elderly callers are a targeted group and this is the guardrail that matters most here",
            "No financial form is auto-submitted and no payment is automatic: an Approval node holds anything involving money, a Razorpay UPI link is generated, and a named family member completes it deliberately",
            "The summary states only what is printed on the document; anything unclear is reported as unclear rather than inferred, because a confidently wrong deadline is the specific harm this recipe exists to prevent",
            "The family notification carries the original photograph alongside the summary, so the person acting can always check the source document themselves",
          ],
          cost:
            "One standard-tier agent per letter, with the Voice channel's minutes — not tokens — being the meaningful cost; cap the call length rather than downgrading the model, since comprehension for an elderly caller is the entire product. Sarvam handles the language layer, so no premium-tier model is needed to reach a non-English speaker, which is what makes running this for a parent affordable at all.",
          output:
            "The photographed letter is read into printed facts → a Voice node replies in the parent's own language through Sarvam and ElevenLabs, repeating on request → the Condition node escalates only letters with a date and an action → an Output node forwards the summary, deadline and photograph to the family member the same day → anything involving money waits at the Approval node and is completed by a person through a Razorpay UPI link.",
        },
      },
    ],
  },
];
