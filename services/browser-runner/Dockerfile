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
ARG PLAYWRIGHT_IMAGE=mcr.microsoft.com/playwright:v1.63.0-noble

FROM ${PLAYWRIGHT_IMAGE}

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && chown -R pwuser:pwuser /app

ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    BROWSER_RUNNER_PORT=3000

USER pwuser
EXPOSE 3000
# Playwright starts a fresh headless Chromium per connected client and closes it when the client goes.
CMD ["sh", "-c", "exec npx playwright run-server --host 0.0.0.0 --port ${BROWSER_RUNNER_PORT}"]
