import type { Industry } from "./recipes-data";
import { Globe, ClipboardList } from "lucide-react";

// ---------------------------------------------------------------------------
// Web research / scraping / price-monitoring & web-form-filling / back-office
// data-entry recipes — same "inline copy, no i18n keys" precedent and honesty
// rules as recipes-data.tsx. Every recipe is expressed ONLY in VegaDūta's real
// building blocks (Trigger / Agent / Knowledge / Condition / Approval / Output
// / Tool(MCP) / Voice / Device / Loop / Code / Parallel / Supervisor /
// Sub-workflow), so each maps 1:1 to something you can actually build in the
// Workflow designer.
//
// Capability honesty — read this before believing any step below:
//   • `web_search` and `http_request` ARE real, first-party built-in agent
//     tools. Fetching a page, hitting a JSON endpoint, running a search — that
//     is native.
//   • Browser automation is NOT first-party. VegaDūta ships no built-in
//     browser. Clicking, typing into a field, navigating a real rendered page
//     is done by registering a browser-automation MCP server (a Playwright-
//     style MCP) in the platform's MCP registry and calling it from a
//     Tool (MCP) node. Wherever a recipe below "fills a form", that is the
//     mechanism, and it is labelled every single time.
//   • The Loop node is the natural fit for "for each row / for each URL".
//     Parallel fans out independent fetches. Code shapes the payload.
//
// Legal/ethical posture — this is the credibility of the whole pack, so it is
// repeated in the guardrails of EVERY recipe rather than stated once here:
// robots.txt and Terms of Service are respected; requests are rate-limited,
// backed off and honestly identified; public data only — nothing behind a
// login you don't own, no bot-detection bypass, and CAPTCHAs are NEVER solved;
// no personal-data harvesting or profile-building; no agent ever types a
// password, card number or government ID into a form; and every irreversible
// submit is held by an Approval node — the agent prepares the draft, a human
// clicks submit.
//
// Impact lines describe the mechanism (why it helps), never invented ROI
// numbers, and no agent count is inflated.
// ---------------------------------------------------------------------------

