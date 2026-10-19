# WhatsApp policy: what Meta requires, and what VegaDūta enforces

All facts below were fetched from Meta's own pages on **2026-10-14**. A statement
that could not be confirmed from a Meta page is marked **unconfirmed** and nothing
in the product is built on it. Meta changes these terms often; re-verify before
relying on any date or rate (Meta's pricing calendar only changes rates on
1 Jan, 1 Apr, 1 Jul or 1 Oct).

## 1. General-purpose AI assistants

Source: WhatsApp Business Platform Terms, section 4.7 "AI Providers"
<https://www.facebook.com/legal/Meta-Terms-for-WhatsApp-Business-Platform>
(page "last updated September 23, 2026"; fetched 2026-10-14).

Confirmed from that page (paraphrased except the two short quotes):

- "AI Providers" are developers and providers of AI/ML technologies, such as
  large language models, generative AI platforms and general-purpose AI
  assistants. Meta decides, at its sole discretion, who counts.
- AI Providers "are strictly prohibited from accessing or using the WhatsApp
  Business Platform" (directly or indirectly) to offer those technologies when
  they are the *primary* functionality, rather than incidental or ancillary.
  Meta decides what counts as primary.
- The section allows those technologies to be made available in certain
  countries, listed on Meta's AI Providers pricing page.
- A business may keep an AI Provider as its Solution Provider, but the platform
  data may not be used to build, train or improve AI models (fine-tuning a
  model for the business's own exclusive use is allowed).
- The pricing page dates the terms update to **15 January 2026**
  (<https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing/ai-providers>,
  fetched 2026-10-14).

Regions, from that same pricing page, where Meta says it is legally required to
permit general-purpose AI assistants: Italy (+39, from 2026-02-16), Brazil (+55,
from 2026-03-11) and 29 European countries (2026-03-11 to 2026-05-12). The page
says Meta charged AI Providers per non-template message from 2026-02-16 and
"will no longer charge" from 2026-05-13. It does **not** say whether the
European arrangement ended or continued after 2026-05-12: **unconfirmed**. Treat
the exceptions as unstable and do not build a product on them.

What the page does **not** say: that a business running an AI agent for a
specific purpose (support, bookings) is restricted. The restriction targets
products whose *primary* offering is the AI assistant itself. News coverage
describes customer-service bots as unaffected; that is third-party reporting
and is **unconfirmed** by Meta's own pages.

Practical consequence for VegaDūta: an agent attached to a WhatsApp number must
have a defined business task. An open-ended "ask me anything" agent is the
shape Meta's terms target. Meta, not VegaDūta, makes the final call.

## 2. Opt-in, templates, frequency, quality

Opt-in and templates: WhatsApp Business Messaging Policy
<https://whatsappbusiness.com/policy/> (page "last updated September 23, 2026";
fetched 2026-10-14). Quotes: "You may only contact people on WhatsApp if: (a)
they have given you their mobile phone number or username" (section 1) and "You
may only initiate conversations using an approved Message Template" (section 2).

| Topic | Confirmed | Source |
|---|---|---|
| Opt-in | Contact only people who gave you their number/username (section 1; the rest of the clause was not retrievable). | policy page above |
| Marketing-specific opt-in wording | **unconfirmed** (not retrieved). VegaDūta therefore requires an opt-in record for every template send, not only marketing. | n/a |
| Business-initiated messages | Must start with an approved template. Only `APPROVED` templates can be sent; review can take up to 24 h. Non-template messages are free-form only inside the 24 h customer service window. | templates overview, pricing page |
| Per-user marketing cap | Exists: Meta limits how many marketing templates one WhatsApp user can receive from any business. **The number is not published** on the pages fetched. | templates overview |
| Quality | Template quality is Quality pending / High / Medium / Low; Low risks pause, paused and disabled templates cannot be sent. Message limits depend on "high quality" and user feedback. | templates overview, policy section 7 |
| Messaging limit tiers | 250 (new default), 2,000, 10,000, 100,000, Unlimited unique users per rolling 24 h outside service windows. Field: `whatsapp_business_manager_messaging_limit`. | messaging limits page |
| Phone number quality | `quality_rating` is a field on the phone number node; the examples show `GREEN`, `NA`, `UNKNOWN`. Other values (`YELLOW`, `RED`) were **not confirmed** on that page. | manage phone numbers page |

URLs (all fetched 2026-10-14):

- <https://developers.facebook.com/documentation/business-messaging/whatsapp/templates/overview>
- <https://developers.facebook.com/documentation/business-messaging/whatsapp/messaging-limits>
- <https://developers.facebook.com/docs/whatsapp/business-management-api/manage-phone-numbers>

### Error codes VegaDūta reacts to

