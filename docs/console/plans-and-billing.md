# Plans and billing

Both rows of the **Plans & billing** group exist only when billing is on. Self-hosted installs have no plans, prices
or payment processor, so the whole group, the public `/pricing` and policy pages and every upgrade prompt are hidden
unless the operator sets `NEXT_PUBLIC_BILLING_ENABLED=1` (Compose: `WEB_BILLING_ENABLED=1`; see [configuration](../configuration.md)). With billing off, `/billing`, `/features` and
`/admin/billing` redirect (`/admin/billing` to `/usage`, which then carries the model-spend section for holders of
`PERM_MANAGE_BILLING`). The **Usage** row lives under [Test and monitor](test-and-monitor.md#usage-usage) for the same
reason.

| Label | Route | Nav needs | Backend rule |
|---|---|---|---|
| Plans & Features | `/features` | any member, billing on | reads open; `POST /api/tenant/upgrade` needs `PERM_MANAGE_BILLING` |
| Billing | `/admin/billing` | `PERM_MANAGE_BILLING`, billing on | invoices, billing profile, upgrade, subscription cancel and payment checkout need `PERM_MANAGE_BILLING` |

## Plans & Features (`/features`)

A tenant-facing comparison of the Free, Premium and Unlimited tiers: what each tier unlocks and what this workspace's
plan currently includes. The columns come from a canonical per-tier reference matrix; the current workspace's live
entitlements are read from `GET /api/tenant/settings`, so a platform administrator's plan-limit edit shows up here. It
is open to every member so anyone can see the upgrade value; the upgrade action itself is a billing-permission
action (`POST /api/tenant/upgrade`, payment gateways from `GET /api/billing/gateways`). The page uses inline English
copy rather than the message bundle.

## Billing (`/admin/billing`)

Titled "Billing & usage": "Your plan, invoices, billing details and AI spend for this workspace." This is the
authenticated in-console counterpart to the public pre-signup `/billing` page. It shows:

- the **plan card** with the single primary action, **Upgrade plan**, a trial banner when the workspace is inside its
  free period, and **Cancel subscription** (the plan stays active to the end of the billing cycle, then moves to Free;
  nothing is deleted);
- **usage versus plan limits** as bars for the caps the platform actually enforces (seats, agents, workflows). The
  page states that message-volume and knowledge-storage metering are not built;
- **Invoices** (paged, each opens in a new tab) and **Billing profile & tax details** (printed on tax invoices;
  GSTIN optional);
- **Payment method** and **Billing contact** rows, which are honest empty states: there is no backend model for them
  yet and the page says so;
- **Refer a friend** (a referral code to copy, a count of redemptions and a field to redeem a friend's code);
- **Get help** (support ticket and a copyable diagnostic summary);
- the **model spend and call log** section (`#llm-usage`), the per-provider estimated cost, today's spend and the call
  list.

API: `GET /api/tenant/invoices` and `/api/tenant/invoices/{id}/html`, `PUT /api/tenant/billing-profile`,
`POST /api/tenant/subscription`, `POST /api/tenant/subscription/cancel`, `POST /api/tenant/upgrade`,
`GET /api/tenant/referral`, `POST /api/tenant/referral/redeem`, `GET /api/billing/gateways`,
`/api/billing/checkout/**`, `/api/admin/llm-usage/**` (`PERM_VIEW_USAGE`). The cost view for agents is
[Cost and ROI](cost-and-roi.md).