export const INDUSTRIES_WEBDATA: Industry[] = [
  // ============================ WEB RESEARCH, SCRAPING & PRICE MONITORING
  {
    id: "web-monitoring",
    name: "Web research, scraping & price monitoring",
    icon: Globe,
    tagline:
      "Turn the Monday-morning copy-paste-into-Excel ritual into a Loop over URLs — economy-tier extraction per page, a premium model only on the genuinely ambiguous record, and robots.txt/ToS respected on every fetch.",
    recipes: [
      {
        id: "web-price-watch",
        title: "Competitor price & stock watch (Loop over URLs, cost-tiered extraction)",
        scenario:
          "Every Monday an analyst opens the same 60 competitor product pages one by one, copies the price and the in-stock flag into a spreadsheet, colours the cells that moved, and emails the sheet to category managers. By Wednesday the sheet is already stale, and half the 'price changes' are actually the same price shown with a different currency format.",
        triggerLabel: "Webhook (scheduled — weekly/daily price sweep)",
        steps: [
          { kind: "trigger", title: "The sweep kicks off", detail: "A scheduled Webhook trigger starts the run with the watchlist reference. The watchlist itself (SKU → competitor URL) lives in your own system and is read via a Tool (MCP) node or posted in on the Webhook — there is no first-party 'price feed' connector." },
          { kind: "loop", title: "For each watched URL", detail: "A Loop node iterates the watchlist one URL at a time. This is the node that makes 60 URLs behave exactly like 600 — same flow, same per-item cost, no unbounded prompt." },
          { kind: "parallel", title: "Fan out the fetches", detail: "Inside the loop batch, a Parallel node fires independent `http_request` fetches concurrently — with a deliberate concurrency cap and delay so the sweep stays polite rather than hammering one host." },
          { kind: "agent", title: "Economy extractor reads the page", detail: "A cheap extractor agent turns one page's fetched HTML into strict JSON {price, currency, inStock, title, capturedAt}. It runs once per URL, so its model tier is the dominant cost lever in the entire workflow." },
          { kind: "code", title: "Normalise + diff", detail: "A Code node shapes the payload — joins each extraction back to last week's stored value and computes the delta — then a user-defined SpEL tool flags the ones that matter (see tools below)." },
          { kind: "condition", title: "Ambiguous record?", detail: "A Condition node routes: a clean, confidently-parsed page goes straight to the report; a page where the extractor was unsure (bundle pricing, strike-through, missing currency) escalates to the premium arbiter — and only those." },
          { kind: "agent", title: "Premium arbiter, only on the hard ones", detail: "A stronger model resolves just the ambiguous handful: which of the three numbers on the page is the actual sell price, is that 'out of stock' or 'ships in 3 weeks'." },
          { kind: "approval", title: "Repricing is human-owned", detail: "An Approval node holds any proposed repricing action. The sweep is read-only by design; the moment its output would change YOUR price, a category manager signs it off." },
          { kind: "output", title: "Deliver the movement report", detail: "An Output node posts the changed-only digest to Slack or Email, with per-SKU old → new, the flag reason, and the source URL and capture timestamp for every single number." },
        ],
        connectors: ["Webhook", "Slack", "Email", "Tool (MCP → your catalogue / watchlist store)", "Approval / HITL"],
        outcome:
          "The whole watchlist is swept on a schedule and lands as a changed-only report with a source URL and timestamp on every figure — instead of a hand-built sheet that is stale before it is read.",
        impact:
          "The mechanism is substitution plus attribution: a Loop does the mechanical page-by-page reading the analyst was doing, and every number carries its source URL and capture time, so a disputed price is checkable rather than arguable. The analyst's remaining work is judging the flagged movements, not producing the sheet.",
        build: {
          agents: 2,
          agentsNote:
            "1 economy extractor (runs once per URL via the Loop node) + 1 premium arbiter (runs only on the Condition node's ambiguous branch). Loop, Parallel, Code, Condition and Approval are workflow nodes, not agents.",
          pattern: "Loop (per URL) → economy extract → Code/SpEL diff → Condition escalates only ambiguous records → premium arbiter → human approves any repricing",
          prompt:
            "You extract ONE product page into strict JSON: {price (number|null), currency (ISO code|null), inStock (true|false|null), title, promoText|null, confidence (0-1), ambiguityReason|null}. Read ONLY the content supplied to you — never guess a price, never carry a value over from a previous page, and never fill a field from general knowledge about the brand. If the page shows several numbers (was/now, bundle, EMI, per-unit) and you cannot be certain which is the single sell price, do NOT pick one: set confidence below 0.6 and describe the ambiguity — the workflow escalates it. If the fetch returned a block page, a consent wall or a login wall, return {blocked: true} and stop; do not attempt to work around it.",
          agentRoles: [
            {
              role: "Page extractor (per URL)",
              model: "Economy tier — this is the volume step; it runs once per watched URL, so this single choice sets the cost of the whole sweep",
              prompt: "Turn one fetched page into schema-valid JSON. Nulls for anything not visibly present. Self-report low confidence rather than guessing between two numbers. Never work around a block/consent/login wall — report it.",
            },
            {
              role: "Ambiguity arbiter (only the flagged minority)",
              model: "Premium tier — justified precisely because it runs on a handful of records, not the whole list",
              prompt: "Given one low-confidence extraction plus its page context, decide the single actual sell price and true availability, and explain the reasoning in one line. If it is still genuinely undecidable, return NEEDS_HUMAN — an honest escalation beats a confident wrong price.",
            },
          ],
          mcp: [
            "Your catalogue / watchlist store (SKU ↔ competitor URL, last-known price) — via a Tool (MCP) node; no first-party connector",
            "Browser-automation MCP (Playwright-style, registered in the MCP registry) — OPTIONAL, only for pages that will not render without JS. VegaDūta ships no built-in browser; this is the mechanism when a plain fetch is not enough",
            "Slack / Email — the digest channel",
          ],
          tools: [
            "http_request (built-in) — the primary fetch, rate-limited and honestly identified",
            "web_search (built-in) — to discover a moved/renamed product page rather than silently reporting it as delisted",
            "User-defined tool `price_drop_flag` (SpEL): input.newPrice < input.oldPrice * 0.9 ? 'ALERT' : 'IGNORE' — the category manager encodes THEIR OWN threshold (10% here) and can change it to 5% without a developer or a redeploy",
            "User-defined tool `stock_state` (SpEL): input.qty > 0 ? 'IN_STOCK' : (input.qty == 0 ? 'OUT_OF_STOCK' : 'UNKNOWN') — a field normaliser that makes three sites' three conventions comparable",
            "User-defined tool `tax_normalised_price` (SpEL): input.taxIncluded ? input.price : input.price * 1.18 — compares like with like across sites that quote pre- and post-tax",
          ],
          guardrails: [
            "robots.txt and the site's Terms of Service are respected — the watchlist only contains pages you are permitted to fetch, and a disallowed path is dropped from the sweep rather than fetched anyway",
            "Rate-limited and backed off: a capped concurrency in the Parallel node, a delay between requests to the same host, exponential backoff on 429/5xx, and an honest identifying user agent — the sweep must never be indistinguishable from an attack",
            "Public data only. No page behind a login you do not own, no bot-detection bypass, and CAPTCHAs are NEVER solved — a block page, consent wall or login wall ends that item as {blocked: true} and is reported, not worked around",
            "No personal data harvesting: product/price fields only. Seller names, reviewer names and any other individual's details are dropped at the Code node — this workflow never builds a profile of a person",
            "No credentials, ever: no agent types a password, card number or government ID anywhere in this flow. There is nothing to log into here; if a source ever requires auth it is handled by a secrets-managed integration under your own account, not by an agent typing into a box",
            "Read-only by construction. This sweep submits no form and clicks no irreversible control; any proposed repricing — the one action with consequences — is held by the Approval node for a category manager",
            "Every reported figure carries its source URL and capture timestamp; an extraction below the confidence floor is escalated or marked NEEDS_HUMAN, never quietly published as fact",
            "The SpEL user-defined tools are data-transform expressions over the tool's JSON input — no method calls, no constructors, no bean or type references, and no loop in the grammar. A threshold the analyst wrote themselves therefore cannot become an execution risk; it can only compute a value",
          ],
          cost:
            "This is the clearest cost-tiering story in the guide. Cost = N × (economy extractor) + a small k × (premium arbiter), where k is the ambiguous minority, not N. Put the premium model on the per-page step and a 600-URL sweep is expensive for no benefit — the per-page job is mechanical field-lifting that an economy model does well. Keep the premium tier for the genuinely undecidable record (three prices on one page, EMI vs sell price) and you pay for reasoning exactly where reasoning exists. Cap the extractor's output tokens (the schema is small), run the Parallel batch for wall-clock speed — which changes latency, not per-item cost — and set a hard per-run budget so a site redesign that breaks parsing fails loudly instead of expensively.",
          output:
            "The Loop maps the economy extractor across the watchlist → a Code node joins to last week's values and the SpEL flags fire → the Condition node escalates only the ambiguous records to the premium arbiter → any repricing proposal waits at the Approval node → an Output node posts the changed-only digest to Slack or Email with old → new, flag reason, source URL and capture timestamp per SKU.",
        },
      },
      {
        id: "web-tender-watch",
        title: "Tender / RFP portal watch with a relevance gate",
        scenario:
          "Someone on the bids team opens the same four public tender portals every morning, scans yesterday's postings, and forwards anything that looks relevant to the sales lead. Half the days there is nothing, so the habit slips; the day it slips is the day a fitting tender closes.",
        triggerLabel: "Webhook (scheduled — daily portal sweep)",
        steps: [
          { kind: "trigger", title: "Daily sweep starts", detail: "A scheduled Webhook trigger fires each morning with the list of public portals and search terms to cover." },
          { kind: "loop", title: "For each portal / search term", detail: "A Loop node walks the configured sources one at a time, keeping each fetch small, isolated and independently retryable." },
          { kind: "agent", title: "Economy screener reads each listing", detail: "A cheap screener agent turns each listing into strict JSON {reference, title, buyer, value, closingDate, url} and gives a first-pass relevance verdict against your capability profile." },
          { kind: "knowledge", title: "Ground relevance in your own profile", detail: "A Knowledge node supplies your capability statement, eligibility criteria and past-performance categories, so 'relevant' means relevant to YOU rather than a generic keyword match." },
          { kind: "condition", title: "Above the relevance bar?", detail: "A Condition node drops the clearly-irrelevant listings and passes only the shortlist forward — this is what keeps the daily digest readable enough to actually be read." },
          { kind: "agent", title: "Standard-tier summariser on the shortlist", detail: "A stronger model summarises only the shortlisted tenders: scope in two lines, eligibility flags, closing date, and what would need to be assembled to bid." },
          { kind: "approval", title: "Bid/no-bid stays human", detail: "An Approval node holds the go/no-go. The workflow never registers interest, downloads a gated document set, or submits anything — a human decides and acts." },
          { kind: "output", title: "Morning digest", detail: "An Output node sends the shortlist to Slack or Email; a genuinely empty day says so explicitly, which is itself the signal that the watch ran." },
        ],
        connectors: ["Webhook", "Slack", "Email", "Knowledge base", "Approval / HITL"],
        outcome:
          "A dated, source-linked shortlist arrives every morning whether or not anyone remembers to look, and an empty day is reported as an empty day rather than as silence.",
        impact:
          "The mechanism is consistency plus grounding: the sweep does not skip the quiet Tuesday that the human habit skips, and relevance is judged against your own capability profile in Knowledge instead of a keyword the portal happens to use. Bid/no-bid remains a human call.",
        build: {
          agents: 2,
          agentsNote: "1 economy screener (once per listing, via the Loop node) + 1 standard-tier summariser (only on the shortlist). Knowledge, Condition and Approval are workflow nodes.",
          pattern: "Loop (per source) → economy screen → Knowledge-grounded relevance gate → standard-tier summary of the shortlist only → human bid/no-bid",
          prompt:
            "(Screener) Read ONE public tender listing and return strict JSON {reference, title, buyer, estimatedValue|null, closingDate|null, url, relevant (true|false), relevanceReason}. Judge relevance ONLY against the capability profile and eligibility criteria supplied from Knowledge — not against your own assumptions about the industry. Never invent a closing date, a value or a reference number; null is always the correct answer for a field the listing does not show. If the listing is behind a login or a document paywall, return {gated: true} with the public URL and stop — do not attempt access.",
          agentRoles: [
            { role: "Listing screener (per listing)", model: "Economy tier — runs across every posting on every portal every day; the volume step", prompt: "One listing → schema-valid JSON plus a relevant/not verdict with a one-line reason grounded in the supplied capability profile. Nulls, not guesses. Gated content is reported, never accessed." },
            { role: "Shortlist summariser", model: "Standard tier — runs only on what survives the relevance gate, typically a handful a day", prompt: "For each shortlisted tender, write a two-line scope summary, list the eligibility flags that need checking, state the closing date, and note what would need assembling to bid. Use only the listing's own content; mark unknowns as unknown." },
          ],
          mcp: [
            "Your CRM / bid tracker (to suppress tenders already seen or already declined) — via a Tool (MCP) node; no first-party connector",
            "Browser-automation MCP (Playwright-style, via the MCP registry) — OPTIONAL and only for portals whose PUBLIC listing pages will not render without JS; it never touches a gated area",
            "Slack / Email — the digest channel",
          ],
          tools: [
            "http_request (built-in) — fetch the public listing pages, rate-limited",
            "web_search (built-in) — catch postings syndicated outside the portals you track",
            "knowledge_search (built-in) — your capability statement, eligibility criteria, past-performance categories",
            "User-defined tool `closing_soon_flag` (SpEL): input.daysToClose <= 7 ? 'URGENT' : (input.daysToClose <= 21 ? 'WATCH' : 'NORMAL') — the bids lead sets their own urgency bands, and changes them, without a developer",
            "User-defined tool `value_band` (SpEL): input.estimatedValue >= input.minBidValue ? 'IN_RANGE' : 'BELOW_FLOOR' — a normaliser that encodes your own commercial floor as data, not code",
          ],
          guardrails: [
            "robots.txt and each portal's Terms of Service are respected — a portal that forbids automated retrieval is watched by a human or not at all, and is removed from the source list rather than fetched quietly",
            "Rate-limited and honestly identified: one polite pass per source per day, delays between requests, backoff on 429/5xx — a daily watch has no reason to ever look like a crawl",
            "Public listings only. No login you do not own, no bot-detection bypass, and CAPTCHAs are NEVER solved — a gated document set is reported as {gated: true} with its public URL for a human to open",
            "No personal data harvesting: buyer organisation and public contact-of-record only, never named individuals compiled across postings into a profile",
            "No credentials, ever: the workflow does not hold or type portal passwords. Where a portal account exists, a human logs in — a secrets-managed integration under your own account is the only alternative, never an agent typing into a login box",
            "No registration, no expression of interest, no submission — the workflow reads and reports. Any consequential step, starting with bid/no-bid, is held by the Approval node for a human",
            "Every shortlisted item carries its source URL and the date it was seen; a null field stays null rather than being filled by inference",
            "The SpEL user-defined tools are pure data-transform expressions over the tool's JSON input — no method calls, no constructors, no bean/type references and no loops in the grammar, so a bids lead editing their own urgency band cannot introduce an execution risk",
          ],
          cost:
            "Volume lives entirely in the screener, so it belongs on the economy tier — most postings on most days are irrelevant and only need a cheap yes/no with a reason. The standard-tier summariser sees only the survivors, which on a normal day is a handful. That ordering is the whole point: screen cheap, reason expensive, and never reason over the rejects. Cap the daily run budget so a portal that changes layout and starts returning junk fails visibly instead of billing quietly.",
          output:
            "The Loop screens each source with the economy model → the Knowledge-grounded Condition node keeps only relevant postings → the standard-tier summariser writes up the shortlist → the Approval node holds bid/no-bid → an Output node delivers the dated digest to Slack or Email, explicitly stating when a day genuinely had nothing.",
        },
      },
      {
        id: "web-market-brief",
        title: "Parallel multi-source research brief (fan-out, then one synthesis)",
        scenario:
          "Before every quarterly review an analyst opens thirty tabs — trade press, competitor newsrooms, a regulator page, two industry blogs — reads them across an afternoon, and pastes the useful paragraphs into a doc. The links are lost by the time anyone questions a claim, and the same thirty tabs get opened again next quarter.",
        triggerLabel: "Webhook (scheduled quarterly) / Slack (analyst asks for a brief)",
        steps: [
          { kind: "trigger", title: "Brief requested", detail: "A scheduled Webhook, or an analyst asking in Slack, starts the run with the topic and the source list." },
          { kind: "parallel", title: "Fan out to independent sources", detail: "A Parallel node fetches the independent sources concurrently — this is what turns an afternoon of sequential tab-reading into one bounded run — with a capped concurrency so no single host is overloaded." },
          { kind: "agent", title: "Economy reader per source", detail: "One cheap reader agent per source returns strict JSON: the claims found, each with a verbatim supporting quote and the source URL. It summarises what is on the page and nothing else." },
          { kind: "code", title: "Merge and de-duplicate", detail: "A Code node merges the per-source results, de-duplicates claims that appeared in three outlets, and preserves every URL against every claim." },
          { kind: "agent", title: "Premium synthesiser, once", detail: "A single premium-tier pass writes the brief — themes, disagreements between sources, and what is genuinely unknown. One expensive call over pre-digested material, not thirty." },
          { kind: "condition", title: "Unsourced claim gate", detail: "A Condition node rejects the brief back to synthesis if any claim lacks a URL — a brief with an unattributed assertion is a defect, not a draft." },
          { kind: "output", title: "Deliver with the citation list", detail: "An Output node posts the brief to Slack or Email with the full source list, so every sentence stays checkable next quarter." },
        ],
        connectors: ["Webhook", "Slack", "Email", "Knowledge base"],
        outcome:
          "A cited brief lands from one run instead of an afternoon of tabs, and every claim in it is traceable to the URL it came from.",
        impact:
          "The mechanism is fan-out plus attribution discipline: independent sources are read concurrently by cheap models, then one strong model reasons over the digest rather than the raw pages. Because the unsourced-claim gate fails the brief rather than the reader, the citations survive to the review meeting.",
        build: {
          agents: 2,
          agentsNote: "1 economy reader (runs once per source, fanned out by the Parallel node) + 1 premium synthesiser (runs exactly once). Parallel, Code and Condition are workflow nodes.",
          pattern: "Parallel fan-out (cheap readers) → Code merge/de-dupe → single premium synthesis → unsourced-claim gate",
          prompt:
            "(Reader) Read ONE source and extract only what it actually says: {claims: [{claim, verbatimQuote, url, publishedDate|null}], sourceType}. Every claim MUST carry a verbatim quote from this source and this source's URL. Do not add context from your own knowledge, do not merge in what another source said, and do not infer a date the page does not state. If the page is a paywall, a consent wall or a login wall, return {blocked: true} — do not attempt to get around it. An empty claims list is a perfectly good answer for a page with nothing relevant.",
          agentRoles: [
            { role: "Source reader (one per source, in parallel)", model: "Economy tier — the fan-out step; N of these run per brief, so this tier sets the run's cost", prompt: "One source → claims with verbatim quotes and URLs. No outside knowledge, no cross-source merging, no inferred dates. Blocked pages are reported, never bypassed." },
            { role: "Brief synthesiser (once)", model: "Premium tier — one call over pre-digested, quoted material is exactly where a premium model earns its price", prompt: "Write the brief from the merged claims only. Name the themes, surface where sources disagree rather than averaging them, and state plainly what remains unknown. Every assertion keeps its URL. Never introduce a fact that is not in the merged input." },
          ],
          mcp: [
            "Your document store / wiki (to file the finished brief) — via a Tool (MCP) node; no first-party connector",
            "Browser-automation MCP (Playwright-style, via the MCP registry) — OPTIONAL, only for public pages that will not render without JS",
            "Slack / Email — request and delivery channel",
          ],
          tools: [
            "web_search (built-in) — discover sources beyond the fixed list",
            "http_request (built-in) — fetch each source, rate-limited and honestly identified",
            "knowledge_search (built-in) — your prior briefs, so the new one says what CHANGED",
            "User-defined tool `source_freshness` (SpEL): input.ageDays <= 90 ? 'CURRENT' : (input.ageDays <= 365 ? 'DATED' : 'STALE') — the analyst defines their own recency bands per topic without touching code",
          ],
          guardrails: [
            "robots.txt and each publisher's Terms of Service are respected; sources that disallow automated retrieval are read by a human, and paywalled content is neither fetched nor reproduced",
            "Rate-limited and backed off, with capped Parallel concurrency and honest identification — a research brief is never a reason to hammer a publisher",
            "Public data only: no login you do not own, no bot-detection bypass, and CAPTCHAs are NEVER solved. A paywall, consent wall or login wall ends that source as {blocked: true} and is listed as not-read in the brief",
            "No personal data harvesting — the brief covers organisations, products and policy, and does not compile information about named individuals across sources",
            "No credentials, ever: no agent types a password or subscription login into any site. Publisher subscriptions you hold are accessed by the human who holds them",
            "Nothing is published, posted or submitted by this workflow — it fetches and reports. If a brief is ever wired to an external publishing step, that submit goes through an Approval node first",
            "Unsourced-claim gate: the Condition node fails the brief if any claim lacks a URL, and sources that were blocked or empty are named as such rather than silently dropped",
            "The SpEL freshness tool is a data-transform expression over the tool's JSON input — no method calls, no constructors, no bean/type refs, no loops in the grammar — so an analyst-authored recency rule is a calculation and can never become executable behaviour",
          ],
          cost:
            "Cost is N cheap reads plus ONE expensive synthesis, and the ordering is the entire savings. Running the premium model across thirty raw pages costs many times more and reads worse, because the strong model spends its budget on boilerplate navigation text instead of on reasoning. Let economy readers strip each page down to quoted claims, then spend the premium call on the merged digest. Parallel affects wall-clock only — per-source cost is unchanged — and a hard per-brief cap keeps an accidentally huge source list from becoming an accidentally huge bill.",
          output:
            "The Parallel node fans economy readers across the sources → a Code node merges and de-duplicates while preserving every URL → one premium pass writes the brief → the Condition node's unsourced-claim gate blocks any unattributed assertion → an Output node delivers the brief plus full citation list to Slack or Email and files it via a Tool (MCP) node.",
        },
      },
      {
        id: "web-change-watch",
        title: "Supplier / regulatory page change detection with a diff summary",
        scenario:
          "A compliance officer has eleven pages bookmarked — a regulator's guidance page, seven supplier T&C pages, three certification registries — and is supposed to re-read them monthly. In practice they skim, notice nothing, and find out about a changed clause when a customer quotes it back at them.",
        triggerLabel: "Webhook (scheduled — weekly/monthly change sweep)",
        steps: [
          { kind: "trigger", title: "Change sweep starts", detail: "A scheduled Webhook trigger fires with the watched-page list. The page list and the stored previous versions live in your own store, reached via a Tool (MCP) node." },
          { kind: "loop", title: "For each watched page", detail: "A Loop node walks the pages one at a time, so an unreachable page fails as one item rather than failing the sweep." },
          { kind: "code", title: "Diff before any model runs", detail: "A Code node fetches the page, normalises it and compares it to the stored version. Pages that did not change never reach a model at all — this is the cheapest possible filter and it removes most of the work." },
          { kind: "condition", title: "Material change?", detail: "A Condition node ignores cosmetic churn (a rotating banner, a changed timestamp) and passes only substantive text differences forward." },
          { kind: "agent", title: "Standard-tier agent explains the change", detail: "For changed pages only, an agent describes what changed in plain language, quotes the before and after text, and flags which internal process the clause touches." },
          { kind: "knowledge", title: "Map it to your own obligations", detail: "A Knowledge node supplies your policy and process register, so the change is reported as 'this affects the onboarding checklist' rather than as a naked diff." },
          { kind: "approval", title: "Acknowledgement is human", detail: "An Approval node routes the change to the accountable owner for acknowledgement — the workflow surfaces and evidences the change; a human accepts it and decides the response." },
          { kind: "output", title: "Change notice + stored snapshot", detail: "An Output node emails or Slacks the change notice with before/after quotes and the capture timestamp, and a Tool (MCP) node stores the new snapshot as the next baseline." },
        ],
        connectors: ["Webhook", "Email", "Slack", "Knowledge base", "Approval / HITL", "Tool (MCP → document/snapshot store)"],
        outcome:
          "Substantive changes to watched public pages are detected on a schedule, explained against your own processes, and acknowledged by a named owner with an evidence trail.",
        impact:
          "The mechanism is a cheap deterministic diff in front of an expensive model: nothing is summarised unless the text actually moved, so the sweep stays affordable at any page count, and the compliance officer reads a short, attributed 'this clause changed and it touches this process' instead of re-reading eleven pages.",
        build: {
          agents: 1,
          agentsNote: "1 change-explainer agent, and it runs ONLY on pages the Code-node diff proved had changed. Loop, Code, Condition, Knowledge and Approval are workflow nodes.",
          pattern: "Loop (per page) → deterministic Code diff → Condition drops cosmetic churn → agent explains only real changes → owner acknowledges",
          prompt:
            "You explain what changed on ONE watched page. You are given the previous and current text plus the computed diff. Return {changed (true|false), materiality (COSMETIC|MINOR|MATERIAL), plainSummary, beforeQuote, afterQuote, affectedProcesses[] (from the process register in Knowledge only), effectiveDate|null}. Quote verbatim — never paraphrase inside a quote field. Do not speculate about intent or about what the publisher will do next. If the diff is only formatting, dates or navigation, say materiality=COSMETIC and stop. You never decide the response; you describe the change so an accountable human can.",
          agentRoles: [
            { role: "Change explainer (only on changed pages)", model: "Standard tier — the diff has already removed the volume, so what is left genuinely needs careful reading; a page with a heavy legal diff can escalate to premium via the same Condition pattern", prompt: "Explain one real diff: plain summary, verbatim before/after quotes, materiality, and which registered processes it touches. No speculation, no paraphrased quotes, no recommended action." },
          ],
          mcp: [
            "Document / snapshot store holding the previous page versions and the new baseline — via a Tool (MCP) node; no first-party connector",
            "Browser-automation MCP (Playwright-style, via the MCP registry) — OPTIONAL, only where a public page will not render its text without JS",
            "Email / Slack — the change-notice channel",
          ],
          tools: [
            "http_request (built-in) — fetch each watched page, rate-limited and honestly identified",
            "knowledge_search (built-in) — your policy and process register, so a change maps to an owner",
            "User-defined tool `materiality_route` (SpEL): input.changedChars > 400 ? 'REVIEW_NOW' : (input.changedChars > 40 ? 'REVIEW_BATCH' : 'LOG_ONLY') — the compliance officer sets their own materiality threshold as data",
            "User-defined tool `days_to_effective` (SpEL): input.effectiveInDays <= 30 ? 'ACT_NOW' : 'PLAN' — a normaliser turning a date field into the urgency band this team actually uses",
          ],
          guardrails: [
            "robots.txt and each site's Terms of Service are respected — only pages you are permitted to fetch are watched, and a disallowed page is removed from the list rather than fetched anyway",
            "Rate-limited and backed off with honest identification; a monthly or weekly cadence per page means there is never a reason to fetch a site aggressively",
            "Public pages only. No login you do not own, no bot-detection bypass, and CAPTCHAs are NEVER solved — a page that becomes gated is reported as no-longer-watchable so a human takes it over",
            "No personal data harvesting: the watch covers published policy, terms and register text, not individuals",
            "No credentials, ever — no agent types a password or ID into any site. A source that moves behind an account you hold is read by the human who holds that account, or through a secrets-managed integration, never by an agent typing a login",
            "The workflow submits nothing and clicks no irreversible control; the one consequential step, the owner's acknowledgement of a material change, is held by the Approval node",
            "Quotes are verbatim and every notice carries the source URL, capture timestamp and both snapshot ids — the evidence trail is the deliverable, not a nicety",
            "The SpEL materiality and urgency tools are data-transform expressions over the tool's JSON input, with no method calls, constructors, bean/type references or loops in the grammar — the officer's own threshold is a formula that cannot become executable behaviour",
          ],
          cost:
            "The deterministic Code-node diff runs at zero model cost and eliminates the majority of items on a typical sweep, so most runs invoke no agent at all. Only genuinely changed pages reach the standard-tier explainer, and only a heavy legal diff needs to escalate above it. This inverts the usual failure mode of change monitoring — summarising every page every cycle — where the model bill scales with the page count instead of with the change count. Cap per-run spend so a site redesign that makes every page look 'changed' fails loudly rather than expensively.",
          output:
            "The Loop fetches each watched page → a Code node diffs it against the stored snapshot at no model cost → the Condition node discards cosmetic churn → the agent explains only real changes and maps them to processes from Knowledge → the accountable owner acknowledges at the Approval node → an Output node sends the change notice with before/after quotes while a Tool (MCP) node stores the new baseline snapshot.",
        },
      },
    ],
  },

  // ================== WEB FORM FILLING & BACK-OFFICE DATA ENTRY ============
  {
    id: "data-entry",
    name: "Web form filling & back-office data entry",
    icon: ClipboardList,
    tagline:
      "The agent reads the source, drafts every field and fills the form through a browser-automation MCP — then stops. A human clicks submit, and no agent ever types a password, card or ID.",
    recipes: [
      {
        id: "entry-invoice-portal",
        title: "Invoice lines → supplier portal draft, human submits",
        scenario:
          "A VA opens a PDF invoice on one screen and the customer's supplier portal on the other, and retypes 400 line items — code, description, quantity, unit price, tax code — over two days. Transposed digits surface a month later as a rejected payment, and the same invoice format arrives again next month.",
        triggerLabel: "Email (invoice received) / Webhook (document dropped)",
        steps: [
          { kind: "trigger", title: "Invoice arrives", detail: "An Email trigger on the AP inbox, or a Webhook from your document drop, starts the run with the invoice file." },
          { kind: "knowledge", title: "Load the field map", detail: "A Knowledge node supplies the portal's field map and this customer's coding rules — which portal field each invoice column feeds, and which tax codes are valid." },
          { kind: "loop", title: "For each invoice line", detail: "A Loop node iterates the line items. 400 lines is 400 small, isolated, individually-checkable passes rather than one enormous prompt that quietly drops row 287." },
          { kind: "agent", title: "Economy extractor per line", detail: "A cheap extractor turns one line into strict JSON {code, description, qty, unitPrice, taxCode, confidence}. Running once per line, its tier sets the cost of the whole job." },
          { kind: "code", title: "Validate and total", detail: "A Code node re-computes line totals and the invoice total arithmetically — never trusting the model's arithmetic — and a user-defined SpEL tool flags rows outside tolerance or below the confidence floor." },
          { kind: "condition", title: "Ambiguous line?", detail: "A Condition node routes flagged rows to the premium arbiter and lets clean rows through — so the expensive model sees the handful of genuinely unclear lines, not all 400." },
          { kind: "agent", title: "Premium arbiter on the unclear rows", detail: "A stronger model resolves the ambiguous minority: a merged description cell, a missing tax code, a credit line that reads like a charge." },
          { kind: "tool", title: "Fill the portal form (browser-automation MCP)", detail: "A Tool (MCP) node drives a browser-automation MCP server — a Playwright-style server registered in the platform's MCP registry — to enter the validated rows into the portal form. VegaDūta ships no built-in browser; this connector IS the mechanism. It fills, and it stops short of submit." },
          { kind: "approval", title: "A human clicks submit", detail: "An Approval node holds the completed draft with a field-by-field diff against the source invoice. Submission is the irreversible act, so a person performs it. The portal login itself is done by that person or via a secrets-managed integration — never typed by an agent." },
          { kind: "output", title: "File the evidence", detail: "An Output node records the submitted reference, the approver and the timestamp back to your AP system via a Tool (MCP) node, and notifies the requester on Email or Slack." },
        ],
        connectors: ["Email", "Webhook", "Slack", "Knowledge base", "Approval / HITL", "Tool (MCP → browser-automation server)", "Tool (MCP → AP / accounting system)"],
        outcome:
          "The portal form arrives pre-filled and arithmetically reconciled with a per-field diff against the source invoice, and a human spends their time checking and submitting rather than typing.",
        impact:
          "The mechanism is per-line isolation plus deterministic arithmetic: each line is extracted in its own small pass so a long invoice cannot silently lose a row, and totals are computed in code rather than by a model, so a transposed digit surfaces as a tolerance flag before submission instead of as a rejected payment a month later.",
        build: {
          agents: 2,
          agentsNote: "1 economy line extractor (once per line, via the Loop node) + 1 premium arbiter (only on flagged lines). The form-filling itself is a Tool (MCP) node against a browser-automation server, not an agent capability.",
          pattern: "Loop (per line) → economy extract → Code validates arithmetic → Condition escalates only flagged rows → premium arbiter → MCP browser fills the draft → human submits",
          prompt:
            "You extract ONE invoice line into strict JSON: {code, description, qty (number), unitPrice (number), lineTotal (number), taxCode, confidence (0-1), issue|null}. Use ONLY this line and the field map from Knowledge. Never infer a missing tax code from what is typical — leave it null and set a low confidence. Never round, never re-compute a total to make it agree, and never carry a value down from the line above. If the line is a credit, a discount or a header row rather than a chargeable item, say so in `issue` and do not force it into the item schema. You prepare a draft only: you never submit anything, and you never enter a password, card number or government ID into any field.",
          agentRoles: [
            { role: "Line extractor (per line)", model: "Economy tier — 400 lines means 400 invocations; this single tier choice dominates the cost of the entire job", prompt: "One line → schema-valid JSON against the Knowledge field map. Nulls plus low confidence instead of plausible guesses. No arithmetic reconciliation, no value carried over from the previous line." },
            { role: "Ambiguity arbiter (flagged lines only)", model: "Premium tier — affordable precisely because it sees the handful of unclear rows rather than the full invoice", prompt: "Resolve one flagged line using the full invoice context and the coding rules. If it remains genuinely unclear, return NEEDS_HUMAN with the specific question — an honest escalation is cheaper than a wrong line in a submitted invoice." },
          ],
          mcp: [
            "Browser-automation MCP (Playwright-style server registered in the MCP registry) — the ONLY way this platform drives a real portal form: navigate, fill, screenshot. There is no built-in browser. It is scoped to fill fields and is not permitted to click the submit control",
            "AP / accounting system (invoice record, submitted reference) — via a Tool (MCP) node; no first-party connector",
            "Email / Slack — intake and notification channel",
          ],
          tools: [
            "knowledge_search (built-in) — the portal field map and this customer's coding rules",
            "http_request (built-in) — where the portal exposes a documented API, prefer it over browser automation; an API is more reliable and better behaved than driving a UI",
            "User-defined tool `line_tolerance` (SpEL): input.lineTotal > input.qty * input.unitPrice * 1.01 || input.lineTotal < input.qty * input.unitPrice * 0.99 ? 'MISMATCH' : 'OK' — the AP lead sets their own tolerance band, and can tighten it, without a developer",
            "User-defined tool `field_ready_flag` (SpEL): input.confidence < 0.85 || input.taxCode == null ? 'REVIEW' : 'READY' — the confidence floor is the team's own number, expressed as data",
          ],
          guardrails: [
            "No agent ever types a password, card number, bank detail or government ID into a form. The portal login is performed by the human approver, or by a secrets-managed integration under your own account — credentials are never placed in a prompt, a field map, or an agent's reach",
            "Every submission goes through the Approval node. The browser-automation MCP is scoped to fill fields only; the irreversible submit control is clicked by a person looking at a field-by-field diff against the source invoice",
            "This portal is your own customer relationship, accessed under access you legitimately hold and within its Terms of Service — automation is used where the portal permits it, and where it does not, the workflow stops at the prepared draft for manual keying",
            "Rate-limited and honestly identified: the browser session paces its interactions, backs off on errors, and does not retry in a tight loop against a live portal",
            "No bot-detection bypass and CAPTCHAs are NEVER solved — a challenge hands the session to the human approver, who completes it themselves; the workflow waits rather than working around it",
            "No personal data harvesting: only the invoice fields the portal requires are processed, nothing about individuals is retained beyond the record, and the extracted payload is not reused for any other purpose",
            "Arithmetic is computed in the Code node, never accepted from a model, and the invoice total must reconcile before the draft can reach the Approval node at all",
            "Idempotent per invoice id — a resent email or a retried run never produces a second draft, and the submitted reference is written back once",
            "The SpEL user-defined tools are data-transform expressions over the tool's JSON input — no method calls, no constructors, no bean/type references, no loops in the grammar — so an AP lead encoding their own tolerance band writes a formula, not code, and it cannot become an execution risk",
          ],
          cost:
            "This is the volume case that makes cost tiering obvious: 400 lines on a premium model is an absurd bill for what is fundamentally field-lifting off a structured document. Economy tier on the per-line extractor, deterministic arithmetic in the Code node (free), and the premium model reserved for the dozen rows the tolerance and confidence flags actually raise. Cap the extractor's output tokens — the per-line schema is tiny — and set a per-invoice budget so a scanned document that OCRs into noise fails loudly instead of burning through 400 expensive calls.",
          output:
            "The Loop extracts every line with the economy model → the Code node reconciles totals and the SpEL flags fire → the Condition node sends only flagged rows to the premium arbiter → a Tool (MCP) node drives the browser-automation server to fill the portal form and stops before submit → the human approver checks the field-by-field diff and clicks submit themselves → an Output node writes the reference, approver and timestamp back to the AP system and notifies on Email or Slack.",
        },
      },
      {
        id: "entry-multi-system-sync",
        title: "One customer record, three systems — re-keyed once, checked everywhere",
        scenario:
          "A new customer is typed into the CRM by sales, then into the billing system by finance, then into the support desk by ops. Three people type the same address three times, one of them types it differently, and six weeks later nobody can say which spelling is correct.",
        triggerLabel: "Webhook (record created in the system of record)",
        steps: [
          { kind: "trigger", title: "Record created upstream", detail: "A Webhook trigger fires from your system of record with the new or updated customer payload." },
          { kind: "code", title: "Normalise once, centrally", detail: "A Code node canonicalises the payload — casing, address shape, phone format — so all three downstream systems receive the identical normalised record rather than three human interpretations of it." },
          { kind: "knowledge", title: "Per-system field maps", detail: "A Knowledge node supplies each target system's required fields, allowed values and validation rules, so a record is checked against the target's rules before anything is entered." },
          { kind: "parallel", title: "Prepare all three drafts at once", detail: "A Parallel node prepares the three target payloads concurrently — they are independent, so there is no reason to do them in sequence." },
          { kind: "agent", title: "Economy mapper per target", detail: "A cheap mapper agent produces one target's payload from the canonical record and that system's field map, returning strict JSON plus any fields it could not confidently map." },
          { kind: "condition", title: "Anything unmappable?", detail: "A Condition node holds any target whose required field could not be filled confidently — an incomplete record is queued for a human, never submitted half-empty." },
          { kind: "tool", title: "API first, browser-automation MCP only if needed", detail: "Where a target exposes an API, an `http_request` writes to it directly. Where it only has a web UI, a Tool (MCP) node drives a browser-automation MCP server registered in the MCP registry to fill the form — the platform has no built-in browser — and it fills only, never submits." },
          { kind: "approval", title: "One approval, three writes", detail: "An Approval node shows all three prepared records side by side against the canonical source. A human approves once, and only then does each write or submit proceed." },
          { kind: "output", title: "Reconcile and report", detail: "An Output node posts the confirmation to Slack with the three target ids, and any target that rejected the record is reported as an open item rather than silently skipped." },
        ],
        connectors: ["Webhook", "Slack", "Email", "Knowledge base", "Approval / HITL", "Tool (MCP → browser-automation server)", "Tool (MCP → CRM / billing / support systems)"],
        outcome:
          "One canonical record produces three consistent target records from a single human approval, with any rejection surfaced as an open item instead of a silent gap.",
        impact:
          "The mechanism is normalise-once: the divergence comes from three humans independently interpreting the same source, so canonicalising in a Code node before any target sees it removes the cause rather than reconciling the symptom afterwards. The human reviews one side-by-side diff instead of typing the same address three times.",
        build: {
          agents: 2,
          agentsNote: "1 economy mapper, invoked once per target system by the Parallel node, + 1 premium escalation reviewer that runs only when a required field could not be mapped. Normalisation is a Code node, validation is Knowledge plus Condition, and the writes are http_request or a browser-automation MCP tool call — none of that is an agent.",
          pattern: "Code normalise → Parallel per target → economy mapper per target → Condition holds unmappable records → single Approval → API or MCP-browser writes",
          prompt:
            "Map ONE canonical customer record into ONE target system's payload, using only that system's field map and allowed values from Knowledge. Return {payload, unmapped[] (required target fields you could NOT fill with certainty), notes}. Never invent a value to satisfy a required field, never pick an allowed value because it looks close, and never reformat a value in a way the field map does not specify — normalisation already happened upstream. You prepare a payload only: you do not write it, you do not submit it, and you never enter a password, card number or government ID into any field.",
          agentRoles: [
            { role: "Target mapper (once per target system)", model: "Economy tier — mapping a normalised record onto a documented field map is mechanical; volume here is per-record × per-system, so cheap matters", prompt: "One canonical record + one field map → one target payload plus an explicit unmapped[] list. No invented values, no near-enough enum matches, no re-normalising." },
            { role: "Escalation reviewer (only when unmapped[] is non-empty)", model: "Premium tier — invoked only for the genuinely ambiguous record, e.g. a legal entity type with no clean equivalent in the target's enum", prompt: "Given the canonical record and the target's allowed values, propose the correct mapping with a one-line justification, or return NEEDS_HUMAN. Never widen an enum or invent a category." },
          ],
          mcp: [
            "CRM / billing / support systems — via Tool (MCP) nodes or documented APIs; no first-party connector for any of them",
            "Browser-automation MCP (Playwright-style, registered in the MCP registry) — used ONLY for a target with no API. This platform has no built-in browser; driving a real form is always this connector, and it is scoped to fill, not submit",
            "Slack / Email — the confirmation and exception channel",
          ],
          tools: [
            "knowledge_search (built-in) — per-system field maps, required fields and allowed values",
            "http_request (built-in) — the preferred write path wherever a target has an API; more reliable and better behaved than driving a UI",
            "User-defined tool `record_completeness` (SpEL): input.unmappedCount == 0 ? 'READY' : 'HOLD' — a normaliser that turns the mapper's own output into a routing decision the ops lead controls",
            "User-defined tool `phone_country_band` (SpEL): input.phoneDigits == 10 ? 'IN_LOCAL' : (input.phoneDigits > 10 ? 'INTERNATIONAL' : 'INVALID') — the team encodes its own field-shape rule as a formula; anything needing real string surgery belongs in the Code node, because the SpEL grammar deliberately has no method calls",
          ],
          guardrails: [
            "No agent ever types a password, card number, bank detail or government ID into a form. Each target system is reached with access you legitimately hold — a documented API using secrets-managed credentials, or a session a human opened — and credentials never appear in a prompt or a field map",
            "Every submission goes through the Approval node. The browser-automation MCP is scoped to fill fields; the irreversible submit or save is a human action taken against a side-by-side diff of all three prepared records",
            "These are your own systems, used within their Terms of Service and under legitimate access; where a vendor's terms prohibit UI automation, the API path is used or the record is prepared for manual entry",
            "Rate-limited and honestly identified against every target — batched syncs pace their writes and back off on errors rather than retrying in a loop",
            "No bot-detection bypass and CAPTCHAs are NEVER solved — a challenge on a target's UI hands the session to the human approver rather than being worked around",
            "Minimum necessary personal data: only the fields each target actually requires are mapped into it, the canonical payload is not retained beyond the run, and no profile is assembled across systems",
            "No invented values — an unfillable required field puts the whole target on hold at the Condition node instead of being satisfied with a plausible placeholder",
            "Idempotent per source record id and version, so a replayed Webhook updates rather than duplicating, and a partial failure is reported as an open item rather than leaving a silent gap",
            "The SpEL user-defined tools are data-transform expressions over the tool's JSON input — no method calls, no constructors, no bean/type refs, no loops in the grammar — so an ops lead writing their own completeness or field-shape rule produces a value, never behaviour",
          ],
          cost:
            "Per-record cost is (number of targets) × economy mapper, with the premium tier reserved for the record that genuinely does not map — an unusual entity type, a country the target's enum does not carry. At three targets per record and a steady onboarding flow, putting a premium model on the mapping step multiplies the bill for work that is deterministic lookup against a documented field map. The Parallel node cuts wall-clock without changing per-target cost, and the Code-node normalisation is free — doing it there rather than asking a model to normalise is itself a cost decision.",
          output:
            "A Code node canonicalises the incoming record → the Parallel node prepares all three target payloads with the economy mapper → the Condition node holds any target with unmapped required fields → one Approval shows all three side by side against the canonical source → on approval, `http_request` writes to API-backed targets while a Tool (MCP) node drives the browser-automation server for UI-only ones, stopping short of submit for the human → an Output node reports the three target ids and any rejection to Slack.",
        },
      },
      {
        id: "entry-form-backlog",
        title: "Scanned application forms → structured draft → portal draft, human submits",
        scenario:
          "A back-office team receives scanned or emailed application forms and retypes them into an internal portal. Handwriting is guessed at, one field gets skipped, and the correction cycle starts a week later when the applicant is asked to confirm a detail that was misread rather than missing.",
        triggerLabel: "Email (scanned form received) / Webhook (upload from your intake portal)",
        steps: [
          { kind: "trigger", title: "Form arrives", detail: "An Email trigger on the intake inbox, or a Webhook from your upload portal, starts the run with the scanned document." },
          { kind: "loop", title: "For each page / each form", detail: "A Loop node handles a multi-page batch page by page, so one unreadable scan is one flagged page rather than a failed batch." },
          { kind: "agent", title: "Economy extractor per page", detail: "A cheap extractor returns strict JSON per field with a per-field confidence score. Per-page volume is where the cost sits, so this is the economy-tier step." },
          { kind: "knowledge", title: "Validate against the form spec", detail: "A Knowledge node supplies the form's field spec — mandatory fields, allowed values, formats — so an extraction is checked against what the form actually permits." },
          { kind: "condition", title: "Below the confidence floor?", detail: "A Condition node splits confident fields from doubtful ones using the team's own SpEL threshold; only the doubtful fields go further, and no doubtful field is ever entered silently." },
          { kind: "agent", title: "Premium reader on the doubtful fields", detail: "A stronger model re-reads just the unclear fields with full page context — a smudged digit, an ambiguous tick-box — and returns NEEDS_HUMAN when it genuinely cannot tell." },
          { kind: "tool", title: "Fill the portal draft (browser-automation MCP)", detail: "A Tool (MCP) node drives a browser-automation MCP server from the platform's MCP registry to enter the confident fields into the portal, leaving flagged fields visibly empty. There is no built-in browser; this connector is how a real form gets filled — and it does not submit." },
          { kind: "approval", title: "Operator completes and submits", detail: "An Approval node presents the draft next to the scan, with every low-confidence field highlighted and blank. Identity numbers, bank details and any signature field are left for the human by policy — the agent never types them — and the human clicks submit." },
          { kind: "output", title: "Acknowledge and file", detail: "An Output node acknowledges receipt to the applicant on Email or WhatsApp with the reference, and a Tool (MCP) node files the record and the scan together." },
        ],
        connectors: ["Email", "Webhook", "WhatsApp", "Knowledge base", "Approval / HITL", "Tool (MCP → browser-automation server)", "Tool (MCP → records system)"],
        outcome:
          "Each form arrives as a portal draft with the confident fields filled and the doubtful ones visibly blank, so the operator's attention lands exactly where the scan was actually unclear.",
        impact:
          "The mechanism is per-field confidence made visible: a misread field is worse than a missing one because nobody checks it, so the flow leaves low-confidence fields empty and highlighted rather than filling them plausibly. The operator verifies a short list instead of retyping a whole form, and sensitive identifiers stay human-entered by design.",
        build: {
          agents: 2,
          agentsNote: "1 economy per-page extractor (via the Loop node) + 1 premium re-reader (only on fields below the confidence floor). Form filling is a Tool (MCP) node against a browser-automation server, not an agent capability.",
          pattern: "Loop (per page) → economy extract with per-field confidence → Knowledge validation → Condition splits on the team's SpEL threshold → premium re-read of doubtful fields only → MCP browser fills confident fields → human completes and submits",
          prompt:
            "Extract ONE page of a scanned form into strict JSON: one entry per field as {field, value|null, confidence (0-1), issue|null}, using only the field spec from Knowledge. If a value is unclear, smudged, ambiguously ticked or handwritten in a way you cannot read with certainty, set value=null with a low confidence and describe the problem — a blank flagged field is CORRECT and a plausible guess is a defect. Never normalise a value the spec does not define, never complete a partially-visible number, and never infer a field from another field. Do NOT extract or transcribe passwords, full card numbers, or government ID numbers into the draft payload: mark those fields HUMAN_ENTRY_REQUIRED so a person enters them directly. You prepare a draft only and never submit.",
          agentRoles: [
            { role: "Page extractor (per page)", model: "Economy tier — the per-page volume step; on a daily intake backlog this tier choice is the difference between viable and absurd", prompt: "One page → per-field JSON with honest per-field confidence. Null plus a reason beats a guess. Sensitive identifiers are marked HUMAN_ENTRY_REQUIRED, never transcribed." },
            { role: "Doubtful-field re-reader", model: "Premium tier — runs on a few fields per form, not every field of every page, which is exactly what makes premium affordable here", prompt: "Re-read only the flagged fields with full page context. Return a value only at high confidence; otherwise return NEEDS_HUMAN with the specific difficulty. Never resolve ambiguity by choosing the more common option." },
          ],
          mcp: [
            "Browser-automation MCP (Playwright-style, registered in the MCP registry) — the mechanism for entering data into the portal's real form. VegaDūta has no built-in browser. Scoped to fill fields; it never clicks submit",
            "Records / case system (file the record and the scan together) — via a Tool (MCP) node; no first-party connector",
            "Email / WhatsApp — intake and applicant acknowledgement",
          ],
          tools: [
            "knowledge_search (built-in) — the form field spec: mandatory fields, allowed values, formats",
            "http_request (built-in) — where the portal exposes an API, use it in preference to driving the UI",
            "User-defined tool `field_ready_flag` (SpEL): input.confidence < 0.9 || input.value == null ? 'HUMAN_CHECK' : 'AUTOFILL' — the operations lead sets the confidence floor for THEIR form and can raise it for a harder document class, without a developer",
            "User-defined tool `age_band` (SpEL): input.ageYears < 18 ? 'MINOR_ROUTE' : 'STANDARD_ROUTE' — a normaliser turning an extracted field into the routing category this team already uses, expressed as a formula rather than code",
          ],
          guardrails: [
            "No agent ever types — or even transcribes into the draft — a password, card number, bank detail or government ID. Those fields are marked HUMAN_ENTRY_REQUIRED and entered by the operator directly, or supplied by a secrets-managed integration; they never pass through a prompt",
            "Every submission goes through the Approval node. The browser-automation MCP fills fields only; the irreversible submit is clicked by the operator with the scan open beside the draft",
            "Access is legitimate and within Terms of Service: this is your own internal portal and your own applicants' forms, entered under access you hold. Where UI automation is not permitted, the flow stops at the structured draft for manual keying",
            "Rate-limited, paced and honestly identified against the portal; failures back off instead of retrying in a tight loop",
            "No bot-detection bypass and CAPTCHAs are NEVER solved — a challenge is handed to the operator, who completes it in their own session",
            "Personal data is handled on a minimum-necessary basis: fields are used for this application only, the scan and payload are retained under your existing retention policy, and nothing is compiled into a profile or reused for another purpose",
            "Low-confidence fields are left BLANK and highlighted, never auto-filled — the flow's core rule is that a missing field is safe and a confidently-wrong field is not",
            "Idempotent per document id, so a re-sent scan updates the existing draft instead of creating a duplicate application",
            "The SpEL user-defined tools are data-transform expressions over the tool's JSON input, with no method calls, constructors, bean/type references or loops in the grammar — the ops lead's own confidence floor is a formula that computes a value and can never become executable behaviour",
          ],
          cost:
            "Pages are the volume unit, so the per-page extractor sits on the economy tier and everything else follows from that. The premium model is scoped to the fields that failed the confidence floor — typically a few per form — which means the expensive tier scales with document difficulty rather than with intake volume. Cap output tokens per page (the field schema is fixed) and set a per-document budget so a badly-scanned batch fails visibly rather than quietly re-reading every field at premium tier.",
          output:
            "The Loop extracts each page with the economy model → Knowledge validates against the form spec → the Condition node applies the team's SpEL confidence floor → the premium re-reader takes only the doubtful fields → a Tool (MCP) node drives the browser-automation server to fill the confident fields and leave flagged ones blank → the operator completes the highlighted fields, enters any sensitive identifiers themselves, and submits at the Approval node → an Output node acknowledges to the applicant with the reference while a Tool (MCP) node files the record with the scan.",
        },
      },
      {
        id: "entry-statutory-filing",
        title: "Recurring statutory / compliance return — draft prepared, filed by the authorised person",
        scenario:
          "Every period an accounts executive exports the same figures, reconciles them in a spreadsheet, and keys them into a statutory portal field by field. The reconciliation is redone from scratch each cycle, the working papers live in someone's downloads folder, and if the filer is on leave the deadline is at risk.",
        triggerLabel: "Webhook (period close / scheduled ahead of the filing deadline)",
        steps: [
          { kind: "trigger", title: "Period close triggers the prep", detail: "A scheduled Webhook fires ahead of the deadline with the period reference; the ledger data is pulled from your accounting system via a Tool (MCP) node — no first-party connector exists for it." },
          { kind: "code", title: "Compute the figures deterministically", detail: "A Code node performs every calculation and cross-total. Statutory figures are arithmetic, not inference, and no model computes a number that goes on a return." },
          { kind: "knowledge", title: "Ground it in the current return spec", detail: "A Knowledge node supplies the current form's schedule structure, field definitions and validation rules, so the draft matches the version actually in force." },
          { kind: "agent", title: "Standard-tier agent assembles the draft", detail: "An agent maps the computed figures onto the return's fields, produces the working-paper narrative, and lists every assumption it relied on — it explains and arranges; it never computes a filing figure." },
          { kind: "condition", title: "Reconciliation gate", detail: "A Condition node blocks the draft unless the computed control totals tie back to the ledger within the team's own tolerance — an unreconciled return never reaches a human as if it were ready." },
          { kind: "tool", title: "Fill the portal draft (browser-automation MCP)", detail: "A Tool (MCP) node drives a browser-automation MCP server from the MCP registry to enter the reconciled figures into the portal's draft form. The platform ships no browser; this is the mechanism — and it fills only." },
          { kind: "approval", title: "The authorised signatory files", detail: "An Approval node holds the completed draft with the working papers and the figure-by-figure tie-back. Filing is a legal act by a named authorised person: they log in themselves, they enter any credential or signing token, and they click submit. The agent does neither." },
          { kind: "output", title: "Archive the filing pack", detail: "An Output node emails the acknowledgement and archives the working papers, the draft, the approver and the timestamp via a Tool (MCP) node — the pack that an audit will ask for." },
        ],
        connectors: ["Webhook", "Email", "Slack", "Knowledge base", "Approval / HITL", "Tool (MCP → browser-automation server)", "Tool (MCP → accounting / ledger system)"],
        outcome:
          "Each period produces a reconciled draft return with its working papers attached, ready for the authorised signatory to review and file — the same way every cycle, whoever is on leave.",
        impact:
          "The mechanism is deterministic computation plus a hard reconciliation gate: figures come from code and are proven to tie back to the ledger before any human sees them, so the review is a check rather than a recomputation, and the working-paper pack is produced as a by-product instead of reconstructed at audit.",
        build: {
          agents: 2,
          agentsNote: "1 draft-assembly agent (once per period) + 1 premium anomaly reviewer that runs only on a flagged item. Neither ever computes a filing figure — all arithmetic is a Code node. The reconciliation gate, the form filling and the filing approval are workflow nodes and MCP tool calls.",
          pattern: "Code computes → Knowledge grounds the current form spec → agent assembles the draft and working papers → hard reconciliation gate → MCP browser fills the portal draft → authorised signatory files",
          prompt:
            "You assemble a statutory return DRAFT from figures that have ALREADY been computed. You must never calculate, adjust, round or reconcile a figure yourself — use the supplied values exactly, and if a required value is absent, report it as missing rather than deriving it. Map figures onto the return's schedules using ONLY the current form specification from Knowledge, write a plain-language working-paper note per schedule, and list every assumption relied upon. Flag any figure that looks inconsistent with the prior period rather than adjusting it. You never file, never enter credentials or a signing token, and never present the draft as submitted. Output {schedules[], workingNotes[], assumptions[], missing[], anomalies[]}.",
          agentRoles: [
            { role: "Draft assembler", model: "Standard tier — one careful pass per period over pre-computed figures; the reasoning is in the mapping and the narrative, not in the arithmetic", prompt: "Map computed figures onto the current schedules, write working notes, list assumptions, flag prior-period inconsistencies. Never compute, never adjust, never file." },
            { role: "Anomaly reviewer (only when the gate or the agent flags something)", model: "Premium tier — invoked only for the genuinely ambiguous case, such as a movement with no obvious ledger explanation", prompt: "Given the flagged item, the ledger extract and the prior period, explain the most likely cause and what evidence would confirm it. Never propose a figure change — the outcome is a question for the preparer, not an adjustment." },
          ],
          mcp: [
            "Accounting / ledger system (period extract, control totals) — via a Tool (MCP) node; no first-party connector",
            "Browser-automation MCP (Playwright-style, registered in the MCP registry) — the only way the platform enters figures into the portal's draft form. No built-in browser exists. Scoped to fill; it never logs in and never submits",
            "Document archive (working papers, filing pack) — via a Tool (MCP) node",
            "Email / Slack — approval routing and acknowledgement",
          ],
          tools: [
            "knowledge_search (built-in) — the current return specification, schedule structure and validation rules",
            "http_request (built-in) — pull the ledger extract; and use the portal's API where one is published, in preference to driving the UI",
            "User-defined tool `reconciliation_gate` (SpEL): input.variance > input.tolerance ? 'BLOCK' : 'PROCEED' — the finance controller owns the tolerance figure and can tighten it per return type without a developer",
            "User-defined tool `period_movement_flag` (SpEL): input.thisPeriod > input.priorPeriod * 1.25 || input.thisPeriod < input.priorPeriod * 0.75 ? 'EXPLAIN' : 'NORMAL' — a normaliser encoding the team's own materiality band for period-on-period movement",
          ],
          guardrails: [
            "No agent ever types a password, digital-signature token, card number or government ID. The authorised signatory logs into the portal themselves in their own session; where credentials are needed for a read-only ledger pull, they come from a secrets-managed integration and never enter a prompt",
            "Every submission goes through the Approval node — and here the submit is a legal act, so it is performed by the named authorised person against the figure-by-figure tie-back, never by the workflow. The browser-automation MCP fills the draft and stops",
            "Legitimate access and Terms of Service: this is your own filing under your own credentials, and where the portal's terms do not permit automated interaction the workflow delivers the reconciled draft for manual keying instead",
            "Rate-limited, paced and honestly identified; no tight retry loops against a government or regulator portal, ever",
            "No bot-detection bypass and CAPTCHAs are NEVER solved — a challenge is completed by the signatory in their own session, which is also the correct control for a filing",
            "No personal data harvesting: only the return's required fields are processed, retained under your statutory retention policy, and never reused for another purpose",
            "No model-computed figures. Every number on the return comes from the Code node, the reconciliation gate must pass before a human is shown a draft, and a flagged anomaly is raised as a question rather than adjusted away",
            "Idempotent per period and return type — a re-run regenerates the draft rather than creating a second filing, and the archived pack records the approver and timestamp for audit",
            "The SpEL user-defined tools are data-transform expressions over the tool's JSON input — no method calls, no constructors, no bean/type references, no loops in the grammar — so a controller setting their own tolerance or materiality band writes a formula that computes a value and cannot become an execution risk",
          ],
          cost:
            "Volume is low here — one return per period — so cost discipline is about not paying for the wrong thing rather than about per-row tiering: the arithmetic sits in a free Code node instead of a model, which is both cheaper and the only defensible way to produce a filing figure. One standard-tier assembly pass per period is sufficient, and the premium tier appears only when a flagged anomaly genuinely needs reasoning. Contrast with the per-row recipes in this pack: there the economy tier is the lever; here the lever is refusing to spend model tokens on work that is deterministic.",
          output:
            "A Code node computes every figure from the ledger extract → Knowledge supplies the in-force form spec → the agent assembles schedules, working notes and assumptions → the reconciliation gate blocks anything that does not tie back within tolerance → a Tool (MCP) node drives the browser-automation server to fill the portal draft without logging in or submitting → the authorised signatory reviews the tie-back, enters their own credentials and files → an Output node archives the working papers, draft, approver and timestamp as the audit pack and emails the acknowledgement.",
        },
      },
    ],
  },
];
