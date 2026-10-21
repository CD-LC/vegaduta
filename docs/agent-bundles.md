# Share an agent as a file

An **agent bundle** is one JSON file that carries an agent's design: its prompt,
model settings, tool list, guardrails and autonomy. You can export one of your
agents, send the file to someone, and they can import it into their own
workspace. A bundle never carries a secret, a knowledge-base id or any data
from the workspace it came from.

Ready-made bundles live in [`examples/agent-bundles/`](https://github.com/CD-LC/vegaduta/tree/main/examples/agent-bundles):
a personal assistant, a daily briefing, a research analyst and a meeting-notes
tool. They use only tools that need no account, so they work on a free local
model (see [local models](local-models.md)).

## In the console

To share one of your agents, open **Agents** and press **Export** on its row: the
browser downloads the agent file.

To import, open **Agents → Agent files** (`/agents/bundles`; an empty Agents page
also offers it). Pick a ready-made agent or
upload or paste a file, check the preview (the tools it may use, the autonomy it
will really get, anything changed for safety), then import. Nothing is created
until you press **Import agent**. From the same page, **Connect channels** takes
you to `/connect` to reach the agent from Telegram, WhatsApp, email and more.

## Import safely

A bundle comes from someone else, so the server treats it as untrusted:

- the format must be `vegaduta.agent-bundle/1` and the design at most 64,000
  characters, with at most 100 tools;
- a bundle can never grant `FULLY_AUTONOMOUS`: it is lowered to `SUPERVISED`,
  so every side effect waits for your approval until you change it yourself;
- knowledge-base restrictions are dropped, because those ids belong to the
  author's workspace;
- the agent is created through the same service as any other agent, so your
  plan limits and ownership apply, and the import is written to the audit log.

**Preview first.** `POST /api/agent-bundles/preview` creates nothing and tells
you the tools the agent may use, the autonomy it will really get and what was
adjusted. Read it, then import.

## API

All three need a signed-in user (or a tenant API key).

| Call | What it does |
|---|---|
| `GET /api/agent-bundles/{agentId}` | Export one of your agents as a bundle |
| `POST /api/agent-bundles/preview` | Show what importing a bundle would do |
| `POST /api/agent-bundles/import` | Create a new agent from a bundle (returns its id) |

```sh
curl -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d @examples/agent-bundles/personal-assistant.json \
  "$API/api/agent-bundles/import"
```

## Write your own

Export an agent from your workspace and edit the JSON, or start from an
example. Check a file offline before sharing it:

```sh
python3 examples/agent-bundles/validate.py my-agent.json
```

The checker applies the same rules as the server and also flags an empty tool
list (which would mean "every tool") and anything that looks like a secret.
Keep the tool list short: small local models pick tools far more reliably from
five than from fifty.
