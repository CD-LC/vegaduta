# Cost & ROI

| | |
|---|---|
| Sidebar | Test & Monitor > **Cost & ROI** |
| Route | `/roi` |
| Nav needs | `PERM_VIEW_USAGE`; not shown on a personal install (the page still opens from a direct link) |
| Backend rule | `GET /api/tenant/roi/**` needs `PERM_VIEW_USAGE`; every other method (prices, assumptions, baseline, outcomes) needs `PERM_MANAGE_BILLING` |

The page answers, per agent: what did it cost, how did its conversations end, and, only if you typed in what a
resolution is worth, what is that worth. The rule behind every figure is that it comes from recorded data or from a
number you typed; a missing price or a missing denominator is shown as a dash, never as zero. The cost model, the
metric definitions and the endpoint table are in [Cost and ROI](../cost-and-roi.md); the process around the pilot
kit is in [Running a 30-day pilot](../pilots/README.md). This page describes the screen.

The row sits in Test & Monitor, not Plans & billing, on purpose: the Plans group is removed when billing is off, and
ROI must stay reachable. Members without `PERM_VIEW_USAGE` see "Cost and ROI are visible to members with the View
usage permission." If the load fails the page shows an error with a **Retry** button.

## Layout, top to bottom

1. **Header**: "Cost & ROI by agent", with a link strip to **Usage and quota** and **Insights: spend and activity**.
2. **Filters**: preset buttons **7 days**, **30 days** (the default) and **90 days**; **From** and **To** date fields
   (yyyy-mm-dd, start not after end, at most one year); a **Count idle-closed conversations as resolved** switch (off
   by default, so guessed resolutions do not inflate ROI); and **Export CSV** for the table.
3. **Monthly budget** card: spend against the monthly cap that the LLM gateway already enforces, as a bar with a
   percentage badge, "X of Y spent in <month>", and the alerts sent this month (sent at 80% and 100%) or "none yet".
   A workspace whose plan has no monthly cap sees "Your plan has no monthly spend cap, so there is no budget to measure
   against." A note explains that this counter can differ from the per-agent estimate below, which uses only the
   prices you entered.
4. **Agents** table, described below.
5. **Model prices**.
6. **Pilot kit**.

## Agents table

A search box ("Search agents") filters the rows; the shared pager pages them in the browser. The columns are:

| Column | Meaning |
|---|---|
| Agent | The agent. |
| Conversations | Conversations with an outcome in the range. |
| Resolved | Conversations the agent resolved. With the switch off, a note shows how many idle-closed conversations were assumed resolved and are **not** counted. |
| Cost (estimated) | Estimated LLM cost from recorded tokens and your prices. A leading "at least" sign means some calls had no price or no usage recorded and are not counted. Your own keys and the platform pool are shown separately. |
| Cost per resolved (estimated) | Cost divided by resolved, or a dash. |
| Platform charges | Billable, undisputed resolution events, in the currency billed. |
| Handed to human, Corrected by human | Rates (percent of conversations with an outcome). |
| Approvals denied | Rate of approvals rejected out of those decided. |
| Estimated ROI | Net value and percentage, "based on your assumptions"; or the reason it cannot be shown (no assumptions entered, no value, currencies differ, cost is a lower bound, no cost recorded). |
| Actions | Edit value assumptions (needs `PERM_MANAGE_BILLING`). |

The columns Cost per resolved, Platform charges and the three rates are hidden below the large breakpoint so the table fits a phone. The footer totals conversations, resolved, cost and cost per resolved for the agents listed. The empty state reads "No agents yet" with a prompt to create an agent
and have a few conversations.

**Value assumptions** (a dialog per agent): *Value per resolved conversation*, *Currency* (three letters, required with
a value) and *Minutes saved per resolved conversation*. Leave a field empty for no assumption; nothing is filled in
for you. ROI is only ever calculated from these.

## Model prices

"Cost uses only prices you enter. A model without a price is listed here and left out of the cost, never guessed."
The section lists the models that made calls in the range but have no price (with the call count and a **Set price**
button) and the price versions you entered, paged in the browser. The price form takes a model-name prefix and USD per
million tokens for input, output, cache write and cache read; leave a rate empty if unknown and calls that need it stay
unpriced. A new price is a new dated row, so history is kept. Entering or deleting a price needs `PERM_MANAGE_BILLING`.

## Pilot kit

The kit turns the page into a one-page report for a pilot. Select an **Agent**, then enter the **Pre-pilot baseline**.
Every field is entered by you; the platform does not measure or fill in any of them, and the report labels each as
"tenant-entered". Leave a field blank if you do not know it.

| Field | Notes |
|---|---|
| Pilot job | Not chosen, Missed-call recovery, Appointment reminders, Payment follow-up or Lead follow-up. |
| Baseline period | Free text up to 80 characters, for example "Aug-Sep 2026". |
| Monthly volume | Enquiries or tasks; a whole number. |
| Average handling time (minutes) | At most two decimal places. |
| Missed rate (%) | 0 to 100, at most two decimals. |
| Average value per converted enquiry, Currency | For reference; at most four decimals; a three-letter currency is required with a value. The ROI uses the value per resolved conversation set in the table above. |

**Save baseline** needs `PERM_MANAGE_BILLING`; without it the form is read-only with a note. "Last entered by" shows who
saved it. **Open pilot report** renders the one-page HTML report in a new tab; **Download PDF** downloads the same
report. The report covers the date range and idle-closed switch selected at the top of the page. API:
`GET/PUT /api/tenant/roi/agents/{agentId}/baseline` and `GET /api/tenant/roi/agents/{agentId}/pilot-report?format=html|pdf|json`.

A workspace seeded by `scripts/vegaduta seed-demo` (fictional data for the pilot report) is detected through
`GET /api/tenant/roi/demo`: the page then shows a large "EXAMPLE" watermark and the banner "EXAMPLE - DEMO DATA, NOT A
CUSTOMER RESULT".

## API behind the page

`GET /api/tenant/roi/agents` (table), `/agents.csv` (export), `/budget`, `/prices`, `POST /api/tenant/roi/prices`,
`DELETE /api/tenant/roi/prices/{id}`, `GET/PUT /api/tenant/roi/agents/{agentId}/assumptions`,
`GET/PUT /api/tenant/roi/agents/{agentId}/baseline`, `GET /api/tenant/roi/agents/{agentId}/pilot-report`,
`GET /api/tenant/roi/demo`; `GET /api/tenant/roi/costs` and `POST /api/tenant/roi/outcomes` exist for API callers
(attribution by dimension and recording outcomes). See [OpenAPI](../api/README.md).
