import type { Industry } from "./recipes-data";
import { Users, Heart } from "lucide-react";

// ---------------------------------------------------------------------------
// Families & caregiving + Couples & shared life admin workflow recipes — same
// "inline copy, no i18n keys" precedent and honesty rules as recipes-data.tsx.
// The `Industry` type is used here as a general CATEGORY, not strictly a trade
// sector: these are household categories, and the people in them are the
// tenants themselves.
//
// CONSENT IS THE DESIGN CONSTRAINT, NOT A DISCLAIMER.
// Every person in every workflow below is an adult with agency (or, where a
// child is involved, a minor under their own guardian's proportionate
// oversight — said plainly, in that recipe). So:
//   • Everyone in the workflow KNOWS it exists and has opted in. There is no
//     covert monitoring of a partner, spouse, adult child or parent — ever.
//   • No location tracking. No reading anyone's messages. No "check whether
//     they're telling the truth". No surveillance of any kind. Recipe ideas
//     that would require watching another adult without their knowledge are
//     simply not written — refusal by omission is the honest form here.
//   • Shared data is shared BY AGREEMENT. Either person can opt out, and the
//     workflow stops for them, immediately and without argument.
// These three appear in the `guardrails` of every recipe on this page because
// they are load-bearing, not boilerplate. A household automation guide that
// skipped them would be a manual for coercive control.
//
// MENTAL LOAD is named honestly throughout: the point of these workflows is a
// shared, neutral system that everyone can see, instead of one person in the
// house privately remembering everything. The value is fewer reminders passing
// between people, not more.
//
// MEDICAL SAFETY: in every caregiving recipe the agent NEVER diagnoses and
// NEVER advises on medication or dosage. It reminds, it organises what the
// family already wrote down, and it escalates to a human being.
//
// Honesty rule (verbatim posture from recipes-data.tsx): systems VegaDūta has
// no first-party connector for — the school ERP, the shared calendar, the bank
// or utility portal, the hospital's appointment system — are reached the real
// way, via an inbound Webhook trigger and/or a Tool (MCP) node, and are
// LABELLED as such. Only real channels are used (Webhook, WhatsApp, SMS/Twilio,
// Voice/Call, Email, Telegram, ElevenLabs, Sarvam, Razorpay UPI). Anything
// consequential — money moving, a message sent on someone's behalf, a document
// shared outside the household — passes an Approval node. Impact lines describe
// the mechanism, never invented ROI numbers.
// ---------------------------------------------------------------------------

