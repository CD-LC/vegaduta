import type { Industry } from "./recipes-data";
import { Video, Clapperboard } from "lucide-react";

// ---------------------------------------------------------------------------
// Individual-creator workflow recipes — the YouTuber / long-form channel and
// the reels-shorts-and-brand-deals influencer. Deliberately NOT the business
// packs: "Media, entertainment & OTT" and "Advertising & marketing agencies"
// already cover studios and agencies. These two categories are ONE PERSON (or
// a two-person team) running a channel, whose "ops team" today is a phone at
// midnight, a personal inbox and a note app.
//
// Same "inline copy, no i18n keys" precedent and honesty rules as
// recipes-data.tsx. Every recipe is expressed ONLY in VegaDūta's real building
// blocks (Trigger / Agent / Knowledge / Condition / Approval / Output /
// Tool(MCP) / Voice / Device / Loop / Code / Parallel / Supervisor /
// Sub-workflow), so each maps 1:1 to something buildable in the designer.
//
// Honesty rules held to here:
//  * Only real first-party channels: Webhook, WhatsApp, SMS, Voice/Call,
//    Email, Slack, Discord, Telegram, Instagram, Messenger, X, ElevenLabs
//    (voice), Sarvam (Indian languages), Razorpay (UPI). Instagram, Messenger,
//    Telegram, Discord and X ARE real messaging connectors and are used
//    honestly for comment/DM flows.
//  * Platform APIs — YouTube Data/Analytics, Instagram Insights, upload and
//    scheduling tools — have NO first-party connector. They are reached the
//    real way: an inbound Webhook and/or a Tool (MCP) node, always LABELLED.
//  * AUTHENTICITY: the agent DRAFTS, the creator APPROVES. Nothing publishes
//    and no brand commitment or price is agreed without an Approval node. The
//    voice stays the creator's — every draft is grounded in the creator's own
//    Knowledge (past scripts, tone guide, rate card, FAQ), never inventing
//    claims about a sponsor's product. Sponsored output keeps its disclosure.
//  * Impact lines describe the mechanism, never invented views/subs/ROI.
// ---------------------------------------------------------------------------

