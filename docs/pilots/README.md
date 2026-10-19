# Running a 30-day pilot

A pilot is one agent doing one narrow job for 30 days, measured against numbers you wrote down **before** it
started. This page is the whole process. It is meant to fit on one printed page per stage: agree it, run it, review it.

Pick **one** of these jobs. A pilot that tries two jobs cannot be judged on either.

| Job | The agent does |
|---|---|
| Missed-call recovery | messages a caller whose call went unanswered, finds out what they wanted, books a call-back or an appointment |
| Appointment reminders | reminds customers of a booking, confirms, or reschedules inside the slots you allow |
| Payment follow-up | reminds a customer of an unpaid invoice, answers questions about it, records a promised date |
| Lead follow-up | replies to a new enquiry, qualifies it with the questions you set, hands qualified leads to a person |

## 1. Before you start (week 0): the baseline

Write down what the job looks like **today**, for the same kind of work the agent will take over. Use your own records
(call log, calendar, invoices, CRM). If you do not have a number, leave it blank: a blank is honest, a guess is not.

| Baseline number | How to get it |
|---|---|
| Monthly volume | how many enquiries or tasks of this kind arrive in a normal month |
| Average handling time | minutes a person spends on one, start to finish |
| Missed rate | share of them nobody answered or followed up in time |
| Average value per converted enquiry | what one successful outcome is worth to you, with its currency |
| Baseline period | the months the numbers come from, for example "Aug-Sep 2026" |

Enter them in the console: **Cost & ROI -> Pilot kit -> Pre-pilot baseline** (needs the *Manage billing* permission).
Every field is stored as **tenant-entered**; the platform never measures or fills in one of them, and the report
repeats that label next to each. Also enter, under **Cost & ROI -> Agents table -> edit value assumptions for the agent**, what one resolved
conversation is worth: that is the number the ROI uses.

Also enter your model prices (**Cost & ROI -> Model prices**). Without a price the cost is shown as a lower bound.

## 2. What the agent will and will not do

Agree this in writing before launch and keep it with the pilot record.

**The agent will:** answer and follow up on the one job above, on the channel(s) you connect, in the languages you
configure, using only the knowledge you give it; hand a conversation to a person when it cannot help, when the customer
asks for one, or when a rule below says so.

**The agent will not:** take payments or move money; give medical, legal or financial advice; change prices, discounts
or policy; message anyone who has not contacted you or agreed to be contacted (see
[the WhatsApp policy guard](../channels/whatsapp-policy.md)); act outside the tools you enable. Anything not on the
"will" list is off.

## 3. Approval rules

Decide, per action, who approves it. The platform enforces the approval; it does not decide the rule for you.

| Action | Suggested start | Why |
|---|---|---|
| Answering a question from your own knowledge | no approval | low risk, easy to review afterwards |
| Booking or moving an appointment | approval for the first 2 weeks, then review | it changes your calendar |
| Sending a message that quotes an amount or a date | approval until you have read 30 of them | a wrong figure is hard to take back |
| Anything touching money | not in a pilot | out of scope |

Read the **Approvals denied** column on the Cost & ROI page (it is not in the pilot report): a high rate means the agent is proposing things you do not want.

## 4. Success criteria, agreed up front

Before launch write three to five criteria, each a number from the report or from your baseline, and have the person who
will judge the pilot sign them. For example, the *shape* of a criterion (the numbers are yours to set, not ours):

- resolved without a person: at least **X%** of conversations;
- human corrections: no more than **Y%**;
- handed to a person: no more than **Z%**;
- estimated cost per resolved conversation: no more than **W** US dollars (the report states cost in USD);
- one outcome that only you can judge, for example "no customer complained about a reminder".

Do not add or change criteria after launch. If a criterion turns out to be wrong, record that and judge against the
original.

## 5. During the 30 days

- Week 1: read every conversation. Record mistakes as a human correction, so the correction rate means something: either an *Edit* on the
  turn, or `POST /api/tenant/roi/outcomes` with `CORRECTED_BY_HUMAN` (the console has no button for it; see
  [Cost and ROI](../cost-and-roi.md#outcomes-and-metrics)).
- Weeks 2-4: read a sample each week (at least ten conversations) and the approval queue.
- Do not change the agent's instructions mid-pilot without writing down what you changed and when.

## 6. End-of-pilot review

1. In **Cost & ROI -> Pilot kit**, choose the agent, set the date range to the 30 days, and open the **pilot report**
   (HTML to read, PDF to keep). It contains only recorded data plus the numbers you typed, and it lists every typed number
   in its **Assumptions** box.
2. Read it against the criteria from section 4, one by one: met, not met, or cannot tell. "Cannot tell" is a valid
   answer when there was too little volume.
3. Read the baseline next to the recorded numbers. They are different measures (for example, the report's response time
   is the model's time per call, not a customer's end-to-end wait), so compare with care and say so in your notes.
4. Decide: continue, change and re-run, or stop. Write one paragraph on why.
5. If you want to publish the result, use the [case-study template](case-study-template.md) and get the named permissions
   in its consent checklist **before** anything is written down for publication.

## What the report does not tell you

It does not prove the agent *caused* a change, only what was recorded while it ran. It does not know about conversations
that happened outside the connected channels. It does not measure customer satisfaction unless you collect feedback in
the product. ROI is *based on your assumptions*: change the assumption and the ROI changes.

## Showing the product without a customer

`scripts/vegaduta seed-demo` fills a tenant with fictional **Example** data and puts an *Example* watermark on the
Cost & ROI page and on every report generated from it, so screenshots cannot be mistaken for results. See
[Example data](#example-data).

## Example data

1. Sign up a tenant named `Example Demo` (or pass `--tenant NAME`) the normal way.
2. `scripts/vegaduta seed-demo` writes four example agents, a few hundred example conversations, outcomes, an example
   price and an example baseline. It refuses a tenant that already holds real data.
3. `scripts/vegaduta seed-demo --remove` deletes only what it wrote.

Never show an Example tenant's numbers as a customer result, in a deck, a screenshot or a sentence.

## Reference

- API: `GET/PUT /api/tenant/roi/agents/{id}/baseline`, `GET /api/tenant/roi/agents/{id}/pilot-report?from&to&format=html|pdf|json`
- Definitions and where each number comes from: [Cost and ROI](../cost-and-roi.md)
