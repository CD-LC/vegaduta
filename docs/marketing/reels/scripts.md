# VegaDūta reels – scripts, on-screen text and shot list

All footage is a real VegaDūta personal install recorded in this session (core, console,
Postgres with pgvector and Apache AGE, a local Llama 3.2 3B model behind the OpenAI-compatible
provider). Fictional company in the demos: **Northwind Bikes**. Cost & ROI uses the project's
own `seed-demo` example data, which the console watermarks as "Example".

Voice: Kokoro `af_heart` (warm, clear). Music: `music_drive` (upbeat) for reels 1–4, `music_calm`
for reel 5 and the overview. Captions burned in, SRT shipped.

Format: 1080×1920, 30 fps, H.264 + AAC. Safe zone for text: 120 px from the top, 320 px from the
bottom, 140 px from the right.

---

## Reel 1 – "One command. Your own agent platform." (install) · ~32 s

| t | Voice-over | On-screen text | Shot |
|---|---|---|---|
| 0–3 | You wanted an AI agent for your team. Every option wanted your data on someone else's cloud. | **Your data. Someone else's cloud?** | Hook card over blurred console |
| 3–8 | VegaDūta runs on your own machine. One command. | `./scripts/vegaduta personal --build` | Terminal: command typed, build and boot log scrolling |
| 8–14 | Postgres, a model, the console. It boots, runs its migrations, and hands you a setup link. | **257 migrations · 1 link** | Terminal: "Started CoreApplication", setup link |
| 14–22 | Choose how you unlock. Passkey, PIN or password. That is your workspace. | **Passkey · PIN · Password** | Recording: first-run page → password → console |
| 22–28 | Open source. Self-hosted. Yours. | **Open source · Self-hosted** | Assistant landing with "Build the agent" card |
| 28–32 | VegaDūta. Build the agent, then give the idea wings. | `github.com/CD-LC/vegaduta` | End card |

Caption (Instagram / YouTube): *Your own AI agent platform, on your own machine. One command, five
containers, no cloud account. Open source. ⤵ github.com/CD-LC/vegaduta*
Hashtags: #AIagents #selfhosted #opensource

---

## Reel 2 – "Build an agent in 60 seconds" · ~42 s

| t | Voice-over | On-screen text | Shot |
|---|---|---|---|
| 0–3 | Northwind Bikes gets the same five questions all day. Returns, warranty, store hours. | **Same 5 questions. All day.** | Hook card |
| 3–9 | Create an agent. Give it a job. | **1 · Give it a job** | Agents → New agent → name + instructions typed |
| 9–15 | Pick the model. Here it is a free local one, running on this laptop. | **2 · Pick a model (local, free)** | Provider: Local / OpenAI-compatible connected |
| 15–22 | Hand it the returns policy. It reads the documents, chunks them, embeds them. | **3 · Add your documents** | Knowledge: upload three .md files, status Ready |
| 22–34 | Ask it anything. It answers from your documents, not from the internet. | **It answers from YOUR docs** | Playground: "Can I return an e-bike after 20 days?" → answer streams |
| 34–42 | Sixty seconds. No code. VegaDūta. | `github.com/CD-LC/vegaduta` | End card |

Caption: *From empty workspace to a support agent that answers from your own documents, in one
take. No code, free local model. ⤵ github.com/CD-LC/vegaduta*
Hashtags: #AIagents #RAG #customersupport

---

## Reel 3 – "Agents that ask before they act" (human approval) · ~42 s

| t | Voice-over | On-screen text | Shot |
|---|---|---|---|
| 0–3 | An agent that can issue refunds is only useful if it cannot go rogue. | **Can your agent go rogue?** | Hook card |
| 3–10 | In VegaDūta you put a human step inside the workflow. Agent, then approval, then action. | **Agent → Approval → Action** | Workflow canvas: three nodes, approval node highlighted |
| 10–18 | The agent drafts the refund decision. The run pauses. | **The run pauses** | Run started, approval pause card appears |
| 18–28 | A person reads it, approves or rejects, and only then does the workflow move on. | **A person decides** | Approve clicked → run completes → output |
| 28–36 | Every step is logged. Every decision has a name on it. | **Logged · Attributed** | Run timeline / audit |
| 36–42 | Autonomy, with a seatbelt. VegaDūta. | `github.com/CD-LC/vegaduta` | End card |

Caption: *Human-in-the-loop is a node, not a prayer. The run pauses until someone approves.
⤵ github.com/CD-LC/vegaduta*
Hashtags: #AIagents #humanintheloop #workflowautomation

---

## Reel 4 – "Reach users where they already are" (channels) · ~30 s

| t | Voice-over | On-screen text | Shot |
|---|---|---|---|
| 0–3 | Your customers will not install another app. | **They won't install your app.** | Hook card |
| 3–12 | Connect the same agent to WhatsApp, Telegram, Slack, SMS, Discord, Teams, email. | **WhatsApp · Telegram · Slack · SMS · Email** | /connect page scrolling the channel list |
| 12–20 | Same agent, same rules, same approvals, whatever the channel. | **One agent. Every channel.** | Telegram inspector / channel setup card |
| 20–26 | And one place to see every conversation. | **One inbox** | Conversations list |
| 26–30 | VegaDūta. | `github.com/CD-LC/vegaduta` | End card |

Caption: *One agent, fourteen channel types, one set of rules. ⤵ github.com/CD-LC/vegaduta*
Hashtags: #WhatsAppBusiness #AIagents #omnichannel

---

## Reel 5 – "Know what every agent costs" (Cost & ROI) · ~30 s

| t | Voice-over | On-screen text | Shot |
|---|---|---|---|
| 0–3 | Nobody signs off on an agent they cannot measure. | **"What does it cost?"** | Hook card |
| 3–12 | VegaDūta records every token and every resolution, and shows cost, value and ROI per agent. | **Cost · Value · ROI per agent** | /roi page (Example data, watermark visible) |
| 12–20 | Type in your own prices and your own baseline. Nothing is invented for you. | **Your prices. Your baseline.** | Price table / baseline form |
| 20–26 | Print the one-page pilot report. Take it to the budget meeting. | **1-page pilot report** | Pilot report |
| 26–30 | VegaDūta. | `github.com/CD-LC/vegaduta` | End card |

Caption: *Per-agent cost, value and ROI from recorded tokens and the prices you type in.
Shown here with the built-in Example data. ⤵ github.com/CD-LC/vegaduta*
Hashtags: #AIagents #ROI #FinOps

---

## Overview – "Build the agent. Then give the idea wings." · ~75 s (YouTube 16:9 + 9:16, LinkedIn)

Stitches the hooks of reels 1–5 into one story: install (0–12) → build (12–30) → approve
(30–48) → channels (48–58) → ROI (58–68) → end card (68–75). Voice-over lines reused; music `calm`.
