# mcp-stdio-runner - spawns STDIO MCP servers (`npx <pkg>`, `uvx <pkg>`) for
# core, away from core's secrets (see docs/architecture/MCP-STDIO-SANDBOXING-DESIGN.md §8).
#
# WITH_BROWSERS selects the variant (essential vs plug-and-play split,
# 2026-09-29 - the browsers moved here out of core/Dockerfile):
#   false (default) - published as `mcp-stdio-runner`: Node + uv + Python 3.12.
#   true            - published as `mcp-stdio-runner-browser`: the same, plus
#                     the pinned Playwright Chromium, @playwright/mcp@0.0.79's
#                     chrome-for-testing and puppeteer@23.11.1's Chrome that the
#                     browser MCP servers (puppeteer, playwright-vision) need.
# core only ever knows ONE runner address; which browser capability exists is
# decided by which image is deployed behind it.
#
#   docker build -t mcp-stdio-runner:local services/mcp-stdio-runner
#   docker build --build-arg WITH_BROWSERS=true -t mcp-stdio-runner-browser:local services/mcp-stdio-runner
ARG WITH_BROWSERS=false

# NODE_IMAGE: the Node base. services/piece-host/Dockerfile --target runner needs
# Node >= 22, which the default already satisfies; override it (e.g.
# --build-arg NODE_IMAGE=node:24.21.0-trixie-slim) only with a runner rollout plan.
ARG NODE_IMAGE=node:22.23.3-trixie-slim@sha256:154ba2f4d6fec323d28e4f4bb86bba4677f1223391a1979cf521304e03a98dfa

# ---- Base (shared by both variants) ---------------------------------------
FROM ${NODE_IMAGE} AS base

WORKDIR /app

