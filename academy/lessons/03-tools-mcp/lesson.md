# Ep 3 — Tools, MCP & SaaS connectors

**Length** ~4–5 min · **Level** everyone · **Renders to** `ep03-tools-mcp.mp4`

An agent with instructions and knowledge can think — tools let it *act*.
`/tools` is the integrations catalog: built-in tools, add-on MCP servers,
and gateways like Composio that open a thousand SaaS apps at once.
`/connect` is the simpler doorway for channels and ready-made agents.

## The scenes

| Step | Click-path | What happens |
|---|---|---|
| Catalog | `/tools` → **Servers** tab | Built-ins (always on) + add-on servers, each card shows status and requirements |
| Web search | search `brave` → **brave-search** → **Set up** | Verified MCP server for live-web research; needs an API key |
| Custom server | **Add MCP server** | Inline "Register a custom server" form — name, description, command/URL; stdio · SSE · HTTP |
| Registry | **Browse registry** tab | Public MCP directory — github, filesystem, postgres, slack, puppeteer… filter by runtime (npx / uvx / docker / remote) |
| Composio | registry search `composio` | `io.github.ComposioHQ/composio` — "Connect AI agents to 1000+ apps with managed authentication" |
| Publishing | catalog search `composio` → **composio-connect** | Packaged hosted connector: Gmail, Slack, GitHub, Notion, Linear, HubSpot — per-app OAuth inside Composio |
| Channels | `/connect` | "Connect your world" — Telegram, WhatsApp, email, Google, SMS, documents, ready-made agents |

## Mental model

- **Tools are verbs.** Instructions say who the agent is; tools say what
  it can do. Enable once, every agent with access can use it.
- **MCP is the plug standard.** A server is just a process (stdio) or an
  endpoint (SSE/HTTP) that advertises tools — the registry is the public
  shelf of ready ones.
- **Verified vs community.** Cards flag verification and key
  requirements — read "needs a key" before promising an agent a tool.
- **Composio = one key, 1000+ apps.** Instead of wiring each SaaS app
  separately, the hosted connector brokers them with managed auth.
- **`/connect` is for people, `/tools` is for plumbing.** Channels and
  ready-made agents up front; servers and registries behind them.

## After watching

Continue to Ep 4 — Workflows: the production line, where fetch, transform,
agent and approval steps become the newspaper's daily pipeline.
