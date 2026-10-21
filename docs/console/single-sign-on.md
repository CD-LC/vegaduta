# Single sign-on and SCIM

| | |
|---|---|
| Sidebar | Administration > **Single sign-on** |
| Route | `/admin/sso` |
| Nav needs | `PERM_MANAGE_TENANT_SETTINGS`; not shown on a personal install |
| Backend rule | every `/api/tenant/sso/**` call, reads included, needs `PERM_MANAGE_TENANT_SETTINGS` ("even reading an SSO config leaks the tenant's IdP shape") |

"Connect your own identity provider and provision users with SCIM." The page is a per-tenant bring-your-own-IdP
screen with two tabs. A caller without the scope is redirected to `/`.

Three conditions decide whether the screen can work:

- **Identity provider.** Connection management is Keycloak-only. Under `IDENTITY_PROVIDER=auth0` the whole
  `/api/tenant/sso` surface (including SCIM token minting) is off and answers 404
  ([configuration](../configuration.md)).
- **Plan.** Saving a connection needs a plan that includes SSO (`plan_limits.sso_allowed`); minting a SCIM token needs
  a plan that includes SCIM (`plan_limits.scim_allowed`). The seeded defaults are Premium and above for SSO and
  Unlimited only for SCIM; a platform administrator can edit them. Reading and removing an existing connection are
  always allowed.
- **Deployment.** The first-broker-login flow used for tenant identity providers is
  `app.sso.tenant-idp.first-broker-login-flow` (default `first broker login`).

## Connection tab

"Identity provider connection: let your team sign in with your own OIDC or SAML identity provider."

A status area shows **Configured** or **Not configured**, the broker alias, whether the realm connection is **Active**,
**Disabled** or "Not pushed to the sign-in service", and the **Redirect URI** to register with your provider as the
allowed redirect (ACS) URL. The form has:

- **Protocol**: OpenID Connect (OIDC) or SAML 2.0.
- **Display name** (required, at most 255 characters) and **Connection is enabled**.
- **OIDC**: Discovery URL (with a discovery URL the endpoints below are optional), Issuer, Authorization URL, Token URL,
  User info URL, JWKS URL, **Client ID** and **Client secret**. The secret is write-only: required the first time, stored
  encrypted and never shown again; leave it blank on a later save to keep the stored one. Without a discovery URL you
  must give both the authorization and token URLs.
- **SAML**: Entity ID and Single sign-on URL (both required), Name ID format (for example the email-address format), and
  the Signing certificate (X.509, PEM).
- URLs are limited to 2048 characters. The page checks these rules before saving; the per-protocol rules are
  enforced by the server.

**Save connection** upserts the configuration and pushes it to the sign-in service. **Remove connection** asks for
confirmation: your team can no longer sign in through this identity provider, and users who signed in that way keep
their accounts. API: `GET/PUT/DELETE /api/tenant/sso`.

## SCIM tokens tab

"A token lets your identity provider create and deactivate users through SCIM."

- **Create token** takes a **Label** and shows the token once in a dialog ("Copy it now. It is shown only once and
  cannot be retrieved later."). Tokens start with `vdscim_`; only the SHA-256 hash is stored.
- The list shows each token's label, created time and last used time, with the shared pager (client-side), and a
  **Revoke** action with confirmation ("stops working immediately"). An empty list reads "No SCIM tokens".

API: `GET/POST /api/tenant/sso/scim-tokens`, `DELETE /api/tenant/sso/scim-tokens/{id}`.

### The SCIM endpoint your identity provider calls

Your provider presents the token as a bearer token to `/scim/v2/Users`, which is not behind the console's login: it
is open at the filter layer and authenticated inside the controller by matching the token hash. The token's row fixes
the tenant, so a token can only ever touch its own tenant's users.

This is "SCIM-lite", not full RFC 7644. It implements what Okta and Entra-style connectors need: list
(`GET /scim/v2/Users`), create (`POST`), get one (`GET /scim/v2/Users/{id}`) and deactivate (`PATCH` with
`active:false`, or `DELETE`). There are no filter expressions, no bulk endpoint and no schema discovery; unknown
operations return a SCIM-shaped 400. Create is re-checked against the plan on every call, so a downgrade stops new
provisioning even for tokens already minted, while deprovisioning stays open. Provisioned users are created without
a password; they activate through the identity provider's own required-actions email, so a SCIM token holder can add
and disable users but cannot mint credentials.

**Not verified here:** this guide was written from the code. An end-to-end sign-in through a real Okta, Entra or other
identity provider has not been exercised as part of writing it.
