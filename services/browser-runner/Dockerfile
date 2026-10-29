# browser-runner - the Playwright server behind the personal install's browser add-on (the agent's own
# browser: browser_* tools and the sign-in takeover pane). Core stays slim and connects here with
# `chromium.connect(ws://browser-runner:3000/)` (app.browseruse.remote-url).
#
#   docker build -t browser-runner:local services/browser-runner
#
# The base image ships Chromium for exactly this Playwright version, and Playwright refuses a client
# of a different major.minor: keep this version equal to the Playwright Java client pinned in
# core/agents-browser/pom.xml (1.63.0) and bump both together.
#
# No published port and no secrets: a Playwright server accepts any client that can reach it, so it
# joins only the private `browser-net` network that core is on (deploy/compose/browser.yml), never the
# network holding Postgres and Valkey.
# Digest-pinned (OpenSSF Pinned-Dependencies): tag alone can be moved; bump the
# tag AND digest together when the Playwright base revs.
ARG PLAYWRIGHT_IMAGE=mcr.microsoft.com/playwright:v1.63.0-noble@sha256:eff16c30e6f3f4af0a03fa4b706120d5e9b0891c344a27d64559aff5900a4a27

FROM ${PLAYWRIGHT_IMAGE}

# The Playwright base image lags Ubuntu's security archive between its releases. Pull in the
# fixed openssl the release image scan (grype, fixed-only) reports, and nothing else.
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade openssl libssl3t64 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && chown -R pwuser:pwuser /app \
    # Clients connect over chromium.connect() only (app.browseruse.remote-url);
    # the playwright base image also ships Firefox and WebKit builds the service
    # never launches, and the release image scan flags their binaries. Drop them.
    && rm -rf /ms-playwright/firefox-* /ms-playwright/webkit-* \
    # npm is a build tool: the server below runs on node alone. The npm bundled
    # in the base image carries its own tar, undici, ip-address and brace-expansion
    # (the release image scan flags them, and no package.json can override them), so
    # drop npm and npx once `npm ci` is done rather than chase the base image's npm.
    && rm -rf /usr/lib/node_modules/npm /usr/bin/npm /usr/bin/npx

ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    BROWSER_RUNNER_PORT=3000

USER pwuser
EXPOSE 3000
# Playwright starts a fresh headless Chromium per connected client and closes it when the client goes.
# Started with node, not npx (npx is removed above): the version that runs is exactly the one in
# package-lock.json.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD node -e "require('net').connect({port:process.env.BROWSER_RUNNER_PORT||3000,host:'127.0.0.1'}).on('connect',()=>process.exit(0)).on('error',()=>process.exit(1))" || exit 1
CMD ["sh", "-c", "exec node /app/node_modules/playwright/cli.js run-server --host 0.0.0.0 --port ${BROWSER_RUNNER_PORT}"]
