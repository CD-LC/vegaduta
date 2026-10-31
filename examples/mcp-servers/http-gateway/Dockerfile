# Streamable-HTTP gateway for the first-party stdio MCP connectors.
#
# This image carries the connectors themselves, which is the whole point: the
# production `mcp-stdio-runner` Container App has no volume mount, so there is
# no host path to bind `mcp-servers/` onto the way `deploy/docker-compose.yml`
# does locally. Baking them in and serving them over HTTP is what makes a
# `STREAMABLE_HTTP` registration possible without touching any running service.
#
# Build from the REPO ROOT so the connector sources are in context:
#   docker build -f examples/mcp-servers/http-gateway/Dockerfile -t mcp-http-gateway:latest .
# Dockerfile.dockerignore (next to this file) allow-lists that context so the
# rest of the repository is never sent to the builder.

FROM node:26.13.0-trixie-slim@sha256:e4db4c914bf8ad241bd24804fc25d272d89bad97e68cdc8a1414862790a02c39

# No npm install anywhere in this image - the gateway and every connector it
# serves are zero-dependency by house rule, so there is no lockfile to honour
# and no registry fetch at build time.

WORKDIR /app

COPY examples/mcp-servers/http-gateway/server.js examples/mcp-servers/http-gateway/package.json ./
COPY examples/mcp-servers/home-assistant  /app/connectors/home-assistant

ENV CONNECTOR_ROOT=/app/connectors \
    SERVERS=home-assistant \
    PORT=8099 \
    NODE_ENV=production

# Non-root with a real home. The connectors never write to disk - every answer
# comes from an in-process fixture - but a writable HOME keeps node from
# complaining and costs nothing. /app stays root-owned so the runtime user
# cannot rewrite the gateway or connector code.
RUN groupadd -r mcpgw && useradd -r -g mcpgw -m -d /home/mcpgw mcpgw
ENV HOME=/home/mcpgw
USER mcpgw

EXPOSE 8099

# /health starts no connector child - it reports readiness of the listener, not
# of a spawn, so a probe never costs a process.
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 \
  CMD node -e "require('http').get('http://127.0.0.1:'+(process.env.PORT||8099)+'/health',r=>process.exit(r.statusCode===200?0:1)).on('error',()=>process.exit(1))"

CMD ["node", "server.js"]
