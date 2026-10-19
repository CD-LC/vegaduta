# Personal data breach runbook (template)

> **A template, not legal advice.** Fill in the bracketed parts for your organisation and have
> counsel confirm the legal steps. The duties summarised here are from the Digital Personal Data
> Protection Act, 2023 (section 8(6)) and the DPDP Rules, 2025 (rule 7), which come into force on
> 13 May 2027 (rule 1(4)). See [DPDP obligations](../compliance/dpdp-obligations.md) for the
> sources and the open questions.

**Who this is for.** A tenant (the Data Fiduciary for the people who message its agents) or the
operator of a VegaDūta deployment (the Data Processor), after something has happened to personal
data: unauthorised access, disclosure, alteration, loss or destruction.

| Role | Name | Contact |
|---|---|---|
| Incident lead | [name] | [phone, email] |
| Data protection contact (published in your notice) | [name] | [email] |
| Legal counsel | [name] | [phone, email] |
| Platform operator (if you are a tenant on someone else's deployment) | [name] | [phone, email] |

## 1. Clock

| When | What | Basis |
|---|---|---|
| T0: you become aware | Write down the exact time. Everything below counts from it | Rule 7(2)(b) |
| Without delay | Tell each affected person | Rule 7(1) |
| Without delay | Tell the Data Protection Board of India: description, nature, extent, timing, location, likely impact | Rule 7(2)(a) |
| Within 72 hours of T0 (or longer if the Board allows, on your written request) | Updated and detailed report to the Board | Rule 7(2)(b) |

The platform does not run this clock for you.

## 2. First hour

1. Record T0, who noticed, and how.
2. Contain: rotate the exposed credentials ([key rotation](key-rotation.md)), revoke the exposed
   API keys and sessions, and, if a whole tenant is affected, use the tenant emergency stop.
3. Preserve evidence. Do not delete anything, including audit entries (they cannot be edited in
   any case, tenant migration V75).
4. Tell the platform operator if you are not the operator.

## 3. Pull the audit events

Each tenant's audit log is in that tenant's own schema, so the export for a tenant contains only
that tenant's events.

```bash
# Replace the host, token and window. from is inclusive, to is exclusive; at most 366 days.
curl -sS -H "Authorization: Bearer $TOKEN" \
  "https://[your-host]/api/tenant/privacy/audit-export?from=2026-06-01T00:00:00Z&to=2026-06-08T00:00:00Z&format=csv" \
  -o audit-events.csv

# JSON adds the signed audit seals covering the window, for tamper evidence.
curl -sS -H "Authorization: Bearer $TOKEN" \
  "https://[your-host]/api/tenant/privacy/audit-export?from=2026-06-01T00:00:00Z&to=2026-06-08T00:00:00Z&format=json" \
  -o audit-events.json
```

The caller needs the `PERM_MANAGE_DATA_GOVERNANCE` permission. The export is recorded in the audit
log (`privacy.audit.export`). Columns: `id, created_at, actor_id, action_type, target_type,
target_id, outcome, detail`. Cells that start with `=`, `+`, `-` or `@` are prefixed with `'` so a
spreadsheet does not run them; remove the prefix when you analyse the data.

Useful `action_type` values to look for: `authz.denied`, `privacy.contact.export`,
`privacy.contact.erase`, `privacy.consent.*`, `privacy.retention.purge`,
`channel_session.erase_data_subject`, and the channel and tool actions your agents use.

**What the export is not.** It is what the platform recorded. It does not show every read of
personal data, and it does not include application server logs, database logs, provider logs or
backups. Collect those from their owners.

## 4. Work out who was affected

The platform does not produce this list. From the audit events and your own investigation:

1. Which tenant(s), agents and channels were involved, and for what time window.
2. Which contacts (channel plus identifier) had data in those places. Per-contact detail is
   available with `POST /api/tenant/privacy/contacts/export` (messages, memory, consent history),
   which is itself audited. The identifier in audit entries is masked to its last four characters.
3. Which kinds of data: messages, memory facts, recordings, CRM leads, credentials.
4. Whether any data left your control (an exposed bucket, a leaked key used from elsewhere).

## 5. Tell the affected people (rule 7(1))

Send through the channel they used or another contact they registered. Plain language. Include:

- what happened: its nature, extent and when it happened;
- the consequences likely to affect them;
- what you have done and are doing to reduce the risk;
- what they can do to protect themselves;
- the name and contact of a person who can answer their questions: [contact].

Draft:

> On [date] we found that [what happened] affected [kinds of your data] held for [purpose, e.g.
> your support conversations with us]. This happened between [start] and [end]. The likely effect
> on you is [consequences]. We have [actions taken]. You can [protective steps, e.g. change the
> password you used, watch for messages asking for codes]. For questions, contact [name, email,
> phone].

The platform can send messages to channel contacts through an agent or a workflow, but it does not
generate or track breach notices. Keep your own record of who was told, when and how; the Board
report asks for it (rule 7(2)(b)(vi)).

## 6. Tell the Board (rule 7(2))

Without delay: a description of the breach with its nature, extent, timing and location, and the
likely impact. Use the Board's channel when it publishes one: [Board contact or portal, to be
confirmed by counsel].

Within 72 hours of T0 (or the period the Board allows on written request): the updated and
detailed information:

- the broad facts: events, circumstances and reasons leading to the breach;
- the measures taken or proposed to reduce the risk;
- findings about the person who caused it;
- remedial measures to prevent a repeat;
- a report on the notices given to the affected people.

## 7. Afterwards

- Attach the audit export and its seals to the incident file.
- Review whether retention is too long for the data involved
  (`PUT /api/tenant/privacy/retention-policies`).
- Review the consent notice and purpose tags if the breach touched data used outside its purpose.
- Write the post-incident review: cause, timeline, what worked, what to change.

## 8. Limits to keep in mind

- The audit log is append-only and sealed, but it only holds what code paths chose to record.
- Backups hold older copies of everything and are not edited per contact.
- Roles (fiduciary or processor) and the legal timing are for counsel to confirm.
