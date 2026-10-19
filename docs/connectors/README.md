# Healthcare / RCM connectors — Availity, Optum (UnitedHealthcare), Office Ally, CollaborateMD

Real API connectors for the payer/clearinghouse demo on example.com. Each one
is an **imported OpenAPI tool source**: paste the matching `*.openapi.yaml`
into **Tools → Import from OpenAPI → Paste the document**, set the auth block,
and every operation becomes an agent-callable tool (`<prefix>_<operationId>`).

Since V340 the OpenAPI importer supports **OAuth 2.0 client-credentials**:
the platform fetches a short-lived access token from the provider's token
endpoint, caches it until just before expiry (Availity tokens live 5 minutes),
retries a call once on `401`, and sends `Authorization: Bearer <token>`. The
client **secret** is AES-GCM-encrypted at rest (`encrypted_secret`, same
chokepoint as every stored credential); the token itself is never persisted.

| Provider | Real API? | Self-serve keys? | Auth | Best demo path |
|---|---|---|---|---|
| **Optum** (UHC's clearinghouse) | Yes — REST/JSON | **Yes** — free sandbox, no contract | OAuth2 client_credentials, **JSON** token body | `optum-eligibility-sandbox` + `optum-claimstatus-sandbox` |
| **Availity** | Yes — REST/JSON | **Yes** — Demo plan auto-approved | OAuth2 client_credentials, **FORM** token body | `availity-coverages` |
| **UnitedHealthcare direct** (`apimarketplace.uhcprovider.com`) | Yes — REST/JSON | Partially — needs OneHealthcare ID + provider org | OAuth2 client_credentials | `uhc-provider-eligibility` (community spec — verify before prod) |
| **Office Ally** | **No self-serve REST API** — SFTP batch + real-time SOAP/MIME EDI (270/271, 276/277, 837, 835) | No — trading-partner enrollment via sales | OAuth2 only for the ONC patient-access API (not clearinghouse) | Defer, or SFTP connector (below) |
| **CollaborateMD** | WebAPI (partner-gated) + HL7 over HTTPS | No — partner program, $0–$5k interface fee | JSESSIONID session cookie (`j_security_check`) | Defer, or session-cookie connector (below) |

---

## 1. Optum (UnitedHealthcare) — sandbox keys, self-serve

Optum's API network is the ex-Change Healthcare clearinghouse; UHC claims and
eligibility run through it. This is the **fastest real working demo**.

**Get sandbox credentials:**

1. Register at <https://developer.optum.com> (free; no financial obligation).
2. In the portal, create a **project/sandbox** and select the **Eligibility
   and Claims** product family (Eligibility v3, Claim Status v2, Professional
   Claims v3 — subscribe to whichever the demo needs).
3. Optum issues a **`client_id` + `client_secret`** for the sandbox. They
   appear under your project (API Environments / credentials panel). Contact
   your Optum representative for production credentials — prod needs a
   contract.
4. Sandbox do's/don'ts: canned fixture values only (documented member ids /
   NPIs in the portal's "API examples"), no load testing, no PHI.

**Wire it:**

- Import `optum-eligibility-sandbox.openapi.yaml` (name: `Optum Eligibility (sandbox)`).
- authType `OAuth 2.0 client credentials`, token URL
  `https://sandbox-apigw.optum.com/apip/auth/v2/token`, style **JSON**, scope
  blank, client id/secret from step 3.
- Repeat for `optum-claimstatus-sandbox.openapi.yaml` — same credentials.
- In the tools list enable `optum_*_checkEligibility` / `*_checkClaimStatus`,
  use the built-in **Try it** button against a documented fixture (e.g. the
  portal's sample member id + NPI), then attach to the demo agent.

Production is identical on `https://apigw.optum.com` with the production
credential pair.

## 2. Availity — Demo plan, self-serve

**Get demo credentials:**

1. <https://developer.availity.com> → **Create Account** (top right): email,
   name, password → confirm via the emailed security code (24 h validity) →
   set up the authenticator-app MFA.
2. Username menu → **My Dashboard → My Org/Users → Create an Organization**
   (org admin + Tax ID help; adding other users later needs a support ticket
   with case reason "API").
3. **My Apps → Create a New App** — name, description, redirect URL.
4. **API Products → HIPAA Transactions → Access this Product → Demo plan** —
   auto-approved, canned data, zero PHI. Check the cart, select your app,
   **Submit Request**.
5. **My Apps → Approved Access → \<product\> → Client info** gives the
   **API Key (client_id) + Secret** and the **two scopes** to send
   space-separated.

**Wire it:**

- Import `availity-coverages.openapi.yaml` (name `Availity`, prefix `availity`).
- authType `OAuth 2.0 client credentials`, token URL
  `https://api.availity.com/v1/token` (test env:
  `https://tst.api.availity.com/v1/token`), style **FORM**, scope = the two
  scopes from Client info.
- **Verified live on the Demo plan (2026-06):** the spec only contains
  endpoints confirmed reachable — `POST /v1/coverages` (eligibility inquiry;
  Availity retired the old `GET` create, the form body is URL-encoded),
  `GET /v1/coverages/{id}` (poll the result), and `GET /v1/availity-payer-list`
  (~1,400 payers). The demo subscription **rejects**
  `GET /v1/coverages?q=` search, `GET /v1/configurations`, and
  `GET /v1/claim-statuses` ("Requested endpoint is forbidden") — those unlock
  on a Standard subscription.
- Demo-plan responses are canned: the spec declares `X-Api-Mock-Scenario-ID`
  as a header parameter on the POST (`Coverages-Complete-i` = success
  scenario). No PHI anywhere on the demo plan.
- Coverages is asynchronous: `availity_create_coverage_inquiry` returns a
  record with an `id`; the agent then calls `availity_get_coverage_by_id`
  until `status` is `Complete`.
- Production = **Standard plan** (Contact Sales → Trading Partner Management
  contracting), same token flow.

## 3. UnitedHealthcare direct (api.uhcprovider.com)

Two different surfaces — do not mix them up:

- **UHC API Marketplace** (`apimarketplace.uhcprovider.com`) — provider-side
  APIs (eligibility, claims, prior auth, provider directory) on
  `api.uhcprovider.com`. Access requires a **OneHealthcare ID** tied to a
  provider organization (UHCprovider.com sign-in → API Marketplace →
  subscribe an app → credentials issued per subscription). Use
  `uhc-provider-eligibility.openapi.yaml`, but note it is a
  community-reconstructed spec — replace it with UHC's official document when
  your subscription page offers one.
- **UHC Interoperability APIs** (`api.uhc.com/fhir/R4`) — member-facing FHIR
  (CMS Patient Access): OAuth2 + OIDC + member consent + IAL2. Wrong tool for
  a provider-side eligibility demo; skip.

For the demo, Optum sandbox already covers UHC as a payer — use UHC-direct
only if the customer specifically wants the UHC-branded path.

## 4. Office Ally — honest assessment

Office Ally **has no public REST API** for its clearinghouse. Integration is:

- **SFTP batch** (837/835 files) and **real-time SOAP/MIME over HTTPS** using
  the CORE envelope (270/271 eligibility, 276/277 claim status). Their
  companion guides are sent **after** a trading-partner enrollment —
  `OFFALLY` is the inbound receiver id and a sender id is assigned at
  enrollment.
- The only OAuth2 API is the ONC §170.315(g)(10) *patient-data-request* API
  (password grant: client_id + client_secret + username + password) — it
  serves a **patient-facing** app fetching that practice's data, not the
  clearinghouse; it is the wrong primitive for an eligibility/claims demo.

**Path to a real account:** free signup at officeally.com (Practice Mate /
Service Center), then a support/sales request for "real-time EDI trading
partner enrollment — 270/271 and 276/277" naming VegaDuta as the vendor.
Expect a companion guide PDF, sender id assignment and endpoint details in
reply. If the customer insists on Office Ally, the honest build is an SFTP /
SOAP-EDI connector — not this OpenAPI path.

## 5. CollaborateMD — honest assessment

CollaborateMD's **WebAPI is a partner program** (signup at
collaboratemd.com/partners; interface fee $0–$5,000 depending on scope; docs
after acceptance). Their documented machine interface today is **HL7 v2.3.1
over HTTPS with JSESSIONID session auth**: `GET api.collaboratemd.com` for a
session cookie, then `GET /j_security_check?j_username=…&j_password=…`, then
HL7 messages on the authenticated session. That is a session-cookie auth
model the OpenAPI importer does not implement (nor should a demo fake it) —
if a CMD customer wants the demo, the connector is a small purpose-built
callback holding username/password (encrypted) and managing the session.

## Demo recipe (example.com)

1. Admin → **Tools → Import from OpenAPI** → paste
   `optum-eligibility-sandbox.openapi.yaml` → fill the OAuth2 block → enable
   `optum_checkEligibility`.
2. Same for `availity-coverages.openapi.yaml` → enable
   `availity_create_coverage_inquiry`, `availity_get_coverage_by_id` and
   `availity_list_payers`.
3. Create/attach a demo agent (e.g. "Front-desk copilot") — tools are on the
   normal tool surface; the approval gate can require human sign-off per call.
4. Scripted demo: *"Check whether member \<sandbox fixture\> is eligible for
   an office visit with payer 87726"* → agent calls Optum eligibility, gets a
   real JSON 271; *"…and where is claim 00012345?"* → claim status call.
   Availity demo plan behaves the same way with canned responses — the whole
   OAuth2 + API round-trip is real.
5. **PHI note:** sandboxes serve fixture data only. The moment a Standard /
   production subscription carries real member data, calls are PHI-bearing —
   keep the approval gate on and treat tool transcripts accordingly.
