import type { Industry } from "./recipes-data";
import { Megaphone, TrendingUp } from "lucide-react";

// ---------------------------------------------------------------------------
// Marketing & sales recipes for the 2–20 person team — the one with no
// marketing ops person and no RevOps. Same "inline copy, no i18n keys"
// precedent and the same honesty rules as recipes-data.tsx.
//
// These two packs are deliberately NOT the big-company ones. "Advertising &
// marketing agencies" and "Professional services & sales ops" already exist
// and assume a team with specialists. Here the marketing team is the founder
// at 11pm, and the CRM is whatever someone set up in week two.
//
// Honesty rules, verbatim posture from recipes-data.tsx:
//  • Real first-party channels ONLY — Webhook, WhatsApp, SMS/Twilio, Voice,
//    Email, Slack, Discord, Telegram, Instagram, Messenger, X, ElevenLabs,
//    Sarvam, Razorpay (UPI). The CRM, the ad platforms, the analytics tool and
//    the email-marketing tool have NO first-party connector — they are reached
//    the real way, via an inbound Webhook and/or a Tool (MCP) node, and are
//    LABELLED as such everywhere they appear. A website lead form arriving on
//    a Webhook IS first-party, and is used as such.
//  • No invented ROI, conversion rates or "engagement lift". Every `impact`
//    line explains the MECHANISM — why the workflow helps — and stops there.
//  • Anti-spam is not decoration here. Consent and opt-out are honoured, the
//    messaging platforms' own policy is respected (WhatsApp really does
//    require opt-in and approved templates outside the customer-service
//    window — the recipes say so), contact lists are never scraped,
//    personalisation is never fabricated, and frequency caps are enforced.
//  • The agent DRAFTS. A human APPROVES anything published, anything sent to
//    prospects at scale, and any price or discount commitment.
//  • Claims about the product come from the team's own Knowledge base, never
//    from the model's memory or from a web search.
//
// User-defined tools appear in both packs and are described honestly: a
// tenant-authored SpEL expression over the tool's JSON input, sandboxed by
// construction — no method calls, no constructors, no bean or type references,
// and no loop construct in the grammar at all. It is a data-transform /
// formula tool, not a scripting engine. That is exactly enough to encode your
// own ICP scoring and your own stale-deal rules, and to retune them on a
// Friday without a developer and without a redeploy.
// ---------------------------------------------------------------------------