export const INDUSTRIES_FAMILY: Industry[] = [
  // ====================================================== FAMILIES & CAREGIVING
  {
    id: "families",
    name: "Families & caregiving",
    icon: Users,
    tagline:
      "School notices, medicine reminders and a parent's hospital visit — held by a shared system everyone opted into, instead of by whoever happens to remember.",
    recipes: [
      {
        id: "fam-school-notices",
        title: "School notices → one calm weekly digest the whole house can see",
        scenario:
          "The school ERP emails a circular, the class WhatsApp group re-posts it with forty replies on top, and by evening the exam date has scrolled past the top of the screen. One parent ends up as the household's unpaid search function — re-reading the group at 11pm to work out whether tomorrow is a uniform day, a fee due date, or nothing at all.",
        triggerLabel: "Email / Webhook (school ERP circular or portal notice)",
        steps: [
          { kind: "trigger", title: "A notice lands", detail: "An Email trigger on the address the school actually writes to, or a Webhook from the school ERP/portal — the ERP has no first-party connector, so it arrives as an inbound Webhook and is read back via a Tool (MCP) node. Notices the family forwards in by WhatsApp land on the same trigger." },
          { kind: "agent", title: "Pull out only the actionable facts", detail: "An Agent node extracts what a parent actually needs — date, what the child must bring or wear, whether money is due, the deadline — into structured fields, and marks anything it could not read clearly as uncertain rather than guessing." },
          { kind: "condition", title: "Is anything due, or is this FYI?", detail: "A Condition node splits genuinely actionable items (a deadline, a fee, a thing to pack) from the general circulars, so the digest does not bury tomorrow's PT kit under a newsletter." },
          { kind: "knowledge", title: "Check it against the household's own notes", detail: "A Knowledge node holds the family's own documents — the term calendar, the fee schedule, the bus timings PDF — so the digest reconciles the new notice with what the house already knew instead of contradicting it." },
          { kind: "output", title: "One digest, one place, everyone", detail: "An Output node posts a single dated digest to the family's WhatsApp or Telegram group — same message, same time, to every adult who opted in. No one has to be the person who 'tells everyone'." },
          { kind: "approval", title: "Fees need a human, always", detail: "An Approval node holds anything involving money. A fee notice becomes a Razorpay/UPI request a parent explicitly approves — the workflow never pays a school on its own initiative." },
        ],
        connectors: ["Email", "Webhook (school ERP / portal — inbound)", "WhatsApp", "Telegram", "Knowledge base", "Approval / HITL", "Razorpay (UPI)"],
        outcome:
          "Every school notice turns into one dated, deduplicated digest that both parents (and any grandparent or helper who opted in) can see — with fee payments held for a human's explicit approval.",
        impact:
          "The mechanism is simple: the notice stops living in a scrolling chat and starts living in a dated list the whole household reads from. The mental load moves off one person's memory and onto a shared surface — nobody is being reminded by anybody, which is exactly the point.",
        build: {
          agents: 2,
          agentsNote:
            "1 extractor (runs per notice) + 1 digest writer (runs once per digest). The completeness split and the fee approval are workflow nodes.",
          pattern: "Extract per notice → reconcile against the family's own Knowledge → one shared digest → Approval gate on money",
          prompt:
            "You read school notices for a family. From ONE notice, extract {title, date, child (if named), action_required (BRING|WEAR|PAY|ATTEND|NONE), what, deadline, amount, currency, source}. Use ONLY what the notice says — never infer a date, a fee amount or which child it applies to. If a field is unreadable or ambiguous, set it to null and add it to uncertain[] so the digest can show 'check the original'. Never summarise a payment instruction as if it were confirmed; PAY items are drafts for a parent to approve.",
          agentRoles: [
            { role: "Notice extractor (per notice)", model: "Economy tier — short, structured extraction; runs on every circular so it dominates volume", prompt: "Extract one notice into strict JSON. Null for anything unclear, and list it in uncertain[]. Never invent a date or an amount. PAY items are proposals only." },
            { role: "Digest writer (once per digest)", model: "Standard tier — reasoning + tone", prompt: "Write one short, warm, dated digest from the validated items, reconciled against the family's own term calendar and fee schedule in Knowledge. Lead with what is due soonest. Flag contradictions between the notice and the household's notes rather than silently picking one. Keep it scannable — a parent reads this between two other things." },
          ],
          mcp: [
            "School ERP / parent portal — inbound via Webhook, read back via a Tool (MCP) node (no first-party connector)",
            "The family's shared calendar — via a Tool (MCP) node, so the digest can offer to add a dated item",
            "WhatsApp / Telegram — the household's own group, as the single delivery surface",
          ],
          tools: [
            "knowledge_search (the family's own term calendar, fee schedule, bus timings, uniform policy)",
            "http_request (read the ERP/portal notice; write an approved calendar entry)",
            "user-defined tool `notice_window` (SpEL): input.actionRequired == 'PAY' ? 5 : (input.actionRequired == 'BRING' ? 1 : 0) — a tenant-authored SpEL expression over the tool's JSON input that returns how many days before the deadline this family wants to start being reminded. It is a data-transform/formula tool, sandboxed by construction (no method calls, no constructors, no bean or type references, and no loop in the grammar) — a formula, not a scripting engine. The benefit: a household that wants five days' notice on fees but only one on 'bring a water bottle' encodes that once, themselves, without a developer.",
          ],
          guardrails: [
            "Everyone in the group knows this workflow exists and opted in — the digest is posted to the household's own shared group, visible to all of them, never sent to one parent about another.",
            "This is a child's school correspondence read by that child's own guardians — ordinary, proportionate parental oversight of a minor, and nothing more. It reads school notices only: no location tracking, no reading anyone's messages, no monitoring of any adult in the house.",
            "Shared by agreement: any adult can opt out and the digest stops going to them; if the family turns the workflow off, it stops entirely and the extracted items stay in their own Knowledge base.",
            "Money never moves on the agent's say-so — a fee notice becomes an approval request a parent confirms, with the amount and payee shown from the original notice.",
            "Anything the extractor could not read is shown as 'check the original' with a link, never quietly guessed.",
          ],
          cost:
            "Volume sits on the extractor, so it belongs on the economy tier with a hard cap — a busy term is many short notices, not a few long ones. The single digest call per day (or per week) is where the standard tier earns its keep. Set an economy fallback model on the digest writer too: a slightly plainer digest that arrives is strictly better than none.",
          output:
            "Each notice is extracted to strict JSON → the Condition node separates actionable from FYI → Knowledge reconciles it with the family's own term calendar → one dated digest goes to the shared group on WhatsApp or Telegram → any fee is held at the Approval node and only then raised as a Razorpay/UPI request.",
        },
      },
      {
        id: "fam-medicine-reminders",
        title: "Medicine reminders for a parent, by voice, in their own language",
        scenario:
          "An 80-year-old's medicine box is refilled by whoever visited last. One sibling thinks the BP tablets ran out on Tuesday, another is sure they bought a strip on Sunday. Nobody wants to keep phoning to ask 'did you take it?' — it feels like checking up on a grown adult, because it is — and the app the children installed has never once been opened.",
        triggerLabel: "Scheduled Webhook (the dose times the family agreed with the parent)",
        steps: [
          { kind: "trigger", title: "The agreed reminder time", detail: "A scheduled Webhook fires at the times the parent themselves agreed to — set once, with them, not for them." },
          { kind: "voice", title: "A short call, in their language", detail: "A Voice node places a brief call, spoken via ElevenLabs with Sarvam handling the Indian language the parent actually speaks. This matters more than any of the app-shaped ideas: a warm thirty-second call in Kannada or Bengali reaches someone who will never open a dashboard." },
          { kind: "condition", title: "They answer, or they don't", detail: "A Condition node branches on the call outcome. Answered and acknowledged: log it and stop — no follow-up, no nagging. No answer: retry once after the interval the family set, then escalate." },
          { kind: "agent", title: "Log what the parent said, verbatim", detail: "An Agent node records the parent's own words ('took it', 'skipping today', 'the strip is finished') into a plain shared log. It records; it does not interpret their health." },
          { kind: "output", title: "The siblings see one log, not four guesses", detail: "An Output node posts to the siblings' shared WhatsApp group — including the parent, who is in that group. Everyone sees the same line: dose acknowledged, or refill needed, or unreachable." },
          { kind: "approval", title: "Refill is a human decision", detail: "'Strip is finished' becomes a refill task with an Approval node, not an order. A person confirms the medicine, the quantity and the pharmacy — the agent never touches a prescription." },
        ],
        connectors: ["Webhook (scheduled)", "Voice / Call", "ElevenLabs (voice)", "Sarvam (Indian languages)", "WhatsApp", "SMS / Twilio", "Approval / HITL"],
        outcome:
          "A gentle, predictable reminder call the parent agreed to, one shared log the siblings all read from, and refills that surface as a task before the strip actually runs out.",
        impact:
          "The mechanism is replacing four separate children phoning to ask the same question with one call the parent expects and one log everyone can see. It removes the 'checking up on you' texture from the relationship precisely because the parent set it up with them and can stop it with a sentence.",
        build: {
          agents: 2,
          agentsNote:
            "1 voice-call agent + 1 logger/escalation agent. The retry branch, the refill approval and the escalation ladder are workflow nodes.",
          pattern: "Scheduled voice reminder → parent's own words logged → escalate to a human, never to a diagnosis",
          prompt:
            "You place a short, warm reminder call to an older adult who has agreed to receive it. Speak in {{language}} via Sarvam, using their name and the dose label they themselves chose (e.g. 'the white BP tablet after breakfast'). Say the reminder, ask if they have taken it, thank them, and end the call — under forty seconds. You are NOT a clinician: never diagnose, never comment on a symptom, never advise on medication, dose, timing changes, or what to do if a dose was missed. If they mention pain, breathlessness, a fall, confusion, or anything that worries you, say kindly that you will let the family know right away, end the call, and raise the escalation flag. If they say they would rather not be called, thank them, log opt_out=true and stop — do not persuade.",
          agentRoles: [
            { role: "Voice reminder agent (per dose)", model: "Economy tier — short scripted turn, latency matters far more than reasoning depth", prompt: "One short reminder call in the parent's language. Warm, unhurried, under forty seconds. No medical opinion of any kind. Honour an opt-out immediately and without persuasion." },
            { role: "Logger / escalation agent (per outcome)", model: "Standard tier — judgement about when to involve a human", prompt: "Record the parent's own words verbatim into the shared log. Decide only whether to (a) log and stop, (b) flag a refill for approval, or (c) escalate to the on-duty family member. Never infer a clinical meaning from what was said. When in doubt, escalate to a human." },
          ],
          mcp: [
            "The household's medicine list — the family's own document in Knowledge, never a drug database and never a prescribing source",
            "Pharmacy or delivery service — via a Tool (MCP) node, and only after a human approves the refill",
            "WhatsApp / SMS — the shared family group and the escalation ladder",
          ],
          tools: [
            "knowledge_search (the family's own written medicine list and dose labels, exactly as the doctor wrote them)",
            "http_request (log the outcome; raise an approved refill task)",
            "user-defined tool `escalate_after` (SpEL): input.missedCount >= 2 ? 'CALL_FAMILY' : 'RETRY_ONCE' — a tenant-authored SpEL formula over the tool's JSON input deciding when a missed dose stops being a retry and starts being a phone call to a person. Sandboxed by construction (no method calls, constructors, bean or type references, and no loop in the grammar): it transforms data, it does not run code. The benefit is that this family sets their own threshold — some want a call after one miss, some after three — without waiting on a developer.",
          ],
          guardrails: [
            "The parent set this up with their family and can end it in one sentence — 'stop calling me' is honoured immediately, logged as an opt-out, and never argued with. Everyone in the shared group, including the parent, knows the workflow exists.",
            "No surveillance of any kind: no location tracking, no reading their messages, no listening between calls, no inference about whether they are telling the truth. The only thing recorded is what they chose to say on a call they agreed to receive.",
            "Shared by agreement — any sibling can leave the group and the workflow stops for them; if the parent withdraws consent, it stops for everyone.",
            "MEDICAL SAFETY, absolute: the agent never diagnoses, never comments on symptoms, and never advises on medication, dosage or timing. It reads back the family's own written dose label, it reminds, it records, and it escalates to a human. Any hint of a symptom ends the call and pages a person.",
            "A refill is a task for a human to approve, never an order the agent places — a person confirms medicine, quantity and pharmacy.",
            "Escalation goes to a named family member on a rota, so 'someone should call Amma' is never everyone's job and therefore nobody's.",
          ],
          cost:
            "Reminder calls are the recurring line item — many short, near-identical turns — so the voice agent sits on the economy tier with a strict per-call duration cap, and a fallback model configured so a reminder still goes out if the primary is unavailable. The logger runs once per outcome on the standard tier, which is the right place to spend, since it is the step that decides whether to wake a human.",
          output:
            "The scheduled Webhook fires → a Voice node calls in the parent's language via ElevenLabs + Sarvam → the Condition node branches on answered/unanswered → the logger writes the parent's own words to the shared log and posts one line to the family group → a finished strip becomes an Approval-gated refill task, and a missed-dose threshold set by the family's own SpEL formula pages a named person.",
        },
      },
      {
        id: "fam-hospital-coordination",
        title: "A parent's hospital visit, coordinated across three cities",
        scenario:
          "Appa has a cardiology follow-up on the 14th. One sibling is in Pune, one in Dubai, one an hour away. The reports from the last visit are a photo in one person's camera roll, the appointment letter is in another's email, and the actual coordination happens as a nine-message phone-tag chain that ends with two people assuming the third booked the cab.",
        triggerLabel: "Webhook / Email (appointment confirmed) or a family member adding the date",
        steps: [
          { kind: "trigger", title: "The appointment exists", detail: "An Email trigger on the appointment letter, or a Webhook from the hospital's portal — no first-party connector, so it arrives inbound and is read back via a Tool (MCP) node. A sibling can also just add the date by message." },
          { kind: "knowledge", title: "Everything about this visit, in one place", detail: "A Knowledge node holds what the family has chosen to put there: previous discharge summaries, the current medicine list, insurance details, the consultant's name. Their documents, in their tenant, shared only among the people who opted in." },
          { kind: "agent", title: "Build the actual checklist", detail: "An Agent node produces the plain list this visit needs: what to carry, what time to leave, fasting instructions IF the letter states them, who is accompanying, and what last time's summary said to bring. It never adds a medical instruction the family's own documents do not contain." },
          { kind: "parallel", title: "Split the jobs, explicitly", detail: "A Parallel node fans the tasks out — cab, reports, insurance pre-auth, accompanying person — each assigned to a named sibling who accepted it, so 'someone will do it' becomes a name next to a task." },
          { kind: "output", title: "One thread, three cities", detail: "An Output node posts the checklist and assignments to the siblings' shared group on WhatsApp or Telegram, and a Voice node can read the parent their own part of it in their language — what time, who is coming, what to keep ready." },
          { kind: "approval", title: "Sharing a report is a decision", detail: "An Approval node gates anything leaving the household: sending a discharge summary to a hospital, an insurer, or a second-opinion doctor needs a human to approve that specific share, every time." },
        ],
        connectors: ["Email", "Webhook (hospital portal — inbound)", "WhatsApp", "Telegram", "Voice / Call", "Sarvam (Indian languages)", "Knowledge base", "Approval / HITL"],
        outcome:
          "One shared thread per visit: a grounded checklist, named owners for each task, the parent told their own part by voice, and every external document share explicitly approved by a person.",
        impact:
          "The mechanism is turning diffuse responsibility into named responsibility. Phone tag exists because nothing is written down where all three siblings can see it at once; a single visit thread with an owner per task removes the ambiguity that caused the phone calls in the first place.",
        build: {
          agents: 2,
          agentsNote:
            "1 checklist agent + 1 coordinator that tracks acceptance and nudges only the unclaimed tasks. The task fan-out and the document-share approval are workflow nodes.",
          pattern: "Ground in the family's own documents → Parallel fan-out with named owners → Approval gate on anything leaving the house",
          prompt:
            "You help a family coordinate ONE hospital visit for their parent. From the appointment details and the family's own documents in Knowledge, produce a plain checklist {leave_by, carry[], accompanying, tasks[{task, suggested_owner}], questions_to_ask}. Include a preparation instruction (fasting, holding a medicine, carrying a prior scan) ONLY if the appointment letter or a family document explicitly states it — quote the source line. You are NOT a clinician: never diagnose, never interpret a report or a test result, never advise on medication or dosage, never suggest what the consultant might say. If the family asks a medical question, say plainly that this is for the doctor, and note it under questions_to_ask so they remember to ask it.",
          agentRoles: [
            { role: "Visit checklist agent (per visit)", model: "Standard tier — must reason over the family's own documents without adding to them", prompt: "Build the carry/leave-by/questions checklist strictly from the appointment letter and the family's own documents, quoting the source line for every preparation instruction. Zero medical interpretation. Anything not in the documents goes under questions_to_ask, never under instructions." },
            { role: "Coordinator (per follow-up round)", model: "Economy tier — short bookkeeping turns, runs repeatedly until tasks are claimed", prompt: "Track which tasks have a named owner who accepted. Nudge ONLY the unclaimed ones, once per round, in the shared group. Never chase an individual privately and never imply anyone is failing — state the open item, not the person." },
          ],
          mcp: [
            "Hospital appointment portal — inbound via Webhook, read back via a Tool (MCP) node (no first-party connector)",
            "The family's shared calendar — via a Tool (MCP) node, to place the visit and the leave-by time",
            "Cab / travel booking — via a Tool (MCP) node, and only from an approved, human-owned task",
          ],
          tools: [
            "knowledge_search (the family's own discharge summaries, medicine list, insurance policy — their documents only)",
            "http_request (read the appointment; write the calendar entry; record task acceptance)",
            "user-defined tool `visit_turn` (SpEL): input.lastAccompaniedBy == 'PUNE' ? 'LOCAL' : 'PUNE' — a tenant-authored SpEL expression over the tool's JSON input that returns whose turn it is to accompany, based on whom the family logged last time. Sandboxed by construction: no method calls, no constructors, no bean or type references, and no loop in the grammar — a data-transform/formula tool, not a scripting engine. The benefit: siblings encode their OWN rotation once (strict alternation, or 'whoever is in the city', or a two-to-one split reflecting who lives nearest) and stop renegotiating it every single visit.",
          ],
          guardrails: [
            "Every sibling — and the parent — knows this thread exists and opted into it. Nothing about the parent is discussed in a channel they are not part of, and no sibling is reported on to another.",
            "No surveillance: no location tracking of anyone, no reading anyone's messages, no checking whether a claimed task was really done. The workflow tracks tasks people accepted, not people.",
            "Shared by agreement: any sibling can opt out and the thread stops reaching them; if the parent withdraws consent, the whole workflow stops and their documents stay in their own Knowledge base.",
            "MEDICAL SAFETY, absolute: the agent never diagnoses, never interprets a report or test result, and never advises on medication or dosage. Preparation instructions are quoted from the appointment letter or the family's own documents with the source line shown; everything else becomes a question to ask the doctor.",
            "Every document leaving the household — to a hospital, an insurer, a second opinion — passes the Approval node as a specific, named share. Blanket 'always share' is deliberately not offered.",
            "The coordinator nudges unclaimed TASKS in the shared group, never individuals in private — it must not become a tool for one sibling to pressure another.",
          ],
          cost:
            "One standard-tier call per visit builds the checklist; that is the pass worth paying for, because it is grounded in documents and must not improvise. The coordinator's repeated nudge rounds are short bookkeeping and belong on the economy tier with a fallback model. Cost scales with visits, not with the number of siblings.",
          output:
            "The appointment arrives by Email or Webhook → Knowledge grounds the checklist in the family's own documents → a Parallel node assigns each task to a named sibling who accepted it → the shared group gets one thread and the parent gets a short Voice call in their language covering their own part → any external document share is held at the Approval node until a person approves that specific share.",
        },
      },
      {
        id: "fam-household-rotation",
        title: "The invisible list, made visible — household tasks on a rotation the family wrote",
        scenario:
          "Nobody in this house is lazy. But the gas cylinder booking, the maid's monthly payment, the water-tank cleaning, the car service and the grandparents' Sunday call all live in one person's head, and that person is tired of being the one who says 'did anyone…'. Asking is itself the work — the mental load is the remembering, and it never shows up on anyone else's list.",
        triggerLabel: "Scheduled Webhook (weekly) + Webhook/Email for anything with a real due date",
        steps: [
          { kind: "trigger", title: "The week turns over", detail: "A scheduled Webhook opens the week's list. Dated items — a bill due date, a service reminder — arrive on their own inbound Email or Webhook and join the same list." },
          { kind: "loop", title: "Walk the recurring list", detail: "A Loop node walks the household's recurring items — the ones that used to live in one person's memory — and works out which are due this week." },
          { kind: "code", title: "Whose turn, by the family's own rule", detail: "A Code node calls the household's user-defined SpEL formula to compute the next owner from who did it last. The rotation is arithmetic the family wrote, not an opinion the agent formed — which is exactly why it doesn't cause arguments." },
          { kind: "agent", title: "Write it as a list, not a nag", detail: "An Agent node writes the week's list in neutral language: the task, whose turn, and by when. It states, it does not chide, and it never compares two people's totals." },
          { kind: "output", title: "Same message, everyone", detail: "An Output node posts the identical list to the household's shared WhatsApp or Telegram group. There is no private version and no 'reminder about you' sent to anyone else." },
          { kind: "approval", title: "Money still needs a person", detail: "Anything payable — the cylinder, the maid's salary, a service bill — becomes an Approval-gated Razorpay/UPI request with amount and payee shown. The workflow never pays anything by itself." },
        ],
        connectors: ["Webhook (scheduled)", "Email", "WhatsApp", "Telegram", "Approval / HITL", "Razorpay (UPI)", "Tool (MCP → shared calendar)"],
        outcome:
          "The household's recurring work becomes a visible weekly list with a turn already computed, posted identically to everyone — and every payment waits for a person to approve it.",
        impact:
          "The mechanism is externalising memory. When the list exists somewhere neutral, remembering stops being a job one person does silently and asking stops being an accusation — the rotation was agreed once, in a formula the family wrote themselves, so the system says whose turn it is and nobody has to.",
        build: {
          agents: 1,
          agentsNote:
            "1 list-writing agent. The rotation is a deterministic user-defined formula in a Code node — deliberately not an LLM judgement — and the payment gate is a workflow node.",
          pattern: "Loop the recurring list → deterministic turn from the family's own SpEL formula → one neutral shared list → Approval on money",
          prompt:
            "You write a household's weekly list. Given the due items and the owner already computed by the rotation formula, write a short, neutral list: task, whose turn, by when. Use plain, warm, adult language. Do NOT chide, do NOT mention what anyone did or did not do last week, do NOT compare people, and do NOT rank anyone. Never reassign a turn yourself — the formula owns that. If an item has no owner, say so as an open item and let the household decide.",
          agentRoles: [
            { role: "Weekly list writer (once per week)", model: "Economy tier — short, tone-sensitive formatting over data that is already decided", prompt: "Turn the computed list into a warm, neutral shared message. No comparisons, no guilt, no history. Open items are stated as open, not as anyone's failure." },
          ],
          mcp: [
            "The household's shared calendar — via a Tool (MCP) node, to place dated items",
            "Gas / utility / service portals — inbound via Webhook or read via a Tool (MCP) node (no first-party connectors)",
            "WhatsApp / Telegram — the household's own group, the single delivery surface",
          ],
          tools: [
            "knowledge_search (the household's own list of recurring items, vendor numbers, service intervals)",
            "http_request (read due dates; record completion; raise an approved payment request)",
            "user-defined tool `chore_turn` (SpEL): input.lastDoneBy == 'A' ? 'B' : 'A' — the canonical example. A tenant-authored SpEL expression evaluated over the tool's JSON input, sandboxed by construction: no method calls, no constructors, no bean or type references, and no loop in the grammar. It is a data-transform/formula tool, not a scripting engine, which is precisely why it is safe to let a household author it directly. The benefit: this family encodes THEIR rotation — strict alternation, a two-to-one split because one person travels, or 'skip whoever is on night shift' — once, themselves, and the system stops being something one person has to run.",
          ],
          guardrails: [
            "Everyone in the household knows the list exists and opted in — the same message goes to all of them, and there is no private variant about anyone.",
            "No surveillance and no scorekeeping: no location tracking, no reading messages, no checking whether someone 'really' did a task, and no running tally of who did more. It tracks tasks, never people's performance.",
            "Shared by agreement: anyone can opt out and the list stops reaching them, and their name stops entering the rotation; if the household stops the workflow, it stops completely.",
            "The rotation is deterministic and family-authored — a SpEL formula, not a model's judgement — so no one can claim the system decided against them, and it can be changed by agreement in one line.",
            "Every payment is Approval-gated with amount and payee shown, and the workflow never initiates money movement on its own.",
            "The list never chides or compares. If it starts reading like pressure on one person, it has failed at its actual job.",
          ],
          cost:
            "This is the cheapest recipe on the page and should stay that way: the turn calculation is a deterministic SpEL formula costing nothing, and one economy-tier call per week formats the message. Set a fallback model so the list still goes out; there is no reasoning here worth a premium tier.",
          output:
            "The weekly Webhook opens the list → a Loop node walks the recurring items → a Code node applies the household's own `chore_turn` SpEL formula to compute each owner → an economy-tier agent writes one neutral shared list → it posts identically to everyone on WhatsApp or Telegram → anything payable becomes an Approval-gated Razorpay/UPI request with amount and payee shown.",
        },
      },
    ],
  },

  // ============================================== COUPLES & SHARED LIFE ADMIN
  {
    id: "couples",
    name: "Couples & shared life admin",
    icon: Heart,
    tagline:
      "Bills, splits, the week ahead and the renewals nobody remembers — one shared, neutral system both people opted into, so the admin stops being an argument.",
    recipes: [
      {
        id: "couple-bills",
        title: "The electricity bill both of you assumed the other paid",
        scenario:
          "The bill arrives in one inbox. Each of you half-remembers seeing it. Neither pays it, because each assumes the other did — and you find out from the disconnection notice. The recurring ones are worse: rent, broadband, the society maintenance, the two subscriptions nobody can remember signing up for.",
        triggerLabel: "Email / Webhook (biller or bank statement notification)",
        steps: [
          { kind: "trigger", title: "The bill lands", detail: "An Email trigger on either partner's forwarded biller mail, or a Webhook from the utility/bank portal — no first-party connector for these, so they arrive inbound and are read back through a Tool (MCP) node." },
          { kind: "agent", title: "Read it into a shared ledger line", detail: "An Agent node extracts biller, amount, due date and account reference into one structured line, marking anything unclear as needing a look at the original rather than guessing an amount." },
          { kind: "condition", title: "Already handled?", detail: "A Condition node checks the shared ledger for this billing period — if either partner already marked it paid, the workflow stops. This one branch is what kills the 'I thought you did it' failure mode." },
          { kind: "output", title: "Both of you, same message", detail: "An Output node posts one line to your shared WhatsApp or Telegram thread: what is due, how much, by when, and who is down to handle it this cycle. Identical message, both people." },
          { kind: "approval", title: "Paying is an explicit act", detail: "An Approval node holds the payment. A Razorpay/UPI request shows payee, amount and due date, and one of you approves it deliberately — autopay-by-agent is not on offer here, because money moving without a human is exactly the wrong default." },
          { kind: "output", title: "Mark it, once, for both", detail: "Once paid, the ledger line is closed for the period and the thread gets one confirmation — so neither of you pays it twice a week later." },
        ],
        connectors: ["Email", "Webhook (biller / bank portal — inbound)", "WhatsApp", "Telegram", "Approval / HITL", "Razorpay (UPI)"],
        outcome:
          "Every bill becomes one shared ledger line both of you can see, deduplicated against what is already paid, with the payment itself always an explicit human approval.",
        impact:
          "The mechanism is removing the assumption. The 'I thought you paid it' failure comes from two people holding a private, unsynchronised belief about the same bill; one shared line with a visible state replaces both beliefs with a fact either of you can check in three seconds.",
        build: {
          agents: 1,
          agentsNote:
            "1 bill-reading agent. The already-paid dedupe and the payment approval are workflow nodes — deliberately not agent decisions.",
          pattern: "Extract → dedupe against the shared ledger → one shared message → Approval gate on every rupee",
          prompt:
            "You read household bills for two people who share them. From ONE bill or biller notification, extract {biller, account_ref, amount, currency, due_date, period, source}. Use ONLY what the document states — never infer an amount, a due date or an account. If anything is unreadable, set it null, flag needs_original=true, and link the source. You never pay, never schedule a payment, and never describe a bill as settled: payment happens only after a person approves it. If this billing period already shows as paid in the ledger, return duplicate=true and stop.",
          agentRoles: [
            { role: "Bill reader (per bill)", model: "Economy tier — short structured extraction, runs on every biller mail", prompt: "Extract one bill into strict JSON with nulls for anything unclear and needs_original=true. Never infer an amount or a due date. Never treat a bill as paid, and stop immediately if the period is already settled in the ledger." },
          ],
          mcp: [
            "Utility / biller portals and bank statement mail — inbound via Webhook and Email, read back via a Tool (MCP) node (no first-party connectors)",
            "The couple's shared calendar — via a Tool (MCP) node, for the due date",
            "WhatsApp / Telegram — the shared thread, as the single delivery surface",
          ],
          tools: [
            "knowledge_search (the couple's own list of recurring bills, account references and providers)",
            "http_request (read the biller notice; write and close the shared ledger line)",
            "user-defined tool `bill_turn` (SpEL): input.lastPaidBy == 'A' ? 'B' : 'A' — a tenant-authored SpEL formula over the tool's JSON input that names whose turn it is to handle this cycle. Sandboxed by construction: no method calls, no constructors, no bean or type references, no loop in the grammar — a data-transform/formula tool, not a scripting engine. The benefit: the couple encodes their own arrangement once (alternate, or one person takes utilities and the other rent, or split by whose account it debits) and never re-litigates it at the due date.",
          ],
          guardrails: [
            "Both partners know this workflow exists and opted into it. The same message goes to both, in a thread both are in — there is no version of this that reports to one person about the other.",
            "No surveillance whatsoever: it reads bills that were forwarded into the workflow on purpose. No bank-login scraping of a partner's personal accounts, no location tracking, no reading anyone's messages, no inference about anyone's spending habits or whereabouts.",
            "Shared by agreement — either partner can opt out and the workflow stops for them; if either stops it, shared bill reading ends, and neither is left with continued access to the other's forwarded mail.",
            "Money never moves without an explicit human approval showing payee, amount and due date. Autopay-by-agent is deliberately not offered.",
            "Idempotent per biller + billing period, so a resent notice or a forwarded duplicate can never trigger a second payment.",
            "Amounts are never inferred — an unreadable bill says 'check the original' and links it.",
          ],
          cost:
            "Bills are frequent and short, so the reader belongs on the economy tier with a hard cap; there is no synthesis step to pay for. Configure a fallback model — a bill that still gets surfaced on a plainer model beats a missed due date. The expensive resource here is human attention, and the dedupe branch is what protects it.",
          output:
            "The bill arrives by Email or Webhook → an economy-tier agent extracts one ledger line → the Condition node stops it dead if the period is already paid → one identical message goes to the shared thread with whose turn it is → a Razorpay/UPI request is held at the Approval node until a person approves payee and amount → the ledger line closes for the period and both of you see the confirmation.",
        },
      },
      {
        id: "couple-shared-split",
        title: "Shared expenses, split by your own formula — not by whoever kept the receipts",
        scenario:
          "You split things unevenly on purpose — 60/40, because one salary is bigger — and it works fine in principle. In practice one of you keeps screenshots in a chat, the other has a half-abandoned spreadsheet, and every couple of months there is an awkward twenty-minute reconciliation that neither of you enjoys and one of you always ends up running.",
        triggerLabel: "WhatsApp / Email / Webhook (an expense either partner logs)",
        steps: [
          { kind: "trigger", title: "Either of you logs a spend", detail: "A WhatsApp or Telegram message ('2,400 groceries'), a forwarded receipt email, or a Webhook from a card/UPI notification the couple chose to forward in. Both partners log to the same place; neither's account is read without them sending it." },
          { kind: "agent", title: "Normalise it", detail: "An Agent node turns free text or a receipt into {payer, amount, category, date, note}, asking one short clarifying question rather than guessing a category or an amount." },
          { kind: "code", title: "Apply your split, exactly", detail: "A Code node runs the couple's own user-defined SpEL formula to compute each share. Arithmetic, deterministic, and authored by the two of you — the model does not get an opinion about who owes what." },
          { kind: "loop", title: "Roll up the period", detail: "A Loop node walks the period's entries and nets them down to a single number and a direction, so the answer is 'X owes Y this much', not a wall of line items." },
          { kind: "output", title: "One statement, both of you", detail: "An Output node posts the same short statement to the shared thread — total, split applied, net position, and the line items available if either of you wants to look." },
          { kind: "approval", title: "Settling up is approved, not automatic", detail: "If you settle by UPI, an Approval node holds a Razorpay request showing the exact net amount. One of you approves it; the workflow never moves money between two people on its own." },
        ],
        connectors: ["WhatsApp", "Telegram", "Email", "Webhook", "Approval / HITL", "Razorpay (UPI)"],
        outcome:
          "A running shared ledger both partners write to, split by a formula they authored, netted to one number per period, with settlement always an explicit human approval.",
        impact:
          "The mechanism is making the split arithmetic instead of memory. The awkward reconciliation exists because the record is incomplete and asymmetric — one person holds it. A ledger both write to, with a deterministic formula, removes both the asymmetry and the negotiation, leaving a number.",
        build: {
          agents: 1,
          agentsNote:
            "1 normalising agent. The split is a deterministic user-defined SpEL formula in a Code node — deliberately not an LLM judgement — and settlement is a workflow approval.",
          pattern: "Normalise → deterministic user-authored split → Loop to net the period → Approval on settlement",
          prompt:
            "You log shared expenses for two people. From a short message or a receipt, extract {payer, amount, currency, category, date, note}. If the amount, the payer or the category is unclear, ask ONE short question rather than guessing — never invent a figure. You do NOT compute the split: the couple's own formula does that. You do not comment on what was bought, whether it was worth it, or on anyone's spending. Log it and move on.",
          agentRoles: [
            { role: "Expense normaliser (per entry)", model: "Economy tier — very short extraction, highest-frequency step by a wide margin", prompt: "Turn one message or receipt into a strict JSON expense line. One clarifying question if unclear, never a guessed amount. No commentary of any kind about the purchase or the person." },
          ],
          mcp: [
            "Card / UPI notification mail the couple chooses to forward — inbound via Email or Webhook (no first-party connector, and no account scraping)",
            "The couple's own ledger store — via a Tool (MCP) node",
            "WhatsApp / Telegram — the shared thread, for logging and for the statement",
          ],
          tools: [
            "knowledge_search (the couple's own categories and their written split agreement)",
            "http_request (append the ledger line; read the period's entries)",
            "user-defined tool `split_share` (SpEL): input.category == 'RENT' ? input.amount * 0.6 : input.amount * 0.5 — a tenant-authored SpEL expression over the tool's JSON input returning one partner's share, with a companion formatter expression producing the display string. Sandboxed by construction: no method calls, no constructors, no bean or type references, and no loop in the grammar — a data-transform/formula tool, not a scripting engine. The benefit is real: this couple encodes THEIR ratios once — 60/40 on rent, 50/50 on groceries, 100% on one person's own subscriptions — and never has to explain the arrangement to a model, a developer, or each other again.",
          ],
          guardrails: [
            "Both partners know the ledger exists, both write to it, and both see the same statement. There is no one-sided view and no report about one partner sent to the other.",
            "Only what a partner deliberately logs or forwards enters the ledger. No bank or card account is connected to read one person's spending, no location tracking, no reading messages, and no inference about where anyone was or what they bought beyond the line they typed.",
            "Shared by agreement: either partner can opt out and the workflow stops for them — logging ends, the statement stops, and neither retains a live feed of the other's spending.",
            "The split is deterministic and authored by the couple, so the system never appears to take a side. Changing it takes one line and requires both of them to agree, not a support ticket.",
            "No settlement without an explicit approval showing the exact net amount and direction. The workflow never transfers money between two people by itself.",
            "The agent never comments on what was bought or on anyone's spending habits. A ledger that editorialises becomes a weapon; this one records.",
          ],
          cost:
            "Every logged expense is one very short economy-tier call, which is the whole recurring cost — the split itself is a SpEL formula and costs nothing, and the period roll-up is a Loop over data, not a reasoning pass. Set a fallback model so logging never fails silently; a dropped entry is what makes people abandon a shared ledger.",
          output:
            "Either partner logs a spend on WhatsApp, Telegram or by forwarded receipt → an economy-tier agent normalises it → a Code node applies the couple's own `split_share` SpEL formula → a Loop nets the period to a single number and direction → the same short statement posts to the shared thread → settling up is a Razorpay/UPI request held at the Approval node until a person approves the exact amount.",
        },
      },
      {
        id: "couple-week-ahead",
        title: "The week ahead, agreed on Sunday instead of discovered on Thursday",
        scenario:
          "There is a shared calendar. Nobody updates it. So the actual coordination happens as a Thursday-night discovery that you are both out on Friday, the car is needed in two places on Saturday, and the dinner you agreed to a month ago is tomorrow. The information existed — it was just never in the same place at the same time.",
        triggerLabel: "Scheduled Webhook (weekly) + Email/Webhook for anything newly dated",
        steps: [
          { kind: "trigger", title: "Sunday evening", detail: "A scheduled Webhook opens the week. New dated things — a booking confirmation, an invitation, a delivery slot — arrive on their own inbound Email or Webhook during the week and join the same view." },
          { kind: "parallel", title: "Read both calendars — both shared on purpose", detail: "A Parallel node reads each partner's calendar through a Tool (MCP) node. Each person connected their own calendar themselves and shares only free/busy plus the events they chose to share — not a full read of the other's life." },
          { kind: "agent", title: "Find the actual collisions", detail: "An Agent node looks for the specific things that cause Thursday-night arguments: both out at once when someone needs to be home, one car in two places, a commitment neither of you has prepared for, an evening with nothing shared in it at all." },
          { kind: "condition", title: "Anything genuinely clashing?", detail: "A Condition node distinguishes a real conflict from a busy week. If nothing collides, the digest says so in one line — a weekly message that always finds a problem trains you both to ignore it." },
          { kind: "output", title: "One short shared digest", detail: "An Output node posts the same digest to both of you: the collisions, the shared commitments, and anything that needs a decision from the two of you rather than from one." },
          { kind: "approval", title: "Nobody's diary is edited without them", detail: "An Approval node gates any calendar write or any message sent on someone's behalf — declining an invitation or moving an event needs the person whose commitment it is to approve it themselves." },
        ],
        connectors: ["Webhook (scheduled)", "Email", "WhatsApp", "Telegram", "Tool (MCP → each partner's own calendar)", "Approval / HITL"],
        outcome:
          "One short Sunday digest showing only the genuine collisions in the week ahead, posted identically to both partners, with no calendar edited and no message sent on anyone's behalf without their own approval.",
        impact:
          "The mechanism is bringing two private schedules into one shared view at a moment when the week can still be changed. Thursday-night discoveries happen because the collision was only visible to someone reading both calendars, and nobody was — the digest is that reader, and it reports to both of you at once.",
        build: {
          agents: 2,
          agentsNote:
            "1 collision-detection agent + 1 digest writer. The parallel calendar reads, the real-conflict gate and the write approval are workflow nodes.",
          pattern: "Parallel read of two consented calendars → detect real collisions only → one shared digest → Approval on any write",
          prompt:
            "You look at the week ahead for two people who have each connected their own calendar and agreed to share it with the other. Identify ONLY genuine collisions: both unavailable when one of them needs to be present, one shared resource (a car, a room) committed twice, a joint commitment neither has prepared for, or a week with no shared time at all. Report what is on the calendars — never speculate about what an event means, who someone is meeting, or why. Do not flag a busy week as a problem. If nothing collides, say exactly that in one line.",
          agentRoles: [
            { role: "Collision detector (once per week)", model: "Standard tier — genuine reasoning over two schedules", prompt: "Compare the two shared schedules and return only real collisions with the reason each is a collision. Report entries as written; never infer the purpose of an event or who anyone is with. An uneventful week returns an empty list, and that is a valid, good answer." },
            { role: "Digest writer (once per week)", model: "Economy tier — short, warm formatting of an already-decided list", prompt: "Write a short shared digest: the collisions, the joint commitments, and anything needing a decision from both. Neutral and warm. Never characterise either person's week as too full or too empty, and never suggest what anyone should cancel." },
          ],
          mcp: [
            "Each partner's own calendar — via a Tool (MCP) node, connected by that partner themselves (no first-party connector); free/busy plus the events they chose to share",
            "Booking / invitation mail — inbound via Email or Webhook",
            "WhatsApp / Telegram — the shared thread, as the single delivery surface",
          ],
          tools: [
            "knowledge_search (the couple's own standing arrangements — who does the school run, which evenings are protected)",
            "http_request (read shared free/busy; write an approved calendar entry)",
            "user-defined tool `digest_window` (SpEL): input.dayOfWeek == 'SUN' ? 7 : 2 — a tenant-authored SpEL formula over the tool's JSON input setting how far ahead each run should look: a full week on Sunday, a short horizon on a mid-week nudge. Sandboxed by construction — no method calls, no constructors, no bean or type references, no loop in the grammar; a data-transform/formula tool, not a scripting engine. The benefit: the couple tunes their own rhythm (some want Sunday only, some want a Wednesday check too) without a developer.",
          ],
          guardrails: [
            "Both partners connected their own calendar themselves and both know the digest exists. Neither has access to the other's calendar through this workflow beyond what they each chose to share.",
            "No surveillance: free/busy and shared events only. No location tracking, no reading messages, no attendee-level snooping, and no inference about who anyone is meeting or why. The agent reports entries as written and speculates about nothing.",
            "Shared by agreement — either partner can disconnect their calendar at any time and the digest stops for both, immediately. There is no residual read.",
            "No calendar is written to and no message is sent on anyone's behalf without that person's own approval — declining an invitation is theirs to do, not their partner's and not the agent's.",
            "The digest is identical for both and posted to a shared thread — no private variant, no 'here is what your partner is doing this week' report.",
            "A quiet week returns nothing to flag, deliberately. A weekly message that manufactures a problem is a message you both stop reading.",
          ],
          cost:
            "One standard-tier reasoning pass per week over two schedules, plus one economy-tier formatting call — a weekly cadence, so total cost is small and flat. Put the digest writer on an economy model with a fallback: the reasoning is where the value is, the wording is not.",
          output:
            "The Sunday Webhook opens the week → a Parallel node reads both consented calendars via Tool (MCP) nodes → a standard-tier agent finds only real collisions → the Condition node lets an uneventful week pass with one line → an economy-tier writer posts the same short digest to both partners → any calendar write or on-behalf message waits at the Approval node for the person whose commitment it is.",
        },
      },
      {
        id: "couple-renewals",
        title: "The renewals nobody owns — insurance, rent, passports, the car",
        scenario:
          "The health policy renews in March, the rent agreement in June, one passport expired last year and was discovered at an airline counter. These live in a folder in one person's email, a physical file in a cupboard, and the memory of whoever dealt with it last time. There is no system, only a person — and when that person is busy, the deadline is simply missed.",
        triggerLabel: "Scheduled Webhook (monthly look-ahead) + Email/Webhook when a renewal notice arrives",
        steps: [
          { kind: "trigger", title: "Look ahead, monthly", detail: "A scheduled Webhook runs the look-ahead. Insurer, landlord or bank renewal notices arrive on their own inbound Email or Webhook and update the same record." },
          { kind: "knowledge", title: "The household's own documents", detail: "A Knowledge node holds what the couple chose to put there: policy schedules, the rent agreement, warranty cards, ID expiry dates. Their documents, in their own tenant — nothing is fetched from anywhere they did not connect." },
          { kind: "loop", title: "Walk every dated item", detail: "A Loop node walks each tracked item and works out what falls inside the look-ahead window the couple set — with enough runway to actually act, not a notice the day before." },
          { kind: "agent", title: "Say what renewing takes", detail: "An Agent node writes, for each item, what is actually needed: the documents to gather, the likely amount from the couple's own record, and who is down to handle it. It quotes the source document rather than asserting a figure from nowhere." },
          { kind: "output", title: "Both of you, one list", detail: "An Output node posts the same short list to the shared thread, with the earliest deadline first and a clear owner per item." },
          { kind: "approval", title: "Money out and documents out both need a person", detail: "An Approval node holds two distinct things: any premium or fee payment via Razorpay/UPI, and any document leaving the household — a policy schedule to a broker, an ID to a landlord — approved as a specific, named share each time." },
        ],
        connectors: ["Webhook (scheduled)", "Email", "WhatsApp", "Telegram", "Knowledge base", "Approval / HITL", "Razorpay (UPI)", "Tool (MCP → insurer / bank portal)"],
        outcome:
          "A monthly look-ahead over every renewal the couple tracks, grounded in their own documents, with an owner per item — and both money out and documents out gated on an explicit human approval.",
        impact:
          "The mechanism is giving deadlines an owner other than someone's memory. A renewal is missed not because it was unknown but because it was known by one person at the wrong moment; a monthly look-ahead with enough runway converts it from a surprise into a scheduled, assignable task.",
        build: {
          agents: 2,
          agentsNote:
            "1 per-item agent inside the Loop + 1 list writer. The payment gate and the document-share gate are separate workflow approvals, on purpose.",
          pattern: "Loop the tracked items → ground each in the couple's own documents → one shared list → two distinct Approval gates (money, and documents leaving the house)",
          prompt:
            "You track renewals for a couple. For ONE item {type, provider, expires_on, last_amount, documents_needed}, state plainly what renewing requires: documents to gather, the expected amount, and the deadline. Ground every figure in the couple's own document in Knowledge and quote the source line — if the amount is not written down, say 'last known' or 'unknown', never estimate. You are not an adviser: never recommend a policy, an insurer, or a financial product, and never say whether cover is adequate. You surface the deadline and what it takes; the decision is theirs.",
          agentRoles: [
            { role: "Renewal item agent (per item, inside the Loop)", model: "Economy tier — short grounded extraction, runs once per tracked item per month", prompt: "One renewal, one short grounded summary. Quote the source line for every figure. 'Unknown' is a valid answer and is always better than an estimate. No product recommendations, ever." },
            { role: "List writer (once per run)", model: "Standard tier — ordering and clarity across items", prompt: "Order the due items by real urgency, group anything sharing a document, and write one short shared list with a clear owner per item. Neutral tone; never imply anyone let something slip." },
          ],
          mcp: [
            "Insurer / bank / landlord portals — inbound via Webhook, read back via a Tool (MCP) node (no first-party connectors)",
            "The couple's shared calendar — via a Tool (MCP) node, to place each deadline with runway",
            "WhatsApp / Telegram — the shared thread, as the single delivery surface",
          ],
          tools: [
            "knowledge_search (the couple's own policy schedules, rent agreement, warranty cards, ID expiry dates)",
            "http_request (read a renewal notice; write an approved calendar entry; raise an approved payment)",
            "user-defined tool `renewal_lead_days` (SpEL): input.type == 'PASSPORT' ? 180 : (input.type == 'INSURANCE' ? 45 : 21) — a tenant-authored SpEL expression over the tool's JSON input returning how much runway each kind of renewal needs. Sandboxed by construction: no method calls, no constructors, no bean or type references, and no loop in the grammar — a data-transform/formula tool, not a scripting engine. The benefit is concrete: a passport genuinely needs months and a broadband renewal needs a fortnight, and the couple encodes that difference themselves, once, instead of receiving every reminder on the same useless schedule.",
          ],
          guardrails: [
            "Both partners know the tracker exists, both can see every item, and both receive the same list. There is no version that reports one partner's paperwork to the other.",
            "No surveillance: only documents the couple deliberately added and portals they deliberately connected. No location tracking, no reading anyone's messages, no scraping of a partner's personal accounts.",
            "Shared by agreement — either partner can opt out and the tracker stops for them; items that are solely theirs leave the shared list, and the workflow stops entirely if either ends it.",
            "Two separate approval gates, deliberately not merged: money out (premium or fee, with payee and amount shown) and documents out (a specific, named share to a specific recipient, every time). Blanket 'always share my ID' is not offered.",
            "Figures are quoted from the couple's own documents with the source line; 'unknown' is an acceptable output and an estimate is not.",
            "The agent never recommends a policy, an insurer or any financial product, and never opines on whether cover is adequate — it surfaces the deadline and what renewing takes, and the decision stays with them.",
          ],
          cost:
            "The per-item agent runs once per tracked item per run, so it sits on the economy tier with a hard cap — a household tracks perhaps a couple of dozen items, and the monthly cadence keeps the total flat. The one standard-tier list-writing call per run is where ordering and clarity are worth paying for. A fallback model on both keeps the look-ahead running, since a missed run is the one failure that costs real money.",
          output:
            "The monthly Webhook opens the look-ahead → a Loop walks each tracked item with the couple's own `renewal_lead_days` SpEL runway → an economy-tier agent grounds each in their own documents in Knowledge, quoting source lines → a standard-tier writer posts one ordered shared list with an owner per item → any premium is held at the payment Approval node, and any document leaving the household is held at a separate, per-share Approval node.",
        },
      },
    ],
  },
];
