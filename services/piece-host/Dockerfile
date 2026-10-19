# piece-host image(s). Build context: the piece-host/ directory.
#
#   # 1) Layer the piece host onto an already-built mcp-stdio-runner image
#   #    (this is the production shape - see README.md "Registration"):
#   docker build -f Dockerfile --target runner \
#       --build-arg RUNNER_IMAGE=<your built mcp-stdio-runner image> \
#       -t mcp-stdio-runner-pieces .
#
#   # 2) Standalone image for local use / `--describe` (no runner, no uv/python):
#   docker build -f Dockerfile --target standalone -t piece-host .
#   docker run --rm -i -e PIECE_AUTH_SLACK=... piece-host --pieces slack
#
# Mirrors services/mcp-stdio-runner/Dockerfile:
# non-root user, no EXPOSE/ports, nothing written at runtime (read-only root
# filesystem friendly: the host never writes unless PIECE_HOST_FILES_DIR is set).
#
# Supply chain: pieces are installed ONCE, here, from the exact versions pinned
# in pieces.txt, with lifecycle scripts disabled (--ignore-scripts). Nothing is
# installed at runtime - the runner's `npx` path is never involved. Every piece
# is then loaded by `--describe --strict` as the final build step, so a piece
# that does not load (missing @activepieces/* peer, native module, Node
# incompatibility) fails the BUILD, not a tenant's first call.

# Node 24 is the current Active LTS (support until 2028-04-30; Node 26 becomes
# LTS 2026-10-28) - https://raw.githubusercontent.com/nodejs/Release/main/schedule.json.
# Pin a digest in your build pipeline if you need byte-for-byte reproducibility.
ARG NODE_IMAGE=node:24.21.0-bookworm-slim@sha256:d6aa754f16b3197301076f047b5def2f02ea1dbbc2ca920407d46d7ec7f87b20
ARG RUNNER_IMAGE=mcp-stdio-runner:latest

# ---------------------------------------------------------------------------
# assets: host code + allow-listed pieces, root-owned and never writable later
# ---------------------------------------------------------------------------
FROM ${NODE_IMAGE} AS assets
WORKDIR /opt/piece-host
COPY package.json server.js pieces.txt ./
COPY src ./src
# Strip comments/blank lines from pieces.txt and hand the pinned specs to npm.
RUN set -eu; \
    mkdir pieces; cd pieces; \
    npm init -y > /dev/null; \
    PKGS="$(sed -e 's/#.*//' -e '/^[[:space:]]*$/d' ../pieces.txt | tr '\n' ' ')"; \
    test -n "$PKGS"; \
    npm install --ignore-scripts --omit=dev --no-audit --no-fund --save-exact $PKGS; \
    rm -rf /root/.npm; \
    chmod -R a+rX,a-w /opt/piece-host

# ---------------------------------------------------------------------------
# standalone: just the host on a plain Node image
# ---------------------------------------------------------------------------
FROM ${NODE_IMAGE} AS standalone
RUN groupadd -r piecehost && useradd -r -g piecehost -M -d /nonexistent -s /usr/sbin/nologin piecehost
COPY --from=assets /opt/piece-host /opt/piece-host
USER piecehost
RUN node /opt/piece-host/server.js --describe --strict > /dev/null
ENTRYPOINT ["node", "/opt/piece-host/server.js"]

# ---------------------------------------------------------------------------
# runner (LAST stage = default target): the piece host layered onto the
# existing mcp-stdio-runner image, so the sandboxed runner can spawn
#   node /opt/piece-host/server.js --pieces <piece>
# The base image's WORKDIR/CMD/HEALTHCHECK/user are untouched.
# ---------------------------------------------------------------------------
FROM ${RUNNER_IMAGE} AS runner
ARG RUNNER_USER=mcprunner
USER root
COPY --from=assets /opt/piece-host /opt/piece-host
USER ${RUNNER_USER}
# Fail fast, with a readable message, when the runner base is older than the Node this host (package.json
# engines) and its pinned pieces are built against. The mcp-stdio-runner image is node:20 today, so layer
# onto a runner rebuilt on node:24 (FROM node:24.20.0-bookworm-slim) - or pass --build-arg MIN_NODE_MAJOR=0 to
# skip this check and rely on the `--describe --strict` load test below as the only gate.
ARG MIN_NODE_MAJOR=22
RUN set -eu; \
    major="$(node -p 'process.versions.node.split(".")[0]')"; \
    if [ "$major" -lt "${MIN_NODE_MAJOR}" ]; then \
      echo "piece-host: RUNNER_IMAGE has Node ${major}; this host needs Node >= ${MIN_NODE_MAJOR}. Rebuild the mcp-stdio-runner base on node:24.20.0-bookworm-slim (or pass --build-arg MIN_NODE_MAJOR=0 to skip this check)." >&2; \
      exit 1; \
    fi
# Proves every pinned piece loads under the runner's actual Node version.
RUN node /opt/piece-host/server.js --describe --strict > /dev/null
