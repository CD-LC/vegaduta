# whatsapp-qr bridge - links a tenant's own WhatsApp number to core as a
# companion device (Baileys / WhatsApp multi-device). The tenant's number, no
# Meta Business setup; works on a laptop because the bridge dials out (no
# inbound webhook, no public URL).
#
#   docker build -t whatsapp-qr:local services/whatsapp-qr
#
# WhatsApp session state lives in /data (the auth-<hash> dirs): mount a volume -
# it IS the linked device. No published port: core reaches it on the compose
# network only.
ARG NODE_IMAGE=node:22.23.3-bookworm-slim@sha256:c3de60bf2f9dd0ac6370e6117950ff62d6e339527e7472301c9c78a017978392

FROM ${NODE_IMAGE}

WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund
COPY server.js ./

# Baileys uses no native modules; slim needs nothing beyond Node itself.
RUN groupadd -r waqr && useradd -r -g waqr -m -d /home/waqr waqr \
    && mkdir -p /data \
    && chown -R waqr:waqr /data /app

ENV WHATSAPP_BRIDGE_PORT=8080 \
    WHATSAPP_BRIDGE_DATA_DIR=/data

USER waqr
# No published port: core reaches it on the compose network only.
CMD ["node", "/app/server.js"]
