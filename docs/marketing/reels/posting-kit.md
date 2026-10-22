# Posting kit – VegaDūta promo reels

Everything a person needs to post the six videos without opening the scripts: title, caption,
hashtags, first comment and the per-platform notes. Hooks, captions and hashtags come from
[scripts.md](scripts.md); the format rules from [research-notes.md](research-notes.md).

Files (rendered into `out/`, never committed; re-render with `pipeline/build.py`):

| Video | Vertical 1080×1920 | Landscape 1920×1080 | SRT | Cover 1080×1920 | Grid 1080×1080 |
|---|---|---|---|---|---|
| Reel 1 – One command | `out/r1.mp4` | – | `out/r1.srt` | `out/cards/cover_r1.png` | `out/cards/grid_r1.png` |
| Reel 2 – Build an agent in 60 s | `out/r2.mp4` | – | `out/r2.srt` | `out/cards/cover_r2.png` | `out/cards/grid_r2.png` |
| Reel 3 – Agents that ask before they act | `out/r3.mp4` | – | `out/r3.srt` | `out/cards/cover_r3.png` | `out/cards/grid_r3.png` |
| Reel 4 – Reach users where they are | `out/r4.mp4` | – | `out/r4.srt` | `out/cards/cover_r4.png` | `out/cards/grid_r4.png` |
| Reel 5 – Know what every agent costs | `out/r5.mp4` | – | `out/r5.srt` | `out/cards/cover_r5.png` | `out/cards/grid_r5.png` |
| Overview – Build the agent. Then give the idea wings. | `out/overview.mp4` | `out/overview_16x9.mp4` | `out/overview.srt` | `out/cards/cover_overview.png` | `out/cards/grid_overview.png` |

YouTube thumbnail (1280×720): `out/cards/thumb_overview.png`.

All footage is a real VegaDūta personal install on a MacBook (Apple silicon), built from source with
`./scripts/vegaduta personal --build --local-ai=host`, with Ollama `qwen3.5:4b` as the free local
model. Terminal footage is an asciinema recording of the real build and boot, rendered to video with
`agg`. The Cost & ROI reel shows the built-in **Example** data, watermarked as such in the console; say
so in the caption every time (it is in the caption below).

Link to use everywhere: `https://github.com/CD-LC/vegaduta` (and the install one-liner
`./scripts/vegaduta personal --build`). No "link in bio" dependency: the end card carries both.

---

## Reel 1 – One command. Your own agent platform.

**Title (YouTube / LinkedIn):** One command. Your own AI agent platform. (VegaDūta, open source)

**Caption:** Your own AI agent platform, on your own machine. One command, five containers, no cloud
account. Open source. ⤵ github.com/CD-LC/vegaduta

**Hashtags:** #AIagents #selfhosted #opensource

**First comment:** Install: `./scripts/vegaduta personal --build` (Docker needed; Ollama optional
for a free local model). Docs and releases: github.com/CD-LC/vegaduta

**Length:** ~32 s. Best as the first post of the series; it is the one every other reel's end card
points back to.

## Reel 2 – Build an agent in 60 seconds

**Title:** Build a support agent from your own documents in 60 seconds (no code, free local model)

**Caption:** From empty workspace to a support agent that answers from your own documents, in one
take. No code, free local model. ⤵ github.com/CD-LC/vegaduta

**Hashtags:** #AIagents #RAG #customersupport

**First comment:** The company in the demo (Northwind Bikes) is fictional; the three policy
documents it answers from are in the repo under docs/marketing/reels/demo-knowledge/. The model is
Qwen 3.5 4B through Ollama on the laptop itself.

**Length:** ~42 s.

## Reel 3 – Agents that ask before they act

**Title:** Human-in-the-loop is a workflow node, not a prayer (VegaDūta approvals)

**Caption:** Human-in-the-loop is a node, not a prayer. The run pauses until someone approves.
⤵ github.com/CD-LC/vegaduta

**Hashtags:** #AIagents #humanintheloop #workflowautomation

**First comment:** The workflow in the clip is four nodes: trigger → agent → approval → output.
The approval node is admin-only, so only an owner or admin can release the refund decision.

**Length:** ~42 s.

## Reel 4 – Reach users where they already are

**Title:** One agent, every channel: WhatsApp, Telegram, Slack, SMS, email (VegaDūta Connect)

**Caption:** One agent, fourteen channel types, one set of rules. ⤵ github.com/CD-LC/vegaduta

