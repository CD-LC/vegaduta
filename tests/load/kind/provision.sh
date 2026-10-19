#!/usr/bin/env bash
# Activates the in-cluster mock model server as the tenant's Ollama provider and creates the load-test agents.
# Needs: LT_TOKEN (access token of the load-test user). Prints the load-test agent id on stdout.
# Talks to the nginx ingress of a local kind stage (127.0.0.1 + Host header).
# Also: seeds one MODEL_ROUTE arm (SE-19) and a slow-model agent for stop/resume tests (SE-40).
# Users the e2e suite needs (platform admin, second member, relaxed brute-force): see provision-stage-users.sh.
set -euo pipefail
API_HOST=${API_HOST:-api.127.0.0.1.nip.io}; : "${LT_TOKEN:?}"
api() { curl -sS -H "Host: $API_HOST" -H "Authorization: Bearer $LT_TOKEN" -H 'Content-Type: application/json' "$@"; }
echo "activate provider:" >&2
api -w ' HTTP %{http_code}\n' -X POST http://127.0.0.1/api/providers \
  -d '{"providerName":"OLLAMA","apiKey":"{\"baseUrl\":\"http://mock-llm:11434\"}"}' >&2
echo "create agent:" >&2
agent_id=$(api -X POST http://127.0.0.1/api/agents -d '{"name":"load-test-agent","systemPrompt":"You are a terse test agent.","providerName":"OLLAMA","model":"mock:latest","maxTokens":256}' \
  | tee /dev/stderr | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')

# SE-19: the MODEL_ROUTE policy is enabled but had no arms, so every turn logged "RL policy MODEL_ROUTE is ENABLED ...
# no selectable arms". One arm that routes to the same model keeps the policy valid and the routing a no-op.
echo "seed MODEL_ROUTE arm:" >&2
api -w ' HTTP %{http_code}\n' -X POST "http://127.0.0.1/api/rl/agents/$agent_id/policies/MODEL_ROUTE/arms" \
  -d '{"armKey":"mock-latest","armConfigJson":"{\"model\":\"mock:latest\"}"}' >&2 || true

# SE-40: model mock-slow:latest streams ~60 tokens at 300 ms (about 18 s), long enough to stop mid-stream or reload.
# A second agent keeps the fast agent's behaviour (and the benchmark) untouched. MOCK_MARKER:slow-agent lets a test
# prove the system prompt reached the model.
echo "create slow agent:" >&2
api -X POST http://127.0.0.1/api/agents -d '{"name":"load-test-slow-agent","systemPrompt":"You are a slow test agent. MOCK_MARKER:slow-agent","providerName":"OLLAMA","model":"mock-slow:latest","maxTokens":256}' >&2 || true
echo >&2
echo "$agent_id"