Source: <https://developers.facebook.com/docs/whatsapp/cloud-api/support/error-codes>
(fetched 2026-10-14).

| Code | Meaning (Meta) | VegaDūta behaviour |
|---|---|---|
| 131049 | Not delivered "to maintain healthy ecosystem engagement"; if you suspect the marketing limit, wait at least 24 h, earlier resends fail again. | Pause marketing templates to that recipient for 24 h. A template whose category cannot be looked up is treated as marketing. |
| 131050 | Recipient stopped receiving marketing messages from the business. Do not retry. | Pause marketing templates to that recipient until a fresh opt-in is recorded. Utility templates are unaffected. |
| 131048 | Sending restricted, likely too many blocked/spam-flagged messages. Check quality status. | Surface the error; no per-recipient state. |
| 132015 / 132016 | Template paused for low quality / permanently disabled. | Surface the error; fix the template. |
| 130429 / 80007 | Throughput / account rate limit; try later or reduce frequency. | Surface the error. |

## 3. Pricing

Source: <https://developers.facebook.com/documentation/business-messaging/whatsapp/pricing>
and <https://developers.facebook.com/docs/whatsapp/pricing>, fetched 2026-10-14.

- Pricing is **per message** (since 2025-07-01); conversation-based pricing is
  gone.
- Template categories: **marketing, utility, authentication.** "Service" appears
  only as the category of free non-template messages.
- Only delivered template messages are charged; rates vary by category, volume
  tier and the recipient's country calling code.
- Non-template messages are free inside an open 24 h customer service window.
  Utility templates sent inside an open window are free. A 72 h window opened by a
  Click-to-WhatsApp ad or Page call-to-action makes all messages free.
- Volume tiers lower utility and authentication rates monthly, per market and
  category.
- Rate cards in effect from 2026-07-01; the next scheduled update is
  2026-10-01 (final rates announced by 2026-09-01). **Whether the 2026-10-01 rates
  have been published and what they are: unconfirmed.**
- **Numeric rates: unconfirmed.** The pricing page text contains no rates. They
  live in an interactive widget at <https://whatsappbusiness.com/products/platform-pricing/>
  and in per-currency CSV/PDF rate cards that were not retrievable. The figures
  the cost calculator used before (marketing ₹0.8631, utility ₹0.115–0.145) came
  from third-party blogs and are no longer shown as defaults.

## 4. What the product enforces

| Rule | Where |
|---|---|
| Binding an agent to WhatsApp needs a declared task (support, booking, orders, lead qualification, reminders, payments follow-up), stored on the channel binding with who and when. | `PUT /api/channels/whatsapp/default-agent` (fields `task`, `acknowledgeOpenEnded`). `PUT /api/channels/routing/whatsapp` refuses to change the agent (it only flips modes for the current agent); `channel_default_agent.declared_task*` (V492) |
| Open-ended prompt/tools raise a warning (HTTP 409 `WHATSAPP_OPEN_ENDED_AGENT`); the tenant can proceed with `acknowledgeOpenEnded=true`; the override is stored and written to the audit log. Not a hard block. | `OpenEndedAssistantCheck`, `WhatsAppBindingPolicy` |
| Template sends need an active opt-in record for the recipient (record with `POST /api/channels/whatsapp-compliance/opt-in`, revoke with `DELETE`; `GET` lists the tenant's records newest first with `limit`, `offset`, `activeOnly` and `X-Total-Count`). | `WhatsAppSendGuard`, `whatsapp_opt_in` |
| 131049 / 131050 pause marketing templates to that recipient. | `WhatsAppSendGuard`, `whatsapp_recipient_backoff` |
| Phone number quality rating shown on the channel page when the API returns it. | `GET /api/channels/whatsapp-compliance/quality` |

Limits of this guard: the open-ended check is a keyword heuristic and cannot
predict Meta's decision; per-conversation `rebind` of an existing session, and
contact/group agent overrides, do not re-run the check; the AI-generated-agent and brief-apply paths refuse to bind WhatsApp at all (connect it on the Channels page); free-form replies inside
the 24 h window are not gated by opt-in records (the user messaged first).

## 5. Console

On the Channels page, the WhatsApp section shows three cards: the binding card (declared task and
the open-ended warning), the phone number quality card, and an **opt-in card**
(`web/app/components/channels/WhatsAppOptInCard.tsx`) where a tenant records a recipient's opt-in
(phone number, a source of `website_form`, `inbound_message`, `checkout`, `in_person` or `import`,
and optional evidence text) or withdraws it by number. The card's table lists the records handled
in the current browser session; it does not load the full list from the server. Numbers recorded
earlier can still be revoked by typing them. Opt-in is the tenant's own record of consent: the
console does not verify that the consent was real, and Meta's rules in section 2 still apply.

See also the [console guide](../console/README.md).
