# Data and privacy

| | |
|---|---|
| Sidebar | Administration > **Data & privacy** |
| Route | `/admin/data-privacy` |
| Nav needs | `PERM_MANAGE_DATA_GOVERNANCE` |
| Backend rule | `/api/tenant/privacy/**`, `POST /api/tenant/data-export` and `POST /api/tenant/data-deletion` all need `PERM_MANAGE_DATA_GOVERNANCE` (every verb, reads included: erase is irreversible and the audit export reads every event) |

The page is the scoped home for "DPDP-oriented controls for this workspace: consent, retention, purposes,
per-contact requests and audit export." The page text is explicit that these are engineering controls, not legal
advice or a statement of compliance; the wording "DPDP-oriented" is used on purpose and the product never claims to
be "compliant". What is and is not covered is in [DPDP obligations](../compliance/dpdp-obligations.md); the incident
procedure is the [personal data breach runbook](../operations/breach-runbook.md). The same workspace section also
exists inside [Maintenance](administration.md#maintenance-adminmaintenance) for full tenant-admins, whose page is
role-gated.

A caller without the scope is redirected to `/`. The page has six tabs, loaded one at a time:

| Tab | Purpose |
|---|---|
| Consent policy | The notice an agent shows before it processes a contact's data. |
| Retention policy | How long each kind of data is kept. |
| Purposes | Purpose tags on agents and knowledge collections. |
| Contact requests | Export or erase the data held for one contact. |
| Audit export | Download audit events for a period. |
| Workspace | Tenant-wide data export and deletion. |

## Consent policy

Per agent. Choose an agent (an empty workspace is told to create one first); the form shows the policy's notice
version and who last updated it, or "No consent policy is set for this agent yet. Saving creates one."

- **Ask for consent before processing** (on or off).
- **Purpose**: 1 to 64 characters of a-z, 0-9, hyphen or underscore, starting with a letter or digit.
- **Default language**: a language tag such as `en` or `hi`.
- **Notices**: one notice per language, up to 3000 characters each. Enabling consent needs a notice in the default
  language.
- **Reply instructions** (optional): per language, telling the contact how to accept, decline or withdraw.
- **Accept, Decline and Withdraw keywords**: comma-separated words a contact can reply with.
- **Erase the contact's data when they withdraw consent** (on or off).

Changing any notice text raises the notice version, so contacts who agreed to the old words are asked again. The
form validates these rules before saving and the server enforces them too. API: `GET/PUT /api/tenant/privacy/consent-policies/{agentId}`
(a missing policy answers 404, shown as the empty state).

## Retention policy

A table of retention rules and a form to add or change one. A rule for a channel overrides the tenant default;
"Expired data is purged automatically." The data classes are **Message transcripts**, **Call recordings** and
**Extracted fields (memory)**. Per rule: data class, channel (or **Tenant default (all channels)**), **Keep for
(days)** (1 to 3650) and whether the rule is active. Recordings apply to the tenant default or voice; extracted fields
to the tenant default only. The list uses the shared pager (client-side). Saving can return warnings, for example when
a period is shorter than 365 days, which the server explains with the rule it relates to and notes that whether it
applies to you is for counsel to confirm.

The purge itself is the nightly job `PRIVACY_RETENTION_ENABLED` / `PRIVACY_RETENTION_CRON` (see
[configuration](../configuration.md)). It is on by default but deletes nothing for a tenant until that tenant enables a
rule; an enabled rule with no period uses 365 days. API: `GET/PUT /api/tenant/privacy/retention-policies`.

## Purposes

Tag agents and knowledge collections with the purposes they serve, "so data collected for one purpose is not used for
another." Pick the resource type (**Agent** or **Knowledge collection**), choose one, add or remove purposes and
save. An untagged resource reads "No purposes set (untagged)." API:
`GET/PUT /api/tenant/privacy/purposes/agents/{id}` and `.../purposes/knowledge-collections/{id}`.

## Contact requests

Export or erase the data held for one contact, identified by **channel** and **contact identifier** (the id as the
channel reports it, for example a phone number or email address). Every request is recorded in the audit log.

- **Include leads** and **Keep consent proof** (keeping the consent ledger lets you show later that consent was asked
  for and given; turn it off to erase that too).
- **Export contact data** shows a report ("Export for <subject>", the time generated, a list of what is **not
  included** in the export) and **Download JSON**.
- **View consent history** lists the contact's consent decisions (decided time, decision, notice version) with the
  shared pager, or "No consent records".
- **Erase contact data** asks for confirmation ("This permanently erases the stored data for <channel> contact
  <id>. It cannot be undone.", with a line saying whether the consent record is kept) and then shows an erasure
  report: "Erasure completed", or "Erasure is incomplete. Some steps failed and need attention" with the failed steps,
  and a list of what erasure did not reach.

API: `POST /api/tenant/privacy/contacts/export`, `POST /api/tenant/privacy/contacts/erase`,
`GET /api/tenant/privacy/consent-records?channel=&externalId=`.

## Audit export

Download the audit events for a period as **CSV or JSON**, "for example to investigate a breach". Fields: **From
(UTC)**, **To (UTC, inclusive)** and format. The range may not be longer than 366 days; the page checks both dates
and the order before calling. The export is itself recorded in the audit log. API:
`GET /api/tenant/privacy/audit-export?from=&to=&format=csv|json`, streamed.

## Workspace

Tenant-wide export and deletion that applies to the whole workspace, not one user.

- **Request data export** downloads a JSON export (`POST /api/tenant/data-export`; the file name comes from the
  response's `Content-Disposition`, default `tenant-data-export.json`).
- **Request account deletion** is irreversible (it drops the workspace schema). The button stays disabled until you type
  the workspace's organisation name exactly (`POST /api/tenant/data-deletion` with `confirmOrgName`).

Neither action goes through the shared API client; they use plain authenticated requests so the browser can handle the
file download.
