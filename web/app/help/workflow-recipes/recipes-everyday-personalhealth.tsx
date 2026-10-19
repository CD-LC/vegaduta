import type { Industry } from "./recipes-data";
import { House, Stethoscope } from "lucide-react";

// ---------------------------------------------------------------------------
// Everyday life & family admin + Personal health & medical records — the same
// "inline copy, no i18n keys" precedent and honesty rules as recipes-data.tsx,
// but pointed at ORDINARY PEOPLE rather than companies. The existing packs
// cover hospitals, clinics, diagnostics labs and pharmacies as businesses;
// these two cover the other side of the counter — the family, the patient, the
// person whose bill due-dates live on a fridge calendar.
//
// Every recipe is expressed ONLY in VegaDūta's real building blocks (Trigger /
// Agent / Knowledge / Condition / Approval / Output / Tool(MCP) / Voice /
// Device / Loop / Code / Parallel / Supervisor / Sub-workflow), so each maps
// 1:1 to something you can actually build in the Workflow designer.
//
// Honesty rule: systems VegaDūta does NOT ship a first-party connector for —
// your bank, the biller, the school portal, the hospital or lab portal, the
// insurer, a government document store — are reached the real way, via an
// inbound Webhook trigger and/or a Tool (MCP) node, and are LABELLED as such.
// Only real channels are used (Webhook, WhatsApp, SMS/Twilio, Voice/Call,
// Email, Slack, Discord, Telegram, Instagram, Messenger, X, ElevenLabs,
// Sarvam, Razorpay UPI). Impact lines describe the mechanism, never invented
// ROI numbers.
//
// MEDICAL SAFETY (absolute, stated in the guardrails of EVERY health recipe):
// the agent NEVER diagnoses, NEVER changes or advises on medication or dosage,
// and NEVER calls a result safe/unsafe or normal/abnormal. It organises,
// reminds, summarises, translates, and prepares questions for the doctor —
// and escalates to a named human. PRIVACY: your household and health records
// live in your own tenant's Knowledge base; nothing is sent to a third party
// (doctor, insurer, relative, school) without passing an Approval step.
//
// USER-DEFINED TOOLS, described honestly: a user-defined tool here is a
// tenant-authored SpEL expression evaluated over the tool's JSON input. It is
// sandboxed by construction — no method calls, no constructors, no bean or
// type references, and the grammar has no loop — so it is a data-transform /
// formula tool, NOT a scripting engine. That is exactly why it fits a family:
// you encode YOUR thresholds and YOUR formats ("urgent means two days, not
// seven") without needing a developer, and it can't do anything else.
// ---------------------------------------------------------------------------

