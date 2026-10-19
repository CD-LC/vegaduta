# keycloak

Bundled Keycloak using the official `quay.io/keycloak/keycloak` image (no Bitnami chart), in production
mode behind the ingress (`KC_PROXY_HEADERS=xforwarded`, `KC_HOSTNAME=https://<hosts.auth>.<domain>`,
backchannel dynamic so pods can use the in-cluster URL for JWKS and the Admin API).

- Realm `agentic-ai` is generated from values (`templates/_realm.tpl`) and imported on first start:
  roles, the confidential `agentic-ai-core` client (secret from the `<release>-keycloak-core-client`
  Secret via an import placeholder), the public `agentic-ai-web` / `agentic-ai-platform-admin` PKCE
  clients bound to your public hosts, and `realm-admin` for core's service account.
- A `post-install,post-upgrade` Job (`kcadm.sh`) reconciles what an import cannot carry:
  `unmanagedAttributePolicy=ADMIN_EDIT` (security: users must not edit `tenant_id`), the core client
  secret, `realm-admin`, `VERIFY_PROFILE` off, `registrationAllowed`, and optional SMTP.
- Database: the `keycloak` database/role in the bundled CloudNativePG cluster, or `database.*` for an
  external PostgreSQL.

To use another IdP instead, disable this chart and set `global.oidc.*` (see the umbrella README; core's
signup/tenant provisioning currently calls the Keycloak Admin API).
