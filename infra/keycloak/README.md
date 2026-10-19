# Production Keycloak realm templates

Two realm files live here:

- `phase1-realm/agentic-ai-realm.json` (+ `Dockerfile`, `reconcile-azure.sh`)
  — the `agentic-ai` realm baked into a Keycloak image for a container
  platform without bind mounts (used by `deploy/helm`).
- `realm-export/vegaduta-ai-realm.json` — a stricter, fully isolated
  production realm (`vegaduta-ai`) described below.

Public hostnames, SMTP host and sender are **Keycloak import placeholders**
(`${env.VEGADUTA_PUBLIC_DOMAIN:example.com}`, `${env.KEYCLOAK_SMTP_HOST:…}`,
`${env.KEYCLOAK_SMTP_FROM:…}`, `${env.KEYCLOAK_SMTP_USER:…}`) that Keycloak
resolves from the container environment at import time. Set
`VEGADUTA_PUBLIC_DOMAIN` to your apex domain (clients get `app.<domain>`,
`admin.<domain>`, `academy.<domain>`, issuer `auth.<domain>`).

The `vegaduta-ai` realm is adapted from `deploy/keycloak/agentic-ai-realm.json`,
but deliberately **not** the same realm and **not** the same Keycloak instance
as a staging `agentic-ai` realm — it is a fully isolated production identity
provider with its own database, own signing keys, own issuer
(`https://auth.<domain>/realms/vegaduta-ai`). No dual-issuer trick is needed
here; that pattern exists in `core`'s `KEYCLOAK_ADDITIONAL_ISSUERS` purely to
reconcile `localhost:8180` vs the public auth host in dev, and this
environment only ever has one issuer.

## Differences from the dev-realm export

- Realm renamed `vegaduta-ai`, `frontendUrl`/CSP/redirect URIs point only at
  `*.<VEGADUTA_PUBLIC_DOMAIN>` (no `localhost`, no staging hosts).
- `accessTokenLifespan` shortened to 600s (10 min) from the dev realm's 900s.
- `bruteForceProtected` + explicit failure/wait thresholds turned on.
- Mobile/native app clients (`agentic-ai-mobile`, `agentic-ai-app`) and the
  social identity provider placeholders were dropped for this first cut —
  add them back here (and in Terraform's ingress rules / redirect URIs) once
  those surfaces actually target your production domain.
- A `platform-admins` group carries the `platform-admin` realm role — see
  below for the one thing this JSON export can't fully express.

## The one manual step this export can't do: conditional MFA

Keycloak's realm-export JSON can declare roles, groups, and clients cleanly,
but a role-conditional authentication flow (require TOTP for members of
`platform-admins`, not for everyone) is expressed as an **authentication
flow**, and hand-authoring one blind — those are notoriously version-
sensitive between Keycloak releases — risks shipping something that imports
but silently doesn't gate anyone. Safer to configure it once, for real, in
the running instance:

1. Import this realm (Keycloak `--import-realm`, or the Admin REST API).
2. In the admin console: **Authentication → Flows → Browser → Duplicate**
   (name it `browser-with-conditional-otp`).
3. Add an execution: **Conditional OTP Form**, condition = *user is member
   of group* `platform-admins`, requirement = **Required**. Leave it
   **Conditional/Disabled** for everyone else.
4. **Authentication → Bindings → Browser Flow** → select the duplicated flow.
5. Verify: log in as a `platform-admins` member → prompted for TOTP setup.
   Log in as a plain `tenant-admin` → not prompted. Verify this before
   trusting the MFA gate in production.

## SMTP — required for `verifyEmail` to actually send anything

The realm has `"verifyEmail": true`, which does nothing on its own without
a working `smtpServer` config. The committed `smtpServer` block takes its
host and sender from `KEYCLOAK_SMTP_HOST` / `KEYCLOAK_SMTP_FROM` (import
placeholders) and ships a placeholder user and **no password at all**
(Keycloak's admin API returns/accepts a masked value for password even on
GET, so it can't safely live in a committed file regardless). Inject the real
user/password after import via the Admin REST API or `kcadm.sh`, reading them
from your secret store (`phase1-realm/reconcile-azure.sh` step 6 shows one
way).

**Verify, don't assume**: after import, trigger a real password-reset or
verification email and confirm it arrives — a successful API call setting
the SMTP config is not the same thing as SMTP actually working.

## Import

Use Keycloak's `--import-realm` on first boot (the realm file in
`/opt/keycloak/data/import/`), or `POST /admin/realms` with the admin-cli
client when the platform gives you no exec shell into the container.

Run production Keycloak in `start` mode, never `start-dev` — the app repo's
`deploy/docker-compose.yml` uses `start-dev --import-realm` for local
convenience only; `start-dev` disables several production safety checks
(HTTPS enforcement, some caching) that must stay on here.
