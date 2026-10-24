# Multi-stage build for the tenant console (Next.js 16, App Router).
#
# ONE IMAGE, ANY DOMAIN (2026-09-29). Public config - API / Keycloak URLs,
# realm, client id, Academy / code-intel / app-builder URLs, feature flags -
# is read at REQUEST time from the container's environment and handed to the
# browser as window.__ENV by the root layout (app/lib/env.ts). So the normal
# way to run this image is:
#
#   docker build -f web/Dockerfile -t vegaduta-web web
#   docker run -p 3000:3000 \
#     -e NEXT_PUBLIC_API_BASE_URL=https://api.example.com \
#     -e NEXT_PUBLIC_KEYCLOAK_URL=https://auth.example.com \
#     -e NEXT_PUBLIC_KEYCLOAK_REALM=agentic-ai \
#     -e NEXT_PUBLIC_KEYCLOAK_CLIENT_ID=agentic-ai-web \
#     vegaduta-web
#
# Precedence is runtime env > build arg > the code's own dev default. The
# NEXT_PUBLIC_* build args below are still accepted (optional, default empty)
# so an existing `--build-arg NEXT_PUBLIC_...` pipeline keeps producing the
# same image; a value passed at runtime simply wins over the baked one.
# See web/docs/RUNTIME-CONFIG.md for the full key list.

FROM node:22.23.3-trixie-slim@sha256:154ba2f4d6fec323d28e4f4bb86bba4677f1223391a1979cf521304e03a98dfa AS build
WORKDIR /app

COPY package.json package-lock.json* ./
# package-lock.json is in sync with package.json again (verified with a clean
# `npm ci`, 2026-09-29), so this is back to the reproducible install.
RUN npm ci --no-audit --no-fund

COPY . .

# All optional. Empty = nothing baked; the runtime env (or the code's dev
# default) decides. Never default these to a localhost URL: a baked localhost
# is exactly what breaks sign-in on a real domain.
ARG NEXT_PUBLIC_API_BASE_URL=
ARG NEXT_PUBLIC_KEYCLOAK_URL=
ARG NEXT_PUBLIC_KEYCLOAK_REALM=
ARG NEXT_PUBLIC_KEYCLOAK_CLIENT_ID=
ARG NEXT_PUBLIC_ACADEMY_URL=
ARG NEXT_PUBLIC_CODE_INTEL_URL=
# App Builder (/build) is optional; empty means "not available on this
# deployment" and the page/nav degrade honestly (app/build/lib.ts).
ARG NEXT_PUBLIC_APP_BUILDER_URL=
# Flags: only the exact value "1" turns them on (capabilities.ts, freeMode.ts).
ARG NEXT_PUBLIC_EDGE_AGENTS=
ARG NEXT_PUBLIC_EDGE_MODEL_MANIFEST_URL=
ARG NEXT_PUBLIC_DEPLOY_ENV=
ARG NEXT_PUBLIC_FREE_MODE=
ARG NEXT_PUBLIC_COFFEE_URL=
ENV NEXT_PUBLIC_API_BASE_URL=$NEXT_PUBLIC_API_BASE_URL \
    NEXT_PUBLIC_KEYCLOAK_URL=$NEXT_PUBLIC_KEYCLOAK_URL \
    NEXT_PUBLIC_KEYCLOAK_REALM=$NEXT_PUBLIC_KEYCLOAK_REALM \
    NEXT_PUBLIC_KEYCLOAK_CLIENT_ID=$NEXT_PUBLIC_KEYCLOAK_CLIENT_ID \
    NEXT_PUBLIC_ACADEMY_URL=$NEXT_PUBLIC_ACADEMY_URL \
    NEXT_PUBLIC_CODE_INTEL_URL=$NEXT_PUBLIC_CODE_INTEL_URL \
    NEXT_PUBLIC_APP_BUILDER_URL=$NEXT_PUBLIC_APP_BUILDER_URL \
    NEXT_PUBLIC_EDGE_AGENTS=$NEXT_PUBLIC_EDGE_AGENTS \
    NEXT_PUBLIC_EDGE_MODEL_MANIFEST_URL=$NEXT_PUBLIC_EDGE_MODEL_MANIFEST_URL \
    NEXT_PUBLIC_DEPLOY_ENV=$NEXT_PUBLIC_DEPLOY_ENV \
    NEXT_PUBLIC_FREE_MODE=$NEXT_PUBLIC_FREE_MODE \
    NEXT_PUBLIC_COFFEE_URL=$NEXT_PUBLIC_COFFEE_URL \
    NEXT_TELEMETRY_DISABLED=1

# next build type-checks inside the same Node process. Node 20 caps the old-space heap near 2 GB by default and the
# build now needs slightly more than that (ACR run cubx died in "Running TypeScript" with "JavaScript heap out of
# memory", 2026-10-05). 3 GB leaves headroom without assuming the build agent has more than that.
RUN NODE_OPTIONS=--max-old-space-size=3072 npm run build

# ---- Runtime stage -------------------------------------------------------
# next.config.js sets output: "standalone", so .next/standalone holds
# server.js plus only the node_modules it actually traces - no full
# dependency tree, no source. The node base image still ships its own package
# managers (npm, corepack, yarn), which the app never runs and which carry
# their own bundled dependencies (brace-expansion, ip-address, picomatch,
# pacote, sigstore) with High advisories, so they are removed below.
FROM node:22.23.3-trixie-slim@sha256:154ba2f4d6fec323d28e4f4bb86bba4677f1223391a1979cf521304e03a98dfa AS runtime
WORKDIR /app
ENV NODE_ENV=production \
    NEXT_TELEMETRY_DISABLED=1 \
    PORT=3000 \
    HOSTNAME=0.0.0.0

# Same user creation as before (system uid/gid, 999 on node:*-trixie-slim) -
# deploy/helm/charts/web values.yaml pins runAsUser 999 against it.
RUN groupadd -r nextjs && useradd -r -g nextjs nextjs

# HEALTHCHECK and CMD below only run `node`; nothing at runtime needs a package
# manager. scripts/ci/web-image-deps.sh fails CI if this line goes away.
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/corepack \
        /usr/local/bin/npm /usr/local/bin/npx /usr/local/bin/corepack \
        /usr/local/bin/yarn /usr/local/bin/yarnpkg /opt/yarn-*

# Application files stay ROOT-owned (INF-031): the runtime user can read and
# execute its code but not rewrite it. Runtime config is read from the env at
# request time (app/lib/env.ts) - nothing is generated into public/ or .next/
# at startup, so no entrypoint needs write access there. The one directory
# Next writes at runtime is .next/cache (image optimizer + fetch()
# revalidate cache), which is the only thing handed to nextjs.
COPY --from=build /app/.next/standalone ./
COPY --from=build /app/.next/static ./.next/static
COPY --from=build /app/public ./public
RUN mkdir -p .next/cache && chown nextjs:nextjs .next/cache

USER nextjs

EXPOSE 3000

# node is always present (curl/wget are not on node:*-trixie-slim): a real HTTP
# readiness check against the running server, not just a port check.
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
    CMD node -e "require('http').get('http://127.0.0.1:'+(process.env.PORT||3000)+'/',r=>process.exit(r.statusCode<500?0:1)).on('error',()=>process.exit(1))"

CMD ["node", "server.js"]
