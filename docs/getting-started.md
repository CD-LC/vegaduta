# Getting started

This guide takes you from nothing to a running VegaDūta with your first agent,
using the **Lite** tier: five containers on one machine. Until the first
release is published, the images are built from your checkout (roughly 20 to
40 minutes the first time, not a benchmark); with published images it takes a
few minutes plus the download.
For servers and Kubernetes, see [installation.md](installation.md).

> **Just want it on your own machine?** Personal mode is the one-person
> install: no Keycloak, no Kafka, a local password instead of SSO, and a
> lighter stack (~4 GB Docker memory). `./scripts/vegaduta personal` — see
> [personal-mode.md](personal-mode.md). The rest of this guide covers Lite.

## 1. Prerequisites

- Docker Engine or Docker Desktop with Compose v2 (`docker compose version`)
- 8 GB of memory available to Docker for building the images from source (the
  running Lite stack needs less) and 15 GB of free disk
- Linux, macOS, or Windows (WSL2 / Git Bash, or PowerShell with `scripts\vegaduta.ps1`)
- An API key for at least one LLM provider, or a local model server such as Ollama

## 2. Start the stack

**Today (no release published yet): build from source.**

```sh
git clone https://github.com/CD-LC/vegaduta.git
cd vegaduta
./scripts/vegaduta up --lite --build
```

**After the first release:** pull the signed images of a pinned version. The
installer downloads that release's tarball (whose `deploy/compose/.env.example`
is pinned to the release) and starts Lite:

```sh
curl -fsSL https://raw.githubusercontent.com/CD-LC/vegaduta/main/install.sh | sh -s -- --version 0.1.0
```

Without `--version` the installer uses GitHub's "latest release", which is
never a pre-release. In a git checkout, `VEGADUTA_VERSION` in
`deploy/compose/.env` selects the image version, and
`./scripts/vegaduta upgrade --version X.Y.Z` changes it (after a backup).

What `up --lite` does:

1. writes `deploy/compose/.env` with random secrets (it never overwrites values you set);
2. runs `doctor` (Docker version, memory, free ports, secrets, URLs);
3. starts `postgres` (with pgvector and Apache AGE), `valkey`, `keycloak`, `core` and `web`;
4. waits until every container reports healthy, then prints the URLs.

The first start runs database migrations; allow a few minutes on top of the
build or the image download.

| Service | URL |
|---|---|
| Console | <http://localhost:3000> |
| API | <http://localhost:8080> (health: `/actuator/health/readiness`) |
| Keycloak | <http://localhost:8180> (admin console at `/admin`; password in `deploy/compose/.env`) |

## 3. Create your organisation

1. Open <http://localhost:3000> and choose **Sign up**. The first account
   creates your organisation (tenant) and makes you its admin.
2. Optional: make yourself platform operator, which unlocks platform-level
   settings:

   ```sh
   ./scripts/vegaduta make-admin you@example.com
   ```

## 4. Add a model provider

In the console, open the providers page and add a key for a provider you use
(Anthropic, OpenAI, Google Gemini, Mistral, Groq, OpenRouter, and others). Keys
are encrypted with the `APP_ENCRYPTION_KEY` from your `.env` before they are
stored.

No key? Run free on your own hardware: start the stack with
`./scripts/vegaduta up --lite --local-ai` and a local model is registered for
you, or run models in the browser from **/edge**. See
[Run free with local models](local-models.md).

To point a tenant at your own Ollama instead, add an **Ollama** provider. Core
refuses private addresses unless they are allow-listed, so add the server to
`APP_SECURITY_OUTBOUND_ALLOWED_PRIVATE_HOSTS` first (for example
`host.docker.internal:11434`) and restart core.

## 5. Build your first agent

1. Create an agent: give it a name, instructions, and pick the model (the [console guide](console/build.md#agents-agents)
   describes the Agents screen).
2. Chat with it in the playground.
3. Give it tools:
   - connect an **MCP server** from the catalog or by URL;
   - import an **OpenAPI** spec, and every operation becomes a tool;
   - attach built-in **skills** such as `pdf`, `xlsx` or `research-report`.
4. Add knowledge: upload documents to a knowledge base and attach it to the
   agent. Document ingestion uses the ML sidecar, which Lite starts only with
   the `rag` profile:

   ```sh
   ./scripts/vegaduta up --lite --profile rag
   ```

## 6. Your first workflow

Workflows chain agents, tools and logic, with human approval where you want a
person to sign off. Import a starter from
[`examples/workflow-templates`](../examples/workflow-templates) or build one on
the canvas: add an agent step, then an **approval** step, then an output.
Runs pause at the approval step until someone approves in the console.

Lite runs workflows on the in-process engine. Durable, long-running workflows
on Temporal need the Full tier (`--profile temporal`).

## 7. Day-to-day commands

```sh
./scripts/vegaduta status          # what is running and healthy
./scripts/vegaduta logs core       # follow the core logs
./scripts/vegaduta doctor          # re-check the machine and configuration
./scripts/vegaduta down            # stop; data is kept in Docker volumes
```

Back up `deploy/compose/.env` together with your data: without its
`APP_ENCRYPTION_KEY`, stored provider keys and channel secrets cannot be
decrypted.

## Next steps

- [console/README.md](console/README.md): the console guide, every screen with its permission and API
- [installation.md](installation.md): the Full tier, a real domain with HTTPS, and Kubernetes
- [configuration.md](configuration.md): profiles, environment variables and feature flags
- [extending.md](extending.md): add tools, MCP servers, skills, providers, workflow nodes and channels
- [architecture/overview.md](architecture/overview.md): how the pieces fit