# uv/uvx (Python launcher) + python3 - the same two launcher families
# core/Dockerfile's MCP_TOOLCHAIN=full variant installs for the identical
# reason: a dynamic STDIO MCP server is almost always started via
# `npx <pkg>` or `uvx <pkg>`, and with the sidecar on, the REAL
# interpreter/package only ever runs HERE, never inside `core`. `node`/`npx`
# come from this image's own base, so only the Python side needs installing.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl python3 \
    && rm -rf /var/lib/apt/lists/*
# uv/uvx from astral's digest-pinned image instead of `curl | sh`
# (supply-chain review 2026-10-10).
COPY --from=ghcr.io/astral-sh/uv:0.13.0@sha256:cdc6093146eb3ff6a40107b38f008b789e050e77ad87865e381d9917da55a168 /uv /uvx /usr/local/bin/

COPY framing.js server.js ./

# Non-root, with a real (writable) home directory - npx/uvx cache downloaded
# packages under $HOME (~/.npm, ~/.cache/uv), same load-bearing reasoning as
# core/Dockerfile's `agentic` user. (SEC-012: children no longer use this
# HOME - each connection gets a private HOME/cache sandbox under /tmp, see
# server.js createSession.) By default every spawned MCP server runs as
# this one fixed, unprivileged, secret-free user, inside a container that
# cap_drop: ALL and no-new-privileges have already stripped down. Optional
# per-child uids (MCP_RUNNER_UID_RANGE) need the container run as root with
# exactly SETUID/SETGID/CHOWN/KILL added back - see README "Per-child uid".
# /app/mcp-data: default root (MCP_FILESYSTEM_ROOT=./mcp-data, relative to
# this WORKDIR) of the static `filesystem` built-in when core relays it here.
RUN groupadd -r mcprunner && useradd -r -g mcprunner -m -d /home/mcprunner mcprunner \
    && mkdir -p /app/mcp-data \
    && chown -R mcprunner:mcprunner /app
ENV HOME=/home/mcprunner

# Live report, 2026-08-13: a2atlassian (and presumably other current-gen
# uv/uvx packages) require Python>=3.12 - Debian's apt repo only
# ships 3.11 (installed above), so `uv` failed dependency resolution outright
# ("current Python version (3.11.2) does not satisfy... Python>=3.12") before
# ever reaching the target package. Baking a managed 3.12 in at BUILD time
# (as this final, unprivileged user - so it lands under
# /home/mcprunner/.local/share/uv/python, already correctly owned, no
# runtime download/network dependency at all) rather than trusting uv to
# fetch it live on first use inside this network- and permission-restricted
# container.
#
# SEC-012 (2026-09-30): each spawned child now gets its OWN private HOME /
# XDG dirs per connection (server.js createSession), so a Python installed
# under /home/mcprunner/.local/share/uv/python would be invisible to it. It
# is therefore installed as root into /opt/uv-python (world-readable, NOT
# writable by any child - no cross-tenant tampering with the interpreter) and
# exposed as /usr/local/bin/python3.12, which uv's interpreter discovery finds
# on PATH. UV_PYTHON_INSTALL_DIR is set for this RUN only: at runtime each
# child's uv keeps its own private install dir, so a package that needs some
# other Python can still download one (into its own sandbox).
# UNVERIFIED in a real image build as of this change (no docker on the dev
# host): confirm `uvx <pkg requiring >=3.12>` picks 3.12 with no download.
RUN UV_PYTHON_INSTALL_DIR=/opt/uv-python uv python install 3.12 \
    && ln -sf "$(UV_PYTHON_INSTALL_DIR=/opt/uv-python UV_PYTHON_PREFERENCE=only-managed uv python find 3.12)" \
        /usr/local/bin/python3.12 \
    && /usr/local/bin/python3.12 --version

# ---- WITH_BROWSERS=false --------------------------------------------------
FROM base AS browsers-false

# ---- WITH_BROWSERS=true ---------------------------------------------------
FROM base AS browsers-true

# Carried over from core/Dockerfile's MCP_TOOLCHAIN=full stage, where these
# lessons were learned live:
#
# Playwright MCP (playwright-vision, application-mcp.yml's stdio connection)
# needs a REAL Chromium binary baked into the image - live prod incident
# 2026-08-09: relying on `npx -y @playwright/mcp` to fetch a browser at
# runtime failed with "Chromium distribution 'chrome' is not found at
# /opt/google/chrome/chrome" (the server defaulted to hunting for the
# BRANDED Chrome channel, which no Docker base image ships, and an ephemeral
# npx install doesn't reliably run postinstall browser-download hooks
# either way). `--with-deps` also installs the OS-level libs (fonts, X11
# stubs) headless Chromium needs to actually launch - that apt-get requires
# root, so this MUST run before the final `USER mcprunner`.
#
# Browser caches live OUTSIDE $HOME on purpose: the helm chart mounts
# emptyDirs over /home/mcprunner/.npm and /home/mcprunner/.cache (writable
# caches under a read-only root filesystem), which would hide anything baked
# there. PLAYWRIGHT_BROWSERS_PATH / PUPPETEER_CACHE_DIR pin explicit shared
# dirs so the runtime `npx`-spawned servers (running as `mcprunner`, not
# root) find exactly what this build step installed instead of guessing a
# per-user default path that may not match. server.js hands these (and
# DOCKER_CONTAINER below) to every spawned child - IMAGE_ENV_PASSTHROUGH -
# because a child otherwise inherits nothing from this container's env.
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/playwright-browsers \
    PUPPETEER_CACHE_DIR=/opt/puppeteer-cache \
    PUPPETEER_LAUNCH_OPTIONS={"executablePath":"/opt/puppeteer-cache/chrome/linux-153.0.8010.47/chrome-linux64/chrome"}

# This RUN executes as root (HOME=/home/mcprunner only points npm/npx at that
# directory, it doesn't change the executing uid), so npx fetching the
# `playwright` CLI package leaves root-owned files under /home/mcprunner/.npm
# - the exact directory every OTHER stdio MCP server's runtime `npx -y <pkg>`
# also caches into as the non-root user. Without the chown below, every one
# of those then fails at spawn with npm's "EACCES...cache folder contains
# root-owned files" - confirmed live in prod (in core) immediately after this
# Chromium install step was first added, 2026-08-09.
#
# SECOND live incident, same day: the plain `playwright` CLI package (used
# below purely for its `--with-deps` apt-get pass - fonts/X11 libs every
# Chromium-family browser needs) resolved to playwright-core 1.62.1 and
# downloaded browser revision chromium-1234, but application-mcp.yml's
# runtime `npx -y @playwright/mcp@latest` resolved ITS OWN, INDEPENDENT
# playwright-core dependency (1.63.0-alpha as of that incident) and expected
# a DIFFERENT revision (chromium-1237, under the label "chrome-for-testing")
# - two unpinned packages drifting to two different browser builds, so the
# server found no browser at the path it actually looked for. Fixed by
# pinning @playwright/mcp to one exact version here AND in
# application-mcp.yml AND in core/Dockerfile's full variant (must stay in
# sync - bump all together, deliberately, never let any float to `@latest`)
# and installing the browser via THAT package's own installer, guaranteeing
# the binary on disk always matches what the pinned runtime package asks for.
# playwright@1.64.0-alpha-1790635538000 is exactly the version @playwright/mcp@0.0.83 depends on.
RUN npx --yes playwright@1.64.0-alpha-1790635538000 install --with-deps chromium \
    && npx --yes @playwright/mcp@0.0.83 install-browser chrome-for-testing \
    # server-puppeteer pre-warm (2026-08-19 incident): unlike playwright-vision
    # above, puppeteer's package AND its browser were still fetched at runtime
    # by the first `npx -y @modelcontextprotocol/server-puppeteer` - a cold
    # start that reliably blew application-mcp.yml's 60s request-timeout, so
    # the client's lazy init failed every attempt ("Client failed to
    # initialize listing tools") and, before AgentToolProvider's per-client
    # isolation fix, took every other static server's tools down with it.
    # `npm exec` seeds the npx cache without STARTING the server (running the
    # package's own binary would listen on stdio and hang this build forever);
    # `puppeteer browsers install chrome` fetches the exact Chrome-for-Testing
    # build pinned by puppeteer@23.11.1 (server-puppeteer@2025.5.12's own
    # dependency - upstream deprecated, so these versions are frozen; bump
    # both together if the connection block ever changes) into
    # $PUPPETEER_CACHE_DIR, which the chown below hands to the runtime user.
    && npm exec --yes --package=@modelcontextprotocol/server-puppeteer@2025.5.12 -- node -e "process.exit(0)" \
    # The frozen puppeteer@23.11.1 pins Chrome 131.0.6778.204, which the
    # release image scan (grype, fail on critical) rejects with fixed
    # criticals. server-puppeteer cannot take a newer puppeteer, but it does
    # honour PUPPETEER_LAUNCH_OPTIONS (env JSON merged into puppeteer.launch,
    # see its dist/index.js), so install the fixed Chrome-for-Testing build
    # under its own path and point the launch at it via the ENV below - it
    # reaches spawned children through server.js's IMAGE_ENV_PASSTHROUGH.
    && npx --yes puppeteer@23.11.1 browsers install chrome@153.0.8010.47 \
    && rm -rf /var/lib/apt/lists/* \
    && chown -R mcprunner:mcprunner "$PLAYWRIGHT_BROWSERS_PATH" "$PUPPETEER_CACHE_DIR" /home/mcprunner

# server-puppeteer (application-mcp.yml's `puppeteer` stdio connection)
# already ships a Docker-aware launch path (docker_args = {headless: true,
# args: ["--no-sandbox", "--single-process", "--no-zygote"]}) gated on
# `process.env.DOCKER_CONTAINER` - see its own dist/index.js. Without this,
# it uses its default launch args, which assume a real user-namespace
# sandbox the non-root user in THIS container does not have, and fails at
# browser launch with Chromium's own "No usable sandbox!" FATAL error (live
# prod incident in core, 2026-08-09) - a permissions problem, not a
# missing-binary one. Reaches spawned children via server.js's
# IMAGE_ENV_PASSTHROUGH (children do NOT inherit this container's env).
ENV DOCKER_CONTAINER=true

# ---- Final ----------------------------------------------------------------
# hadolint ignore=DL3006
FROM browsers-${WITH_BROWSERS}

USER mcprunner

# No EXPOSE/ports published to the host - see deploy/docker-compose.yml's
# service comment: only `core`, over the isolated mcp-stdio-net network,
# ever talks to this container.

CMD ["node", "server.js"]