export const INDUSTRIES_CREATORS: Industry[] = [
  // ================================================ YOUTUBERS & LONG-FORM
  {
    id: "youtube-creator",
    name: "YouTubers & long-form video creators",
    icon: Video,
    tagline:
      "A manager, an editor's assistant and a first-line community mod for a one-person channel — every reply, every script and every brand yes still approved by you, in your voice.",
    recipes: [
      {
        id: "yt-comment-triage",
        title: "Clear the comment backlog in one batch (map-reduce, 2 agents)",
        scenario:
          "Comments get read at midnight on a phone, thumb-scrolling a feed that never ends. The genuinely useful ones — a correction, a repeated question, a collab offer, someone reporting a broken link — are buried between hundreds of emoji replies, and the same question gets answered twice because nobody remembers answering it the first time.",
        triggerLabel: "Webhook (comment export from the platform API via Tool/MCP — daily or post-upload)",
        steps: [
          { kind: "trigger", title: "The day's comments arrive", detail: "A scheduled Webhook fires with the batch reference; a Tool (MCP) node pulls the comment list from the platform's own API. YouTube has no first-party connector — it is reached via Webhook + Tool (MCP), never pretended otherwise." },
          { kind: "loop", title: "Map: one cheap pass per comment", detail: "A Loop node fans an economy-tier triage agent across every comment — isolated context per item, so a 40-comment day and a 4,000-comment launch day are handled identically." },
          { kind: "agent", title: "Triage + draft, never post", detail: "The triage agent returns strict JSON per comment (bucket: QUESTION / CORRECTION / BUSINESS / SUPPORT / ABUSE / NOISE, sentiment, needs_reply) and, for QUESTION/SUPPORT, a draft reply written from the channel FAQ and tone guide in Knowledge. It drafts. It never publishes." },
          { kind: "knowledge", title: "Answer from what you already said", detail: "A Knowledge node holds the channel FAQ, pinned-comment answers, past scripts and the tone guide — so a repeated question gets the answer you already gave, in your phrasing, instead of a fresh invention." },
          { kind: "agent", title: "Reduce: the daily digest", detail: "A reducer agent rolls the validated items into one brief: the top repeated questions (candidates for a pinned comment), corrections worth acknowledging on-camera, business/collab enquiries pulled out to the top, and anything flagged for moderation." },
          { kind: "approval", title: "You approve the replies", detail: "An Approval node holds the drafted replies as a single review list — approve, edit, or drop each one. Nothing is posted to your channel without that click." },
          { kind: "output", title: "Post approved + file the digest", detail: "A Tool (MCP) node posts only the approved replies back through the platform API; the digest goes to Email or Telegram so it can be read once, in the morning, instead of scrolled at midnight." },
        ],
        connectors: ["Webhook", "Tool (MCP → YouTube Data API)", "Knowledge base", "Approval / HITL", "Email", "Telegram"],
        outcome:
          "The whole comment backlog is bucketed, deduped and drafted in one run, and the creator spends their time approving replies and reading one digest instead of scrolling a feed.",
        impact:
          "Map-reduce turns an unbounded scroll into cheap per-comment passes plus one synthesis. The mechanism that matters: repeat questions are grouped so the same answer is written once, and business enquiries stop being lost between emoji replies — with the creator still the only one who can post.",
        build: {
          agents: 2,
          agentsNote: "1 triage/draft agent (runs once per comment via the Loop node) + 1 reducer (runs once). Loop, Knowledge and Approval are workflow nodes.",
          pattern: "Map-reduce: Loop (map, economy tier) → reducer (reduce) → creator approves every reply",
          prompt:
            "(Triage) Handle ONE comment on {{channel}}. Return strict JSON {bucket (QUESTION|CORRECTION|BUSINESS|SUPPORT|ABUSE|NOISE), sentiment, needs_reply (bool), duplicate_of (id|null), draft_reply (string|null)}. Write draft_reply ONLY for QUESTION or SUPPORT, and ONLY from the FAQ, pinned answers, past scripts and tone guide in Knowledge — match the creator's voice, never invent a fact, a date, a product claim or a promise about future uploads. If the answer is not in Knowledge, set draft_reply=null and needs_reply=true so a human writes it. Route BUSINESS to the enquiry list, never reply to it. Never post anything; you only draft.",
          agentRoles: [
            { role: "Comment triage + draft (per comment)", model: "Economy tier — fast and cheap; it runs N times, so this single choice dominates the whole workflow's cost. Fallback model configured so one provider hiccup does not stall the batch", prompt: "Bucket ONE comment into schema-valid JSON and, for questions only, draft a reply grounded strictly in the channel's own Knowledge. Null instead of a guess. Never publish." },
            { role: "Digest reducer (once)", model: "Standard tier — reasoning over the batch", prompt: "Aggregate the validated comment results into one morning brief: repeated questions ranked by frequency (pinned-comment candidates), corrections worth an on-camera acknowledgement, business/collab enquiries lifted to the top, moderation flags. Cite real counts from the batch; never estimate." },
          ],
          mcp: [
            "YouTube Data API (comment list + reply post) — via a Tool (MCP) node; no first-party connector exists",
            "Email / Telegram — the digest delivery channel (both first-party)",
          ],
          tools: [
            "knowledge_search (channel FAQ, pinned answers, past scripts, tone guide)",
            "http_request (pull the comment batch; post only approved replies)",
            "user-defined tool comment_priority (SpEL): input.bucket == 'BUSINESS' ? 100 : (input.bucket == 'CORRECTION' ? 80 : (input.needsReply ? (input.likeCount >= input.pinThreshold ? 60 : 30) : 0)) — a tenant-authored SpEL expression over the tool's JSON input: a data-transform formula, sandboxed by construction (no method calls, no constructors, no bean or type references, and no loop in the grammar), NOT a scripting engine. The creator encodes their own idea of what matters once, and every comment in every future batch is scored against it automatically",
          ],
          guardrails: [
            "The agent drafts, the creator approves — every reply passes the Approval node before a single character reaches the channel; nothing auto-posts",
            "Voice stays the creator's: drafts are grounded only in the channel's own Knowledge (FAQ, pinned answers, past scripts, tone guide). No invented facts, dates, product claims or upload promises — if the answer is not in Knowledge, the agent returns null and asks for a human",
            "Schema validation per comment — a malformed item is dropped from the reduce and surfaced in the digest, never silently answered",
            "ABUSE-bucketed items are only ever flagged for the creator's own moderation decision; the agent never issues a strike, block or removal itself",
            "Idempotent per comment id, so re-running the batch cannot double-reply to the same person",
          ],
          cost:
            "Cost is linear and easy to reason about: N × (economy-tier, hard-capped triage agent) + ONE standard-tier reduce. Because triage runs across the entire comment volume — the largest item count in a creator's whole operation — it MUST sit on the economy tier; that is the dominant lever, and premium models belong nowhere near it. Run the map in Parallel batches for wall-clock speed without changing per-item cost, and set a per-run token cap so a viral video cannot produce a surprise bill.",
          output:
            "Loop maps the cheap triage agent over every comment → the reducer writes one morning digest → the creator approves, edits or drops each drafted reply at the Approval node → a Tool (MCP) node posts only the approved replies through the platform API and the digest lands on Email or Telegram. Unapproved drafts stay drafts.",
        },
      },
      {
        id: "yt-sponsor-inbox",
        title: "Brand-deal inbox: screen every sponsor email against your own rate card",
        scenario:
          "Sponsor emails land in the same personal inbox as everything else. Half are mass-blast offers well under the creator's floor rate, some are categories they would never promote, and the two genuinely good ones get answered a week late — or never, because the thread scrolled off the first screen. The rate card lives in the creator's head.",
        triggerLabel: "Email (business address) / Instagram DM / Webhook (contact form)",
        steps: [
          { kind: "trigger", title: "An offer arrives", detail: "An Email trigger on the business address, an Instagram DM, or a Webhook from the site contact form catches the enquiry — all three are real first-party inbound paths." },
          { kind: "agent", title: "Extract the actual offer", detail: "An Agent node pulls the deal into structured fields: brand, product category, deliverables (integration / dedicated / short), usage rights, exclusivity, timeline, and the offered fee. Missing fields come back as null — never guessed." },
          { kind: "tool", title: "Score it against YOUR rules", detail: "A user-defined tool (SpEL formula, see the build) compares the offer to the creator's own floor rate and off-limits categories. The creator writes those rules once; every future email is screened against them automatically, with no model judgement involved in the money threshold." },
          { kind: "condition", title: "Below rate / wrong category?", detail: "A Condition node branches: BELOW_RATE and DECLINE_CATEGORY route to a polite, pre-approved decline draft; CONSIDER routes to a real proposal draft." },
          { kind: "knowledge", title: "Price from the real rate card", detail: "A Knowledge node holds the rate card, standard usage/exclusivity terms and past deal notes — the proposal is assembled from documented rates, never a number the model invented." },
          { kind: "approval", title: "You agree the price, not the agent", detail: "An Approval node holds every outbound reply. No fee, no deliverable list, no exclusivity window and no yes leaves without the creator's click — a brand commitment is binding." },
          { kind: "output", title: "Reply + track the thread", detail: "An Output node sends the approved reply on the channel it came in on, and a Tool (MCP) node files the deal in the creator's tracker so a good offer cannot scroll away again." },
        ],
        connectors: ["Email", "Instagram", "Webhook", "Knowledge base", "Approval / HITL", "Tool (MCP → deal tracker / sheet)"],
        outcome:
          "Every sponsor email is parsed, screened against the creator's own floor rate and category rules, and answered with a grounded draft — with the price and the yes still entirely the creator's decision.",
        impact:
          "The mechanism: the rate card moves out of the creator's head into a rule that runs on every email, so lowball mass-blasts get a fast polite decline and real offers surface the same day instead of a week later. Nothing about the money is left to model judgement — the threshold is a formula the creator wrote.",
        build: {
          agents: 2,
          agentsNote: "1 extraction agent + 1 reply-drafting agent (they run on different branches of the Condition node, so a decline never pays for the drafting model). The scoring is a user-defined tool, not an agent.",
          pattern: "Extract → deterministic SpEL screen → branch (decline draft | proposal draft) → creator approves",
          prompt:
            "You screen inbound brand enquiries for {{creator}}. From an email or DM, extract strict JSON {brand, category, deliverables[], usage_rights, exclusivity_months, timeline, offer_amount, currency}. Use null for anything the sender did not actually state — NEVER infer a fee, a category or a usage term. Do not evaluate whether the deal is good: the workflow's deal_fit tool decides that from the creator's own rules. Do not reply, do not negotiate, do not agree to anything.",
          agentRoles: [
            { role: "Offer extractor", model: "Economy tier — structured extraction only, and it runs on every enquiry including the junk ones", prompt: "Parse ONE enquiry into the offer schema. Nulls, not guesses. No judgement on quality, no reply." },
            { role: "Reply drafter (CONSIDER branch)", model: "Standard tier — it writes in the creator's voice and quotes real numbers, so it gets the better model; only reached after the SpEL screen passes", prompt: "Draft a reply in the creator's voice using ONLY the rate card, standard terms and past deal notes in Knowledge. Quote documented rates only. Never invent a discount, a package or a claim about the brand's product. Mark the draft as a proposal pending the creator's approval — never phrase it as an agreement." },
            { role: "Decline drafter (BELOW_RATE / DECLINE_CATEGORY branch)", model: "Economy tier — short, templated, high volume", prompt: "Write a brief, warm, non-committal decline from the creator's saved decline template. Never state the floor rate or the reason category out loud unless the template does." },
          ],
          mcp: [
            "Deal tracker / sheet — via a Tool (MCP) node (no first-party connector)",
            "Email + Instagram — real first-party inbound and reply channels",
            "Site contact form — inbound via Webhook",
          ],
          tools: [
            "knowledge_search (rate card, usage/exclusivity terms, past deal notes)",
            "user-defined tool deal_fit (SpEL): input.offer >= input.floorRate ? (input.category matches input.allowedCategories ? 'CONSIDER' : 'DECLINE_CATEGORY') : 'BELOW_RATE' — a tenant-authored SpEL expression evaluated over the tool's JSON input. Sandboxed by construction: no method calls, no constructors, no bean or type references, and the grammar has no loop. It is a data-transform/formula tool, not a scripting engine. The creator encodes THEIR OWN floor rate and off-limits categories once, and every brand email from then on is screened against it automatically — the money threshold never depends on how a model felt that day",
            "send_email (the approved reply)",
            "http_request (file the deal in the tracker)",
          ],
          guardrails: [
            "No price and no yes without the creator: every outbound reply — decline or proposal — passes the Approval node, and a fee, deliverable list or exclusivity window is never agreed by the agent",
            "Proposals are priced only from the rate card in Knowledge. No invented rates, bundles, discounts or lead times",
            "The agent never writes a claim about the sponsor's product — it describes the creator's own deliverables only; any product claim must come from the brand's brief and be approved on-camera by the creator",
            "The screening threshold is the creator's own SpEL formula, not model judgement — deterministic, auditable, and identical on every email",
            "Extraction returns null for anything the sender did not state, so a missing fee can never be read as an acceptable one",
          ],
          cost:
            "The volume here is junk-heavy, so the cost design is the branch itself: extraction and declines sit on the economy tier and absorb the mass-blast traffic, while the standard-tier drafting model is only ever reached after deal_fit returns CONSIDER. A fallback model keeps the inbox moving if the primary provider is down. There is no batch multiplier — one pass per enquiry.",
          output:
            "The extractor parses the offer → deal_fit (the creator's own SpEL formula) screens it → the Condition node routes to a cheap decline draft or a rate-card-grounded proposal draft → the creator approves or edits at the Approval node → the reply goes out on Email or Instagram and a Tool (MCP) node files the deal in the tracker.",
        },
      },
      {
        id: "yt-script-research",
        title: "Research while you draft: a script pack in your own voice (parallel, supervisor)",
        scenario:
          "The content calendar is a note on a phone. Research means twenty open tabs that get closed before the script is written, the hook gets rewritten four times, and by the time the outline exists the idea has gone cold. Every video starts from a blank page even though there are eighty past scripts sitting in a folder.",
        triggerLabel: "Webhook / Slack / Telegram (creator drops an idea)",
        steps: [
          { kind: "trigger", title: "Idea goes in", detail: "The creator drops a title or a one-line idea into Slack, Telegram or a Webhook — the note-on-a-phone replacement, but one that actually starts work." },
          { kind: "supervisor", title: "Supervisor plans the pack", detail: "A Supervisor node decides what this idea needs — research depth, whether a past script covers it already, whether it is sponsor-safe — and dispatches the branches." },
          { kind: "parallel", title: "Research and structure at the same time", detail: "A Parallel node runs the researcher and the structure agent concurrently instead of serially: the outline takes shape while sources are still being gathered." },
          { kind: "agent", title: "Researcher gathers, with sources", detail: "The research agent uses web_search and http_request. web_search returns the URL behind every result, so you can instruct the agent to carry those URLs into its answer — that instruction lives in YOUR agent prompt; the platform does not enforce sourcing or flag unsourced claims for you." },
          { kind: "knowledge", title: "Your voice, from your archive", detail: "A Knowledge node grounds the writing in the creator's past scripts, hook patterns and tone guide — so the draft reads like the channel, not like a language model." },
          { kind: "agent", title: "Writer assembles the pack", detail: "The writer produces the pack: three hook options, an outline with beats, B-roll and on-screen-text notes, a title/description draft and chapter markers — all in the creator's register." },
          { kind: "approval", title: "Nothing publishes; you take it or leave it", detail: "An Approval node hands the whole pack to the creator to edit. It is a first draft for a human writer, never a finished script and never published by the workflow." },
          { kind: "output", title: "Pack lands where you work", detail: "The approved pack goes to Email or Telegram and, if wanted, a Tool (MCP) node files it into the creator's own doc/notes system as a draft — still a draft." },
        ],
        connectors: ["Slack", "Telegram", "Webhook", "Knowledge base", "Approval / HITL", "Email", "Tool (MCP → docs/notes)"],
        outcome:
          "An idea becomes a sourced, structured script pack in the creator's own voice — hooks, beats, B-roll notes, title and chapters — that the creator edits rather than starts from scratch.",
        impact:
          "The mechanism is twofold: Parallel removes the serial research-then-write wait, and Knowledge grounding in eighty past scripts means the draft starts inside the creator's voice instead of drifting toward generic. The blank page is what gets removed — not the creator's authorship.",
        build: {
          agents: 3,
          agentsNote: "1 supervisor + 1 researcher + 1 writer. The researcher and writer run concurrently under the Parallel node; the Supervisor decides scope and dispatches.",
          pattern: "Supervisor → Parallel (research ∥ structure) → writer synthesis → creator approves the draft",
          prompt:
            "(Writer) You draft video scripts for {{channel}}. Using the researcher's sourced claims and the past scripts, hook patterns and tone guide in Knowledge, produce a pack: 3 hook options, an outline with beats and rough timings, B-roll / on-screen-text notes, a title and description draft, and chapter markers. Write in the creator's register — mirror the archive, do not smooth it out. Use ONLY sourced claims; if a claim arrived flagged as unsourced, either drop it or mark it clearly [UNVERIFIED — check before recording]. Never invent statistics, quotes, dates or product claims. This is a first draft for the creator to rewrite; never present it as finished and never publish it.",
          agentRoles: [
            { role: "Supervisor", model: "Standard tier — planning and dispatch only, short context", prompt: "Decide what this idea needs: research depth, whether an existing script in Knowledge already covers it (say so instead of duplicating work), and whether the topic is sponsor-sensitive. Dispatch the research and structure branches; do not write the script yourself." },
            { role: "Researcher", model: "Standard tier with web_search — breadth matters more than prose here", prompt: "Gather claims relevant to the idea. Return {claim, source_url, confidence} for each. Anything you cannot source, return with source_url=null and confidence=LOW — flagged, never asserted. No synthesis, no opinions." },
            { role: "Writer", model: "Premium tier — this is the ONE place the reasoning budget belongs, because voice and structure are the whole product. Fallback model configured", prompt: "Assemble the script pack from sourced claims plus the creator's archive. Mirror the archive's voice. Mark anything unverified inline. Draft only." },
          ],
          mcp: [
            "Docs / notes system — via a Tool (MCP) node (no first-party connector)",
            "Slack / Telegram / Email — real first-party creator-facing channels",
          ],
          tools: [
            "knowledge_search (past scripts, hook patterns, tone guide, sponsor-safe topic list)",
            "web_search (sourced research)",
            "http_request (fetch a specific source page)",
            "send_email (deliver the pack)",
          ],
          guardrails: [
            "The agent drafts, the creator writes: the pack lands at an Approval node as a first draft and is never published, uploaded or scheduled by the workflow",
            "The voice stays the creator's — the writer is grounded in the creator's own past scripts and tone guide, and is instructed to mirror the archive rather than normalise it",
            "Every factual claim carries a source; unsourced claims are returned flagged and rendered as [UNVERIFIED — check before recording], never smoothed into the script",
            "No invented statistics, quotes or product claims. If the video is sponsored, the pack carries the required disclosure line in the script AND in the description draft — the disclosure is not optional and is not something the creator has to remember to add",
          ],
          cost:
            "This is the deliberate inverse of the comment workflow: volume is low (a handful of scripts a week), stakes are high, so the premium tier is justified — but only for the writer. The supervisor is a short planning call and the researcher is standard tier with search; putting a premium model on either buys nothing. Parallel improves wall-clock time without changing token spend, and a fallback model keeps a recording day from being blocked by one provider outage.",
          output:
            "The Supervisor scopes the idea → Parallel runs sourced research alongside structuring → the premium-tier writer assembles hooks, outline, B-roll notes, title, description and chapters in the creator's voice → the creator edits and approves at the Approval node → the pack lands on Email/Telegram and, optionally, as a draft in the creator's own docs via a Tool (MCP) node.",
        },
      },
      {
        id: "yt-dub-multilang",
        title: "Dub and caption one video into your other languages (ElevenLabs + Sarvam)",
        scenario:
          "Captions get retyped per language, usually by hand, usually only for one language, usually late. An Indian-language audience that would happily watch gets a machine-auto-caption or nothing at all, and a proper dub means booking a voice artist for a video that has already peaked.",
        triggerLabel: "Webhook (upload/render complete, via the creator's own tooling)",
        steps: [
          { kind: "trigger", title: "The cut is locked", detail: "A Webhook fires from the creator's render or upload tooling with the video reference and transcript. Upload/scheduling tools have no first-party connector — this is the honest inbound path." },
          { kind: "agent", title: "Clean the transcript", detail: "An economy-tier agent tidies the raw transcript into caption-ready segments — timings preserved, filler trimmed, names and channel-specific terms corrected from Knowledge." },
          { kind: "loop", title: "Loop over target languages", detail: "A Loop node runs the localisation pass once per target language, so adding a fifth language is a config change, not a new workflow." },
          { kind: "agent", title: "Translate with Sarvam for Indian languages", detail: "Sarvam is a real first-party provider — Hindi, Tamil, Telugu, Kannada, Bengali, Marathi and more get a proper localisation pass rather than a generic machine translation, with the channel's own glossary applied." },
          { kind: "voice", title: "Dub with ElevenLabs", detail: "A Voice node uses ElevenLabs (first-party) to render the approved translated script as a dub track for the languages the creator has chosen to dub rather than only subtitle." },
          { kind: "approval", title: "You hear it before anyone else does", detail: "An Approval node holds both the caption files and the dub audio. The creator (or a trusted native-speaker reviewer) signs off per language — a bad dub in a language you do not speak is exactly the thing that must never auto-publish." },
          { kind: "output", title: "Files back, still unpublished", detail: "On approval, a Tool (MCP) node attaches the caption tracks and dub audio through the platform API. Publishing remains the creator's action, not the workflow's." },
        ],
        connectors: ["Webhook", "Sarvam (Indian languages)", "ElevenLabs (voice)", "Knowledge base", "Approval / HITL", "Tool (MCP → platform upload API)"],
        outcome:
          "One locked cut produces reviewed caption tracks and, where the creator wants them, ElevenLabs dub tracks across multiple languages — each signed off per language before anything is attached.",
        impact:
          "The mechanism: the per-language cost of localisation collapses from booking a voice artist and hand-retyping captions to a Loop iteration, so languages that were never worth doing individually become worth doing. Sarvam does the Indian-language pass properly instead of a generic translate; ElevenLabs renders the voice — and a human still signs off each language before it exists publicly.",
        build: {
          agents: 2,
          agentsNote: "1 transcript-cleanup agent + 1 localisation agent (runs once per target language via the Loop node). ElevenLabs voice rendering is a Voice node, not an agent.",
          pattern: "Clean → Loop per language (Sarvam localisation → ElevenLabs dub) → per-language human sign-off",
          prompt:
            "(Localisation) Localise ONE video's caption segments into {{target_language}} for {{channel}}. Preserve segment timings exactly. Apply the channel glossary in Knowledge — product names, recurring segment names, the creator's catchphrases and anything explicitly marked do-not-translate stay as written. Localise meaning and register, not word-for-word. If a joke or idiom does not carry, adapt it and mark the segment {adapted: true} so the reviewer sees it. Never add, remove or soften a claim that was in the original — a dub is a translation, not an edit. Output is a draft for human sign-off.",
          agentRoles: [
            { role: "Transcript cleanup", model: "Economy tier — mechanical segmentation and term correction over a long transcript", prompt: "Turn the raw transcript into caption-ready segments with timings preserved. Trim filler, fix channel-specific names from the glossary. Change no meaning." },
            { role: "Localiser (per language)", model: "Standard tier, routed to Sarvam for Indian languages — runs once per language, so the tier is a real multiplier; keep it standard, not premium. Fallback model configured", prompt: "Localise one language's segments with the glossary applied, timings preserved, adapted idioms flagged. Draft only — never final." },
          ],
          mcp: [
            "Platform upload / caption API — via a Tool (MCP) node; no first-party connector",
            "Render / upload tooling — inbound via Webhook",
            "Sarvam and ElevenLabs are first-party providers, not MCP — used directly",
          ],
          tools: [
            "knowledge_search (channel glossary, do-not-translate list, catchphrases, prior approved translations)",
            "http_request (attach approved caption and audio tracks via the platform API)",
          ],
          guardrails: [
            "Nothing is attached or published without the Approval node — caption tracks and dub audio are both held for per-language sign-off, and publishing stays the creator's own action",
            "A language the creator does not speak gets a named human reviewer on the Approval step; the workflow will not release an unreviewed language just because the other four passed",
            "The dub is a translation, not a rewrite: no claim may be added, dropped or softened relative to the original, and adapted idioms are flagged so the reviewer sees exactly what changed",
            "The channel glossary and do-not-translate list are authoritative — product names, segment names and catchphrases are never localised away, so the creator's voice survives the language change",
            "If the video is sponsored, the required disclosure is carried into every language's captions and dub script — a localised version never loses its disclosure",
          ],
          cost:
            "The multiplier here is languages, not items: total cost is roughly (one economy-tier cleanup) + L × (standard-tier localisation + ElevenLabs render). Keep the localiser on the standard tier — premium buys little once the glossary is doing the heavy lifting — and let the creator choose per language whether it gets captions only (cheap) or captions plus a dub. That per-language toggle is the real cost control.",
          output:
            "The cleanup agent produces timed caption segments → the Loop node localises per language via Sarvam with the channel glossary applied → a Voice node renders ElevenLabs dub audio for the chosen languages → each language is signed off individually at the Approval node → a Tool (MCP) node attaches the approved caption and audio tracks through the platform API, leaving publishing to the creator.",
        },
      },
    ],
  },

  // ========================================== REELS, SHORTS & BRAND DEALS
  {
    id: "shortform-influencer",
    name: "Reels, shorts & influencer brand deals",
    icon: Clapperboard,
    tagline:
      "Turn one long video into a shorts queue, keep DMs answered without losing your voice, and track every brand deliverable out of the DM thread — with you approving every post and every yes.",
    recipes: [
      {
        id: "reel-cutdown",
        title: "One long video → a queue of shorts candidates you actually approve",
        scenario:
          "A forty-minute video gets scrubbed through manually, twice, hunting for the moments that would work as a short. Whichever clip the creator happens to remember gets cut; the rest never happen. Captions and hooks for each one are written from scratch, at night, in a note app.",
        triggerLabel: "Webhook (transcript ready from the creator's render/transcription tooling)",
        steps: [
          { kind: "trigger", title: "Transcript lands", detail: "A Webhook from the creator's own render or transcription tooling delivers the timed transcript. Editing and upload tools have no first-party connector — Webhook plus Tool (MCP) is the honest path and is labelled as such." },
          { kind: "loop", title: "Loop over transcript windows", detail: "A Loop node walks the transcript in overlapping windows so a cheap agent scores every part of the video, not just the part someone remembered." },
          { kind: "agent", title: "Score each window as a clip candidate", detail: "An economy-tier agent returns strict JSON per window: start/end timecodes, a self-contained score, the hook line already present in the creator's own words, and why it stands alone. It quotes the transcript — it does not write new dialogue." },
          { kind: "agent", title: "Rank and write the pack", detail: "A ranking agent picks the strongest non-overlapping candidates and drafts, per clip, a caption and 2 hook variants in the creator's voice from the tone guide and past top-performing captions in Knowledge." },
          { kind: "approval", title: "You pick the clips", detail: "An Approval node presents the ranked candidates with timecodes and drafted captions. The creator approves, reorders or rejects — nothing is cut, scheduled or posted automatically." },
          { kind: "output", title: "Approved queue goes to your editor", detail: "The approved list — timecodes, captions, hooks — goes to Telegram, Slack or Email as an editing worklist, and optionally to the creator's own scheduling tool via a Tool (MCP) node as unpublished drafts." },
        ],
        connectors: ["Webhook", "Telegram", "Slack", "Email", "Knowledge base", "Approval / HITL", "Tool (MCP → editing/scheduling tool)"],
        outcome:
          "The whole video is scanned for clip candidates, ranked, and handed over as an approved editing worklist with captions and hooks drafted in the creator's voice.",
        impact:
          "The mechanism: coverage. A human scrub finds the moments they remember; a Loop over the full transcript scores every window at economy-tier cost, so good moments in the middle of a long video stop being invisible. The creator still chooses what gets cut and what gets posted.",
        build: {
          agents: 2,
          agentsNote: "1 window-scoring agent (runs once per transcript window via the Loop node) + 1 ranking/caption agent (runs once). Loop and Approval are workflow nodes.",
          pattern: "Map-reduce over transcript windows: Loop (score) → rank + draft (reduce) → creator picks",
          prompt:
            "(Scorer) Assess ONE transcript window from {{channel}} as a standalone short. Return strict JSON {start, end, standalone_score (0-100), hook_line (VERBATIM from the transcript), topic, needs_context (bool), reason}. hook_line MUST be words the creator actually said — quote, never write new dialogue. Score low if the window only makes sense with the preceding twenty minutes. Do not cut, schedule or publish anything; you are proposing candidates for the creator to choose from.",
          agentRoles: [
            { role: "Window scorer (per window)", model: "Economy tier — it runs across the entire transcript of every video, so it is the dominant cost line and belongs nowhere near a premium model", prompt: "Score ONE window into schema-valid JSON with verbatim hook lines and honest standalone scoring. No new dialogue, no cutting, no posting." },
            { role: "Ranker + caption drafter (once)", model: "Standard tier — it writes the captions, so it needs the better model, but it only runs once per video", prompt: "Pick the strongest non-overlapping candidates from the scored windows. For each, draft a caption and 2 hook variants using ONLY the tone guide and past top-performing captions in Knowledge. Match the creator's voice; never invent a claim, a statistic or a product mention. Everything is a draft for approval." },
          ],
          mcp: [
            "Editing / scheduling tool — via a Tool (MCP) node; no first-party connector",
            "Render / transcription tooling — inbound via Webhook",
            "Telegram / Slack / Email — real first-party delivery channels",
          ],
          tools: [
            "knowledge_search (tone guide, past captions that performed, banned-phrase list)",
            "http_request (write approved clips as unpublished drafts into the creator's own scheduler)",
            "user-defined tool clip_gate (SpEL): input.standaloneScore >= input.minScore ? (input.needsContext ? 'NEEDS_INTRO' : 'READY') : 'SKIP' — a tenant-authored SpEL expression over the tool's JSON input; sandboxed by construction (no method calls, no constructors, no bean or type references, no loop in the grammar). A data-transform formula, not a scripting engine. The creator sets their own bar for what is worth cutting once, and every video from then on is filtered against it identically",
          ],
          guardrails: [
            "The agent proposes clips, the creator picks: nothing is cut, scheduled or published without the Approval node, and anything written into the scheduler goes in as an UNPUBLISHED draft",
            "Hook lines are verbatim transcript quotes — the agent never writes dialogue the creator did not say, so a clip cannot misrepresent them",
            "Captions are grounded in the creator's own tone guide and past captions, with a banned-phrase list applied; no invented statistics, claims or product mentions",
            "If the source video is sponsored, every derived short inherits the required disclosure in its caption — a cut-down never quietly drops the disclosure the original carried",
            "Idempotent per video id and window, so re-running a transcript cannot duplicate candidates in the queue",
          ],
          cost:
            "Cost is W × (economy-tier scorer) + ONE standard-tier rank-and-caption pass, where W is the number of transcript windows — long videos cost proportionally more, which is exactly right. The scorer must stay economy tier; that is the whole cost story. Run the windows in Parallel batches for speed, and use the clip_gate SpEL filter to drop weak candidates BEFORE the standard-tier captioning pass ever sees them.",
          output:
            "Loop scores every transcript window cheaply → clip_gate filters against the creator's own bar → the ranking agent picks non-overlapping winners and drafts captions and hooks in the creator's voice → the creator approves, reorders or rejects at the Approval node → the approved worklist goes to Telegram/Slack/Email and, optionally, into the creator's scheduler as unpublished drafts.",
        },
      },
      {
        id: "reel-dm-triage",
        title: "First-line community mod across Instagram, Telegram and Discord DMs",
        scenario:
          "DMs arrive across Instagram, Telegram and Discord at once. The same five questions ('what mic', 'is the course still open', 'link is broken') get answered over and over, a genuine collab offer sits unread underneath them, and by the time the creator opens the app there are four hundred unread and the honest answer is that most will never be read.",
        triggerLabel: "Instagram / Telegram / Discord / Messenger (inbound DM)",
        steps: [
          { kind: "trigger", title: "A DM lands on any channel", detail: "Instagram, Telegram, Discord and Messenger are all real first-party connectors — one workflow serves all four inboxes instead of four separate habits." },
          { kind: "agent", title: "Classify and draft from the FAQ", detail: "An economy-tier agent buckets the message (FAQ / BUSINESS / SUPPORT / FAN / ABUSE) and, for FAQ and SUPPORT, drafts a reply grounded strictly in the creator's saved FAQ and tone guide." },
          { kind: "condition", title: "Does this commit anything?", detail: "A Condition node checks whether the draft would commit the creator to anything — a price, a collab, a deadline, an appearance, a discount. If yes, it can never go out on the auto path." },
          { kind: "approval", title: "Anything that commits you, you approve", detail: "An Approval node holds every BUSINESS reply and every draft that touches money, dates or promises. Pure FAQ answers can be batched into a single approve-all review; commitments are always individual." },
          { kind: "output", title: "Reply on the channel it came in on", detail: "An Output node replies on the same connector — Instagram DMs get Instagram replies, Discord gets Discord — so the conversation stays where the person started it." },
          { kind: "loop", title: "Clear the existing backlog too", detail: "A Loop node runs the same triage over the accumulated unread backlog in batches, so the four hundred unread get handled by the same rules rather than being written off." },
        ],
        connectors: ["Instagram", "Telegram", "Discord", "Messenger", "Knowledge base", "Approval / HITL"],
        outcome:
          "Every DM across four platforms is bucketed and answered from the creator's own FAQ in their own voice, with anything that commits the creator held for their approval — and the historic backlog cleared by the same rules.",
        impact:
          "The mechanism: the repeat questions, which are the overwhelming majority, get the answer the creator already wrote, once, consistently, on the channel they were asked on. That is what makes the genuine collab offer visible instead of buried. The creator's exposure is unchanged, because nothing that commits them can send itself.",
        build: {
          agents: 2,
          agentsNote: "1 triage/draft agent (per message, and reused by the Loop node for backlog batches) + 1 escalation summariser for the BUSINESS bucket. The commit-check is a Condition node plus a SpEL tool, not an agent.",
          pattern: "Per-message triage → deterministic commit-check → auto-path (FAQ, batch-approved) vs escalation path (individually approved)",
          prompt:
            "You are the first-line community assistant for {{creator}} across Instagram, Telegram, Discord and Messenger. Bucket ONE message as FAQ|BUSINESS|SUPPORT|FAN|ABUSE and, for FAQ or SUPPORT only, draft a reply using ONLY the saved FAQ, link list and tone guide in Knowledge. Match the creator's voice — short, their register, no corporate softening. If the answer is not in Knowledge, set draft=null and escalate; do NOT improvise. Never quote a price, agree to a collab, commit to a date, promise a feature or upload, or offer a discount — route anything like that to BUSINESS and stop. You draft; the creator sends.",
          agentRoles: [
            { role: "DM triage + draft", model: "Economy tier — DM volume is the largest message count a creator handles, so this is the dominant cost lever. Fallback model configured so an outage does not silently stall the inbox", prompt: "Bucket ONE DM and draft only from the saved FAQ and tone guide. Null instead of improvising. Never commit the creator to anything. Never send." },
            { role: "Escalation summariser (BUSINESS bucket)", model: "Standard tier — low volume, higher stakes", prompt: "Summarise the escalated business/collab DMs into one short list for the creator: who, what they want, what they are offering, what is missing. Quote the sender; add nothing. Propose no reply." },
          ],
          mcp: [
            "None required — Instagram, Telegram, Discord and Messenger are all first-party connectors and are used directly",
            "Optional: the creator's CRM or deal tracker via a Tool (MCP) node for escalated business DMs",
          ],
          tools: [
            "knowledge_search (FAQ, current link list, tone guide, do-not-answer topics)",
            "user-defined tool commit_check (SpEL): input.bucket == 'BUSINESS' ? 'HOLD' : (input.mentionsPrice || input.mentionsDate || input.mentionsPromise ? 'HOLD' : 'AUTO_DRAFT') — a tenant-authored SpEL expression over the tool's JSON input, sandboxed by construction (no method calls, no constructors, no bean or type references, no loop in the grammar): a data-transform formula, not a scripting engine. The creator defines once what counts as a commitment, and every DM on every platform is checked against the same rule — the safety line is deterministic rather than something a model has to remember",
          ],
          guardrails: [
            "The agent drafts, the creator sends — no reply reaches any DM inbox without passing the Approval node; FAQ answers may be reviewed as a batch, but anything the commit_check holds is approved individually",
            "The agent may never quote a price, agree to a collab, commit to a date, promise an upload or offer a discount — that class of message is routed to BUSINESS and stops there",
            "Replies are grounded strictly in the creator's saved FAQ, link list and tone guide. If the answer is not in Knowledge the agent escalates instead of improvising, so the creator's voice is never simulated beyond what they have actually said",
            "ABUSE-bucketed messages are flagged to the creator only — the agent never blocks, reports or replies to them on its own",
            "Any reply about a sponsored product or affiliate link carries the required disclosure, and the agent never makes a claim about a sponsor's product that is not in the brand-approved copy in Knowledge",
          ],
          cost:
            "This is the highest-volume workflow a creator has, and the design is built around that: economy tier on triage with a hard per-run token cap and a fallback model, standard tier reserved for the small BUSINESS escalation summary. The commit_check SpEL tool costs nothing to run and removes the need for a bigger model to be trusted with the safety decision — a genuine cost AND safety win. Backlog clearing runs through the same Loop in Parallel batches, so cost stays linear per message.",
          output:
            "Each DM is bucketed and drafted on the economy tier → commit_check (the creator's own SpEL rule) decides auto-draft vs hold → FAQ replies are batch-approved and business DMs individually approved at the Approval node → replies go back out on the exact connector the message arrived on, and the Loop node clears the historic backlog under identical rules.",
        },
      },
      {
        id: "reel-deal-deliverables",
        title: "Get brand deliverables out of the DM thread and onto a tracked checklist",
        scenario:
          "The brand deal lives in a DM thread: three posts, one story, usage rights for ninety days, a hashtag that must appear, a deadline mentioned once in the middle of a voice note. There is no checklist. The creator remembers most of it, misses the story, and finds out at invoice time — which is also when they realise the payment link was never sent.",
        triggerLabel: "Email / Instagram / WhatsApp (signed brief or confirmed deal thread)",
        steps: [
          { kind: "trigger", title: "The deal is confirmed", detail: "The confirmed brief arrives by Email, Instagram DM or WhatsApp — the same places it actually arrives today, all first-party connectors." },
          { kind: "agent", title: "Extract the deliverables", detail: "An Agent node turns the thread into a structured deliverable list: each asset, its platform, its due date, required hashtags and mentions, usage-rights window, exclusivity terms and the agreed fee. Anything not explicitly stated comes back null and gets asked about — never assumed." },
          { kind: "approval", title: "You confirm the list is right", detail: "An Approval node puts the extracted list in front of the creator before it becomes the source of truth. A misread deadline is worse than no tracker, so the creator confirms the extraction itself." },
          { kind: "knowledge", title: "Brand-approved copy only", detail: "A Knowledge node holds the brand's own brief and approved claims alongside the creator's tone guide — so any drafted caption uses the brand's language for the product and the creator's language for everything else." },
          { kind: "condition", title: "Deadline approaching or asset missing?", detail: "A Condition node checks the tracked list on a schedule and branches on what is genuinely outstanding." },
          { kind: "output", title: "Nudge the creator, not the audience", detail: "An Output node sends a short WhatsApp or Telegram nudge listing exactly what is still outstanding and by when — the reminder goes to the creator, never to the brand on their behalf." },
          { kind: "tool", title: "Payment follow-up, on approval", detail: "Once deliverables are marked complete, a Tool (MCP) node can draft the invoice follow-up and, where the creator uses it, generate a Razorpay UPI payment link — sent only after the creator approves." },
        ],
        connectors: ["Email", "Instagram", "WhatsApp", "Telegram", "Knowledge base", "Approval / HITL", "Razorpay (UPI)", "Tool (MCP → tracker)"],
        outcome:
          "A DM thread becomes an explicit, creator-confirmed deliverable checklist with deadline nudges and an approved payment follow-up — instead of a set of obligations held in the creator's memory.",
        impact:
          "The mechanism: the terms are extracted once, confirmed by the creator, and then checked on a schedule rather than recalled under pressure. Missed story slots and forgotten hashtags are misses of memory, not of effort — a tracked list with a nudge is the specific fix, and the money conversation still only happens when the creator approves it.",
        build: {
          agents: 2,
          agentsNote: "1 deliverable-extraction agent (once per deal) + 1 caption drafter used per asset. The deadline checking is a scheduled Condition node; the payment link is a Tool node behind an Approval.",
          pattern: "Extract → creator confirms the extraction → scheduled deadline checks → per-asset drafts, each approved",
          prompt:
            "You track brand-deal deliverables for {{creator}}. From a confirmed brief or deal thread, extract strict JSON {brand, assets[{type, platform, due_date, required_hashtags[], required_mentions[]}], usage_rights_days, exclusivity_months, fee, currency, disclosure_requirement}. Use null for anything not explicitly stated in the thread — NEVER infer a deadline, a fee, a usage window or an exclusivity term, and never resolve a vague phrase into a specific date. List anything ambiguous under {to_confirm[]} so the creator resolves it. You do not agree to anything and you do not message the brand.",
          agentRoles: [
            { role: "Deliverable extractor", model: "Standard tier — one run per deal, and a misread term is expensive, so this is not the place to save tokens", prompt: "Extract the deliverable schema from the thread. Nulls, not inferences. Flag every ambiguity under to_confirm[]. Agree to nothing." },
            { role: "Asset caption drafter (per asset)", model: "Standard tier — low volume, and it must hold two voices at once. Fallback model configured", prompt: "Draft ONE asset's caption using the creator's tone guide for the creator's own words and the brand's approved copy in Knowledge for anything about the product. Include every required hashtag, mention and the required disclosure. Never write a product claim that is not in the brand's approved copy. Draft only." },
          ],
          mcp: [
            "Deal tracker / sheet — via a Tool (MCP) node (no first-party connector)",
            "Email, Instagram, WhatsApp, Telegram — real first-party channels, used directly",
            "Razorpay (UPI) — first-party, for the approved payment link only",
          ],
          tools: [
            "knowledge_search (brand brief and approved claims, creator tone guide, disclosure requirements, rate card)",
            "http_request (read and update the deliverable tracker)",
            "send_email (approved invoice follow-up)",
            "user-defined tool deliverable_gate (SpEL): input.daysToDue <= input.nudgeWindow ? (input.assetsOutstanding > 0 ? 'NUDGE' : 'CLEAR') : 'WAIT' — a tenant-authored SpEL expression over the tool's JSON input; sandboxed by construction (no method calls, no constructors, no bean or type references, no loop in the grammar). A data-transform formula, not a scripting engine. The creator sets their own nudge window once and every deal thereafter is checked against the same rule",
          ],
          guardrails: [
            "The agent drafts, the creator approves — the extracted deliverable list is itself confirmed by the creator before it becomes the tracker, and every asset caption passes the Approval node before it is posted",
            "No commitment is ever made on the creator's behalf: the agent does not message the brand, does not agree a fee, a deadline, a usage window or an exclusivity term, and does not renegotiate",
            "Nothing is inferred — an unstated deadline or fee comes back null and lands in to_confirm[], because a confidently wrong date is worse than an acknowledged gap",
            "Two voices, kept separate: the creator's tone guide governs the creator's own words; anything asserted about the sponsor's product must come verbatim from the brand's approved copy in Knowledge. The agent never invents a product claim",
            "Required disclosure is inserted into every sponsored draft and is not removable by the drafting agent — an asset that loses its disclosure cannot reach the Approval node",
            "Reminders go to the creator only. The Razorpay UPI link and any invoice follow-up are generated but never sent until the creator approves",
          ],
          cost:
            "Volume is low and stakes are high, so this workflow inverts the usual advice: standard tier on both agents, no economy shortcut on extraction. The scheduled deadline check costs almost nothing because deliverable_gate is a SpEL formula, not a model call — the tracker can be checked daily for free and only wakes an agent when there is genuinely something to draft. A fallback model protects a deadline day from a provider outage.",
          output:
            "The extractor turns the thread into a deliverable schema with ambiguities flagged → the creator confirms the list at an Approval node, making it the source of truth → deliverable_gate checks it on a schedule for free and fires a WhatsApp or Telegram nudge to the creator when something is outstanding → per-asset captions are drafted in the creator's voice with brand-approved product copy and mandatory disclosure, each approved before posting → on completion, an approved invoice follow-up and optional Razorpay UPI link go out.",
        },
      },
      {
        id: "reel-weekly-plan",
        title: "Weekly slate: turn the note-app idea list into a planned, sourced calendar",
        scenario:
          "The content calendar is a list in Notes with eleven half-ideas, three of which are the same idea written twice. Planning happens on Sunday night, or it does not happen, and the week gets filled with whatever is easiest to shoot on the day. Ideas that needed a prop, a location or a week of lead time never make it.",
        triggerLabel: "Webhook / Telegram / Slack (weekly schedule, or creator drops ideas any time)",
        steps: [
          { kind: "trigger", title: "Ideas in, weekly plan due", detail: "Ideas arrive whenever the creator has them, via Telegram, Slack or a Webhook; a scheduled trigger runs the planning pass once a week." },
          { kind: "supervisor", title: "Supervisor scopes the week", detail: "A Supervisor node reads the idea pool plus the tracked brand-deal deadlines and decides how the week should be split — sponsored obligations first, then the creator's own slate." },
          { kind: "parallel", title: "Dedupe and prep-check at once", detail: "A Parallel node runs two branches concurrently: one clusters duplicate and near-duplicate ideas, the other checks each idea for what it actually needs — prop, location, guest, lead time — from the creator's own production notes in Knowledge." },
          { kind: "agent", title: "Build the slate", detail: "A planner agent assembles a realistic week: which ideas are shoot-ready now, which need lead time and should be scheduled later, which are duplicates already covered by a past post, and where the sponsored deliverables must sit to hit their deadlines." },
          { kind: "approval", title: "The plan is a proposal", detail: "An Approval node hands the slate to the creator to accept, reorder or throw out. The workflow schedules nothing and posts nothing — it produces a plan, and the creator owns the week." },
          { kind: "output", title: "Plan where you will see it", detail: "The approved slate goes to Telegram or Slack as the week's working list, and optionally into the creator's own planning tool as drafts via a Tool (MCP) node." },
        ],
        connectors: ["Telegram", "Slack", "Webhook", "Knowledge base", "Approval / HITL", "Tool (MCP → planning tool)"],
        outcome:
          "The idea list becomes a deduplicated, prep-aware weekly slate with sponsored deadlines already placed — proposed to the creator, never imposed on them.",
        impact:
          "The mechanism: ideas are checked against what they actually require and against what has already been posted, before the week is filled. Lead-time ideas get scheduled where they can happen instead of being repeatedly deferred, and a sponsored deadline is placed by the plan rather than remembered on the day.",
        build: {
          agents: 3,
          agentsNote: "1 supervisor + 1 dedupe/archive-check agent + 1 planner. The dedupe and prep-check branches run concurrently under the Parallel node.",
          pattern: "Supervisor → Parallel (dedupe ∥ prep-check) → planner synthesis → creator approves the slate",
          prompt:
            "(Planner) Build a proposed weekly content slate for {{creator}}. Inputs: the deduplicated idea pool with prep requirements, the archive of what has already been posted, and the tracked brand-deal deliverables with their due dates. Place sponsored deliverables first so their deadlines are met, then fill with the creator's own ideas that are genuinely shoot-ready this week; push lead-time ideas to a later week and say what they are waiting on. Never claim an idea will perform well — you have no data for that and must not pretend to. Never schedule or publish anything: output a proposal the creator will edit.",
          agentRoles: [
            { role: "Supervisor", model: "Standard tier — short planning and dispatch context", prompt: "Read the idea pool and the tracked deliverables; decide how the week splits between sponsored obligations and the creator's own slate; dispatch the dedupe and prep-check branches. Do not build the slate yourself." },
            { role: "Dedupe + archive check", model: "Economy tier — mechanical similarity work across the idea pool and the past-post archive", prompt: "Cluster near-duplicate ideas and flag any idea already covered by a past post, citing the post. Do not judge quality." },
            { role: "Planner", model: "Standard tier — one run per week; the reasoning is scheduling, not prose. Fallback model configured", prompt: "Assemble the proposed slate with sponsored deadlines placed first, shoot-ready ideas next and lead-time ideas deferred with their blocker named. Make no performance predictions. Propose only." },
          ],
          mcp: [
            "Planning / calendar tool — via a Tool (MCP) node; no first-party connector",
            "Analytics or archive export — inbound via Webhook and/or a Tool (MCP) node, since platform analytics APIs have no first-party connector",
            "Telegram / Slack — real first-party channels for idea capture and plan delivery",
          ],
          tools: [
            "knowledge_search (production notes, prop/location/guest requirements, past-post archive, brand-deal deliverables)",
            "http_request (read the archive; write approved slate items as drafts into the planning tool)",
          ],
          guardrails: [
            "The workflow proposes a plan and nothing else — no idea is scheduled, uploaded or published, and anything written into a planning tool goes in as an unpublished draft after the Approval node",
            "No performance predictions: the agent must not claim an idea will do well, since it has no basis for that. It reports prep requirements, duplicates and deadlines — facts, not forecasts",
            "Duplicate flags cite the specific past post, so the creator can disagree with the call rather than take it on trust",
            "Sponsored deliverables are placed from the tracked deal terms only; the agent never invents or shifts a brand deadline, and any conflict between two deals is surfaced to the creator rather than resolved silently",
            "The slate stays the creator's: the agent proposes ordering and timing but never removes an idea from the pool, and rejected proposals do not train it to stop suggesting a topic the creator cares about",
          ],
          cost:
            "One run a week over a small idea pool, so total spend is negligible and the design can afford to be thorough. The only item with any real multiplier is the dedupe pass across the past-post archive — keep that on the economy tier since it is similarity work, and let the standard-tier planner run once. Parallel shortens wall-clock time without changing spend.",
          output:
            "The Supervisor scopes the week against tracked deliverables → Parallel runs dedupe/archive-check alongside prep-requirement checking → the planner proposes a slate with sponsored deadlines placed and lead-time ideas deferred with named blockers → the creator accepts, reorders or rejects at the Approval node → the approved slate lands on Telegram or Slack and, optionally, as drafts in the creator's own planning tool.",
        },
      },
    ],
  },
];