export const INDUSTRIES_GROWTH: Industry[] = [
  // ============================================ MARKETING & CONTENT (SMALL TEAM)
  {
    id: "smb-marketing",
    name: "Marketing & content for small teams",
    icon: Megaphone,
    tagline:
      "For the team where marketing is the founder at 11pm: turn one idea into channel-native drafts, answer inbound from your own Knowledge, and never send anything a human hasn't approved.",
    recipes: [
      {
        id: "mkt-content-engine",
        title: "One idea → a week of channel-native drafts (supervisor + 3 writers)",
        scenario:
          "The content calendar was made in week one and died in week three. Now it's one founder at 11pm writing a LinkedIn post, rewriting it shorter for X, forgetting Instagram entirely, and posting nothing for eleven days because the blank page is worse than the silence.",
        triggerLabel: "Webhook / Slack (an idea, a customer quote, a shipped feature)",
        steps: [
          { kind: "trigger", title: "Capture the idea where it happens", detail: "A Slack message in your #ideas channel, or a Webhook from wherever you dump notes, fires the workflow with one seed: a shipped feature, a customer sentence, a support ticket that keeps recurring. No calendar to maintain — the trigger IS the calendar." },
          { kind: "knowledge", title: "Ground it in your own positioning", detail: "A Knowledge node pulls your positioning doc, tone-of-voice notes, objection-handling and the case studies you're actually allowed to name. Every claim in every draft has to come from here — not from the model's general knowledge of your category." },
          { kind: "supervisor", title: "Supervisor plans the angle", detail: "A Supervisor agent decides the single angle worth writing this week and what each channel gets — it does not write; it briefs. If the seed is too thin to say anything true, it says so and stops rather than generating filler." },
          { kind: "parallel", title: "Three writers draft at once", detail: "A Parallel node runs the channel writers side by side — long-form (LinkedIn/blog), short-form (X/Telegram), visual-first caption (Instagram) — each with the same grounded brief and the same claim limits, each writing natively for its channel instead of truncating one post three ways." },
          { kind: "approval", title: "The founder approves, in one place", detail: "An Approval node holds every draft together — one review, one decision, edits in-line. Nothing is published, scheduled or sent by the workflow before this click." },
          { kind: "output", title: "Publish-ready, delivered back to you", detail: "Approved drafts go to Slack (or Email) as ready-to-post copy with the source claim cited next to each one. Scheduling into a social tool, if you use one, is a Tool (MCP) node — there is no first-party connector for those platforms' publishing APIs." },
        ],
        connectors: ["Slack", "Webhook", "Knowledge base", "Approval / HITL", "Email", "Tool (MCP → your scheduling tool)"],
        outcome:
          "One captured idea becomes three channel-native drafts, each grounded in your own positioning, waiting in a single approval queue instead of a blank editor at midnight.",
        impact:
          "The mechanism is removing the blank page and the context-switch, not replacing judgement: the supervisor does the framing that usually stalls you, the writers do the channel-shaping that usually gets skipped, and the founder is left doing the only part that actually needs them — deciding whether it's true and whether it ships.",
        build: {
          agents: 4,
          agentsNote:
            "1 supervisor (briefs, never writes) + 3 channel writers running in Parallel. The Knowledge grounding, the approval and the delivery are workflow nodes.",
          pattern: "Supervisor → parallel specialists → single human approval",
          prompt:
            "You are the content lead for {{company}}. You receive ONE seed (a shipped feature, a customer sentence, a recurring question). Decide the single most useful angle and write a brief for three channel writers: {angle, audience, the one claim we are making, the proof for that claim, what NOT to say}. Every claim must be supported by a passage you retrieved from Knowledge — quote the passage id in the brief. If Knowledge contains no support for the claim, return {publishable: false, reason} and stop; a thin week with no post is correct, a fabricated week is not. Never invent a customer name, a metric, a result, or a logo. You brief only — you do not write posts and you do not publish.",
          agentRoles: [
            { role: "Supervisor / content lead (runs once)", model: "Standard tier — this is the judgement step, worth the reasoning budget", prompt: "Pick the one angle. Write the brief with the claim and its Knowledge citation. Refuse to proceed if the claim isn't supported. Never publish." },
            { role: "Long-form writer (LinkedIn / blog)", model: "Standard tier — length and structure benefit from it", prompt: "Write the long-form draft from the brief only. Use the approved claim and nothing beyond it. No invented statistics, no 'studies show', no customer names not in the brief. Plain sentences; the founder's voice from the tone notes, not a marketing voice." },
            { role: "Short-form writer (X / Telegram)", model: "Economy tier — short output, tight constraints, runs on every seed", prompt: "Write the short-form draft natively for the channel — not a truncation of the long post. Same claim, same limits. No hashtag stuffing, no engagement-bait question at the end." },
            { role: "Caption writer (Instagram / visual-first)", model: "Economy tier", prompt: "Write a visual-first caption plus a one-line description of the image or clip it needs. Same claim, same limits. Never describe an image as if it already exists." },
          ],
          mcp: [
            "Your social scheduling / email-marketing tool — via a Tool (MCP) node (no first-party connector)",
            "Slack — idea capture + draft delivery (first-party)",
            "Webhook — inbound seeds from notes, forms or your own tooling (first-party)",
          ],
          tools: [
            "knowledge_search (positioning, tone of voice, objection handling, nameable case studies)",
            "web_search (context only — never a source for a claim about your own product)",
            "send_email (deliver the approved pack if you'd rather review in your inbox than Slack)",
          ],
          guardrails: [
            "Nothing is published by the workflow — every draft is held at the Approval node until the founder approves or edits it; the agents draft, a human ships",
            "Product claims come only from the team's own Knowledge base, with the supporting passage cited beside each draft; if Knowledge doesn't support it, the supervisor refuses to write it rather than filling the slot",
            "No fabricated personalisation and no invented proof — no customer names, logos, metrics, awards or 'results' that aren't in Knowledge",
            "Frequency cap on the trigger: one content run per week per channel, so a burst of ideas on Monday doesn't turn into six posts nobody asked for",
            "Publishing is a Tool (MCP) call to your own scheduling tool under your own credentials — the workflow never posts to a platform on its own initiative",
          ],
          cost:
            "The two short-form writers sit on the economy tier — they run on every seed and their output is short and heavily constrained, so that's where the volume is and where the cheap model belongs. The supervisor and the long-form writer get the standard tier because framing and structure are the parts that actually degrade on a small model. Configure a fallback model on the supervisor so a provider hiccup at 11pm downgrades the draft rather than losing the idea, and cap the run so one pathological seed can't burn a week's budget.",
          output:
            "Supervisor briefs → three writers draft in Parallel → all drafts land together in ONE Approval node with their Knowledge citations → approved copy is delivered to Slack or Email ready to post, and scheduled through your own tool via a Tool (MCP) node only if you've wired one.",
        },
      },
      {
        id: "mkt-inbound-desk",
        title: "Website form + DM replies, answered from your own Knowledge",
        scenario:
          "The website form posts into an inbox nobody owns. Instagram DMs and the WhatsApp number get the same five questions — pricing, does it integrate with X, is there a free tier — and whoever sees them first answers from memory, slightly differently each time, sometimes two days later.",
        triggerLabel: "Webhook (website form) / WhatsApp / Instagram / Messenger / Telegram",
        steps: [
          { kind: "trigger", title: "Every inbound lands in one flow", detail: "The website lead form arrives on a Webhook (first-party — this is the honest, real path for a form). WhatsApp, Instagram, Messenger and Telegram messages arrive on their own first-party channels. One workflow, one queue, regardless of where the person turned up." },
          { kind: "agent", title: "Classify before answering", detail: "An Agent node on the economy tier tags each inbound: QUESTION / PRICING / DEMO_REQUEST / SUPPORT / SPAM — and extracts what the person actually asked. Cheap, because this runs on everything including the spam." },
          { kind: "knowledge", title: "Answer from what you've written down", detail: "A Knowledge node retrieves from your own FAQ, docs, pricing page and objection-handling notes. If there is no passage that answers the question, the agent does not improvise a capability — it says a human will confirm." },
          { kind: "condition", title: "Route: answerable, or human?", detail: "A Condition node splits it. A documented question gets a drafted reply. Anything touching price beyond the published page, a discount, a commitment, a legal or security question, or anything Knowledge can't support, routes straight to a person." },
          { kind: "approval", title: "First reply to a new person is approved", detail: "An Approval node holds the drafted reply. You can relax this later per-tag once you trust the FAQ answers, but the first-touch reply to a stranger is the one worth reading before it goes." },
          { kind: "output", title: "Reply on the channel they used", detail: "The approved reply goes back on the same channel — WhatsApp inside the 24-hour customer-service window as a normal message, or an approved template outside it, because that's the platform's actual rule and not something a workflow can wish away." },
        ],
        connectors: ["Webhook", "WhatsApp", "Instagram", "Messenger", "Telegram", "Knowledge base", "Approval / HITL", "Slack"],
        outcome:
          "Every inbound gets the same accurate, sourced answer within minutes on the channel it arrived on, and the questions your Knowledge can't answer become a visible list instead of five improvised replies.",
        impact:
          "The mechanism is consistency and ownership: an unowned inbox becomes one queue with one grounded answer per question, and the gaps surface as 'Knowledge couldn't answer this' — which is the actual to-do list for your FAQ.",
        build: {
          agents: 2,
          agentsNote:
            "1 economy-tier classifier (runs on everything, including spam) + 1 standard-tier responder (runs only on the answerable branch). Routing and approval are workflow nodes.",
          pattern: "Cheap classify → route → grounded draft → human approves first touch",
          prompt:
            "You answer inbound questions for {{company}} on behalf of a small team. Answer ONLY from passages retrieved via knowledge_search, and cite the passage id beside each factual statement. If the question is not covered — a capability we haven't documented, a price beyond the published page, a discount, a timeline, a security or legal question — do NOT answer it: return {escalate: true, reason, what_they_asked} so a human replies. Never state a roadmap date, never confirm an integration we haven't documented, never offer a discount or waive a fee. Do not fabricate familiarity: you have not read their blog, seen their site or used their product, so never say you have. Match the channel — short on WhatsApp and Instagram, fuller on email. Sign off as the team, not as a person who doesn't exist.",
          agentRoles: [
            { role: "Classifier (every inbound)", model: "Economy tier — highest-volume step, strict JSON out, no prose", prompt: "Return {tag: QUESTION|PRICING|DEMO_REQUEST|SUPPORT|SPAM, question_text, channel, has_opt_in: true|false|unknown}. Judge only from the message. Never answer the question yourself." },
            { role: "Responder (answerable branch only)", model: "Standard tier, with a configured fallback model — this is the text a stranger actually reads", prompt: "Draft a reply grounded strictly in retrieved Knowledge with citations. Escalate anything uncovered, priced, promised or dated. No fabricated personalisation. No invented capabilities." },
          ],
          mcp: [
            "Your CRM — via a Tool (MCP) node to log the contact (no first-party connector)",
            "WhatsApp / Instagram / Messenger / Telegram — first-party inbound + reply channels",
            "Website lead form — inbound via Webhook (first-party)",
          ],
          tools: [
            "knowledge_search (FAQ, docs, published pricing, objection handling)",
            "http_request (log the enquiry to your CRM through its own API)",
            "send_email (reply on the email branch)",
          ],
          guardrails: [
            "Answers come only from the team's own Knowledge with citations — no roadmap dates, no undocumented integrations, no invented capabilities; an uncovered question escalates instead of being improvised",
            "No price, discount, fee waiver or commercial commitment is ever stated by the agent — those route to a human every time",
            "The first reply to a new contact passes the Approval node; you can relax this per-tag later, but never for the PRICING or DEMO_REQUEST branches",
            "WhatsApp policy is respected as a rule of the workflow, not an afterthought: free-form replies only inside the 24-hour customer-service window, an approved template with prior opt-in outside it — the workflow will not send an un-templated message to someone who hasn't messaged first",
            "No fabricated personalisation — the agent never claims to have read their post, visited their site or admired their work",
            "Opt-out is honoured on every channel and stored against the contact, so a later campaign workflow can't message them again",
          ],
          cost:
            "The classifier is the cost-routing decision that matters: it runs on 100% of inbound including spam, so it belongs on the economy tier with a hard token cap and a JSON-only output. The responder — the standard tier plus a fallback model — runs on the smaller answerable slice. Spam is dropped before it ever reaches a paid reasoning call, which in a public inbox is most of the saving.",
          output:
            "Classifier tags every inbound cheaply → the Condition node routes anything commercial or uncovered to a human → the responder drafts a cited, Knowledge-grounded reply → the Approval node gates the first touch → the reply goes out on the originating channel under that channel's real messaging rules, and the enquiry is logged to your CRM via a Tool (MCP) node.",
        },
      },
      {
        id: "mkt-repurpose",
        title: "One long asset → the derivative pieces you never get around to",
        scenario:
          "You recorded a customer webinar in March. It's a 50-minute file in a folder. The clips, the quote graphics, the FAQ additions and the three posts that were obviously in there never happened, because carving them out is two hours nobody has.",
        triggerLabel: "Webhook (a transcript, recording or long doc is uploaded)",
        steps: [
          { kind: "trigger", title: "A long asset lands", detail: "A Webhook fires when a transcript, recording or long document is added — a webinar, a podcast episode, a customer call you had permission to record, a longread you already published." },
          { kind: "loop", title: "Loop over the segments", detail: "A Loop node fans a cheap extractor across the asset segment by segment, so a 50-minute transcript is processed the same way as a 5-minute one and no part of it is skipped because it was late in the file." },
          { kind: "agent", title: "Extract candidate moments", detail: "The extractor returns strict JSON per segment: the quotable line, its timestamp, the question it answers, and whether it names a customer or a number (which flags it for checking). It extracts what was actually said — it never paraphrases a claim into something stronger." },
          { kind: "agent", title: "Shape the best ones into drafts", detail: "A second agent takes only the top candidates and shapes them into the derivative pieces: two short posts, a clip list with timestamps, and any FAQ entries the asset answers better than your current docs." },
          { kind: "approval", title: "Approve — including permission to quote", detail: "An Approval node holds everything. This is also where a human confirms you actually have permission to quote the named customer, which no model can determine for you." },
          { kind: "output", title: "Drafts + a Knowledge suggestion", detail: "Approved drafts go to Slack ready to post; approved FAQ entries are proposed as additions to your Knowledge base, so the next inbound question gets a better answer than it would have last week." },
        ],
        connectors: ["Webhook", "Slack", "Knowledge base", "Approval / HITL", "Email"],
        outcome:
          "A recording that would have stayed in a folder becomes a reviewed set of posts, a timestamped clip list and concrete FAQ additions — in one approval pass.",
        impact:
          "The mechanism is that extraction is the expensive part and it parallelises: the Loop reads every segment cheaply so nothing is missed, and the human only makes the judgement calls — is this quotable, do we have permission, is this the right framing.",
        build: {
          agents: 2,
          agentsNote:
            "1 extractor run once per segment via the Loop node + 1 shaper run once on the shortlist. The permission check is human, at the Approval node.",
          pattern: "Map-reduce: Loop (extract per segment) → shape the shortlist → human approves",
          prompt:
            "(Extractor) Process ONE segment of a long asset. Return strict JSON {quote (verbatim, never paraphrased), timestamp, question_it_answers, names_customer: true|false, contains_number: true|false, strength: LOW|MED|HIGH}. Quote exactly what was said — if a line only works when reworded, mark strength LOW rather than improving it. Set names_customer or contains_number to true whenever either appears, so a human checks permission and accuracy. Never invent a timestamp. Return an empty list for a segment with nothing worth keeping — a thin segment is a normal result.",
          agentRoles: [
            { role: "Extractor (per segment, via Loop)", model: "Economy tier — this is the N-times step, so its tier sets the total cost of the recipe", prompt: "Verbatim quote extraction into strict JSON with timestamps and permission/accuracy flags. Never paraphrase, never strengthen a claim, never invent a timestamp." },
            { role: "Shaper (once, on the shortlist)", model: "Standard tier — the one call where framing matters", prompt: "Turn the HIGH/MED candidates into two short posts, a timestamped clip list and any FAQ entries. Keep quotes verbatim. Flag every quote that names a customer or cites a number for human permission and fact checking." },
          ],
          mcp: [
            "Your video host / storage — via a Tool (MCP) node or an inbound Webhook (no first-party connector)",
            "Slack — draft delivery (first-party)",
          ],
          tools: [
            "knowledge_search (check whether the FAQ already covers this, so you propose additions rather than duplicates)",
            "http_request (fetch the transcript or asset from wherever it lives)",
          ],
          guardrails: [
            "Quotes are extracted verbatim with timestamps and are never paraphrased into a stronger claim — a line that only works reworded is marked weak, not improved",
            "Any quote naming a customer or citing a number is flagged for human permission and fact-checking before it can be approved; consent to quote is a human determination, never a model's",
            "Nothing is published — the Approval node gates every derivative piece, and proposed FAQ entries are suggestions to your Knowledge base, not automatic writes",
            "Recordings are only processed where you already had permission to record; the workflow assumes nothing about consent it cannot see",
            "Idempotent per asset id, so re-uploading the same recording doesn't produce a second set of near-identical drafts",
          ],
          cost:
            "Classic map-reduce economics: cost is N segments × (economy-tier, token-capped extractor) + ONE standard-tier shaping call. The extractor's tier is the dominant lever — the shaper only ever sees the shortlist, so it stays cheap no matter how long the asset is. Run the Loop in Parallel batches to cut wall-clock time without changing per-segment cost.",
          output:
            "Loop extracts verbatim candidates from every segment cheaply → the shaper turns the shortlist into posts, a timestamped clip list and FAQ entries → the Approval node gates publication AND the permission-to-quote decision → approved drafts land in Slack and approved FAQ entries are proposed into Knowledge.",
        },
      },
      {
        id: "mkt-broadcast",
        title: "Customer broadcast that can't accidentally become spam",
        scenario:
          "You want to tell existing customers about a release. The list is a spreadsheet export from three places, you're not sure who opted in, someone remembers a rule about WhatsApp templates, and the safest option — sending nothing — is what happens most months.",
        triggerLabel: "Webhook (release note published) / scheduled",
        steps: [
          { kind: "trigger", title: "Something worth telling people", detail: "A Webhook when you publish a release note, or a scheduled run for a monthly update. The bar is 'this is genuinely useful to an existing customer', not 'it's been a while'." },
          { kind: "loop", title: "Loop the list, one contact at a time", detail: "A Loop node walks the recipient list so eligibility is decided per person, not per campaign. Nobody is included because they happened to be in the same export." },
          { kind: "code", title: "Eligibility as YOUR rule, not a hardcoded one", detail: "A user-defined tool — a SpEL expression you author over the tool's JSON input — decides eligibility. It is sandboxed by construction: no method calls, no constructors, no bean or type references, and there is no loop construct in the grammar. A formula, not a scripting engine — which is exactly what a send-eligibility rule is." },
          { kind: "condition", title: "Ineligible means skipped, not downgraded", detail: "A Condition node drops anyone without opt-in, anyone who opted out, and anyone already messaged inside your frequency window. There is no 'send anyway on a different channel' branch — that's the branch that turns a broadcast into spam." },
          { kind: "agent", title: "Draft one message per channel", detail: "An Agent node drafts the update from the release note and your Knowledge — one email version, one WhatsApp template-compatible version. Every claim comes from the release note; no benefit is embellished." },
          { kind: "approval", title: "A human approves the send", detail: "An Approval node holds the campaign: the copy, the eligible recipient count, and the channel breakdown. Anything sent to prospects or customers at scale is a human decision, every time." },
        ],
        connectors: ["Webhook", "Email", "WhatsApp", "SMS / Twilio", "Knowledge base", "Approval / HITL", "Tool (MCP → your email-marketing tool)"],
        outcome:
          "The update reaches exactly the people who opted in to hear it, on a channel they consented to, with copy a human approved — and the ineligible list is visible rather than quietly included.",
        impact:
          "The mechanism is making eligibility an explicit, per-contact, auditable decision instead of a spreadsheet's implicit one — the reason small teams under-send is uncertainty, and this removes the uncertainty without removing the human sign-off.",
        build: {
          agents: 1,
          agentsNote:
            "1 drafting agent (runs once, not per recipient). Eligibility is a user-defined SpEL tool inside the Loop — no model call per contact, which is both cheaper and more predictable than asking an LLM who to message.",
          pattern: "Loop → deterministic eligibility (user-defined SpEL tool) → one drafting call → human approves the send",
          prompt:
            "You draft a customer update for {{company}}. Source material: the release note plus passages from Knowledge. Produce TWO versions: (1) email — subject line plus body, plain and specific; (2) a WhatsApp version that fits an approved template's structure, short, with no marketing filler. Rules: every statement must trace to the release note or a retrieved Knowledge passage — cite the source for each. Do not embellish a fix into a feature or a feature into a transformation. No urgency manufacturing, no 'don't miss out', no fake scarcity. Include the opt-out line the channel requires. You are drafting for human approval — you never trigger the send.",
          agentRoles: [
            { role: "Update writer (once per campaign)", model: "Standard tier — it runs once regardless of list size, so the reasoning budget here is trivial and worth it", prompt: "Two grounded versions (email + WhatsApp-template-shaped) with per-claim citations, no embellishment, no manufactured urgency, opt-out included." },
            { role: "Eligibility (per contact) — NOT an agent", model: "User-defined SpEL tool: zero model calls, deterministic, auditable", prompt: "A tenant-authored formula, e.g. `send_eligible`: input.optedIn && !input.optedOut && input.daysSinceLastCampaign > 21 && (input.channel == 'EMAIL' || (input.channel == 'WHATSAPP' && input.templateApproved && input.whatsappOptIn)). You own this expression: change the 21 to 30 on a Friday, add a segment condition, and it takes effect on the next run — no developer, no redeploy." },
          ],
          mcp: [
            "Your email-marketing tool / CRM list — via a Tool (MCP) node (no first-party connector)",
            "Email, WhatsApp, SMS (Twilio) — first-party send channels",
          ],
          tools: [
            "user-defined tool `send_eligible` (SpEL over the contact JSON — consent, opt-out, frequency window, channel-specific template/opt-in state)",
            "knowledge_search (release note, positioning, what we're allowed to claim)",
            "send_email (the email branch)",
          ],
          guardrails: [
            "Consent is a precondition, not a filter applied afterwards: no opt-in, no send — and an opt-out is permanent and honoured across every channel and every future workflow",
            "WhatsApp platform policy is enforced in the workflow: outside the 24-hour customer-service window a broadcast requires prior opt-in AND an approved template — the ineligible contacts are skipped, never rerouted to SMS or email to get around it",
            "No scraped or purchased contact lists, ever — recipients exist because they gave you their address for this purpose",
            "Frequency cap per contact via the SpEL rule (e.g. nothing within 21 days), evaluated per person inside the Loop rather than per campaign",
            "The whole campaign — copy, eligible count, channel breakdown — passes the Approval node before a single message is sent; the agent drafts, a human sends",
            "No manufactured urgency, no fake scarcity, and every claim cites the release note or a Knowledge passage",
          ],
          cost:
            "Cost is deliberately flat in list size: ONE standard-tier drafting call for the whole campaign, and zero model calls per recipient because eligibility is a deterministic SpEL expression rather than an LLM judgement. That's the right trade twice over — a formula is cheaper AND more auditable than a model for a consent decision, and consent is precisely the thing you want to be able to explain later.",
          output:
            "The writer produces both channel versions once → the Loop evaluates the user-defined `send_eligible` SpEL rule per contact → ineligible contacts are skipped and listed → the Approval node shows the copy and the real recipient count → on approval the send goes out on Email/WhatsApp/SMS under each channel's own consent rules, with opt-outs written back through a Tool (MCP) node.",
        },
      },
    ],
  },

  // ============================================ SALES PROSPECTING & PIPELINE
  {
    id: "sales-prospecting",
    name: "Sales prospecting & pipeline hygiene",
    icon: TrendingUp,
    tagline:
      "For teams with no RevOps: score inbound with your own ICP rules, follow up past attempt one, keep every deal owning a next step — and never let an agent commit a price.",
    recipes: [
      {
        id: "sales-inbound-triage",
        title: "Website form → enriched, scored and routed in minutes",
        scenario:
          "Leads from the website form land in a shared inbox nobody owns. By the time someone opens it, it's Thursday, half are students and job applicants, and the two that mattered have already booked a call with someone else.",
        triggerLabel: "Webhook (website lead form submission)",
        steps: [
          { kind: "trigger", title: "The form posts to a Webhook", detail: "Your website's lead form posts straight into the workflow on a Webhook — first-party and honest, no connector required and none pretended. Every submission enters the same path within seconds of being submitted." },
          { kind: "parallel", title: "Research while the reply is drafted", detail: "A Parallel node runs two things at once: a research pass over public, business-level information about the company, and a first-draft acknowledgement. The lead isn't waiting on the slower branch." },
          { kind: "agent", title: "Enrich — company facts only", detail: "An economy-tier agent gathers business-level facts: what the company does, rough size band, whether they mention a competitor publicly. It never assembles a personal profile, and it never scrapes personal contact details." },
          { kind: "code", title: "Score it with YOUR ICP rule", detail: "A user-defined tool — a SpEL expression over the tool's JSON input — computes the score. Sandboxed by construction: no method calls, no constructors, no bean or type references, no loop in the grammar. A formula tool, not a scripting engine — which is precisely the shape of an ICP score, and it means you tune your own definition of a good lead weekly without a developer or a redeploy." },
          { kind: "condition", title: "Route by score and intent", detail: "A Condition node splits: high-score demo requests to the founder on Slack immediately, mid-score into the nurture cadence, students/applicants/spam closed politely and out of the pipeline." },
          { kind: "approval", title: "Approve the first outbound reply", detail: "An Approval node holds the drafted reply to a real prospect. The agent drafts; a human sends anything going to a prospect." },
        ],
        connectors: ["Webhook", "Slack", "Email", "WhatsApp", "Approval / HITL", "Tool (MCP → your CRM)"],
        outcome:
          "Every form submission is enriched, scored against your own written ICP rule and routed within minutes, with the genuinely good ones surfacing in Slack while they're still warm.",
        impact:
          "The mechanism is speed and an explicit rule: the delay that loses inbound leads is queue time, not reply quality, and the scoring being a formula you wrote means routing is consistent on Thursday afternoon and explainable when you disagree with it.",
        build: {
          agents: 2,
          agentsNote:
            "1 economy-tier enrichment agent + 1 standard-tier reply drafter, run in Parallel. Scoring is a user-defined SpEL tool — deterministic, no model call, no per-lead variance.",
          pattern: "Parallel (enrich ‖ draft) → deterministic SpEL scoring → route → human approves the outbound",
          prompt:
            "You triage inbound leads for {{company}}. Given a form submission {name, work_email, company, message} plus enrichment, return strict JSON {intent: DEMO|PRICING|SUPPORT|JOB_SEEKER|STUDENT|SPAM, summary, what_they_want, employees_band, uses_competitor: true|false|unknown, requested_demo: true|false}. Use ONLY the submission and publicly available business-level facts about the company. Never build a personal profile of the individual, never look for personal contact details, never guess at seniority or salary. Set unknown rather than inferring. Then draft a short first reply that answers their actual question from Knowledge — no pitch, no fabricated familiarity, and no claim that you've read their blog, used their product or admired their work. Never state a price beyond the published page and never offer a discount.",
          agentRoles: [
            { role: "Enricher (every lead)", model: "Economy tier — runs on 100% of submissions including the job applicants, so this is the volume step", prompt: "Business-level public facts only, into strict JSON, with `unknown` where the facts aren't there. No personal profiling, no scraped contact details, no inference dressed as fact." },
            { role: "Reply drafter (real prospects only)", model: "Standard tier with a fallback model — a prospect reads this one", prompt: "Short reply grounded in Knowledge, answering what they actually asked. No pitch, no fabricated personalisation, no price or discount commitment." },
            { role: "Scorer — NOT an agent", model: "User-defined SpEL tool: deterministic, zero model cost, identical every run", prompt: "Tenant-authored formula, e.g. `lead_score`: (input.employees > 200 ? 40 : 10) + (input.usesCompetitor ? 30 : 0) + (input.requestedDemo ? 30 : 0). You own the weights — raise the demo weight, add a geography or industry term, and the next lead is scored the new way with no developer and no deploy." },
          ],
          mcp: [
            "Your CRM — via a Tool (MCP) node to create the lead record (no first-party connector)",
            "Your analytics / ad platform — via a Tool (MCP) node if you attribute the source (no first-party connector)",
            "Website form — inbound via Webhook (first-party); Slack, Email, WhatsApp — first-party notification and reply channels",
          ],
          tools: [
            "user-defined tool `lead_score` (SpEL over the enriched lead JSON — your ICP weights, tunable weekly)",
            "web_search (public, business-level company context only)",
            "knowledge_search (positioning and objection handling, so the reply answers the real question)",
            "http_request (create the lead in your CRM through its API)",
          ],
          guardrails: [
            "Enrichment is business-level and public only — no personal profiles, no scraped personal contact details, no purchased data, and `unknown` wherever the fact isn't there rather than an inference presented as fact",
            "No fabricated personalisation: the agent may not claim to have read their post, visited their site or used their product",
            "No price beyond the published page and no discount — ever, from any agent; commercial commitments route to a human",
            "Every outbound reply to a real prospect passes the Approval node before sending; the agent drafts, a human sends",
            "Consent and opt-out are checked before the first message, and the reply channel is the one they used or consented to — a form submission is not WhatsApp opt-in",
            "Job seekers, students and spam are routed out of the sales pipeline politely and are never added to any nurture cadence",
          ],
          cost:
            "This is the textbook cost-routing case: enrichment runs on every single submission including the ones that will be closed as job applicants, so it belongs on the economy tier with a hard cap; the reply drafter — standard tier plus a fallback — only ever runs on the qualified slice. Scoring costs nothing at all because it's a SpEL formula rather than a model call, which also means the score doesn't drift between runs.",
          output:
            "The Webhook fires → Parallel enrichment and drafting → the user-defined `lead_score` SpEL tool scores against your own ICP → the Condition node routes (Slack ping for hot demo requests, cadence for mid, polite close for the rest) → the Approval node gates the outbound reply → the lead is written to your CRM via a Tool (MCP) node with the score and its reason attached.",
        },
      },
      {
        id: "sales-followup",
        title: "Follow-up that survives past attempt one",
        scenario:
          "You send one follow-up. If they don't reply, that's the end of it — not by decision, but because remembering to send a second one on day six while shipping a release is not a thing that happens. Deals you'd have won die of silence.",
        triggerLabel: "Scheduled (daily sweep of contacts awaiting a reply)",
        steps: [
          { kind: "trigger", title: "Daily sweep, not a mass blast", detail: "A scheduled run reads the contacts awaiting a reply from your CRM via a Tool (MCP) node. It is a queue of individuals, deliberately, not a campaign list." },
          { kind: "loop", title: "One contact at a time", detail: "A Loop node handles each contact separately, so every send decision is made on that person's own consent state, history and channel — never on the batch's average." },
          { kind: "code", title: "Should this person be touched at all?", detail: "A user-defined SpEL tool decides eligibility before anything is drafted: consent present, not opted out, attempt count under the cap, minimum gap since the last touch elapsed, channel policy satisfied. A sandboxed formula — no method calls, no constructors, no type references, no loop construct — which is exactly right for a rule you want auditable." },
          { kind: "agent", title: "Draft a follow-up that adds something", detail: "An Agent node drafts a message that says something new — the answer to a question raised on the call, a relevant case study from Knowledge — rather than 'just bumping this'. If it has nothing new to say, it returns nothing and the contact is skipped." },
          { kind: "approval", title: "Batch approval before anything sends", detail: "An Approval node presents the day's drafts together with each recipient's attempt number. Outbound at scale is a human decision — you scan, cut two, and send." },
          { kind: "output", title: "Send, log, and stop when they say stop", detail: "Approved messages go out on the consented channel, the attempt count is written back to the CRM via a Tool (MCP) node, and any reply — especially an opt-out — permanently ends the sequence for that person." },
        ],
        connectors: ["Email", "WhatsApp", "SMS / Twilio", "Slack", "Approval / HITL", "Tool (MCP → your CRM)"],
        outcome:
          "Follow-ups continue to a defined limit with something new to say each time, on consented channels, and stop the moment someone replies or opts out.",
        impact:
          "The mechanism is that the second and third touch stop depending on someone remembering — the cap and the consent rule are enforced in the workflow, which is also what keeps persistence from turning into pestering.",
        build: {
          agents: 1,
          agentsNote:
            "1 drafting agent, and it only runs for contacts the SpEL eligibility rule already cleared — the cheapest way to run a cadence is to not generate text for people you shouldn't message.",
          pattern: "Scheduled sweep → Loop → deterministic eligibility (SpEL) → draft → batch human approval",
          prompt:
            "You draft follow-ups for {{company}}. Given the contact's history {last_message_sent, their_last_reply, call_notes, attempt_number, open_questions[]}, draft ONE short message that adds something they don't already have: the answer to a question they raised, a relevant case study from Knowledge, or a genuinely useful resource. If there is nothing new to say, return {send: false, reason} — a skipped touch is a correct outcome and 'just bumping this to the top of your inbox' is not. Never invent familiarity ('I loved your post about X') and never reference anything you did not receive in the history. Never offer a discount, a price concession or an extended trial. Include a plain one-line opt-out. Reference the attempt number in your own reasoning: by the final attempt, say plainly that this is the last one.",
          agentRoles: [
            { role: "Follow-up drafter (eligible contacts only)", model: "Standard tier, fallback model configured — the message is read by a person deciding whether to take you seriously", prompt: "One short message that adds something new, grounded in call notes and Knowledge, with opt-out. Return send:false rather than writing a bump. Never fabricate familiarity, never concede on price." },
            { role: "Eligibility + cadence rule — NOT an agent", model: "User-defined SpEL tool: deterministic, zero cost, fully auditable", prompt: "Tenant-authored formula, e.g. `followup_due`: input.consented && !input.optedOut && input.attemptCount < 4 && input.daysSinceLastTouch >= (input.attemptCount == 1 ? 4 : 9) && !input.replied. You own the cap and the spacing — widen the gap or drop the cap to 3 next week and it applies immediately, no developer, no redeploy." },
          ],
          mcp: [
            "Your CRM — via a Tool (MCP) node to read the awaiting-reply queue and write back the attempt count (no first-party connector)",
            "Email, WhatsApp, SMS (Twilio) — first-party send channels; Slack — where the daily approval batch is reviewed",
          ],
          tools: [
            "user-defined tool `followup_due` (SpEL over the contact JSON — consent, opt-out, attempt cap, spacing, replied-state)",
            "knowledge_search (case studies, objection handling, the answer to what they actually asked)",
            "send_email (the email branch)",
            "http_request (read and update the CRM)",
          ],
          guardrails: [
            "Hard cap on attempts, enforced by the SpEL rule and not by anyone's memory; the final attempt says plainly that it's the last one",
            "Opt-out is permanent, honoured on every channel, and written back to the CRM so no other workflow can re-enter that person into a sequence",
            "Any reply — including a negative one — ends the cadence immediately; the workflow never continues a sequence into a live conversation",
            "Messaging-platform policy is respected per channel: WhatsApp follow-ups require prior opt-in and an approved template outside the 24-hour window, and an ineligible contact is skipped rather than rerouted to another channel to get around it",
            "No scraped or purchased contact lists — every contact in the queue is someone who gave you their details in a real interaction",
            "No fabricated personalisation: the agent may not claim to have read, seen or admired anything it wasn't given",
            "No discount, price concession or extended trial from an agent — those are a human's to offer, at the Approval node or in person",
            "The whole day's batch passes the Approval node before any of it sends",
          ],
          cost:
            "The ordering is the saving: eligibility is evaluated first as a free, deterministic SpEL expression, so the standard-tier drafting call only happens for contacts who should actually be messaged. On a list where most contacts are inside their spacing window on any given day, that means most days generate very few paid calls. Add a fallback model so a provider outage delays a follow-up rather than dropping it silently.",
          output:
            "The scheduled sweep pulls the awaiting-reply queue from the CRM → the Loop evaluates `followup_due` per contact → eligible contacts get one grounded, additive draft (or a documented skip) → the day's batch is reviewed at one Approval node → approved messages send on the consented channel, attempt counts and opt-outs are written back through a Tool (MCP) node.",
        },
      },
      {
        id: "sales-pipeline-hygiene",
        title: "Every deal owns a next step (weekly pipeline sweep)",
        scenario:
          "Open the CRM and half the deals have no next step, no date and a last-activity in April. Nobody is lying — the pipeline just decays quietly between calls, and the weekly review turns into ten minutes of scrolling and a shared feeling that it's probably fine.",
        triggerLabel: "Scheduled (weekly, before the pipeline review)",
        steps: [
          { kind: "trigger", title: "Run it before the review, not during", detail: "A scheduled run each week pulls all open deals from your CRM via a Tool (MCP) node, so the review starts with a list instead of a scroll." },
          { kind: "loop", title: "Per deal, not per pipeline", detail: "A Loop node walks each open deal on its own facts — stage, last activity, next-step field, expected close date — instead of eyeballing the board." },
          { kind: "code", title: "Staleness as a rule you wrote down", detail: "A user-defined SpEL tool classifies each deal: FRESH / DRIFTING / STALE / GHOST, from your own thresholds. Sandboxed by construction — no method calls, no constructors, no bean or type references, no loop construct in the grammar. It's a classifier formula, and having it as a formula means the definition of 'stale' is written down once and applied identically to every deal, including the ones you like." },
          { kind: "agent", title: "Propose the concrete next step", detail: "An economy-tier agent proposes a specific next action per flagged deal — from the call notes and your Knowledge — not 'follow up'. Where there's genuinely nothing left to do, it proposes closing the deal, which is the step nobody takes voluntarily." },
          { kind: "agent", title: "Summarise the week", detail: "A standard-tier agent rolls it up: what moved, what's drifting, what should close-lost this week, and where the pipeline is thin — in one message you can actually read before the meeting." },
          { kind: "output", title: "Digest to Slack, nothing auto-changed", detail: "The digest posts to Slack with per-deal proposed next steps. Writing them back to the CRM happens only for the ones a human accepts — the agent doesn't edit your pipeline behind you." },
        ],
        connectors: ["Slack", "Email", "Approval / HITL", "Tool (MCP → your CRM)"],
        outcome:
          "Every Monday the pipeline arrives pre-classified with a proposed next step per stalled deal and an explicit close-lost list, instead of a board nobody wants to scroll.",
        impact:
          "The mechanism is a written-down definition of stale applied uniformly: the deals that decay are the ones you avoid looking at, and a rule that runs on all of them removes the selective attention without pretending to know more than your notes do.",
        build: {
          agents: 2,
          agentsNote:
            "1 economy-tier next-step proposer (runs per flagged deal) + 1 standard-tier weekly summariser (runs once). Staleness classification is a user-defined SpEL tool — no model call, no drift between weeks.",
          pattern: "Loop → deterministic SpEL classification → cheap per-deal proposal → one synthesis → human accepts changes",
          prompt:
            "(Per deal) Propose the single most useful next step for ONE open deal, using only its own record and notes {stage, last_activity_date, next_step, close_date, notes, last_contact_summary}. Return strict JSON {deal_id, classification, proposed_next_step (specific and dated — 'send the security questionnaire answers by Thursday', never 'follow up'), owner_action_required, confidence}. If the notes show nothing left to pursue, propose CLOSE_LOST with the reason from the notes. Never invent activity that isn't in the record, never assume a conversation happened, never infer intent from silence beyond flagging it as silence. You propose only — you never edit the CRM.",
          agentRoles: [
            { role: "Next-step proposer (per flagged deal)", model: "Economy tier — it runs once per stalled deal every week, so this is the recurring volume", prompt: "One specific, dated next step per deal from its own record. CLOSE_LOST where the notes support it. Never invent activity. Never write to the CRM." },
            { role: "Weekly summariser (once)", model: "Standard tier — the one call anyone actually reads", prompt: "Roll the per-deal results into a short pre-review digest: what moved, what's drifting, the close-lost candidates, where the pipeline is thin. Use only the provided items and real counts — no estimated forecasts and no invented probabilities." },
            { role: "Staleness classifier — NOT an agent", model: "User-defined SpEL tool: deterministic, free, identical every week", prompt: "Tenant-authored formula, e.g. `deal_staleness`: input.daysSinceActivity > 45 ? 'GHOST' : (input.nextStep == null || input.nextStepDate == null ? 'STALE' : (input.daysSinceActivity > 14 ? 'DRIFTING' : 'FRESH')). Your thresholds, your definition — change 14 to 10 for a faster cycle and the whole pipeline is reclassified on the next run, with no developer and no redeploy." },
          ],
          mcp: [
            "Your CRM — via a Tool (MCP) node to read open deals and (only on acceptance) write next steps back (no first-party connector)",
            "Slack — where the digest lands (first-party); Email — alternative delivery",
          ],
          tools: [
            "user-defined tool `deal_staleness` (SpEL over the deal JSON — your thresholds for drifting, stale and ghosted)",
            "knowledge_search (what the standard next step is at each stage, per your own sales notes)",
            "http_request (read open deals; write accepted next steps back)",
          ],
          guardrails: [
            "The agents propose only — no next step, stage change or close-lost is written to the CRM until a human accepts it in the digest; your pipeline is never edited behind you",
            "No invented activity and no invented forecast: proposals cite the deal's own notes, and the summary uses real counts rather than estimated probabilities or a made-up close likelihood",
            "Silence is reported as silence — the classifier flags an absence of activity, it does not infer that a prospect has decided anything",
            "The digest is internal (Slack/Email) — this workflow never messages a prospect; any outreach it suggests goes through the follow-up recipe with its own consent checks and approval",
            "Deal data stays inside the workflow: no customer record is sent to a web search or any external tool that isn't your own CRM",
          ],
          cost:
            "Cost scales with stalled deals, not total deals, because the free SpEL classifier filters first — FRESH deals never reach a model at all. The per-deal proposer sits on the economy tier since it runs weekly across the flagged set; the single standard-tier synthesis is where the reasoning budget goes, and it costs the same whether you have 20 deals or 200.",
          output:
            "The weekly run pulls open deals via a Tool (MCP) node → the Loop applies your `deal_staleness` SpEL rule → flagged deals get a cheap, specific, dated next-step proposal → one standard-tier call writes the pre-review digest → it posts to Slack, and only the proposals a human accepts are written back to the CRM.",
        },
      },
      {
        id: "sales-demo-followup",
        title: "Demo call → grounded recap and a tailored proposal (price still human)",
        scenario:
          "The same eight demo questions get answered from scratch every time — the integration one, the data-residency one, the 'can we export' one. Afterwards the recap gets written from memory two days later, if at all, and the proposal is last quarter's deck with the name changed.",
        triggerLabel: "Webhook (call notes or transcript submitted after the demo)",
        steps: [
          { kind: "trigger", title: "Notes or transcript come in", detail: "A Webhook fires when the rep submits call notes or a transcript from a recorded, consented call. This is the only input — the workflow never assumes anything about a conversation it wasn't given." },
          { kind: "agent", title: "Extract what was actually asked", detail: "An economy-tier agent pulls the structured facts: questions asked, objections raised, requirements stated, commitments made by the rep, and who is involved in the decision — each tied to the line it came from." },
          { kind: "knowledge", title: "Answer the recurring eight, from Knowledge", detail: "A Knowledge node retrieves the team's own documented answers to the integration, security, export and residency questions. This is what stops the from-scratch rewriting — and it means the answer is the same one the last prospect got." },
          { kind: "parallel", title: "Recap and proposal, in parallel", detail: "A Parallel node runs the recap draft and the proposal draft at once. The recap needs to go out today; the proposal takes longer, and neither should wait for the other." },
          { kind: "approval", title: "Human approves — always, for price", detail: "An Approval node holds both. Any price, discount, term, timeline or scope commitment is a human decision without exception; the agent may reproduce the published pricing and nothing beyond it." },
          { kind: "output", title: "Send the recap, log the answers", detail: "The approved recap goes to the prospect on Email; the questions that Knowledge could not answer are logged, because that list is what your docs are missing and it's why the same eight questions were being improvised in the first place." },
        ],
        connectors: ["Webhook", "Email", "Slack", "Knowledge base", "Approval / HITL", "Tool (MCP → your CRM)"],
        outcome:
          "A recap with consistent, sourced answers goes out the same day, a tailored proposal draft is ready for review, and the gaps in your own documentation become a visible list.",
        impact:
          "The mechanism is reuse plus a gap log: the recurring questions get one canonical answer instead of eight improvised ones, and the questions Knowledge can't answer are surfaced rather than quietly re-improvised on the next call.",
        build: {
          agents: 3,
          agentsNote:
            "1 economy-tier extractor + 2 drafters running in Parallel (recap on standard tier, proposal on premium — the only premium call in either pack). The price gate is a workflow node, not a prompt instruction.",
          pattern: "Extract cheaply → Parallel (recap ‖ proposal) → human approves, with price always human",
          prompt:
            "(Extractor) From demo call notes or a transcript, return strict JSON {questions_asked[{question, line_ref}], objections[{objection, line_ref}], requirements[], commitments_made_by_rep[], stakeholders[], next_step_agreed}. Extract only what is present — never infer a requirement from tone, never assume a budget, never record a commitment that wasn't spoken. If the notes are thin, return what's there and mark the rest missing; a short honest extraction is better than a plausible full one.",
          agentRoles: [
            { role: "Call extractor", model: "Economy tier — structured extraction on every call, no prose required", prompt: "Strict JSON of questions, objections, requirements, spoken commitments and stakeholders, each tied to a line reference. Nothing inferred, nothing assumed." },
            { role: "Recap writer", model: "Standard tier — goes to the prospect today", prompt: "Write the recap: what we discussed, answers to their questions taken verbatim-in-substance from Knowledge with citations, the agreed next step, and an explicit 'we'll confirm' for anything uncovered. Never state a price beyond the published page. Never invent a timeline." },
            { role: "Proposal drafter", model: "Premium tier — the one place in both packs worth it: a tailored commercial document, drafted once, read closely, and edited by a human before it leaves", prompt: "Draft a proposal from the extracted requirements and Knowledge case studies. Leave every price, discount, term and timeline as an explicit blank for the human to fill — do not populate them, not even from the published page. No invented references, no logo the case studies don't cover." },
          ],
          mcp: [
            "Your CRM — via a Tool (MCP) node to attach the recap and update the deal (no first-party connector)",
            "Your call-recording tool — inbound via Webhook (no first-party connector)",
            "Email — first-party recap channel; Slack — internal review",
          ],
          tools: [
            "knowledge_search (documented answers to the recurring questions, security and residency notes, nameable case studies)",
            "send_email (the approved recap)",
            "http_request (attach the recap and next step to the CRM record)",
          ],
          guardrails: [
            "No price, discount, term, timeline or scope commitment from any agent — the proposal drafter leaves them blank by construction and the Approval node is where a human fills and owns them",
            "Answers about the product come only from the team's own Knowledge with citations; an undocumented question is written as 'we'll confirm' and logged as a documentation gap, never improvised",
            "Nothing is inferred from the call that wasn't said — no assumed budget, no assumed authority, no commitment recorded that wasn't spoken",
            "Transcripts are used only where the call was recorded with the participants' consent, and call content is never sent to a web search or any external tool that isn't your own CRM",
            "No fabricated familiarity or invented references in the proposal — a case study can only be named if Knowledge says you're allowed to name it",
            "The recap goes to the prospect only after human approval; the agent drafts, a human sends",
          ],
          cost:
            "This is deliberate cost routing across three tiers in one workflow: extraction is economy (it runs on every call and produces JSON), the recap is standard (a person reads it today), and the proposal is the single premium call — justified because it runs rarely, carries commercial weight and is edited by a human anyway. Configure a fallback model on the premium step so an outage downgrades the draft instead of stalling the deal, and cap the transcript window so a two-hour recording can't quietly become the most expensive run of the month.",
          output:
            "Extractor produces structured call facts cheaply → Parallel drafts the recap and the proposal → both land at one Approval node where a human fills in every price and term → the approved recap sends on Email and attaches to the CRM via a Tool (MCP) node → unanswerable questions are logged as the documentation gaps to close before the next demo.",
        },
      },
    ],
  },
];
