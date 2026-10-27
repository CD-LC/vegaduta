# Azure phase-1 Keycloak image — bakes agentic-ai-realm.json (this
# directory's copy, with the phase-1 Container Apps FQDNs added to
# agentic-ai-web/agentic-ai-platform-admin's redirectUris/webOrigins) into
# the base Keycloak image's auto-import directory. Public hostnames in the
# realm file are Keycloak import placeholders
# (${env.VEGADUTA_PUBLIC_DOMAIN:example.com}, ${env.KEYCLOAK_SMTP_*}) - set
# those env vars on the container before the first boot. Needed because Container
# Apps has no host-path bind mount equivalent to docker-compose.yml's
# `./keycloak/agentic-ai-realm.json:/opt/keycloak/data/import/...:ro` — the
# file has to be IN the image instead. Started with `start-dev --import-realm`
# (see environments/azure-phase1/main.tf's keycloak module).
#
# Theme note: an earlier version of this image did not include the custom "agentic-ai"
# Keycloak theme (branded login/register/account/email templates at
# deploy/keycloak/themes/agentic-ai/) — the realm's loginTheme/accountTheme/
# emailTheme attributes all reference it, so without the theme files present
# Keycloak silently falls back to its built-in default theme (no branding,
# and the custom register.ftl/login.ftl markup — including the visible
# sign-up link — never rendered). Build context moved to the REPO ROOT so
# this Dockerfile can reach deploy/keycloak/themes/ alongside its own
# realm.json; see the updated build command below.
#
# Build from the repo root (context is now the repo root, not this dir):
#   docker build -f infra/keycloak/phase1-realm/Dockerfile \
#     -t <acr-login-server>/keycloak-phase1:latest .
# Context size: Dockerfile.dockerignore (next to this file) allow-lists just
# the realm JSON and the theme for BuildKit builds. `az acr build ... .` may
# not honour a per-Dockerfile ignore, and then uploads the whole repo (minus
# the root .dockerignore) — this can take a very long time or appear to hang.
# Faster in that case: copy just infra/keycloak/phase1-realm/agentic-ai-realm.json
# and deploy/keycloak/themes/agentic-ai into an isolated temp directory
# (preserving those same relative paths under the temp root) and build
# from THAT directory with this same Dockerfile instead — this is what
# is much faster (seconds vs. a multi-GB upload).
#
# Gotcha (found via real E2E browser testing):
# `start-dev --import-realm` only imports a realm if it does NOT already
# exist in KC_DB — once "agentic-ai" exists in Postgres, editing this JSON
# and rebuilding/redeploying the image has ZERO effect on the live realm.
# This bit us hard: attributes.frontendUrl was originally missing/wrong,
# every login/register form action + the OIDC issuer resolved to the dead
# previous host (502), and two full rebuild+redeploy cycles
# did nothing because the DB row never changed. The only way to fix a
# realm that already exists is a live admin-API/kcadm update against the
# running server (or drop the realm and let --import-realm recreate it).
# attributes.frontendUrl here is kept in sync with that live value so a
# genuinely FRESH deploy (empty DB) starts correct too — but if you ever
# need to change it again, you MUST also push the change live via kcadm,
# e.g. from inside the container (`az containerapp exec`):
#   kcadm.sh config credentials --server http://localhost:8080 --realm master --user admin --password $KEYCLOAK_ADMIN_PASSWORD
#   kcadm.sh update realms/agentic-ai -s attributes.frontendUrl=https://<new-value>
#
# smtpServer.password is deliberately left "" here - the real value lives
# ONLY in your secret store (e.g. an Azure Key Vault secret
# "keycloak-smtp-password") and is pushed live via kcadm, same
# gotcha as attributes.frontendUrl above: baking the real password into
# this JSON would put it in a Docker image layer AND git history. On a
# genuinely fresh deploy (empty KC_DB), re-set it the same way:
#   kcadm.sh update realms/agentic-ai -s smtpServer.password=<value from Key Vault>
# 26.7.5: same Keycloak line as deploy/compose and deploy/helm (26.x; the 25.x
# line is out of support). This image sets none of the options 26 removed
# (KC_PROXY, hostname v1), so start-dev --import-realm is unchanged.
# Digest-pinned (INF-018) - bump tag+digest together via Dependabot.
FROM quay.io/keycloak/keycloak:26.8.1@sha256:79c623a22abacf3fe6c00a5fb30f5b7843d717b234033b80995891c06116d244
COPY infra/keycloak/phase1-realm/agentic-ai-realm.json /opt/keycloak/data/import/agentic-ai-realm.json
COPY deploy/keycloak/themes/agentic-ai /opt/keycloak/themes/agentic-ai

USER 1000
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=5 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080"]
