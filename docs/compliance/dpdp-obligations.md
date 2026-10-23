# DPDP obligations and the controls that support them

> **This is engineering work, not legal advice.** Every reading of the law below is the
> engineering team's plain-language summary. Each one is **to be confirmed by counsel** before
> anyone relies on it. This page does not say that VegaDūta, a deployment, or a customer is
> "DPDP compliant". Compliance belongs to the organisation that decides why and how personal data
> is processed, and the law has not finished coming into force.

## 1. What was checked, and how

The text below was read from the official Government of India publications on 2026-10-14:

| Instrument | Official source read | Identification in the text |
|---|---|---|
| Digital Personal Data Protection Act, 2023 (the **Act**) | [meity.gov.in, Act PDF](https://www.meity.gov.in/static/uploads/2024/06/2bf1f0e9f04e6fb4f8fef35e82c42aa5.pdf) | Act No. 22 of 2023, Gazette of India Extraordinary, Part II, Section 1, 11 August 2023 |
| Digital Personal Data Protection Rules, 2025 (the **Rules**) | [meity.gov.in, Rules PDF](https://www.meity.gov.in/static/uploads/2025/11/53450e6e5dc0bfa85ebd78686cadad39.pdf) | G.S.R. 846(E), Gazette of India Extraordinary, Part II, Section 3(i), "New Delhi, the 13th November, 2025", F. No. AA-11038/1/2025-CLandES |

Both PDFs were downloaded and their text extracted; section and rule numbers below are quoted
from that text.

**Not verified.** Treat each as open until counsel or the operator has checked it:

- The copies on the e-Gazette portal (egazette.gov.in) were not opened. The MeitY PDFs carry the
  Gazette header and notification text, but they are not the certified e-Gazette copies.
- The notification under Act section 1(2), which brings the Act's own sections into force, was
  **not read**. Section 1(2) says the Act comes into force on the date the Central Government
  appoints by notification, and different dates may be set for different provisions. So this page
  cannot say which Act sections are in force on any given day.
- Some press reports and PIB announcements give 14 November 2025 for the Rules. The Gazette
  notification itself says 13 November 2025, which is the date used here.

## 2. Dates, each with its official source

| Date | What | Source |
|---|---|---|
| 11 Aug 2023 | The Act was published after Presidential assent | Act, Gazette header ("11th August, 2023") |
| 3 Jan 2025 | Draft Rules published, notification G.S.R. 02(E), for objections and suggestions | Rules, preamble of G.S.R. 846(E) |
| 13 Nov 2025 | The Rules were made and notified | Rules, G.S.R. 846(E), "New Delhi, the 13th November, 2025" |
| 13 Nov 2025 | In force on publication: rules 1, 2 and 17 to 21 (title, definitions, and the Data Protection Board's constitution and procedure) | Rules, rule 1(2) |
| 13 Nov 2026 | Rule 4 (registration and obligations of Consent Managers) comes into force "one year after the date of publication" | Rules, rule 1(3). The date is computed here from the publication date |
| 13 May 2027 | Rules 3, 5 to 16, 22 and 23 come into force "eighteen months after the date of publication". This includes the notice, security, breach, retention and erasure, children's data and Data Principal rights rules | Rules, rule 1(4). The date is computed here; whether the period ends on 13 or 14 May is for counsel |

On the date this page was written (2026-10-14), by rule 1(2) and 1(4), only rules 1, 2 and 17 to
21 are in force. The duties that matter most to a chat platform (notice, security safeguards,
breach intimation, retention and erasure, children) start on the rule 1(4) date.

## 3. Who is who

The Act puts the duties on the **Data Fiduciary**, the person who decides the purpose and means
of processing (section 2(i)). A **Data Processor** processes on the fiduciary's behalf (section
2(k)). The Act makes the fiduciary responsible for processing "undertaken by it or on its behalf
by a Data Processor" and allows a processor to be used "only under a valid contract" (section
8(1), 8(2)).

This page assumes the usual shape for this platform, and counsel should confirm it: **the tenant
is the Data Fiduciary** for the people who message its agents (the "contacts" or Data Principals),
and **whoever runs the VegaDūta deployment is the Data Processor**. For a self-hosted deployment
both roles may be the same organisation. The controls below are what the platform offers so that
the tenant, as fiduciary, can meet its duties and can have its processor do what the Act requires
of a processor (section 6(6), 8(7)(b)).

## 4. Obligations and what the platform does about them

"Today" means what is in the repository. Paths are relative to the repository root. "Missing" is
what the code does not do, stated as plainly as the rest.

### 4.1 Notice

- **Provision.** Act s.5(1): a request for consent is accompanied or preceded by a notice that
  says what personal data and what purpose, how the person can withdraw consent and exercise
  rights (s.6(4), s.13), and how to complain to the Board. s.5(3): the person can read the notice
  in English or any Eighth Schedule language. Rule 3: the notice must stand on its own, be in
  clear and plain language, give an itemised description of the data and the specified purposes,
  and give the link and other means to withdraw consent (as easily as it was given), to exercise
  rights and to complain to the Board. In force 13 May 2027.
- **For a processor running agents.** The tenant writes and owns the notice. The platform has to
  be able to show it, in more than one language, before any processing of a new contact.
- **Today.** Per-agent opt-in notice step with the notice text per language and a `LANG xx`
  reply to switch language: `core/app/.../common/privacy/ConsentGateService.java`,
  `ConsentPolicyService.java`, table `privacy_consent_policy`
  (`core/app/src/main/resources/db/migration/tenant/V495__dpdp_oriented_controls.sql`). The gate
  runs first in the chat turn (`core/agents-turn/.../turn/ChatTurnPreflight.java`).
  The earlier AI-disclosure line ("you're chatting with an AI") is a separate one-time notice
  (`ChannelSessionService.consumeAiDisclosurePrefix`).
- **Missing.** No template that satisfies rule 3. The platform stores whatever text the tenant
  writes and does not check that it lists the data items, purposes, withdrawal route, rights
  route and Board-complaint route. Only channel conversations are covered (see 4.2). The notice
  is sent as a chat message, so whether that meets "presented and understandable independently"
  is for counsel.

### 4.2 Consent

- **Provision.** Act s.6(1): free, specific, informed, unconditional and unambiguous, "with a
  clear affirmative action", for the specified purpose and limited to the data necessary for it.
  s.6(4) to 6(6): withdrawal at any time, as easily as consent was given; on withdrawal the
  fiduciary must "cease and cause its Data Processors to cease" processing, unless law requires
  or allows it. s.6(10): the fiduciary must be able to prove that notice was given and consent
  was given. Rule 3(c)(i) repeats the ease-of-withdrawal point.
- **Today.** While an agent has the consent step on, a channel contact who has not agreed to the
  current notice version gets the notice, and nothing else happens: no model call, no tool, no
  memory write, no cache lookup (`ConversationConsentGate.intercept`, tested in
  `AgentChatServiceConsentGateTest`). Their message text is not written to the transcript or kept
  as the session preview (`ChannelSessionService.resolveOrCreate`, tested in
  `ChannelSessionServiceTest`). Agreement is a clear word (`YES`, `I agree`, `haan`, `हाँ`, or the
  tenant's own words); `ok`, `y` and sentences that only contain the word do not count, and
  neither does a reply longer than 512 characters (`ConsentTextTest`). Every decision is an append-only row with timestamp, channel, contact id,
  agent, purpose, notice version, notice language and the SHA-256 of the exact notice text shown
  (`ConsentLedger`, table `privacy_consent_record`). Editing the notice text raises the version
  and asks everyone again. `STOP` withdraws consent and, unless the tenant switched it off,
  erases that contact's data (`ConsentGateService.withdraw`).
- **Missing.**
  - The identifier the channel hands us (phone number or chat id) and the session row are
    processed in order to send the notice. That is unavoidable and is not gated.
  - Workflow-trigger channels, outbound campaigns and `send_channel_message` to a contact who
    never wrote in do not pass through the chat turn and are **not** gated. Voice calls
    were not checked; the gate only recognises a turn that belongs to a `channel_session`, so
    treat voice as not gated.
  - The first message the person wrote is discarded; they are asked to send it again.
  - There is no consent step for the web console chat or the public share link.
  - The platform does not register as, or integrate with, a Consent Manager (rule 4).

### 4.3 Purpose limitation

- **Provision.** Act s.4(1): process personal data only for a lawful purpose for which consent
  was given, or for a certain legitimate use (s.7). s.6(1): consent is for a *specified* purpose.
- **Today.** Tenants can tag knowledge collections and agents with purposes
  (`PUT /api/tenant/privacy/purposes/...`, table `privacy_purpose_tag`,
  `PurposeService.java`). A tagged collection is closed to an agent that does not carry a matching
  purpose; the purpose of an enabled consent policy counts as the agent's purpose. Enforced where
  an agent is given its knowledge tools (`AgentKnowledgeToolResolver`, search, list and write),
  tested in `DpdpControlsIT`.
- **Missing.** An untagged collection is open (so nothing changes until a tenant tags). Not
  enforced for workflow nodes, direct knowledge API calls, agent or tenant memory rows, or the
  CRM. A purpose is a free-form tag, not a defined list. The platform cannot tell whether a
  workflow is a "marketing flow".

### 4.4 Retention

- **Provision.** Act s.8(7)(a): erase personal data when consent is withdrawn or "as soon as it
  is reasonable to assume that the specified purpose is no longer being served", unless law
  requires retention; s.8(8) and (11): the purpose is deemed no longer served after a prescribed
  period without the person approaching the fiduciary or exercising rights. Rule 8(1) and the
  Third Schedule set that period (three years from last contact) only for e-commerce entities
  with at least two crore registered users, online gaming intermediaries with at least fifty
  lakh, and social media intermediaries with at least two crore; rule 8(2) requires warning the
  person at least 48 hours before such an erasure. **Rule 8(3)** and rule 6(1)(e) go the other
  way: a fiduciary must keep personal data, associated traffic data and logs of processing for
  **at least one year** (for the purposes in the Seventh Schedule), and then erase them unless
  other law requires longer. In force 13 May 2027.
- **Today.** Per-tenant and per-channel retention periods for transcripts (`channel_message_log`
  and the session preview), voice recordings (`voice_call_recording` and the stored audio) and
  extracted fields (agent memory and tenant memory), enforced nightly by
  `RetentionPurgeJob` through `RetentionPurger`, one tenant at a time, with an audit event
  `privacy.retention.purge` for each rule that removed or failed to remove something.
  **Defaults:** nothing is purged until a tenant enables a rule; an enabled rule without a period
  gets **365 days**, which is the rule 8(3) one-year floor; a shorter period is allowed but the
  response carries a warning (`RetentionPolicyService`). A recording whose audio cannot be
  deleted keeps its row and is retried the next night. Session conversation memory in Redis
  already expires on its own TTL.
- **Missing.** Graph memory is not purged by age (graph vertices carry no creation time). The
  operational logs `llm_call_log`, `tool_call_log` and `sarvam_call_log`, CRM leads, workflow run
  data and generated documents have no retention rule here. The audit log is append-only by
  design and is not purged. The rule 8(2) 48-hour advance warning to the person is not
  implemented (it applies only to the Third Schedule classes). Backups are outside the platform.
  The platform does not decide whether the one-year floor applies to a tenant.

### 4.5 Erasure and access

- **Provision.** Act s.12(3): on request, erase the person's data unless retention is necessary
  for the specified purpose or to comply with law. s.8(7)(b): the fiduciary must cause its
  processor to erase data it made available. s.11: the person can obtain a summary of the
  personal data processed and the processing, and the identities of those it was shared with.
  Rule 14: means to make requests must be published; rule 14(3) a grievance system that answers
  within a period not exceeding ninety days. In force 13 May 2027.
- **Today.** One call erases or exports one contact in the caller's tenant
  (`POST /api/tenant/privacy/contacts/erase` and `.../export`, `ContactPrivacyService.java`).
  Erase reaches: the contact's messages, the contact and session rows, agent memory across
  every agent, shared tenant memory including memory blocks, graph memory entities, the live
  conversation window in Redis, knowledge chunks the agent wrote while talking to that contact
  (matched through the session id the write tool stamps in the chunk's `source`), email send
  records (email channel), optionally CRM leads matched by email or phone, and the consent
  ledger (unless the caller keeps it as proof). The response lists what was erased with counts,
  any step that failed (`complete: false`) and **what it does not reach**. Older, narrower
  paths still exist: `POST /api/channels/pairing/erase-subject`, the user's own forget-all in
  `UserMemoryController`, and whole-tenant erasure in `TenantDataErasureService`.
  Tested in `DpdpControlsIT`, including that derived data is removed and another tenant's
  identical contact is untouched.
- **Missing, and returned in every response.** Offline and database backups and replicas; copies
  held by the channel provider, the LLM provider and any tool or MCP server; operational logs
  that may quote messages (`llm_call_log`, `tool_call_log`, `sarvam_call_log`); response caches;
  the append-only audit log (masked identifiers only); voice recordings and transcripts (not
  linked to a channel identity); device-ingested data; workflow outputs and generated documents;
  knowledge chunks that were not written by the agent's write tool; the email suppression list,
  kept on purpose so opt-outs keep working. Graph memory facts are erased but cannot be listed in
  an export. There is no self-service request form for the person themselves; a tenant admin runs
  the call. There is no verification that the requester is the contact.

### 4.6 Processor duties, security and logs

- **Provision.** Act s.8(2): a processor only under a valid contract. s.8(5): reasonable security
  safeguards. Rule 6(1): at least encryption or masking or tokens, access control, logs and
  monitoring that allow detection and investigation of unauthorised access, backups and
  continuity, retention of those logs for one year, contract terms with the processor, and
  technical and organisational measures. In force 13 May 2027.
- **Today.** See [compliance-posture](../platform-documentation/security/compliance-posture.html):
  tenant schema isolation, AES-256-GCM for stored credentials, role-gated routes, audit log
  (append-only trigger `V75`, signed seals `V422`), denied-access auditing. The operator
  deployment decides backups and monitoring.
- **Missing.** No data processing agreement template ships with the project. Audit coverage of
  every read of personal data is not claimed.

### 4.7 Personal data breach

- **Provision.** Act s.8(6). Rule 7(1): on becoming aware, tell each affected person, without
  delay and in plain language, through their user account or registered contact: what happened
  (nature, extent, timing), likely consequences, what is being done, what they can do, and a
  business contact. Rule 7(2): tell the Board without delay, and within **72 hours** (or longer if
  the Board allows on written request) give updated and detailed information: facts and causes,
  mitigation, findings about who caused it, remedial measures, and a report on the intimations
  given to people. In force 13 May 2027.
- **Today.** Time-range export of the tenant's audit events as CSV or JSON, with the signed seals
  that cover the range (`GET /api/tenant/privacy/audit-export`, `AuditExportService.java`), and a
  runbook: [breach-runbook](../operations/breach-runbook.md). The export is itself audited.
- **Missing.** Nothing detects or classifies a breach. No 72-hour clock, no Board intimation
  form, and no sender for the per-person messages. The contact list of "affected people" has to
  be worked out by the tenant from the audit events and the data stores.

### 4.8 Children and persons with disability under guardianship

- **Provision.** Act s.2(f): a child is under eighteen. s.9(1): verifiable consent of a parent or
  lawful guardian before processing a child's data; s.9(2): no processing likely to harm a child's
  well-being; s.9(3): no tracking, behavioural monitoring or targeted advertising directed at
  children. Rules 10 and 11 say how the parent or guardian is verified. Rule 12 and the Fourth
  Schedule list exemptions. In force 13 May 2027.
- **Today.** Nothing. The platform has no age signal for a channel contact and no parental
  consent flow.
- **Missing.** Everything above. A tenant that may be messaged by children must not rely on this
  platform to meet s.9 and must run its own age and guardian verification. The Fourth Schedule
  exemptions were not analysed here.

### 4.9 Contact point and grievance

- **Provision.** Act s.8(9) and rule 9: publish the business contact of the DPO or a person who
  can answer questions, and mention it in every response to a rights request. s.8(10), s.13 and
  rule 14(3): a grievance system with a stated response period of at most ninety days.
- **Today.** Nothing specific. The tenant puts the contact into its notice text.
- **Missing.** No dedicated field or workflow for a DPO contact or a grievance queue.

### 4.10 Other provisions not addressed

Significant Data Fiduciaries (s.10, rule 13), transfers outside India (s.16, rule 15; the platform
has only an informational residency field, `control_plane.tenant.data_residency_region`, V96),
Consent Managers (s.6(7) to (9), rule 4), nomination (s.14, rule 14(4)), and the exemptions in s.17
and rule 16 are out of scope for these controls.

## 5. Questions for counsel

1. Is the tenant the Data Fiduciary and the deployment operator the Data Processor, for the
   people who message tenant agents?
2. Rule 8(3) requires keeping personal data and logs for one year. Does that override erasure on
   withdrawal (the platform default is to erase) or on request, and for which data?
3. Does a chat reply of `YES` to a notice sent in the same chat meet "clear affirmative action"
   (s.6(1)) and the standalone-notice wording of rule 3?
4. Must the consent record be kept after the person's data is erased, as proof under s.6(10)?
   (Erase has a `keepConsentProof` option; the default deletes it.)
5. Does "eighteen months after the date of publication" end on 13 or 14 May 2027?
6. Which sections of the Act are in force on which date (the s.1(2) notification was not read)?
7. Does an agent that never asks for personal data still need consent for the identifier the
   channel supplies?

## 6. What the platform may say about itself

Use "**supports DPDP-oriented controls**" and list them. Do not write "DPDP compliant", "DPDP
certified" or similar anywhere. The controls, all opt-in per tenant or agent:

1. Per-agent notice and consent step for channel conversations, with a per-contact consent record.
2. Purpose tags on knowledge collections and agents.
3. Per-tenant and per-channel retention periods, enforced nightly, with an audit event per purge.
4. Per-contact export and erasure, with a list of what is not reached.
5. Audit-event export for a time range, and a breach runbook template.

Each is described, with its limits, in section 4.

## 7. Console

The controls above are all reachable from the console page **Admin > Data & privacy**
(`/admin/data-privacy`, `web/app/admin/data-privacy/`), which needs the `MANAGE_DATA_GOVERNANCE`
permission (the same permission `PrivacyController` requires). The page is titled "Data & privacy"
and describes itself as "DPDP-oriented controls", not as a compliance claim. It has six tabs:

| Tab | What it does | Endpoint(s) under `/api/tenant/privacy` unless noted |
|---|---|---|
| Consent policy | Per-agent notice text per language, version and on/off; recent consent records | `GET/PUT /consent-policies/{agentId}`, `GET /consent-records` |
| Retention policy | Retention days per data class (transcripts, recordings, extracted fields) and channel | `GET/PUT /retention-policies` |
| Purposes | Purpose tags on agents and knowledge collections | `GET/PUT /purposes/agents/{id}`, `GET/PUT /purposes/knowledge-collections/{id}` |
| Contact requests | Export or erase everything held for one contact | `POST /contacts/export`, `POST /contacts/erase` |
| Audit export | Audit events for a date range as CSV or JSON | `GET /audit-export` |
| Workspace | Export or delete the whole workspace's data | `POST /api/tenant/data-export`, `POST /api/tenant/data-deletion` |

The screens add no behaviour of their own: every limit listed in section 4 (for example, gating
only channel conversations and the "not reached" list for contact erasure) applies unchanged.
Console screen text and the API are the source of truth; this table was written from
`web/app/lib/api/privacy.ts` and `PrivacyController` on 2026-10-15 and has not been exercised
against a running stack.

Related: [Console guide](../console/README.md), [WhatsApp policy guard](../channels/whatsapp-policy.md),
[breach runbook](../operations/breach-runbook.md).