**Hashtags:** #WhatsAppBusiness #AIagents #omnichannel

**First comment:** Channels are connected on the Connect page; each needs that platform's own
credentials (a Telegram bot token, a WhatsApp Business number, a Slack app...). The clip shows the
channel list and the Telegram setup; nothing is sent to a real channel in it.

**Length:** ~30 s.

## Reel 5 – Know what every agent costs

**Title:** Cost, value and ROI per agent, from recorded tokens and your own prices (VegaDūta)

**Caption:** Per-agent cost, value and ROI from recorded tokens and the prices you type in.
Shown here with the built-in Example data. ⤵ github.com/CD-LC/vegaduta

**Hashtags:** #AIagents #ROI #FinOps

**First comment:** The numbers on screen are the "Example" dataset that ships with the project
(`scripts/vegaduta seed-demo`), watermarked as such. Your own install shows your own recorded
tokens and the prices and baseline you enter.

**Length:** ~30 s.

## Overview – Build the agent. Then give the idea wings.

**Title:** VegaDūta in 75 seconds: self-hosted AI agents with approvals, channels and ROI

**Caption (YouTube description):** VegaDūta is an open-source, self-hosted platform for AI agents:
install with one command, build an agent from your own documents on a free local model, put a human
approval step inside any workflow, connect the agent to WhatsApp, Telegram, Slack, SMS and email, and
see what every agent costs. Everything in this video is a real install recorded on a laptop; the Cost &
ROI screen shows the built-in Example data.
Install: `./scripts/vegaduta personal --build` · Source and docs: https://github.com/CD-LC/vegaduta
Chapters: 0:00 Install · 0:12 Build an agent · 0:30 Approvals · 0:48 Channels · 0:58 Cost & ROI

**LinkedIn post text:** Most "AI agent" tools want your customer data on their cloud. VegaDūta runs
on your own machine or cluster: one command to install, agents that answer from your own documents,
a human approval node inside the workflow, every channel your customers already use, and a cost and
ROI view per agent. Open source (Apache 2.0). 75-second tour below; repo in the first comment.

**Hashtags:** #AIagents #opensource #selfhosted (YouTube: also #humanintheloop #RAG)

**First comment:** github.com/CD-LC/vegaduta — install, docs, and the demo documents used in the
video.

**Length:** ~75 s. Vertical for Shorts/Reels, 16:9 for the YouTube main upload and LinkedIn.

---

## Per-platform notes

**Instagram Reels**
- Upload the 1080×1920 MP4; pick `out/cards/cover_rN.png` as the cover and `grid_rN.png` as the
  profile-grid crop (Instagram asks for the grid crop separately).
- Captions are burned in; Instagram's auto-captions can stay on, they will match the SRT.
- Keep hashtags to the three listed (2026 guidance: 1–3 relevant tags beat tag walls). Put them
  at the end of the caption, not in the first line.
- Post the reels in order 1 → 2 → 3 → 4 → 5, one per weekday; the overview a week later.
- Length: all five are 30–42 s, inside the 15–45 s window that holds attention on Reels.

**YouTube Shorts**
- Same vertical files; title under 100 characters (titles above), the caption as the description,
  hashtags in the description. Upload the SRT as a caption track (Subtitles → Add).
- Shorts rarely convert to subscribers on their own: every Short's end card and pinned comment
  points to the 16:9 overview, and the overview's description points to the repo.
- The overview goes up as a regular 16:9 video (`overview_16x9.mp4`) with `thumb_overview.png` as
  the thumbnail and the chapters above in the description; also post the vertical cut as a Short.

**LinkedIn**
- Native video, 16:9 `overview_16x9.mp4` (LinkedIn's feed is landscape on desktop) or the vertical
  reels 2, 3 and 5 (the ones with a business "receipt": a working agent, an approval, an ROI number).
- Use the "LinkedIn post text" above; the link goes in the first comment, not the post body.
- Upload the SRT as the video's caption file (LinkedIn supports SRT on native video).
- Length: the 75 s overview is fine; LinkedIn tolerates longer than Reels, but keep it under 90 s.

**Everywhere**
- Never cut the "Example" watermark out of the ROI shots, and keep the sentence "Shown here with the
  built-in Example data" in reel 5's caption.
- Nothing in the videos is a mock-up: if a screen changes in a later release, re-record the take
  (`recorder/scene-*.js`) and rebuild (`pipeline/build.py`) rather than editing the footage.