export const INDUSTRIES_EVERYDAY: Industry[] = [
  // ================================================ EVERYDAY LIFE & FAMILY ADMIN
  {
    id: "everyday-life",
    name: "Everyday life & family admin",
    icon: House,
    tagline:
      "The fridge calendar, the 11pm school-group message, the renewal you only remember when the SMS arrives — turned into one household assistant that reminds, drafts and files, with your approval on anything that spends money or sends a document.",
    recipes: [
      {
        id: "life-bill-calendar",
        title: "Every bill in one place, chased before the late fee — not after",
        scenario:
          "Electricity, water, broadband, the phone, the maintenance, two EMIs and a credit card. Some arrive by email, some by SMS, some as a WhatsApp forward from the society group. Due dates live on a fridge calendar in three different handwritings, and you find out one was missed when the late fee shows up on the next statement.",
        triggerLabel: "Email / SMS / WhatsApp (a bill or reminder arrives)",
        steps: [
          { kind: "trigger", title: "The bill lands, wherever it lands", detail: "An Email trigger on the household inbox, an SMS trigger on the number the billers use, and a WhatsApp trigger for forwards all feed the same workflow — the point is that you stop having to notice." },
          { kind: "agent", title: "Read it into fields", detail: "An Agent node pulls out biller, account/consumer number, amount, due date and billing period into structured fields. If the amount or due date isn't clearly stated, it says so instead of guessing — a guessed due date is worse than no due date." },
          { kind: "tool", title: "Your own urgency rule", detail: "A user-defined tool (a SpEL expression you write once) turns days-to-due into your household's own label — not a vendor's idea of 'urgent'. It's a formula over the input JSON: no code, no loops, nothing it can do beyond compute that label." },
          { kind: "condition", title: "Urgent, soon or later?", detail: "A Condition node branches on that label: URGENT goes out now on WhatsApp and, if you want, a Voice call; SOON joins the weekly digest; LATER is just filed." },
          { kind: "approval", title: "You approve before a rupee moves", detail: "An Approval node holds any payment. The workflow can prepare a Razorpay UPI payment request with the amount and biller filled in — but it is your tap that pays it, every single time, including on recurring bills." },
          { kind: "output", title: "Reminder out, bill filed", detail: "An Output node sends the reminder on WhatsApp (and Email for the record), and the bill itself is filed into your household Knowledge base so 'what did we pay for electricity in May?' is a question you can just ask." },
        ],
        connectors: ["Email", "SMS/Twilio", "WhatsApp", "Voice/Call", "Razorpay (UPI)", "Approval / HITL", "Knowledge base", "Tool (MCP → biller or bank portal, if you have one)"],
        outcome: "Every bill that arrives on any channel ends up in one dated list, with a reminder that fires on your own definition of 'urgent' and a payment you approve with one tap.",
        impact: "The mechanism is simple: the miss happens because bills arrive on four channels and the calendar only captures the ones somebody remembered to write down. Catching all four channels into one structured list removes the step that actually fails — noticing — while the Approval node keeps every payment a deliberate human act.",
        build: {
          agents: 1,
          agentsNote: "1 bill-reading agent. The urgency rule is a user-defined SpEL tool, and the branching, the payment approval and the filing are workflow nodes — not extra agents.",
          prompt:
            "You are a household bill assistant for {{family}}. From an incoming message (email, SMS or a forwarded WhatsApp), extract {biller, account_ref, amount, currency, due_date, billing_period, channel}. Use ONLY what the message actually says — if the amount or due date is not clearly stated, return that field as null and set needs_check=true with a one-line reason. NEVER estimate a due date from a previous bill and never round an amount. You do not pay anything and you do not confirm anything as paid: you only prepare a reminder and a draft payment for a human to approve. Write reminders in plain, warm language, in the household's preferred language, with the biller, the amount and the date — no lectures, no urgency theatre beyond the label the workflow gives you.",
          agentRoles: [
            { role: "Bill reader (per message)", model: "Economy tier — this runs on every bill, SMS and forward the household receives, so its per-call cost is the only cost that scales", prompt: "Extract the bill fields into strict JSON. Null for anything not explicitly stated, needs_check=true with a reason. No estimating, no rounding, no inferring from history." },
            { role: "Weekly digest writer (once a week)", model: "Standard tier — runs once per week over the whole list, so a better model here is nearly free", prompt: "Write one short weekly household digest from the filed bills: what is due this week with dates and amounts, what was paid, and anything flagged needs_check. Use only the filed records; never invent a bill and never state something was paid unless the record says so." },
          ],
          mcp: ["Your bank or biller portal, if you use one — via a Tool (MCP) node (no first-party connector; nothing is read or written there without your approval)", "Email / SMS / WhatsApp — the channels the bills actually arrive on"],
          tools: [
            "user-defined tool `bill_urgency` (SpEL): input.daysToDue <= 2 ? 'URGENT' : (input.daysToDue <= 7 ? 'SOON' : 'LATER') — you set the thresholds yourself, so 'urgent' means what it means in YOUR house; it is a pure formula over the input JSON, with no method calls, no loops and no way to reach anything else",
            "user-defined tool `share_per_head` (SpEL): input.amount / (input.householdSize == 0 ? 1 : input.householdSize) — for shared flats and joint families, the split is computed the same way every time instead of being argued about",
            "knowledge_search (your own filed bills — 'what was the maintenance last quarter?')",
            "send_email (the archive copy of each reminder, so there's a record outside chat)",
          ],
          guardrails: [
            "No payment is ever made by the agent. A Razorpay UPI request can be prepared with the biller and amount pre-filled, but it passes the Approval node and needs your tap — recurring bills included, every time",
            "Nothing is inferred: a missing amount or due date is returned as null with needs_check=true, never estimated from last month's bill",
            "Your bills, account numbers and payment history stay in your own tenant's Knowledge base. Nothing is forwarded to a relative, an accountant or any third party without an Approval step",
            "Idempotent per (biller, billing_period) — the same bill arriving by both email and SMS produces one entry and one reminder, not two",
          ],
          cost:
            "Cost tracks message volume, not household size. The reader runs on every incoming bill/SMS/forward, so it sits on the economy tier with a hard token cap and a fallback model configured — if the primary is unavailable the reminder still goes out, which matters more here than prose quality. The one standard-tier call per week is the digest. Reminder sends are channel cost, not model cost: keep the routine ones on WhatsApp/SMS and reserve the Voice call for the URGENT branch only.",
          output:
            "Each bill becomes a structured record filed in your household Knowledge base; the `bill_urgency` SpEL tool labels it, the Condition node routes URGENT to an immediate WhatsApp (and optional Voice call) while SOON joins the weekly digest; any payment is a Razorpay UPI request that waits at the Approval node for your tap, and an Email copy of every reminder gives you a record outside chat.",
        },
      },
      {
        id: "life-renewals",
        title: "Renewals you find out about a month early, not two days late",
        scenario:
          "The insurance renewal SMS arrives 48 hours before expiry. The car's PUC, the driving licence, the passport, the fridge's extended warranty, the domain name, the society's NOC — each has its own date and its own piece of paper, and the only alarm system is an SMS from whoever happens to send one. Twice you've paid a penalty for something you'd have happily renewed a month earlier.",
        triggerLabel: "Webhook (scheduled monthly sweep of your own document folder)",
        steps: [
          { kind: "trigger", title: "Monthly sweep", detail: "A scheduled Webhook trigger kicks off a sweep across the documents you've filed — policies, licences, warranties, agreements. Nothing external is polled; it reads your own Knowledge base." },
          { kind: "knowledge", title: "Your own paperwork, indexed", detail: "A Knowledge node holds the documents you've already photographed or forwarded in: policy PDFs, the RC book, the rent agreement, warranty cards. This is your household's file drawer, in your own tenant." },
          { kind: "loop", title: "One pass per document", detail: "A Loop node runs a cheap agent once per document to pull out the expiry date and what renewing actually involves. Twenty documents cost twenty small calls, not one enormous one." },
          { kind: "tool", title: "Your own lead time", detail: "A user-defined SpEL tool converts days-to-expiry into your household's own window. A passport wants ninety days of warning; a PUC wants seven. You write that rule once as a formula — no developer, and nothing the tool can do beyond return a label." },
          { kind: "condition", title: "Anything inside its window?", detail: "A Condition node keeps quiet about everything that isn't due — the whole value is that this is silent eleven months of the year and loud in the right week." },
          { kind: "agent", title: "What renewing actually takes", detail: "An Agent node drafts the practical bit: which documents you'll need to hand, roughly what it costs based on the last renewal you filed, and where you did it last time. It states what your records say — it does not invent a fee or a government procedure." },
          { kind: "approval", title: "You approve anything binding", detail: "An Approval node gates any payment (Razorpay UPI) and any submission or sharing of a document to a portal or an agent. The reminder is free to send; the commitment is yours." },
          { kind: "output", title: "One calm message", detail: "An Output node sends a single WhatsApp with everything coming up in the window and the checklist for each — not eight separate pings." },
        ],
        connectors: ["Webhook (scheduled)", "WhatsApp", "Email", "Knowledge base", "Razorpay (UPI)", "Approval / HITL", "Tool (MCP → an insurer or government portal, if you use one)"],
        outcome: "A quiet monthly sweep that produces nothing at all until something genuinely needs doing, then hands you the date, the checklist and a one-tap payment you approve.",
        impact: "The failure mode isn't laziness, it's that the reminder is owned by the counterparty and they send it when it suits them. Moving the clock to your own filed documents means the warning window is one you chose — and because the sweep reads only your own records, the reminder can't quietly stop arriving because a marketing list changed.",
        build: {
          agents: 2,
          agentsNote: "1 cheap extractor (runs once per document via the Loop node) + 1 writer that composes the single monthly message. The lead-time rule is a user-defined SpEL tool; the branch, the approval and the payment request are workflow nodes.",
          pattern: "Map-reduce: Loop over your filed documents (map) → one composed reminder (reduce) → human approves anything binding",
          prompt:
            "(Extractor) Read ONE household document and return strict JSON {doc_type, holder, identifier, expiry_date, issuing_body, renewal_notes, source_file}. Take every value from the document itself — if the expiry date is not printed on it, return null and set needs_check=true. NEVER infer an expiry from a typical validity period ('passports are ten years'), and never state a fee or a procedure that is not written in the document or in a previously filed renewal receipt. You are indexing paperwork, not advising on it.",
          agentRoles: [
            { role: "Document extractor (per document, in a Loop)", model: "Economy tier — it runs across the whole folder every month, so this is the single dominant cost lever", prompt: "Extract {doc_type, holder, identifier, expiry_date, issuing_body, renewal_notes} from ONE document as strict JSON. Null + needs_check=true for anything not printed on the document. No inferred validity periods, no invented fees." },
            { role: "Reminder writer (once per sweep)", model: "Standard tier — one call a month, so this is where the reasoning budget goes", prompt: "Compose ONE warm, short message covering only the documents inside their reminder window: what expires, when, what you'll need to renew it, and what it cost last time according to the filed receipt. Say 'per your filed receipt' when quoting a past amount. If a document was flagged needs_check, list it plainly as 'we couldn't read the expiry — worth a look' rather than skipping it." },
          ],
          mcp: ["An insurer / RTO / municipal portal, if you use one — via a Tool (MCP) node (no first-party connector), and read-only unless an Approval says otherwise", "WhatsApp / Email — the reminder channel"],
          tools: [
            "user-defined tool `renewal_window` (SpEL): input.daysToExpiry <= input.leadDays ? 'DUE' : (input.daysToExpiry <= input.leadDays + 30 ? 'APPROACHING' : 'QUIET') — the lead time is per document type and set by you, so a passport can shout ninety days out while a PUC waits until seven",
            "knowledge_search (your own filed policies, licences, warranties and past renewal receipts)",
            "http_request (only ever behind an Approval node, to submit or fetch from a portal you've explicitly connected)",
          ],
          guardrails: [
            "The agent reminds; it never renews. Every payment (Razorpay UPI) and every submission or document upload to a portal passes the Approval node first",
            "Expiry dates come from the document, not from general knowledge about how long that document usually lasts — unreadable means needs_check, never a guess",
            "Costs and procedures are quoted only from your own filed receipts, always attributed ('per your filed receipt'), never asserted as the current official fee",
            "Your policies, licences and identity documents stay in your own tenant. Nothing is shared with an agent, a broker or a relative without an Approval step",
          ],
          cost:
            "Linear and tiny: N documents × one economy-tier extraction + ONE standard-tier compose, once a month. Because the extractor runs across the whole folder, keep it on the economy tier with a strict token cap and a fallback model — a reminder that arrives in plainer prose beats one that doesn't arrive. If the folder is large, run the map in Parallel batches: same per-document cost, finishes sooner.",
          output:
            "The Loop maps the cheap extractor over your filed documents → the `renewal_window` SpEL tool labels each one against YOUR lead time → the Condition node drops everything QUIET → the writer composes a single WhatsApp listing what's due, the checklist and the last known cost → any payment or portal submission waits at the Approval node for you.",
        },
      },
      {
        id: "life-school-comms",
        title: "The school WhatsApp group, read for you (and only the parts that need you)",
        scenario:
          "Two hundred messages a day in the class group. Somewhere in there: a consent form due Friday, a fee reminder, a change of uniform for sports day, and one genuinely important note from the class teacher — usually posted at 11pm and buried by breakfast under photographs and forwarded good-morning images. You either read all of it or miss something that mattered.",
        triggerLabel: "WhatsApp / Telegram (school or class group messages)",
        steps: [
          { kind: "trigger", title: "Group messages arrive", detail: "A WhatsApp (or Telegram) trigger takes the group's messages as they come. Nothing is posted back to the group — this workflow reads on your behalf and speaks only to you." },
          { kind: "agent", title: "Is there an action in here?", detail: "A cheap Agent node classifies each message: ACTION (something you must do, by a date), INFO (worth knowing, no action), or NOISE. Photos, greetings and thank-you chains are noise, and it says so without editorialising." },
          { kind: "condition", title: "Anything actionable?", detail: "A Condition node drops NOISE entirely and holds INFO for the evening digest. Only ACTION items go further — that is the entire point." },
          { kind: "agent", title: "Turn it into a task", detail: "A second Agent node turns each ACTION into {what, for which child, by when, what you need to bring or sign, how much it costs} — quoting the group message rather than paraphrasing away the details." },
          { kind: "tool", title: "Your own 'is this urgent' rule", detail: "A user-defined SpEL tool labels each task from days-to-deadline plus whether money or a signature is involved, using your own thresholds. A formula over the input JSON — nothing more, by construction." },
          { kind: "approval", title: "You approve anything that leaves the house", detail: "An Approval node gates paying a fee (Razorpay UPI), submitting a form, or replying to the school. A draft reply can be written for you; sending it is your decision." },
          { kind: "output", title: "One evening digest, one morning nudge", detail: "An Output node sends a short digest each evening and a nudge on the morning of anything due — on WhatsApp direct to you, not to the group." },
        ],
        connectors: ["WhatsApp", "Telegram", "Email", "Razorpay (UPI)", "Approval / HITL", "Knowledge base", "Sarvam (Indian languages)", "Tool (MCP → school portal, if the school has one)"],
        outcome: "You stop reading two hundred messages and start reading a five-line evening digest, with a nudge on the morning of anything due and a one-tap approval for fees and forms.",
        impact: "The mechanism is filtering with a memory: classification alone would just be a smarter notification, but keeping the extracted tasks in your own Knowledge base means Friday's 'did we sign that?' is answerable. Because sending anything back to the school passes an Approval, the agent can be aggressive about reading and still incapable of embarrassing you in the group.",
        build: {
          agents: 2,
          agentsNote: "1 economy-tier classifier on every message + 1 task extractor that runs only on messages classified ACTION. The digest, the urgency label and the approval are nodes and a SpEL tool, not agents.",
          pattern: "Cheap filter → expensive extraction only on what survived the filter",
          prompt:
            "(Classifier) Classify ONE school-group message as ACTION, INFO or NOISE. ACTION means a parent must DO something by a date — sign, pay, send, attend, bring. INFO is worth knowing but requires nothing. NOISE is greetings, photos, thanks, forwards. Return {class, confidence, child_hint}. When you are unsure between ACTION and INFO, choose ACTION — a false alarm costs a glance, a missed consent form costs a school trip. Never summarise away a date, an amount or a child's name. You never reply to the group.",
          agentRoles: [
            { role: "Message classifier (every message)", model: "Economy tier — this is the highest-volume call in the whole guide, running on hundreds of messages a day; nothing else affects the bill as much", prompt: "ACTION / INFO / NOISE plus confidence, in strict JSON. When torn between ACTION and INFO, pick ACTION. Never drop a date, an amount or a name." },
            { role: "Task extractor (only on ACTION)", model: "Standard tier — a small fraction of messages reach it, so the better model is affordable here", prompt: "Turn one ACTION message into {task, child, due_date, requires (signature|payment|item|attendance), amount, quoted_text}. Quote the original line for anything factual. If the deadline is vague ('by next week'), keep it vague and say so — never invent a specific date." },
          ],
          mcp: ["School portal / fee portal, if the school runs one — via a Tool (MCP) node (no first-party connector), and only ever written to after an Approval", "WhatsApp / Telegram — where the group actually lives"],
          tools: [
            "user-defined tool `school_task_priority` (SpEL): (input.requiresPayment or input.requiresSignature) and input.daysToDue <= 2 ? 'TONIGHT' : (input.daysToDue <= 1 ? 'TONIGHT' : (input.daysToDue <= 5 ? 'THIS_WEEK' : 'LATER')) — money and signatures get pulled forward because those are the ones that can't be fixed the next morning; you set the numbers",
            "knowledge_search (your own extracted school tasks — 'did we already pay the annual day fee?')",
            "send_email (the weekly copy to the second parent, so it isn't one person's job to remember)",
          ],
          guardrails: [
            "The workflow never posts to the school group or replies to a teacher on its own. Replies are drafted; sending passes the Approval node",
            "Fees are never paid automatically — a Razorpay UPI request is prepared and waits for your tap",
            "Dates and amounts are quoted from the original message, never paraphrased or inferred; a vague deadline stays vague and is flagged as vague",
            "Group content and your children's names stay in your own tenant's Knowledge base. Nothing is forwarded outside the family without an Approval step",
            "Bias the classifier toward ACTION on low confidence — the cost of a false alarm is a glance; the cost of a miss is a child left out",
          ],
          cost:
            "This is the clearest cost-routing case in the pack: hundreds of messages a day hit the classifier, and perhaps five a week reach the extractor. Put the classifier on the economy tier with a hard per-message cap and a fallback model; put the extractor on the standard tier. Getting that split right is worth more than any prompt tuning. Digests are one message a day on a channel you already pay nothing extra for. Sarvam handles the group's mixed-language messages so a Hinglish note about a consent form doesn't slip through as noise.",
          output:
            "Every message is classified cheaply → NOISE is dropped, INFO waits for the digest, ACTION goes to the extractor → the `school_task_priority` SpEL tool ranks the tasks by YOUR thresholds → an evening WhatsApp digest and a morning-of nudge go to you (never to the group) → fees and form submissions wait at the Approval node, with a weekly Email copy to the second parent.",
        },
      },
      {
        id: "life-document-drawer",
        title: "Ask the drawer: 'where is the rent agreement?' answered in ten seconds",
        scenario:
          "The rent agreement is a photo somewhere in April's gallery. The car's RC is in a plastic folder in the almirah, unless it's in the car. Someone asks for a copy of a document at the worst possible moment — a bank, a school, a landlord — and you spend twenty minutes scrolling through your own photographs, then send it on WhatsApp and forget you ever did.",
        triggerLabel: "WhatsApp (send it a photo, or ask it a question)",
        steps: [
          { kind: "trigger", title: "Photograph it once", detail: "A WhatsApp trigger takes a photo of any document — agreement, RC, warranty card, receipt, certificate. Sending it is the whole filing effort." },
          { kind: "agent", title: "Name it and date it", detail: "An Agent node reads the document, gives it a sensible name, pulls out the identifiers and dates it carries, and writes a one-line description of what it actually is." },
          { kind: "knowledge", title: "Into your own drawer", detail: "A Knowledge node stores it in your tenant's household knowledge base — searchable by what it is, not by which month you happened to photograph it." },
          { kind: "condition", title: "Sensitive?", detail: "A Condition node marks documents carrying identity or financial numbers so that anything involving them later takes the stricter path." },
          { kind: "agent", title: "Answer questions about the drawer", detail: "When you ask 'when does the rent agreement end?' or 'what's the chassis number?', an Agent node answers from the filed documents with knowledge_search, and cites which document it read. If it isn't in the drawer, it says it isn't in the drawer." },
          { kind: "approval", title: "Sharing needs a yes", detail: "An Approval node gates every outbound copy. The agent will prepare the message with the right document attached and the right recipient — you approve the send, and the approval is recorded so you know later who got what." },
          { kind: "output", title: "Send, and remember sending", detail: "An Output node delivers on WhatsApp or Email, and logs the share against the document so 'did we already send this to the bank?' has an answer." },
        ],
        connectors: ["WhatsApp", "Email", "Knowledge base", "Approval / HITL", "Telegram", "Sarvam (Indian languages)"],
        outcome: "Every household document is one question away, and every copy that leaves the house is one you approved and can look up later.",
        impact: "Search is the small half of this. The bigger mechanism is the share log: the reason document chaos is stressful isn't finding things, it's not knowing where copies of your identity documents have already been sent. Routing every outbound copy through an Approval node makes that list exist as a side effect of using it.",
        build: {
          agents: 2,
          agentsNote: "1 filing agent (runs when you send a photo) + 1 retrieval agent (runs when you ask a question). Sharing is an Approval node, not an agent decision.",
          pattern: "File-on-ingest, answer-on-ask — two small agents over one Knowledge base",
          prompt:
            "(Filing) You file household documents. From a photograph, return {suggested_name, doc_type, parties, identifiers[], issue_date, expiry_date, one_line_description, sensitivity (NORMAL|IDENTITY|FINANCIAL)}. Read only what is visible — if the photo is cut off or blurred, say which field you could not read rather than filling it in. Mark sensitivity IDENTITY or FINANCIAL whenever the document carries a government identifier, an account number or a card number. You never send this document anywhere; you only file it.",
          agentRoles: [
            { role: "Filing agent (per document)", model: "Economy tier — short, structured extraction, and it runs every time a family member photographs something", prompt: "Extract the filing fields as strict JSON, marking sensitivity. Unreadable fields are named as unreadable, never filled in. No sending, no sharing." },
            { role: "Retrieval agent (per question)", model: "Standard tier — it must be careful about saying 'I don't have that', which is the answer that actually protects you", prompt: "Answer household questions using knowledge_search over the filed documents ONLY. Always cite which document you read. If the answer is not in a filed document, say plainly that it isn't filed — never answer from general knowledge about how such documents usually work." },
          ],
          mcp: ["A government or bank document store, if you use one — via a Tool (MCP) node (no first-party connector); read-only, and any pull is initiated by you", "WhatsApp / Telegram — how documents come in and questions get asked"],
          tools: [
            "user-defined tool `share_risk` (SpEL): input.sensitivity == 'IDENTITY' or input.sensitivity == 'FINANCIAL' ? 'STRICT_APPROVAL' : 'NORMAL_APPROVAL' — your own rule for which documents need the slower, more explicit confirmation before they leave the house",
            "knowledge_search (the household drawer — the one tool this recipe lives on)",
            "send_email (approved copies, with the share logged against the document)",
          ],
          guardrails: [
            "No document leaves your tenant without passing the Approval node, and documents marked IDENTITY or FINANCIAL take the stricter approval path via the `share_risk` rule",
            "Every approved share is logged against the document — recipient, channel, date — so you can always answer 'who has a copy of this?'",
            "The retrieval agent answers only from filed documents and cites them; when something isn't filed it says so instead of reasoning from what such a document usually contains",
            "Unreadable fields in a photograph are reported as unreadable, never inferred — a wrong identifier confidently filed is worse than a gap",
          ],
          cost:
            "Both agents are single-call and event-driven, so there is no batch multiplier — cost tracks how much your household actually files and asks. Keep the filing agent on the economy tier (it is the higher-volume of the two and the task is structured extraction) and the retrieval agent on the standard tier, because the valuable behaviour there is a careful 'that isn't filed', which weaker models are worse at. Configure a fallback model on retrieval so a question during a bank visit still gets answered.",
          output:
            "A photographed document is named, dated, classified for sensitivity and filed into your household Knowledge base; questions are answered from those filed documents with a citation; any outbound copy is drafted with the right attachment and recipient and waits at the Approval node — stricter for identity and financial documents — with every approved share logged back against the document.",
        },
      },
    ],
  },

  // ============================================ PERSONAL HEALTH & MEDICAL RECORDS
  {
    id: "personal-health",
    name: "Personal health & medical records",
    icon: Stethoscope,
    tagline:
      "Prescriptions in a plastic folder, lab reports lost in the photo gallery, a parent phoning to ask which tablet is the white one — organised, reminded and translated, with an absolute rule: it never diagnoses, never touches a dose, and always sends you to the doctor.",
    recipes: [
      {
        id: "health-record-timeline",
        title: "Every report and prescription in one timeline, ready for the next visit",
        scenario:
          "The prescriptions are in a plastic folder in date-ish order. The lab reports are photographs somewhere between a birthday and a holiday. At the next appointment the doctor asks 'what was your HbA1c in March?' and you scroll through eight months of gallery while everyone waits, or you say 'I'll bring it next time' and the visit achieves less than it should have.",
        triggerLabel: "WhatsApp (photograph a report or prescription) / Email (lab sends a PDF)",
        steps: [
          { kind: "trigger", title: "Send it the moment you get it", detail: "A WhatsApp trigger for photographs and an Email trigger for the PDFs labs mail out. Filing happens at the counter, not later — which is the only time it ever actually happens." },
          { kind: "agent", title: "Transcribe, don't interpret", detail: "An Agent node extracts what is printed: test name, value, unit, the lab's own reference range, date, doctor, and for a prescription the drug names and the schedule AS WRITTEN. It transcribes; it does not say whether a number is good or bad." },
          { kind: "knowledge", title: "Your own health file", detail: "A Knowledge node keeps it in your tenant, per family member — this is your records, not a shared health database." },
          { kind: "tool", title: "How old is this reading?", detail: "A user-defined SpEL tool labels each entry by age so 'the most recent one' is a fact rather than a memory. It computes a bucket from the input JSON — a formula, nothing else." },
          { kind: "loop", title: "Build the timeline", detail: "A Loop node walks the filed entries for a chosen test and lays out the values in date order — the same numbers the reports carry, side by side, so a doctor can see the sequence at a glance." },
          { kind: "agent", title: "Prepare, never conclude", detail: "A second Agent node writes a one-page visit sheet: current medicines as prescribed, the timeline of the values, and questions worth asking. It never adds an assessment, a trend judgement, or a suggestion." },
          { kind: "approval", title: "Sharing is your decision", detail: "An Approval node gates sending anything to a doctor, a relative, or an insurer — health records don't leave your tenant on the agent's initiative." },
          { kind: "output", title: "One page you can hand over", detail: "An Output node produces the sheet on WhatsApp or Email, in the language you want, ready to hand to the doctor or read on the way in." },
        ],
        connectors: ["WhatsApp", "Email", "Knowledge base", "Approval / HITL", "Sarvam (Indian languages)", "Tool (MCP → a lab or hospital portal, if yours has one)"],
        outcome: "One dated, per-person health timeline built from the reports you already receive, and a one-page visit sheet that turns a scattered folder into a doctor's ten useful minutes.",
        impact: "The mechanism is transcription plus ordering — nothing more, deliberately. The reason a visit underdelivers is that the doctor's questions are about sequence ('has it moved since March?') and your evidence is unordered. Putting the same printed numbers in date order answers that without anyone, human or model, interpreting them.",
        build: {
          agents: 2,
          agentsNote: "1 transcription agent per report + 1 visit-sheet composer per appointment. No agent ever evaluates a value; the timeline is assembled by a Loop node over filed records.",
          pattern: "Transcribe-on-arrival → assemble-on-demand, with interpretation excluded by design",
          prompt:
            "(Transcription) You transcribe medical documents for a patient's personal records. Return strict JSON {doc_type (LAB_REPORT|PRESCRIPTION|DISCHARGE|SCAN_REPORT), patient, date, doctor, facility, tests[{name, value, unit, reference_range_as_printed}], medicines[{name_as_written, strength, schedule_as_written, duration}], notes_as_written}. Transcribe EXACTLY what is printed, including the lab's own reference range verbatim. You must NOT say whether a value is normal, high, low, safe, concerning or improved. You must NOT name a condition, suggest a diagnosis, or comment on any medicine or dose. If a field is unreadable, return null and set needs_check=true naming the field — never guess a value, a strength or a schedule. If the document seems to describe an emergency, do not assess it: flag escalate=true so the workflow tells the person to contact their doctor or emergency services.",
          agentRoles: [
            { role: "Transcriber (per document)", model: "Economy tier — pure structured extraction, and it runs on every report and prescription the family receives", prompt: "Transcribe exactly what is printed into strict JSON, reference ranges verbatim. No interpretation of any kind. Unreadable fields are null + needs_check, never guessed." },
            { role: "Visit-sheet composer (per appointment)", model: "Standard tier — runs once before a visit; this is where care with wording matters most", prompt: "Assemble a one-page visit sheet: current medicines exactly as prescribed, the requested test values in date order with the printed reference ranges, and questions the patient may want to ask the doctor. Add NO assessment, NO trend judgement, NO suggestion. Questions must be neutral and open ('what does this trend mean for me?'), never leading ('should my dose be increased?'). End with a line stating that this sheet is a record for the doctor and is not medical advice." },
          ],
          mcp: ["Your lab's or hospital's portal, if it has one — via a Tool (MCP) node (no first-party connector); read-only and initiated by you", "WhatsApp / Email — how reports arrive and how the sheet comes back"],
          tools: [
            "user-defined tool `report_recency` (SpEL): input.daysSinceTest <= 30 ? 'RECENT' : (input.daysSinceTest <= 180 ? 'THIS_HALF' : 'OLDER') — your own definition of what counts as a current reading, so the visit sheet leads with the right one; it is a formula over the input JSON with no method calls and no loops",
            "knowledge_search (your own filed reports and prescriptions, per family member)",
            "send_email (the visit sheet to yourself, so it exists outside chat when the phone dies in the waiting room)",
          ],
          guardrails: [
            "MEDICAL SAFETY, absolute: the agent NEVER diagnoses, NEVER names a condition, NEVER calls a value normal/abnormal/safe/concerning, and NEVER advises on, changes or comments on any medicine or dose. It transcribes, orders, translates and prepares questions — nothing else",
            "Reference ranges are reproduced verbatim from the lab's own report and are never compared against by the agent — the comparison is the doctor's, not the model's",
            "Anything suggesting an emergency sets escalate=true and the workflow responds with 'contact your doctor or emergency services now', with no assessment of severity attached",
            "Unreadable values, strengths and schedules are returned null with needs_check — a guessed dose is the single most dangerous output this workflow could produce, so it is structurally excluded",
            "PRIVACY: health records stay in your own tenant, filed per family member. Nothing goes to a doctor, relative, employer or insurer without passing the Approval node",
            "The visit sheet carries an explicit line stating it is a personal record for the doctor, not medical advice",
          ],
          cost:
            "The transcriber runs on every incoming report, so it belongs on the economy tier with a hard token cap — it is doing structured extraction, not reasoning, and a cheaper model is genuinely the right tool. The composer runs once before an appointment: standard tier, and worth it, because careful non-interpretive wording is exactly what smaller models drift away from. Configure a fallback model on the transcriber so filing never silently stops; the timeline itself is assembled by a Loop node over stored records, which costs nothing per read.",
          output:
            "Each report or prescription is transcribed verbatim into your per-person Knowledge base; the `report_recency` SpEL tool marks how current each reading is; a Loop node lays the requested values out in date order with their printed reference ranges; the composer produces a one-page visit sheet — medicines as prescribed, the timeline, and neutral questions for the doctor — delivered on WhatsApp or Email in your language, with any sharing beyond you gated by the Approval node.",
        },
      },
      {
        id: "health-parent-medicines",
        title: "A voice reminder for an elderly parent, in their own language",
        scenario:
          "Your mother is on six tablets. Two in the morning, one after lunch, three at night, one of them only on alternate days. She phones you at work to ask whether the white one is the morning one, and you're in a meeting trying to picture a strip of tablets from four hundred kilometres away. Some days nobody calls and nobody knows whether the evening dose happened.",
        triggerLabel: "Webhook (scheduled dose times) / Voice (she calls and asks)",
        steps: [
          { kind: "trigger", title: "The schedule the doctor wrote", detail: "A scheduled Webhook fires at each dose time from the schedule transcribed off the actual prescription — not a schedule anyone typed from memory." },
          { kind: "knowledge", title: "The prescription itself", detail: "A Knowledge node holds the transcribed prescription: medicine names, strengths and timings exactly as written by the doctor, plus the description you added ('the small white one, in the blue strip')." },
          { kind: "voice", title: "A call in her language", detail: "A Voice node calls and speaks in her language — Sarvam for Indian languages, ElevenLabs for a natural voice. It reads out what the doctor prescribed for this time slot, slowly, and repeats it if she asks." },
          { kind: "agent", title: "Read back, never advise", detail: "An Agent node handles her questions by reading back the prescription verbatim. 'Which is the morning tablet?' gets the doctor's own words plus your description. 'Should I take an extra one?' gets a kind refusal and an offer to reach you or the doctor — always, without exception." },
          { kind: "condition", title: "Was it taken?", detail: "A Condition node handles the answer: confirmed goes to the log; no answer or an unclear one, after a retry, goes to the family." },
          { kind: "tool", title: "How many days of stock left?", detail: "A user-defined SpEL tool divides tablets remaining by tablets per day so the refill reminder arrives before the strip runs out. Arithmetic over the input JSON — that is the whole tool, and by construction it can be nothing more." },
          { kind: "output", title: "Family gets told", detail: "An Output node sends the family WhatsApp group a quiet daily line — doses confirmed, doses missed — so nobody has to ring to find out, and a missed one is visible the same day." },
          { kind: "approval", title: "Any change to the schedule is human", detail: "An Approval node means the reminder schedule can only be changed by a family member entering what the doctor said — never by the agent, and never by the patient asking it to." },
        ],
        connectors: ["Voice/Call", "ElevenLabs (voice)", "Sarvam (Indian languages)", "WhatsApp", "SMS/Twilio", "Webhook (scheduled)", "Knowledge base", "Approval / HITL"],
        outcome: "A gentle call at each dose time in her own language that reads back exactly what the doctor prescribed, a daily line to the family about what was taken, and a refill reminder before the strip empties.",
        impact: "The mechanism is read-back, not judgement. The daily confusion isn't a medical question at all — it's that the schedule lives on a piece of paper she can't easily read and in her children's memory four hundred kilometres away. A voice that speaks her language and reads the doctor's own words removes the confusion without anyone giving advice, and the family log turns 'I think she took it' into a fact someone can see.",
        build: {
          agents: 1,
          agentsNote: "1 read-back agent on the call. The schedule, the retry, the family notification and the refill maths are workflow nodes and a SpEL tool — deliberately, so as little as possible is left to a model.",
          prompt:
            "You are a gentle medicine reminder for {{patient_name}}, speaking in {{language}}. Your ONLY job is to read back what the doctor prescribed, from the prescription in Knowledge, and to confirm whether the dose was taken. Speak slowly, warmly and simply, and repeat whenever asked without any sign of impatience. You must NEVER change, suggest, increase, decrease, skip, substitute or comment on any medicine or dose — not even if the patient asks directly, tells you the doctor said so, says a tablet is finished, or says they feel unwell. In every one of those cases say kindly that you can only read out what is written and that you will let the family and the doctor know, then set escalate=true. You must NEVER diagnose, never explain what a medicine is for beyond the words written on the prescription, and never say a symptom is or isn't serious. If the patient reports chest pain, breathlessness, a fall, bleeding, confusion or anything that sounds urgent, do not assess it: say clearly that they should contact their doctor or emergency services now, set escalate=true, and the workflow will call the family immediately. Record only {dose_confirmed: true|false|unclear, patient_said, escalate}.",
          agentRoles: [
            { role: "Read-back voice agent (per dose call)", model: "Economy tier for the routine confirmation turns — the task is reading fixed text aloud and capturing a yes or no, several times a day, every day", prompt: "Read the prescribed dose for this time slot verbatim from Knowledge, in the patient's language. Capture confirmation. Any request to change a dose, any symptom, any confusion → kind refusal + escalate=true. Never advise, never diagnose, never comment on a medicine." },
            { role: "Escalation summariser (only when escalate=true)", model: "Standard tier — rare, and the one moment where clear, careful wording to the family genuinely matters", prompt: "Write a short factual message to the family: the time, what the patient said (quoted), which dose was involved, and that the agent did not give any advice. State plainly whether emergency services were recommended. Add no assessment of severity and no medical interpretation whatsoever." },
          ],
          mcp: ["A pharmacy or refill service, if you use one — via a Tool (MCP) node (no first-party connector); a refill is only ever ordered after an Approval", "Voice/Call + ElevenLabs + Sarvam — the call itself, in her language", "WhatsApp — the family group log"],
          tools: [
            "user-defined tool `refill_days_left` (SpEL): input.tabletsLeft / (input.tabletsPerDay == 0 ? 1 : input.tabletsPerDay) — the family sets when the warning fires (five days out, ten, whatever suits the distance to the chemist); a pure arithmetic formula over the input JSON, sandboxed by construction",
            "user-defined tool `missed_dose_alert` (SpEL): input.unansweredCalls >= input.alertAfter ? 'CALL_FAMILY' : 'RETRY' — your own patience threshold before the family's phone rings, not a default someone else chose",
            "knowledge_search (the transcribed prescription and your own descriptions of the tablets)",
          ],
          guardrails: [
            "MEDICAL SAFETY, absolute: the agent NEVER changes, suggests, skips, substitutes, increases, decreases or comments on any medicine or dose — including when the patient asks directly or says the doctor approved it. It reads back the prescription verbatim and escalates. It NEVER diagnoses and NEVER says a symptom is or isn't serious",
            "Any symptom report, any dose question, any confusion → kind refusal + escalate=true → the family is contacted the same run. Urgent-sounding symptoms get 'contact your doctor or emergency services now' with no severity assessment attached",
            "The reminder schedule is read from the transcribed prescription and can only be changed through the Approval node by a family member entering what the doctor said — the agent cannot edit its own schedule and neither can the patient over the phone",
            "The agent never asserts a dose was taken. Unclear or unanswered is logged as unclear or unanswered and surfaced to the family — a false 'taken' is worse than a missed call",
            "PRIVACY: the prescription, the call log and the daily confirmations stay in your family's own tenant. Nothing is shared with a doctor, a pharmacy or anyone else without an Approval step",
            "A refill is never ordered automatically — the stock warning is a reminder, and any purchase passes the Approval node",
          ],
          cost:
            "The routine calls dominate: several a day, every day, forever. Put the read-back agent on the economy tier with a strict cap — it is reading fixed text and capturing a yes, which is exactly the shape cheap models handle well — and configure a fallback model, because a reminder that fails silently is the one failure this recipe cannot afford. Reserve the standard tier for the rare escalation summary. Voice minutes and the ElevenLabs/Sarvam synthesis are the real recurring line item, not tokens: keep the routine call short and scripted, and let the longer conversation happen only on the escalation path.",
          output:
            "At each prescribed time a Voice call in her own language reads back exactly what the doctor wrote, with your description of the tablet; the answer is logged as taken / missed / unclear and posted as one quiet daily line to the family's WhatsApp group; the `refill_days_left` SpEL tool warns before the strip empties, with any purchase gated by the Approval node; and any dose question, symptom or confusion ends the same way every time — a kind refusal, the doctor, and a message to the family.",
        },
      },
      {
        id: "health-visit-prep",
        title: "Ten minutes with the doctor, spent on the right things",
        scenario:
          "You get maybe ten minutes. You go in with a mental list, come out having forgotten the two things you most wanted to ask, and remember the third one in the car. Somebody else in the family asked you to mention something and you forgot that too. Half the visit goes on reconstructing what happened since last time, from memory.",
        triggerLabel: "WhatsApp (appointment coming up) / Webhook (scheduled, two days before)",
        steps: [
          { kind: "trigger", title: "Two days before", detail: "A scheduled Webhook (or a WhatsApp message from you) starts the prep with enough time to actually find anything that's missing." },
          { kind: "parallel", title: "Gather in parallel", detail: "A Parallel node pulls three things at once: what's happened since the last visit (from your notes), the current medicines as prescribed, and any reports filed since then." },
          { kind: "agent", title: "Assemble the facts", detail: "An Agent node lays out what your own records say — symptoms you logged with their dates, medicines exactly as prescribed, new reports with their printed values. Records only, no reading between the lines." },
          { kind: "agent", title: "Draft the questions", detail: "A second Agent node turns that into neutral, open questions to ask: 'this has been happening since the 14th — what should I make of it?' Never leading, never a suggested treatment, never a proposed dose change." },
          { kind: "condition", title: "Anything missing?", detail: "A Condition node checks whether a report the doctor asked for is absent from your records, so you find out two days early rather than in the chair." },
          { kind: "knowledge", title: "Grounded in your own file", detail: "A Knowledge node keeps every line traceable to a document or a note you actually made — the sheet cites its sources so the doctor can see where a number came from." },
          { kind: "approval", title: "You review before it's shared", detail: "An Approval node holds the sheet if it's going anywhere but you — a doctor's WhatsApp, a family member, a second opinion." },
          { kind: "voice", title: "Or hear it on the way", detail: "A Voice node can read the sheet aloud in your language on the drive over, which is when most people actually review it." },
        ],
        connectors: ["Webhook (scheduled)", "WhatsApp", "Email", "Voice/Call", "Knowledge base", "Approval / HITL", "Sarvam (Indian languages)", "ElevenLabs (voice)"],
        outcome: "A one-page, source-cited prep sheet two days before the appointment: what's changed, what you're taking, what's newly filed, what's missing, and the questions worth asking.",
        impact: "The mechanism is recall, not insight. Ten minutes get spent reconstructing history because the history is scattered across chat messages, a folder and memory. Assembling it from records you already have — with citations, and with the missing-report check landing two days early — moves the conversation to the part only a doctor can do.",
        build: {
          agents: 2,
          agentsNote: "1 facts assembler + 1 question drafter. Split on purpose: the assembler is forbidden to interpret, and the drafter is forbidden to add facts, so neither can quietly become an advisor.",
          pattern: "Parallel gather → assemble facts → draft neutral questions, with interpretation excluded at both steps",
          prompt:
            "(Assembler) Assemble a pre-appointment record sheet for {{patient}} from their own filed records ONLY. Output {since_last_visit[{date, note_as_written}], current_medicines[{name, strength, schedule_as_prescribed}], new_reports[{date, test, value, unit, reference_range_as_printed, source_doc}], missing[]}. Every line must cite the document or note it came from. You must NOT interpret, evaluate, trend, rank by seriousness or comment on ANY value, symptom or medicine. You must NOT name a possible condition. If a record the doctor previously asked for is absent, list it under missing[] — do not speculate about why. If any logged note describes something urgent, do not assess it: set escalate=true so the workflow advises contacting the doctor now.",
          agentRoles: [
            { role: "Facts assembler", model: "Standard tier — precision and citation discipline matter more here than fluency, and it runs a handful of times a year", prompt: "Assemble the facts from filed records only, each line cited to its source document or note. Zero interpretation, zero evaluation, zero condition names. Absent requested reports go in missing[]." },
            { role: "Question drafter", model: "Standard tier — the whole risk sits in the wording of the questions", prompt: "From the assembled facts, draft neutral open questions for the doctor. Questions may point at a fact and a date; they must never propose a diagnosis, a treatment, a test or a dose change, and must never be leading. Add no facts that are not in the assembled sheet. Close with a line stating this sheet is the patient's own record and is not medical advice." },
          ],
          mcp: ["A hospital or clinic portal, if yours has one — via a Tool (MCP) node (no first-party connector); read-only, and any upload passes an Approval", "WhatsApp / Email / Voice — how the sheet reaches you"],
          tools: [
            "user-defined tool `prep_urgency` (SpEL): input.daysToAppointment <= 2 ? 'PREP_NOW' : (input.daysToAppointment <= 7 ? 'PREP_SOON' : 'LATER') — you choose how much runway you want; a formula over the input JSON, no method calls, no loops, nothing else it can reach",
            "knowledge_search (your own filed reports, prescriptions and symptom notes)",
            "send_email (the sheet to yourself, so it survives a dead phone battery in the waiting room)",
          ],
          guardrails: [
            "MEDICAL SAFETY, absolute: neither agent diagnoses, names a condition, evaluates any value or symptom, or suggests, changes or comments on any medicine or dose. The sheet is a record and a list of questions — nothing else",
            "Questions are neutral and open by construction; leading questions and any suggested treatment, test or dose change are prohibited in the drafter's prompt and are the specific thing to check when reviewing output",
            "Every line on the sheet cites the document or note it came from — nothing appears that isn't in your own records, and general medical knowledge is never used to fill a gap",
            "Anything urgent in the logged notes sets escalate=true and the response is 'contact your doctor now', with no assessment of how serious it is",
            "PRIVACY: the sheet is built inside your own tenant. Sending it to a doctor, a second opinion or a family member passes the Approval node every time",
            "The sheet carries an explicit line: this is a personal record to support a conversation with a doctor, not medical advice",
          ],
          cost:
            "This runs a handful of times a year per person, so both agents can sit on the standard tier without the bill noticing — this is the one recipe in the pack where economising is the wrong call, because every risk here lives in the wording. The Parallel node is for wall-clock, not cost: three gathers at once means the sheet is ready while you're still thinking about the appointment. Add a fallback model so a prep run two days out never silently fails.",
          output:
            "A Parallel gather pulls notes, current medicines and newly filed reports at once → the assembler lays them out with a citation on every line and a missing[] list → the drafter adds neutral open questions → the one-page sheet arrives on WhatsApp and Email (and can be read aloud in your language by a Voice node on the way there), with any sharing beyond you gated by the Approval node.",
        },
      },
      {
        id: "health-claim-pack",
        title: "The insurance claim pack, assembled before the deadline finds you",
        scenario:
          "After the hospital stay you have a discharge summary, a stack of bills, some pharmacy receipts stapled to nothing, and a claim form asking for documents by numbers you've never heard of. You post it, and three weeks later it comes back for one missing paper. Meanwhile the deadline is a date in a letter you've already filed somewhere.",
        triggerLabel: "WhatsApp / Email (bills, discharge summary and receipts as they arrive)",
        steps: [
          { kind: "trigger", title: "Photograph as you go", detail: "A WhatsApp trigger takes each bill and receipt as it happens — at the counter, not in a shoebox three weeks later. Emailed hospital PDFs come in the same way." },
          { kind: "loop", title: "One pass per document", detail: "A Loop node runs a cheap agent over each document to classify it — hospital bill, pharmacy receipt, discharge summary, diagnostic bill — and pull the amount, the date and the invoice number as printed." },
          { kind: "knowledge", title: "The insurer's own checklist", detail: "A Knowledge node holds YOUR policy document and the insurer's own document checklist, as you received it. The completeness check is against that document, not against how claims generally work." },
          { kind: "condition", title: "What's still missing?", detail: "A Condition node compares what you've filed against that checklist and produces the gap list — the piece that normally surfaces three weeks later in a rejection letter." },
          { kind: "tool", title: "Ready or not, by your rule", detail: "A user-defined SpEL tool turns the missing count and days-to-deadline into a single status. Your thresholds, computed as a formula over the input JSON, nothing more." },
          { kind: "agent", title: "Assemble the pack", detail: "An Agent node produces the claim pack: an indexed list of every document with its date, amount and invoice number, the totals, and the gap list. It reports what the documents say. It never states what is covered — that is the insurer's decision on the policy's terms." },
          { kind: "approval", title: "Nothing is submitted without you", detail: "An Approval node gates every submission and every upload. Medical bills and a discharge summary are among the most sensitive documents you own; they leave your tenant only when you say so." },
          { kind: "output", title: "Pack out, chased on a schedule", detail: "An Output node delivers the pack, and a scheduled reminder nudges you about the missing items and the deadline until the gap list is empty." },
        ],
        connectors: ["WhatsApp", "Email", "Webhook (insurer acknowledgements)", "Knowledge base", "Approval / HITL", "Tool (MCP → insurer or TPA portal, if yours has one)", "Sarvam (Indian languages)"],
        outcome: "An indexed, totalled claim pack with a live list of exactly what's still missing, chased before the deadline — and not one page sent anywhere without your approval.",
        impact: "The mechanism is checking completeness early against the insurer's own written checklist. Claims come back for a missing paper because nobody compares the stack to the list until submission, by which point the documents are dispersed and the deadline is close. Doing that comparison the day each bill arrives moves the discovery weeks earlier, when the hospital counter is still an easy phone call away.",
        build: {
          agents: 2,
          agentsNote: "1 cheap document classifier (runs once per bill or receipt via the Loop node) + 1 pack assembler that runs when you ask. The completeness check is a Condition node against your policy's own checklist, not a model's opinion.",
          pattern: "Map-reduce: Loop over documents (map) → assemble one pack with a gap list (reduce) → human approves the submission",
          prompt:
            "(Classifier) Classify ONE claim document and return strict JSON {doc_type (HOSPITAL_BILL|PHARMACY_RECEIPT|DIAGNOSTIC_BILL|DISCHARGE_SUMMARY|PRESCRIPTION|ID_PROOF|OTHER), date, provider, invoice_no, amount, currency, patient, legible (true|false)}. Read only what is printed — a blurred or cropped amount is null with legible=false, never a best guess, because a wrong amount on a claim is a rejection. Do NOT interpret any clinical content: you are classifying paperwork, not reading a medical record. Never state or imply whether an item is covered by the policy.",
          agentRoles: [
            { role: "Document classifier (per document, in a Loop)", model: "Economy tier — it runs across every bill and receipt in the stack, so it is the only meaningful cost lever here", prompt: "Classify one document into strict JSON with date, provider, invoice number and amount as printed. Illegible fields are null with legible=false. No clinical interpretation, no coverage opinions." },
            { role: "Pack assembler (once per submission)", model: "Standard tier — one call, and it has to be careful about the line between 'what the documents say' and 'what will be paid'", prompt: "Assemble an indexed claim pack: every document with type, date, provider, invoice number and amount; category subtotals and a grand total; and a missing[] list built strictly from the insurer's checklist in Knowledge. State clearly that coverage is the insurer's decision under the policy terms — never assess, estimate or imply what will or won't be paid, and never characterise the treatment clinically." },
          ],
          mcp: ["Your insurer's or TPA's portal — via a Tool (MCP) node (no first-party connector); every upload passes an Approval first", "Insurer acknowledgements — inbound via Webhook", "WhatsApp / Email — how documents arrive and the pack comes back"],
          tools: [
            "user-defined tool `claim_readiness` (SpEL): input.missingCount == 0 ? 'READY' : (input.daysToDeadline <= 7 ? 'URGENT_GAPS' : 'GAPS') — one status from your own thresholds, so the nudges get sharper as the deadline approaches; a formula over the input JSON, sandboxed by construction with no method calls and no loop",
            "user-defined tool `claim_total` (SpEL): input.hospital + input.pharmacy + input.diagnostics + input.other — the household's own totalling rule, computed identically every time rather than re-added by hand at midnight",
            "knowledge_search (your policy document and the insurer's own document checklist)",
            "http_request (only behind the Approval node, to submit to a portal you've connected)",
          ],
          guardrails: [
            "MEDICAL SAFETY, absolute: this workflow handles paperwork only. It never diagnoses, never interprets clinical content in a discharge summary or report, never comments on any medicine or dose, and never characterises the treatment or the patient's condition",
            "It never states, estimates or implies what the insurer will pay. Coverage is the insurer's decision under the policy terms, and the pack says so explicitly",
            "Amounts and invoice numbers are transcribed as printed; anything illegible is null with legible=false and lands on the gap list — a guessed amount is a rejected claim",
            "The missing[] list is built only from the insurer's own checklist as filed in Knowledge, never from general assumptions about what claims usually need",
            "PRIVACY: bills, the discharge summary and the pack stay in your own tenant. Not one page is uploaded to an insurer, a TPA, an agent or a relative without passing the Approval node",
            "Submission is never automatic even when readiness is READY — the status is a prompt for you, not a trigger",
          ],
          cost:
            "Cost is linear in the size of the stack: N documents × one economy-tier classification + ONE standard-tier assembly per submission. Because the classifier runs over every bill and receipt, it must sit on the economy tier with a hard token cap — that single choice dominates. Run the map in Parallel batches when a hospital hands you thirty pages at once: same per-document cost, done while you're still in the corridor. The deadline nudges are scheduled messages, not model calls.",
          output:
            "The Loop maps the cheap classifier over each bill and receipt as it arrives → the Condition node compares the filed set against the insurer's own checklist and produces the gap list → the `claim_readiness` and `claim_total` SpEL tools give you one status and one total on your own rules → the assembler produces an indexed, totalled pack with the gap list and an explicit note that coverage is the insurer's decision → scheduled reminders chase the missing items until the list is empty, and nothing is uploaded anywhere until you approve it.",
        },
      },
    ],
  },
];
