#!/bin/sh
# Native core start for this sandbox: the same environment scripts/vegaduta's
# personal_start_native() exports (personal tier, host JVM), minus Docker.
set -eu
REPO="${VEGADUTA_REPO:-/home/user/vegaduta}"
ENV_FILE="$REPO/deploy/compose/.env"
get_env() { sed -n "s/^$1=//p" "$ENV_FILE" | head -1; }
PERSONAL_HOME="${VEGADUTA_PERSONAL_HOME:-$HOME/.vegaduta/personal}"
export SPRING_PROFILES_ACTIVE="security,knowledge,graph-memory,personal"
export SERVER_PORT=8080 SERVER_ADDRESS=127.0.0.1
export DB_HOST=127.0.0.1 DB_PORT=5432 POSTGRES_DB="$(get_env POSTGRES_DB)" POSTGRES_USER="$(get_env POSTGRES_APP_USER)" POSTGRES_PASSWORD="$(get_env POSTGRES_APP_PASSWORD)"
export REDIS_HOST=127.0.0.1 REDIS_PORT=6379 REDIS_PASSWORD="$(get_env VALKEY_PASSWORD)"
export APP_ENCRYPTION_KEY="$(get_env APP_ENCRYPTION_KEY)" PLATFORM_MASTER_KEY="$(get_env PLATFORM_MASTER_KEY)"
export APP_WEB_PUBLIC_URL="http://localhost:3000" APP_WEB_ALLOWED_ORIGINS="http://localhost:3000,http://127.0.0.1:3000"
export APP_STORAGE_TYPE=local APP_STORAGE_LOCAL_PATH="$PERSONAL_HOME/storage"
export MCP_STDIO_SIDECAR_ENABLED=false MANAGEMENT_HEALTH_MAIL_ENABLED=false MANAGEMENT_TRACING_ENABLED=false
export SIDECAR_BASE_URL="http://127.0.0.1:8001" SIDECAR_SHARED_SECRET="$(get_env SIDECAR_SHARED_SECRET)"
export VEGADUTA_MCP_DENY_READ="$REPO/deploy/compose,$HOME/.vegaduta"
# The local llama.cpp server (OpenAI-compatible) the demo uses: allow-list it for the SSRF guard.
export APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS="127.0.0.1:8089,localhost:8089,127.0.0.1:8090,localhost:8090"
export APP_EMBEDDINGS_PROVIDER=openai-compatible APP_EMBEDDINGS_BASE_URL="http://127.0.0.1:8090/v1" APP_EMBEDDINGS_API_KEY="local-no-key" APP_EMBEDDINGS_MODEL="all-minilm-l6-v2" APP_EMBEDDINGS_DIMENSIONS=384
export APP_LOCAL_LLM_BOOTSTRAP_ENABLED=${APP_LOCAL_LLM_BOOTSTRAP_ENABLED:-false}
export TOOL_AUTO_SELECTION_MAX_TOOLS=6 TOOL_AUTO_SELECTION_MAX_SCHEMA_TOKENS=2000
export LLM_HTTP_STREAM_IDLE_TIMEOUT=900s
export JAVA_TOOL_OPTIONS=""
exec /usr/bin/java -Xms128m -Xmx2g -XX:+UseSerialGC -XX:MaxMetaspaceSize=320m -XX:+ExitOnOutOfMemoryError -Djava.net.preferIPv4Stack=true \
  -jar "$REPO/core/app/target/app-0.1.0-SNAPSHOT.jar"
