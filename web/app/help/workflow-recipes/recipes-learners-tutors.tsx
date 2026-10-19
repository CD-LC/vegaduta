import type { Industry } from "./recipes-data";
import { BookMarked, Presentation } from "lucide-react";

// ---------------------------------------------------------------------------
// Individual learners & individual teachers/tutors — same "inline copy, no i18n
// keys" precedent and honesty rules as recipes-data.tsx. These two packs are
// deliberately NOT the institutional ones: "Education & EdTech", "Schools
// (K-12)" and "Colleges & universities" already cover the institution. Here the
// user is one student revising for their own exam, or one teacher / tutor /
// small coaching centre running their own batch.
//
// Every recipe is expressed ONLY in VegaDūta's real building blocks (Trigger /
// Agent / Knowledge / Condition / Approval / Output / Tool(MCP) / Voice /
// Device / Loop / Code / Parallel / Supervisor / Sub-workflow), so each maps
// 1:1 to something you can actually build in the Workflow designer.
//
// Honesty rules carried over verbatim in posture:
//   • Only real first-party channels (Webhook, WhatsApp, SMS/Twilio, Voice/Call,
//     Email, Slack, Discord, Telegram, Instagram, Messenger, X, ElevenLabs,
//     Sarvam, Razorpay UPI). An LMS, an exam board, a school ERP or a school app
//     has NO first-party connector — it arrives as an inbound Webhook and/or is
//     read via a Tool (MCP) node, and is LABELLED as such every time.
//   • Impact lines describe the MECHANISM. No invented ROI, no invented
//     "marks improved by X%" — nobody can honestly promise that.
//   • ACADEMIC INTEGRITY is the non-negotiable spine of the student pack: the
//     agent explains, generates PRACTICE questions on similar problems, gives
//     hints and worked examples, and checks understanding. It does NOT write the
//     assignment that gets submitted for marks. This guardrail is on every
//     single student recipe, and it is stated in the tagline too.
//   • Grounding: answers come from the learner's / tutor's OWN Knowledge (their
//     syllabus, textbook, class notes) via knowledge_search. If it isn't in the
//     material, the agent says so rather than inventing it.
//   • Anything that reaches a parent, any fee or receipt, and any published mark
//     passes an Approval node. The teacher owns the assessment — always.
//
// User-defined tools referenced below are the real feature: a tenant-authored
// SpEL expression over the tool's JSON input, sandboxed by construction — no
// method calls, no constructors, no bean or type references, and no loop in the
// grammar. It is a data-transform / formula tool, not a scripting engine. That
// is exactly enough for "encode MY revision cadence" or "encode MY grade bands"
// without a developer and without a redeploy.
// ---------------------------------------------------------------------------

export const INDUSTRIES_LEARNING: Industry[] = [
  // ============================================= STUDENTS & SELF-LEARNERS
  {
    id: "students",
    name: "Students & self-learners",
    icon: BookMarked,
    tagline:
      "A patient tutor at midnight, in your own language, grounded in your own syllabus and notes — it explains, drills you and sets practice questions, and it will not write the assignment you have to submit.",
    recipes: [
      {
        id: "study-notes-to-knowledge",
        title: "Three notebooks and a camera roll → one searchable revision base",
        scenario:
          "Your notes are in three different notebooks, a photo gallery full of blackboard snaps, a couple of PDFs the teacher shared on WhatsApp, and a Word file from a friend. Two nights before the test you are scrolling the gallery trying to remember which photo had the derivation on it.",
        triggerLabel: "WhatsApp / Telegram / Email (send a photo, PDF or note to your own agent)",
        steps: [
          { kind: "trigger", title: "Send it in as it happens", detail: "A WhatsApp, Telegram or Email trigger accepts whatever you send after class — a blackboard photo, a scanned page, the teacher's PDF, a typed line. No filing, no folder discipline; you forward it and move on." },
          { kind: "agent", title: "Read it and label it", detail: "An Agent node runs OCR/extraction on the image or PDF, then tags it against your syllabus: subject, chapter, topic, and whether it's a definition, a derivation, a worked example or a question. Anything it can't read confidently is flagged rather than guessed." },
          { kind: "condition", title: "Legible and placeable?", detail: "A Condition node branches: a clean, confidently-tagged item goes straight into your Knowledge base; a blurry photo or an item it can't place on the syllabus comes back to you with one specific question ('which chapter is this from?')." },
          { kind: "knowledge", title: "File into your own Knowledge base", detail: "A Knowledge node stores the extracted text with its syllabus tags, so every later answer can be grounded in YOUR material — your teacher's wording, your board's notation — instead of a generic internet answer." },
          { kind: "output", title: "Weekly 'what's still missing' note", detail: "An Output node sends you a short weekly message on WhatsApp: chapters with no notes at all, chapters with only questions and no worked examples, and the pages that came back unreadable and need re-shooting." },
        ],
        connectors: ["WhatsApp", "Telegram", "Email", "Knowledge base", "Sarvam (Indian languages)"],
        outcome: "Everything you were going to revise from lives in one place, tagged to the actual syllabus, and you find out which chapters have gaps while there's still time to fix them.",
        impact: "The mechanism is simple: capture at the moment it happens (a forward, not a filing session) plus extraction and syllabus tagging, so search works on content rather than on you remembering which notebook. The weekly gap note surfaces missing chapters early instead of on the night before.",
        build: {
          agents: 2,
          agentsNote: "1 extraction/tagging agent (runs once per item you send) + 1 weekly gap-summary agent. The legibility gate and the Knowledge write are workflow nodes.",
          pattern: "Ingest-per-item → Condition (legibility/placement gate) → Knowledge; scheduled weekly summary pass",
          prompt:
            "You are a study-notes librarian for ONE student. Given an incoming item (image, PDF page, or text), extract the readable content and tag it against the syllabus in Knowledge: {subject, chapter, topic, kind (DEFINITION|DERIVATION|WORKED_EXAMPLE|QUESTION|DIAGRAM|OTHER), confidence}. Transcribe only what is actually legible — never complete a half-visible formula, never fill in a cut-off line. If the image is unclear or you cannot place it on the syllabus, return {needs_input: true, question} with ONE specific question. You organise material; you do not answer it here.",
          agentRoles: [
            { role: "Extractor / tagger (per item)", model: "Economy tier — runs on every photo and page you ever send, so this model choice is the whole cost story", prompt: "Transcribe only what is legible and tag it to the syllabus. Confidence score on every tag. Never invent the unreadable part of a formula or a cut-off sentence — flag it for a re-shoot instead." },
            { role: "Weekly gap summariser (once a week)", model: "Standard tier — needs to reason over syllabus coverage", prompt: "Compare the syllabus in Knowledge against what has actually been filed. Report chapters with zero notes, chapters with questions but no worked examples, and items flagged unreadable. State only what the data shows — no guesses about how well the student knows a chapter." },
          ],
          mcp: ["School / coaching LMS or class group (shared PDFs) — inbound via Webhook, or read via a Tool (MCP) node; there is no first-party LMS connector", "WhatsApp / Telegram / Email — the capture channel"],
          tools: [
            "knowledge_search (your syllabus outline, so tagging is grounded in the real chapter list)",
            "document extraction / OCR (photos of blackboards and scanned pages)",
            "user-defined tool `note_confidence_band` (SpEL): input.ocrConfidence > 0.9 ? 'FILE' : (input.ocrConfidence > 0.6 ? 'FILE_WITH_FLAG' : 'RESHOOT') — you set your own thresholds for how blurry is too blurry, and change them later without a developer or a redeploy",
          ],
          guardrails: [
            "ACADEMIC INTEGRITY: this workflow only organises YOUR material. It transcribes and tags; it does not answer questions, write solutions, or produce anything submittable — that boundary holds across every recipe in this pack",
            "Transcription is literal — an illegible formula or a cut-off line is flagged for a re-shoot, never completed from guesswork",
            "Grounding: chapter/topic tags come only from the syllabus you loaded into Knowledge. If an item doesn't map to anything in your syllabus, it asks rather than inventing a chapter",
            "Items stay in your own private Knowledge base; nothing is published or shared anywhere by this workflow",
          ],
          cost:
            "This is the recipe that runs most often, so keep the extractor on the economy tier — one cheap call per photo or page, and nothing else per item. The only standard-tier call is the single weekly gap summary. A student photographing a few boards a day lands in 'a handful of economy calls a day plus one reasoning call a week' territory; there is no premium-tier work anywhere in this workflow, and none is needed.",
          output:
            "Each item you forward gets one economy-tier extraction pass → the Condition node either files it into your Knowledge base with syllabus tags or bounces it back with one specific question → once a week an Output node sends a short WhatsApp note listing the chapters with no notes, the chapters with no worked examples, and the pages that need re-shooting.",
        },
      },
      {
        id: "study-midnight-doubt",
        title: "Midnight doubt, answered in your own language — from your own textbook",
        scenario:
          "It's 11:40pm, you're stuck on one step of a problem, and the class WhatsApp group is where doubts go to be answered by whoever happens to be awake — sometimes right, sometimes confidently wrong, often just a photo of someone else's completed answer that teaches you nothing.",
        triggerLabel: "WhatsApp / Telegram / Voice call (ask a doubt any time)",
        steps: [
          { kind: "trigger", title: "Ask, in text or by voice", detail: "A WhatsApp/Telegram message or a Voice call trigger takes the doubt — typed, spoken, or as a photo of the question. Sarvam handles the Indian-language side, so the question can come in Hindi, Kannada, Tamil, Telugu, Marathi, Bengali and be answered in the same language." },
          { kind: "knowledge", title: "Ground it in your material first", detail: "A Knowledge node pulls the relevant passage from YOUR syllabus, textbook and class notes via knowledge_search. The explanation is built from your board's notation and your teacher's definitions — not a generic version that contradicts them." },
          { kind: "condition", title: "Is this a doubt, or is this the assignment?", detail: "A Condition node classifies the request. 'Explain why this step works' is a doubt. 'Solve Q7 from tomorrow's submission' is graded work — that branch does NOT get a solution; it gets the concept, a worked example on a SIMILAR problem, and the first hint." },
          { kind: "agent", title: "Explain, at the level you're at", detail: "An Agent node explains the concept step by step, using the grounded passage, then works a similar example end to end. If the material doesn't cover it, it says so plainly instead of inventing an answer." },
          { kind: "voice", title: "Hear it out loud if reading isn't landing", detail: "A Voice node (ElevenLabs for the delivery, Sarvam for Indian-language understanding) reads the explanation back — useful for a concept that makes more sense heard than read, and for anyone revising while commuting." },
          { kind: "agent", title: "Check you actually got it", detail: "A second, cheap agent turns the explanation into two quick check-questions and marks your answers — so the session ends with evidence you understood, not just a feeling that you did." },
        ],
        connectors: ["WhatsApp", "Telegram", "Voice / Call", "Sarvam (Indian languages)", "ElevenLabs (voice)", "Knowledge base"],
        outcome: "A doubt at midnight gets a grounded, step-by-step explanation in the language you think in, plus a similar worked example and two check-questions — and the graded question itself stays yours to answer.",
        impact: "The mechanism is availability plus grounding plus a hard boundary: the material it explains from is your own syllabus and notes (so it matches what you'll be marked against), the language is your own (so the concept isn't lost in translation before it's understood), and the integrity gate means the thing you submit is still your work. No claim is made about your marks — what changes is that a stuck moment at 11:40pm has somewhere to go.",
        build: {
          agents: 2,
          agentsNote: "1 explainer agent + 1 cheap comprehension-check agent. The integrity classifier is a Condition node on the explainer's own structured output; the language and voice handling are channel features, not extra agents.",
          pattern: "Grounded explain → Condition (integrity gate) → worked example on a similar problem → comprehension check",
          prompt:
            "You are a patient tutor for ONE student, working ONLY from their syllabus, textbook and class notes in Knowledge. First classify the request: {intent: CONCEPT_DOUBT | STEP_DOUBT | GRADED_WORK}. If intent=GRADED_WORK (a question from a homework/assignment/test that will be submitted for marks), you MUST NOT produce the answer to that question. Instead: explain the underlying concept, work a SIMILAR problem fully, give the first hint for theirs, and say clearly that the submitted answer has to be their own. For any intent, ground the explanation in the retrieved passage and use your board's notation. If the material does not cover it, say 'this isn't in your notes' and point at what is — never fill the gap with something you invented. Answer in the language the student used. End with two short check-questions.",
          agentRoles: [
            { role: "Explainer", model: "Standard tier by default; premium tier only for the genuinely hard explanations (a multi-step derivation, an abstract proof) routed there by the Condition node", prompt: "Explain step by step from the retrieved passage. Match the student's language and their board's notation. On graded work: concept + similar worked example + first hint only, never the submitted answer. Say plainly when the material doesn't cover it." },
            { role: "Comprehension checker", model: "Economy tier — two short questions and marking them; it runs on every single doubt, so it must be cheap", prompt: "From the explanation just given, write two short check-questions and mark the student's answers with a one-line reason. Encourage the retry rather than giving the answer straight away." },
          ],
          mcp: ["Class group / coaching LMS where the teacher posts material — inbound via Webhook or read via a Tool (MCP) node (no first-party LMS connector)", "WhatsApp / Telegram / Voice — the ask channel", "Sarvam — Indian-language understanding; ElevenLabs — spoken delivery"],
          tools: [
            "knowledge_search (your syllabus, textbook and class notes — the only source of truth for an answer)",
            "document extraction (read a photographed question)",
            "user-defined tool `explain_depth` (SpEL): input.attemptCount > 2 ? 'FULL_WORKED_SIMILAR_EXAMPLE' : (input.gradeLevel < 9 ? 'SIMPLE' : 'STANDARD') — you encode your own escalation rule, so the third time you're stuck on the same thing it changes how it explains, without anyone touching code",
          ],
          guardrails: [
            "ACADEMIC INTEGRITY (hard rule): the agent will NOT write, solve or draft the graded work a student submits. On anything classified as GRADED_WORK it gives the concept, a fully worked SIMILAR problem, and the first hint — and states plainly that the submitted answer must be the student's own",
            "Grounding: every explanation is built from the student's own syllabus/textbook/notes retrieved via knowledge_search. If the material doesn't cover it, the agent says so instead of inventing an answer",
            "No invented facts, formulae or notation — where the student's board differs from the general convention, the board's version in Knowledge wins",
            "Language honesty: Sarvam handles the Indian-language turn, but if the agent is not confident it understood the question, it asks again rather than answering a question it guessed at",
            "It never claims a mark, grade or outcome — it explains and checks understanding, nothing more",
          ],
          cost:
            "This is the highest-volume workflow a student will run, so the tiering matters more than anywhere else in this pack. Constant doubt-answering sits on the standard tier and the comprehension-check agent sits on the economy tier — that check runs on literally every doubt, so a premium model there would double the bill for two short questions. Premium tier is reserved for the Condition-routed hard explanations only (the multi-step derivation, the proof), which are a small fraction of asks. A student asking a dozen doubts a night stays affordable precisely because the default is standard + economy and premium is the exception, not the setting.",
          output:
            "The doubt arrives on WhatsApp, Telegram or by voice → knowledge_search grounds it in the student's own material → the Condition node's integrity gate decides whether this is a doubt (explain fully) or graded work (concept + similar worked example + first hint only) → the explanation comes back in the student's own language, optionally read aloud via ElevenLabs → an economy-tier agent closes with two check-questions and marks the answers.",
        },
      },
      {
        id: "study-syllabus-plan",
        title: "The syllabus PDF nobody mapped → a revision plan that knows what you've actually done",
        scenario:
          "The syllabus is a PDF you downloaded in April and opened twice. Nobody has ever mapped it against what you've actually studied, so 'revision' means starting at chapter one again every time and never reaching the last three chapters, which is where the questions you keep losing marks on live.",
        triggerLabel: "Webhook / scheduled (weekly plan) + WhatsApp (log what you studied)",
        steps: [
          { kind: "knowledge", title: "Load the syllabus once, properly", detail: "A Knowledge node holds the syllabus broken into chapters and topics — the real, official list, extracted from the PDF, not a remembered version of it." },
          { kind: "trigger", title: "Log study as it happens", detail: "A WhatsApp trigger takes one-line logs ('did integration by parts, 40 min, felt shaky') and a Webhook takes any test/quiz scores you or your coaching centre push in. The score source is an inbound Webhook or a Tool (MCP) node — there is no first-party connector for a coaching-centre portal." },
          { kind: "loop", title: "Score every topic on the syllabus", detail: "A Loop node walks every topic and calls a cheap agent per topic to combine coverage (studied? how long ago?) and confidence (last practice score, your own shaky/solid flag) into a state." },
          { kind: "code", title: "Apply YOUR revision cadence", detail: "A user-defined tool node runs your own SpEL formula over each topic's JSON — your thresholds for what counts as urgent, your spacing interval — so the plan reflects how you actually revise, not a default someone else picked." },
          { kind: "agent", title: "Turn states into this week's plan", detail: "An Agent node builds a realistic week: what to revise, in what order, roughly how long, and which topics have never been touched at all — explicitly flagged rather than quietly left out." },
          { kind: "output", title: "Sent where you'll see it", detail: "An Output node sends the week's plan on WhatsApp or Telegram every Sunday, with a one-line reason next to each item so you can argue with it and adjust." },
        ],
        connectors: ["Webhook", "WhatsApp", "Telegram", "Knowledge base", "Tool (MCP → coaching-centre / LMS score feed)"],
        outcome: "A weekly, syllabus-complete revision plan that ranks by how long ago you studied something and how you actually scored on it — with never-touched chapters called out by name instead of silently skipped.",
        impact: "The mechanism is coverage tracking plus a spacing rule you define yourself. Restarting at chapter one is what happens when nothing tracks coverage; once every topic carries a 'last studied' and a 'last score', the ordering falls out of the data. No promise is made about results — the honest claim is that the last three chapters stop being invisible.",
        build: {
          agents: 2,
          agentsNote: "1 cheap per-topic scoring agent (via the Loop node) + 1 planner agent that runs once a week. The revision-priority formula is a user-defined tool, not an agent — deterministic and free to run.",
          pattern: "Loop (score every syllabus topic) → user-defined SpEL priority formula → single weekly planner pass",
          prompt:
            "(Per-topic scorer) For ONE syllabus topic, combine the study log and any practice/test scores into strict JSON {topic, lastStudiedDays, lastScore, selfRating, status (NEVER_TOUCHED|SHAKY|OK|SOLID), evidence}. Use ONLY logged data — if a topic has no log and no score, status=NEVER_TOUCHED with lastScore=null. Never estimate a score the student never took, and never infer confidence from a topic being 'easy'.",
          agentRoles: [
            { role: "Per-topic scorer (runs once per syllabus topic)", model: "Economy tier — it runs across the entire syllabus on every planning pass, so it is the dominant cost lever here", prompt: "Emit strict JSON per topic from logged evidence only. Nulls where there is no data. Zero invented scores." },
            { role: "Weekly planner (runs once)", model: "Standard tier — needs to sequence and be realistic about time", prompt: "Build one week's revision plan from the prioritised topic list. Respect the hours the student says they actually have. Give a one-line reason per item. Name the NEVER_TOUCHED topics explicitly rather than pushing them to next week again." },
          ],
          mcp: ["Coaching-centre portal / LMS / exam-board result feed — inbound via Webhook or read via a Tool (MCP) node; none of these have first-party connectors", "WhatsApp / Telegram — logging and delivery"],
          tools: [
            "knowledge_search (the official syllabus chapter/topic list)",
            "user-defined tool `revision_priority` (SpEL): input.lastScore < 50 ? 'DAILY' : (input.daysSinceReview > 14 ? 'REVISE_NOW' : 'ON_TRACK') — a sandboxed expression over the tool's JSON input (no method calls, no constructors, no bean or type references, no loops in the grammar). It is a formula, not a script — which is exactly what a revision cadence is. Change 50 to 60, or 14 days to 10, and your plan changes on the next run with no developer and no redeploy",
            "http_request (pull test scores pushed in by a coaching centre)",
          ],
          guardrails: [
            "ACADEMIC INTEGRITY: this workflow plans and prioritises revision. It does not produce answers, solutions or anything submittable — planning what to study never becomes doing the graded work",
            "The topic list comes from the official syllabus in Knowledge — the plan can never quietly drop a chapter that exists on the syllabus, and never-studied topics are named explicitly",
            "Scores and coverage come only from logged data; a topic with no log is NEVER_TOUCHED, never an optimistic guess",
            "The priority rule is your visible SpEL expression, not a hidden model judgement — you can read it, change it, and know exactly why a topic was ranked where it was",
          ],
          cost:
            "The per-topic scorer runs across the whole syllabus (often 60–150 topics) on every weekly pass, so it must be economy tier — that one choice is essentially the entire budget for this recipe. The SpEL priority tool is deterministic and costs nothing per call. Only the weekly planner needs the standard tier, and it runs once a week. Net effect for a student on a tight budget: one cheap sweep plus one reasoning call, weekly.",
          output:
            "A Loop node scores every syllabus topic with an economy-tier agent → your own `revision_priority` SpEL formula turns each topic's JSON into DAILY / REVISE_NOW / ON_TRACK → a standard-tier planner sequences a realistic week → an Output node sends it on WhatsApp every Sunday with a one-line reason per item and the never-touched chapters named.",
        },
      },
      {
        id: "study-practice-paper",
        title: "Practice questions and mock papers generated from your own material",
        scenario:
          "Past papers cost money, run out, or cover a syllabus that changed two years ago. So practice means re-doing the same solved examples in the textbook — which you now half-remember the answers to, which means you're testing recall of the answer, not of the method.",
        triggerLabel: "WhatsApp / scheduled (\"quiz me on thermodynamics\")",
        steps: [
          { kind: "trigger", title: "Ask for practice, or get it on a schedule", detail: "A WhatsApp message ('quiz me on chapter 4') or a scheduled trigger kicks it off — a short set every evening, or a full mock on Sunday." },
          { kind: "knowledge", title: "Pull the source material", detail: "A Knowledge node retrieves the chapter from YOUR textbook, notes and syllabus, so the questions test what your board actually examines and use the notation your paper will use." },
          { kind: "parallel", title: "Generate across question types at once", detail: "A Parallel node fans out generation by type — short answer, numerical, derivation, MCQ — so a mixed paper is built in one pass rather than four sequential rounds." },
          { kind: "agent", title: "Write questions and a marking scheme", detail: "An Agent node writes each question WITH its expected answer and a mark breakdown — the marking scheme is what makes self-marking honest, and it's kept hidden until you've attempted the question." },
          { kind: "condition", title: "Attempted before revealing", detail: "A Condition node holds the solution until you've submitted an attempt. You can ask for a hint at any point; the full worked solution unlocks after the attempt, not before." },
          { kind: "output", title: "Marked, with the specific gap named", detail: "An Output node returns your marked attempt: what was right, where exactly the method went wrong, and which topic to send back into the revision plan." },
        ],
        connectors: ["WhatsApp", "Telegram", "Knowledge base", "Sarvam (Indian languages)", "Email"],
        outcome: "Unlimited fresh practice on your own syllabus, with a marking scheme, hints on demand, and a named weak topic at the end of every set — instead of re-reading examples whose answers you already remember.",
        impact: "The mechanism is generation from your own grounded material plus a reveal gate. Re-reading solved examples tests recognition; a new question on the same method tests the method. Holding the solution until after the attempt is what keeps that distinction real. No score improvement is promised — what's promised is questions that don't run out and that match your syllabus.",
        build: {
          agents: 3,
          agentsNote: "1 question-generator (runs in Parallel per question type) + 1 marking agent + 1 hard-explanation agent used only when an attempt goes wrong in a way the marking agent can't explain simply.",
          pattern: "Parallel generation by question type → attempt gate (Condition) → marking → escalate the hard explanation only when needed",
          prompt:
            "You are a practice-paper setter working ONLY from this student's syllabus, textbook and notes in Knowledge. Generate NEW questions in the requested style — never reproduce a question verbatim from a live assignment or an ongoing exam. For each question return {question, type, marks, expected_answer, marking_scheme[], topic, difficulty}. The expected answer and marking scheme are for the workflow to hold back until the student has attempted it. Use your board's notation and stay strictly within the syllabus scope in Knowledge — if the requested topic isn't in the material, say so rather than generating from general knowledge.",
          agentRoles: [
            { role: "Question generator (one per question type, in Parallel)", model: "Economy tier — question generation is high-volume and repetitive, and this is where a budget quietly disappears if you tier it wrong", prompt: "Generate NEW questions of one type, in-syllabus, with expected answer and mark breakdown. Never copy a live assignment question. Never go outside the material in Knowledge." },
            { role: "Marker", model: "Economy tier — runs on every attempt; short, structured comparison against the marking scheme", prompt: "Mark the attempt against the marking scheme. Name the exact step where the method went wrong, not just 'incorrect'. Return the topic to push back into the revision plan." },
            { role: "Hard-explanation escalation", model: "Premium tier — invoked ONLY when the marker flags that a mistake needs a real conceptual explanation", prompt: "The student got this wrong in a way that suggests a conceptual gap. Explain the underlying idea from the retrieved material, then work one similar problem end to end." },
          ],
          mcp: ["Coaching-centre / LMS material feed — inbound via Webhook or via a Tool (MCP) node (no first-party connector)", "WhatsApp / Telegram / Email — delivery and attempts"],
          tools: [
            "knowledge_search (your textbook, notes and syllabus — the only permitted source for questions)",
            "user-defined tool `set_difficulty_mix` (SpEL): input.lastSetScore > 80 ? 'HARD_HEAVY' : (input.lastSetScore < 45 ? 'BASICS_FIRST' : 'BALANCED') — your own progression rule over the tool's JSON input; adjust the thresholds yourself and the next set's mix changes, no developer, no redeploy",
          ],
          guardrails: [
            "ACADEMIC INTEGRITY (hard rule): this generates PRACTICE questions on the same methods — it does not answer the assignment or exam the student has to submit. If a student pastes in a live assignment question, the workflow generates a similar practice question and explains the method, and says plainly that the submitted answer must be their own",
            "Questions are generated only from the student's own syllabus and material in Knowledge; if a topic isn't in the material, the agent says so rather than pulling from general knowledge",
            "The marking scheme and worked solution stay hidden until an attempt is submitted — hints are available before, the full solution is not",
            "No mark, grade, rank or predicted-score claim is ever made — the output is 'you missed this step, revise this topic'",
          ],
          cost:
            "Generation and marking both sit on the economy tier, deliberately: they are the two things that run constantly, and putting a big model on either is how a student's monthly bill goes from small to painful for no learning benefit — a structured question with a mark breakdown does not need a premium model. Premium is spent in exactly one place, escalated by the marker: the conceptual explanation after a wrong attempt, which is the one moment where model quality genuinely changes what the student takes away. Parallel generation improves wall-clock time without changing per-question cost.",
          output:
            "A Parallel node generates the mixed set with an economy-tier agent per question type → the Condition node holds each solution until an attempt is submitted (hints available meanwhile) → an economy-tier marker returns the marked attempt naming the exact failed step → genuinely conceptual failures escalate once to a premium-tier explanation with a worked similar problem → the weak topic is pushed back into the revision plan.",
        },
      },
    ],
  },

  // ================================= TEACHERS, TUTORS & COACHING CENTRES
  {
    id: "tutors",
    name: "Teachers, tutors & coaching centres",
    icon: Presentation,
    tagline:
      "Give back the evenings lost to marking sixty identical worksheets, retyping question papers and returning parent calls — with every mark, every parent message and every fee receipt approved by you before it goes out.",
    recipes: [
      {
        id: "tutor-worksheet-marking",
        title: "Sixty identical worksheets, marked in one pass — you approve every mark",
        scenario:
          "You set the same worksheet to a batch of sixty. Now sixty photographed or scanned sheets sit in a WhatsApp thread, and you're marking the same five questions sixty times over, by hand, at 10pm — while the actually useful information (which question the whole batch got wrong) never gets extracted because you're too deep in the marking to see the pattern.",
        triggerLabel: "WhatsApp / Email / Webhook (submissions come in)",
        steps: [
          { kind: "trigger", title: "Submissions arrive", detail: "A WhatsApp, Email or Webhook trigger collects the scans/photos. An LMS submission feed has no first-party connector — it comes in as a Webhook or is pulled via a Tool (MCP) node." },
          { kind: "knowledge", title: "Load YOUR marking scheme", detail: "A Knowledge node holds the answer key and mark breakdown you wrote. Every mark traces back to your scheme — the agent has no independent opinion about what the right answer is." },
          { kind: "loop", title: "One pass per student", detail: "A Loop node runs a cheap marking agent once per submission — transcribe the working, compare against your scheme, award provisional marks per question with the reason." },
          { kind: "condition", title: "Unclear or borderline?", detail: "A Condition node pulls out the ones that need your eye: unreadable handwriting, an unconventional-but-possibly-valid method, an answer that's right by a route your scheme doesn't cover. These go to the top of your review queue rather than being force-fitted to the key." },
          { kind: "approval", title: "You approve the marks", detail: "An Approval node holds the entire mark sheet. Nothing is published, recorded or shown to a student or parent until you've reviewed and signed off — you own the assessment, always." },
          { kind: "agent", title: "The pattern you couldn't see", detail: "After approval, a second agent summarises the batch: which question the batch failed, the common wrong method, and the three students who need a different explanation rather than more practice." },
        ],
        connectors: ["WhatsApp", "Email", "Webhook", "Knowledge base", "Approval / HITL", "Tool (MCP → LMS / school ERP)"],
        outcome: "A batch of sixty comes back as a provisional mark sheet with per-question reasons, a short queue of genuinely ambiguous scripts flagged for you, and a batch-level diagnosis of what to re-teach — all held behind your approval.",
        impact: "The mechanism is a per-student loop against your own marking scheme plus a reduce step. Marking the same five questions sixty times is mechanical comparison, which is what a loop is for; spotting that forty of them failed question 3 the same way is aggregation, which nobody can do while marking sequentially by hand. Your judgement stays in the loop exactly where it matters — the ambiguous scripts and the final sign-off.",
        build: {
          agents: 2,
          agentsNote: "1 marking agent (runs once per submission via the Loop node) + 1 batch-analysis agent (runs once, after approval). The ambiguity gate and the mark approval are workflow nodes.",
          pattern: "Loop (mark per student) → Condition (ambiguity gate) → teacher Approval → reduce (batch diagnosis)",
          prompt:
            "You are a marking assistant for ONE teacher. Mark ONE submission strictly against the marking scheme in Knowledge — that scheme is the only authority on what is correct. Return {student_ref, per_question:[{q, awarded, max, reason, evidence_quote}], total, flags[]}. Award marks only where the scheme supports it. If the handwriting is unreadable, if the method is valid but not covered by the scheme, or if the answer is right by an unlisted route, do NOT force a mark — set flags and route it to the teacher. Every mark you produce is PROVISIONAL until the teacher approves. Never message a student or parent.",
          agentRoles: [
            { role: "Marker (per submission)", model: "Economy tier — it runs once per student in the batch, so with sixty submissions this single choice is the whole cost of the recipe", prompt: "Compare one script against the teacher's scheme. Per-question marks with a reason and a quoted piece of the student's own working as evidence. Flag rather than guess on unreadable or unconventional work." },
            { role: "Batch analyst (once)", model: "Standard tier — needs to spot the pattern across the whole batch", prompt: "From the approved mark sheet, report: the questions the batch failed, the specific common wrong method, and the students whose errors differ from the batch pattern and need individual attention. Use only the marked data — no speculation about ability or effort." },
          ],
          mcp: ["School LMS / ERP / gradebook — via a Tool (MCP) node for the mark write-back, or inbound via Webhook for submissions; no first-party connector exists for these", "WhatsApp / Email — submission collection"],
          tools: [
            "knowledge_search (YOUR answer key and mark breakdown)",
            "document extraction / OCR (photographed and scanned scripts)",
            "user-defined tool `mark_flag` (SpEL): input.ocrConfidence < 0.7 || input.methodMatched == false ? 'TEACHER_REVIEW' : 'AUTO_PROVISIONAL' — you set the exact bar at which a script must reach your eyes, and change it mid-term without a developer or a redeploy",
          ],
          guardrails: [
            "The teacher owns the assessment: every mark is PROVISIONAL until the Approval node. Nothing is published to a student, a parent or a gradebook without the teacher's sign-off",
            "Marks are awarded only from the teacher's own marking scheme in Knowledge — the agent has no independent view of the correct answer",
            "Ambiguity is escalated, never resolved by guessing: unreadable handwriting, valid-but-unlisted methods and right-by-another-route answers all route to the teacher",
            "Every awarded mark carries a reason and a quote from the student's own working, so a challenged mark can be audited",
            "The agent never messages a student or a parent directly — all outbound communication is a separate, approved step",
          ],
          cost:
            "The marker runs N times — once per student — so for a batch of sixty it must be on the economy tier; that is the difference between a workflow a tutor can run on every worksheet and one they run twice and abandon. The single batch-analysis call is the one place the standard tier earns its keep. Run the marking Loop in Parallel batches for wall-clock speed: same per-script cost, whole batch back in minutes. No premium tier is needed anywhere in this recipe.",
          output:
            "A Loop node marks every submission with an economy-tier agent against your scheme → the Condition node pulls ambiguous scripts into a short review queue → the full mark sheet waits at the Approval node for your sign-off → after approval a standard-tier agent reports which question the batch failed and which students need individual attention → optionally a Tool (MCP) node writes the approved marks to the school gradebook.",
        },
      },
      {
        id: "tutor-parent-updates",
        title: "Parent progress updates that write themselves — and wait for your approval",
        scenario:
          "Parents phone during class hours asking how their child is doing. You give an off-the-cuff answer from memory, promise to send something in writing, and then spend Sunday evening writing forty near-identical WhatsApp messages from a register and a stack of marked papers — or you don't, and the calls start again on Monday.",
        triggerLabel: "Scheduled (monthly/term) + Webhook (attendance & marks)",
        steps: [
          { kind: "trigger", title: "Scheduled update cycle", detail: "A scheduled trigger opens the update run. Attendance and marks come from your own records or from a school ERP via an inbound Webhook / a Tool (MCP) node — labelled, because no first-party ERP connector exists." },
          { kind: "loop", title: "One update per student", detail: "A Loop node runs a cheap agent per student, assembling the update from that student's approved marks, attendance and your own logged remarks — nothing else." },
          { kind: "code", title: "Your grade bands, your wording", detail: "A user-defined tool node applies YOUR grade-band formula to each student's numbers, so 'needs support / on track / strong' means exactly what you decided it means, consistently across all forty." },
          { kind: "agent", title: "Draft in the parent's language", detail: "An Agent node writes the message in the language the parent actually reads — Sarvam handles the Indian-language drafting — keeping the tone factual and specific: what improved, what didn't, what to do at home." },
          { kind: "approval", title: "You read every one before it sends", detail: "An Approval node holds ALL forty drafts. You skim, edit the two that need a human touch, and release. Nothing about a child reaches a parent without a teacher approving that exact text." },
          { kind: "output", title: "Sent, and available by voice", detail: "An Output node sends the approved message on WhatsApp or SMS. For parents who'd rather listen than read, a Voice node reads the same approved text aloud via ElevenLabs in their language — a real accessibility win for parents who don't read comfortably." },
        ],
        connectors: ["WhatsApp", "SMS / Twilio", "Voice / Call", "Sarvam (Indian languages)", "ElevenLabs (voice)", "Approval / HITL", "Tool (MCP → school ERP)"],
        outcome: "Every parent gets a specific, factual, written update in their own language on a predictable schedule — each one read and approved by you first — and the interrupting phone calls have somewhere to point.",
        impact: "The mechanism is assembly from records you already keep, plus a consistent grade-band formula, plus batched approval. Writing forty updates by hand is forty acts of retrieval and rephrasing; approving forty assembled drafts is one review session. Consistency is the second gain — 'needs support' means the same thing for student 4 and student 39 because a formula, not end-of-evening fatigue, decides it.",
        build: {
          agents: 2,
          agentsNote: "1 per-student drafting agent (via the Loop node) + 1 supervisor agent that checks the batch for tone and for anything that shouldn't go out. The grade banding is a deterministic user-defined tool.",
          pattern: "Loop (draft per student) → SpEL grade-band formatter → supervisor consistency check → single batched teacher Approval",
          prompt:
            "You are drafting a progress update for ONE student's parent, for the teacher to approve. Use ONLY: approved marks, attendance, and the teacher's own logged remarks for this student. Structure: what improved (with the specific evidence), what still needs work (specific, one or two things), one concrete thing to do at home. Write in the parent's preferred language. Never compare this child to another child, never rank, never speculate about ability, effort, home circumstances or diagnosis, and never state a mark that has not been through the teacher's approval. If the record is too thin to say anything specific, say that plainly and suggest the teacher add a remark — do not pad. This is a DRAFT; it is sent only after the teacher approves it.",
          agentRoles: [
            { role: "Update drafter (per student)", model: "Economy tier — it runs once per student across the whole batch every cycle, which is exactly the pattern that has to be cheap", prompt: "Assemble one parent update from approved marks, attendance and teacher remarks only. Specific and factual. No comparisons, no speculation, no unapproved marks. Parent's own language." },
            { role: "Batch supervisor (once)", model: "Standard tier — one pass over all drafts before they reach the teacher", prompt: "Review the batch of drafts: flag any comparison between students, any speculation about ability or home life, any mark not in the approved set, and any message so generic it says nothing. Return a flagged list for the teacher's review queue — do not rewrite silently." },
          ],
          mcp: ["School ERP / attendance & gradebook system — via a Tool (MCP) node, or inbound via Webhook; no first-party connector exists", "WhatsApp / SMS — the parent channel", "Sarvam — drafting in the parent's language; ElevenLabs — spoken delivery of the approved text"],
          tools: [
            "knowledge_search (your reporting rubric and the phrasing you want used)",
            "http_request (pull attendance and marks from the school system)",
            "user-defined tool `grade_band` (SpEL): input.percent >= 75 ? 'Strong' : (input.percent >= 55 ? 'On track' : (input.percent >= 35 ? 'Needs support' : 'Needs urgent attention')) — a sandboxed expression over the tool's JSON input (no method calls, no constructors, no bean or type references, no loops in the grammar): a formatter, not a script. You encode YOUR OWN band boundaries and YOUR OWN wording once, every report card uses them identically, and when your centre changes its bands you edit the expression yourself — no developer, no redeploy",
          ],
          guardrails: [
            "MANDATORY APPROVAL: nothing reaches a parent without the teacher approving that exact text at the Approval node — no auto-send path exists in this workflow",
            "No mark is ever quoted to a parent unless it has already been through the marking workflow's teacher approval",
            "Only that student's own records are used — no comparison with classmates, no ranking, no speculation about ability, effort, home circumstances or any diagnosis",
            "A thin record produces an honest 'not enough recorded this month' plus a prompt for the teacher to add a remark, never padded filler",
            "Grade bands come from the teacher's own SpEL expression, so the same numbers always produce the same band across the whole batch and across terms",
          ],
          cost:
            "The drafter runs once per student per cycle, so economy tier is the only sensible choice for a tutor with forty or four hundred students — this is the recipe where tiering decides whether the feature is affordable at all. The `grade_band` SpEL tool is deterministic and free per call, which is a genuine saving over asking a model to apply the bands. Exactly one standard-tier supervisor pass runs over the whole batch. A monthly cycle for forty students is forty economy calls plus one standard call.",
          output:
            "A Loop node drafts one update per student with an economy-tier agent from approved records only → your `grade_band` SpEL formula labels each consistently → a standard-tier supervisor flags comparisons, speculation or empty drafts → ALL drafts wait at the Approval node for your review and edits → an Output node sends the approved text on WhatsApp/SMS, with an ElevenLabs voice version in the parent's language for those who prefer to listen.",
        },
      },
      {
        id: "tutor-paper-setting",
        title: "Question papers built from your blueprint instead of retyped every term",
        scenario:
          "Every term you rebuild the question paper by scrolling old Word files, copy-pasting questions, and manually counting whether the marks add to eighty and whether the chapter weightage matches the blueprint. Then you retype the whole thing again for the retest, because the students who took it have already circulated it.",
        triggerLabel: "Webhook / manual start (\"set a term paper: Physics, 80 marks\")",
        steps: [
          { kind: "knowledge", title: "Blueprint + your question bank", detail: "A Knowledge node holds the official blueprint (chapter weightage, question-type split, difficulty spread) and your own accumulated question bank and past papers." },
          { kind: "parallel", title: "Draft each section at once", detail: "A Parallel node fans generation across the sections — MCQ, short answer, long answer, numerical — each drafted against its own slice of the blueprint rather than sequentially." },
          { kind: "agent", title: "Set questions to the blueprint", detail: "An Agent node drafts questions that hit the required chapter weightage and difficulty mix, each tagged with chapter, marks and difficulty, and each with a full marking scheme." },
          { kind: "code", title: "Arithmetic checked by code, not vibes", detail: "A Code node verifies the hard constraints deterministically: total marks, per-chapter weightage, question-type counts, difficulty distribution. A model should never be trusted to confirm that a column of marks adds to eighty." },
          { kind: "condition", title: "Blueprint satisfied?", detail: "A Condition node loops back for a targeted regeneration if any constraint is missed — it fixes the specific failing section rather than regenerating the whole paper." },
          { kind: "approval", title: "You sign off before it's a paper", detail: "An Approval node holds the draft paper AND its marking scheme. A question paper is your professional judgement — you review, edit, and approve before it exists as an exam." },
        ],
        connectors: ["Webhook", "Email", "Knowledge base", "Approval / HITL", "Tool (MCP → exam-board / LMS question bank)"],
        outcome: "A blueprint-compliant draft paper with a full marking scheme in one pass, with the marks arithmetic and weightage machine-verified — and a fresh variant for the retest without retyping anything.",
        impact: "The mechanism is generation grounded in your own bank plus deterministic constraint checking. The tedious part of paper-setting is not writing questions, it is the bookkeeping — weightage, totals, difficulty spread — which is arithmetic and belongs in a Code node. The variant-for-the-retest problem disappears because generation is cheap once the blueprint is encoded. The paper is still yours: you approve it.",
        build: {
          agents: 2,
          agentsNote: "1 section-setting agent (runs in Parallel per section) + 1 reviewer agent that reads the assembled paper as a whole. The constraint arithmetic is a deterministic Code node, not an agent.",
          pattern: "Parallel section drafting → deterministic Code constraint check → Condition (targeted regeneration) → teacher Approval",
          prompt:
            "You are setting ONE section of a question paper for {{subject}}, {{grade}}, {{board}}. Work strictly within the syllabus and blueprint in Knowledge and draw on the teacher's own question bank for style and standard. Return {questions:[{text, chapter, type, marks, difficulty (EASY|MED|HARD), expected_answer, marking_scheme[]}]}. Hit the chapter weightage and difficulty mix given for THIS section. Never reproduce a question verbatim from a live or recently-used paper — vary the numbers and the framing. Never go outside the syllabus scope. Do not attempt to verify the marks total yourself; the workflow checks the arithmetic deterministically. This is a DRAFT for the teacher to approve.",
          agentRoles: [
            { role: "Section setter (one per section, in Parallel)", model: "Premium tier — this is the one place in the whole pack where a premium model genuinely pays: a badly-set question is a term's worth of unfair marking, and it runs a handful of times per term, not thousands of times a day", prompt: "Draft one section to its blueprint slice with a full marking scheme per question. In-syllabus only. No verbatim reuse of a recent paper. Vary numbers and framing." },
            { role: "Whole-paper reviewer (once)", model: "Standard tier — reads the assembled paper for the things a section-level view can't see", prompt: "Read the assembled paper: flag duplicated concepts across sections, ambiguous wording, questions whose marks don't match their demand, and anything outside the syllabus. Report; do not silently rewrite." },
          ],
          mcp: ["Exam-board / LMS question bank — via a Tool (MCP) node, or inbound via Webhook; no first-party connector exists", "Email — delivering the approved paper and scheme to the teacher"],
          tools: [
            "knowledge_search (the blueprint, the syllabus, and your own question bank / past papers)",
            "user-defined tool `blueprint_gap` (SpEL): input.chapterMarks < input.requiredMarks ? 'REGENERATE_SECTION' : (input.hardCount > input.hardMax ? 'EASE_MIX' : 'OK') — your own blueprint tolerance rule over the tool's JSON input, so a targeted regeneration is triggered by YOUR standard, editable without a developer or a redeploy",
          ],
          guardrails: [
            "The teacher approves before it is a paper: the draft and its marking scheme both wait at the Approval node — there is no path where a generated paper reaches students unreviewed",
            "Questions are drawn strictly from the syllabus and blueprint in Knowledge and from the teacher's own bank — never invented outside the prescribed scope",
            "No verbatim reuse of a live or recently-circulated paper; numbers and framing are varied for every generated variant",
            "Marks totals, chapter weightage and difficulty distribution are verified by a deterministic Code node, never by a model asserting the arithmetic is fine",
            "Every question ships with its marking scheme, so the paper and the marking standard are approved together rather than the scheme being improvised later",
          ],
          cost:
            "This is the deliberate inversion of the rest of the pack. Paper-setting runs a handful of times a term, so premium tier on the section setters is affordable and correct — the quality of a question is high-stakes and long-lived. Parallel sections keep wall-clock time down; the deterministic Code node does the constraint arithmetic for free rather than burning premium tokens re-checking totals; and the whole-paper review is a single standard-tier call. Compare it with the doubt-answering and marking recipes, which run thousands of times and sit on economy for exactly the same reasoning applied in the other direction.",
          output:
            "A Parallel node drafts every section with a premium-tier setter against its blueprint slice → a deterministic Code node verifies totals, weightage and difficulty spread → the Condition node regenerates only the failing section → a standard-tier reviewer reads the assembled paper for duplication and ambiguity → the paper AND its marking scheme wait at the Approval node for the teacher's edits and sign-off, and a fresh variant for the retest is a re-run, not a retype.",
        },
      },
      {
        id: "tutor-fees-and-attendance",
        title: "Tuition fee reminders and UPI collection, with every receipt approved",
        scenario:
          "Fees are tracked in a notebook or a spreadsheet. You send reminders yourself, awkwardly, in the same WhatsApp thread you use to share notes — or you don't send them and carry the arrears. Payments come in by UPI from a phone number you have to match back to a student by hand, and receipts get written out one at a time when a parent asks.",
        triggerLabel: "Scheduled (fee cycle) + Webhook (payment received)",
        steps: [
          { kind: "trigger", title: "Fee cycle opens", detail: "A scheduled trigger starts the cycle against your own fee register (or one pulled from a coaching-centre system via a Tool (MCP) node — labelled, since no first-party connector exists for it)." },
          { kind: "loop", title: "Work out each family's position", detail: "A Loop node runs a cheap agent per student: what's due, what's paid, what concession or sibling discount applies, how many cycles overdue." },
          { kind: "code", title: "Your fee rules, as a formula", detail: "A user-defined tool node applies YOUR reminder policy deterministically — how many days before the date, how many follow-ups, when it stops being an automated reminder and becomes a conversation you have yourself." },
          { kind: "approval", title: "You approve every reminder before it sends", detail: "An Approval node holds the reminder batch. Money messages to a parent are relationship-sensitive; you see the list, remove the family you know is having a hard month, and release the rest." },
          { kind: "output", title: "Reminder with a UPI link", detail: "An Output node sends the approved reminder on WhatsApp or SMS with a Razorpay UPI payment link — parents pay from the message rather than asking for account details again." },
          { kind: "approval", title: "Receipts are approved too", detail: "When the Razorpay webhook confirms a payment, the workflow matches it to the student and drafts the receipt — which passes a second Approval before it's issued, because a receipt is a financial record and it has to be right." },
        ],
        connectors: ["WhatsApp", "SMS / Twilio", "Razorpay (UPI)", "Webhook", "Approval / HITL", "Tool (MCP → coaching-centre fee register)"],
        outcome: "Fee reminders go out on a schedule you set, in your wording, with a UPI link — and every reminder and every receipt passes your approval, so the awkward parts stay human and the clerical parts don't stay on your evenings.",
        impact: "The mechanism is a per-student position calculation plus a policy formula plus two approval gates. The reminder-writing and the payment-to-student matching are clerical and repeat every cycle; the decision about which family gets chased this month is not, which is why it sits behind an approval you actually read. Receipts get a second gate because a financial record with the wrong number is worse than no record.",
        build: {
          agents: 2,
          agentsNote: "1 per-student fee-position agent (via the Loop node) + 1 payment-matching agent triggered by the Razorpay webhook. The reminder policy is a deterministic user-defined tool; both approvals are workflow nodes.",
          pattern: "Loop (fee position per student) → SpEL reminder policy → Approval → UPI link; payment webhook → match → second Approval on the receipt",
          prompt:
            "You are a fee-desk assistant for ONE coaching centre. For ONE student, compute the position from the fee register only: {student_ref, due, paid, concession_applied, balance, cycles_overdue, evidence}. Apply concessions and sibling discounts exactly as recorded — never assume one applies. Draft a short, respectful reminder in the parent's language: amount, due date, how to pay. Never threaten, never mention any consequence for the child's attendance or class participation, and never compare families. If the register is ambiguous (an unmatched payment, a conflicting entry), do NOT draft a reminder — flag it for the teacher. Everything you produce is a DRAFT pending approval.",
          agentRoles: [
            { role: "Fee-position agent (per student)", model: "Economy tier — it runs once per student every cycle; a coaching centre with three hundred students is precisely why this cannot be a big model", prompt: "Compute one student's fee position from the register only, with evidence. Apply recorded concessions exactly. Draft a short respectful reminder. Flag ambiguity instead of drafting." },
            { role: "Payment matcher (per payment webhook)", model: "Economy tier — a short, structured matching task per payment", prompt: "Match one confirmed Razorpay payment to a student from the amount, reference and payer details. Return {matched: true/false, student_ref, confidence, unmatched_reason}. Never guess a match on low confidence — an unmatched payment goes to the teacher's queue, and the receipt is drafted only for a confident match." },
          ],
          mcp: ["Coaching-centre fee register / accounting sheet — via a Tool (MCP) node, or inbound via Webhook; no first-party connector exists", "Razorpay — UPI payment links and payment-confirmation webhooks (first-party)", "WhatsApp / SMS — the reminder channel"],
          tools: [
            "knowledge_search (your fee structure, concession policy and the wording you want used)",
            "http_request (read the fee register; issue the approved receipt)",
            "user-defined tool `reminder_stage` (SpEL): input.daysOverdue > 30 ? 'TEACHER_CALLS' : (input.daysOverdue > 7 ? 'SECOND_REMINDER' : (input.daysUntilDue <= 3 ? 'GENTLE_NUDGE' : 'NONE')) — your own escalation ladder over the tool's JSON input, ending in a human conversation rather than a harder automated message. Change the day counts yourself, mid-term, with no developer and no redeploy",
          ],
          guardrails: [
            "MANDATORY APPROVAL, twice: no fee reminder reaches a parent without the teacher approving the batch, and no receipt is issued without a second approval — a receipt is a financial record",
            "Amounts, concessions and balances come only from the fee register; an unmatched payment or a conflicting entry is flagged for the teacher, never reconciled by guesswork",
            "Reminders are respectful by construction: no threats, no mention of any consequence for the child's attendance or participation, no comparison between families",
            "The escalation ladder ends in 'the teacher calls' — the workflow never gets progressively harsher on its own",
            "Payments are handled by Razorpay's own first-party flow; the agent never sees or stores card, bank or UPI credentials — it only matches a confirmed payment reference to a student",
          ],
          cost:
            "Both agents are economy tier by design: one runs per student per cycle, the other per payment, and neither task is more than structured lookup and short drafting. The `reminder_stage` SpEL tool costs nothing per call and replaces what would otherwise be a model deciding escalation — cheaper AND more predictable. There is no standard- or premium-tier work in this recipe at all, which is the right answer for a tutor: the fee desk should be the cheapest thing they run, not a line item that competes with the teaching workflows.",
          output:
            "A Loop node computes each student's fee position with an economy-tier agent → your `reminder_stage` SpEL formula decides who is nudged, who is reminded again and who you call yourself → the whole batch waits at the Approval node for you to prune and release → approved reminders go out on WhatsApp/SMS with a Razorpay UPI link → the payment webhook fires, an economy-tier matcher ties it to a student, and the drafted receipt passes a second Approval before it is issued.",
        },
      },
    ],
  },
];
