# desktop-agent

The personal install's hands on the laptop: a tiny loopback HTTP agent that exposes the
screen and input devices to VegaDuta core so the Assistant can do computer-use work —
take screenshots, name the buttons on screen (accessibility tree), click, type, open apps,
send system notifications and read or set the clipboard.

It runs **on the laptop itself, never in a container** — GUI access is the whole point.
No dependencies beyond Node 22 and macOS's built-ins (`screencapture`, `osascript`,
`pbcopy`/`pbpaste`); on Linux it uses `xdotool`, `gnome-screenshot`/`scrot`/`grim`,
`xclip`/`xsel`/`wl-*`, `wmctrl` and `notify-send` when installed.

## Run it

```sh
export DESKTOP_AGENT_SECRET=$(openssl rand -hex 32)
export DESKTOP_AGENT_SECRET=<the value you generated>
node services/desktop-agent/server.js
```

It binds `127.0.0.1:8787` only. Set the same secret on core as
`APP_ASSISTANT_DESKTOP_AGENT_SECRET` and `APP_ASSISTANT_DESKTOP_AGENT_URL=http://127.0.0.1:8787`
(`http://host.docker.internal:8787` when core runs in Docker Desktop).

To run it permanently, a `launchd` agent (replace the two paths and the secret):

```sh
cat > ~/Library/LaunchAgents/ai.vegaduta.desktop-agent.plist <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>ai.vegaduta.desktop-agent</string>
  <key>ProgramArguments</key><array>
    <string>/usr/local/bin/node</string>
    <string>/path/to/vegaduta/services/desktop-agent/server.js</string>
  </array>
  <key>EnvironmentVariables</key><dict>
    <key>DESKTOP_AGENT_SECRET</key><string>paste-the-secret-here</string>
  </dict>
  <key>KeepAlive</key><true/>
  <key>RunAtLoad</key><true/>
</dict></plist>
EOF
launchctl load ~/Library/LaunchAgents/ai.vegaduta.desktop-agent.plist
```

`which node` gives the right node path for the first entry.

## macOS permissions

Both the click path (CoreGraphics events) and the accessibility tree need
**Accessibility** permission; screenshots need **Screen Recording**. Grant them to
whatever runs the agent — Terminal, iTerm, or the app your launchd job uses — in
System Settings → Privacy & Security. The API answers a named error when a grant is
missing.

## Security

These actions are **not sandboxed** — unlike `local_shell`, a `local_desktop_*` call can
touch anything the logged-in user can. That is why:

- the agent only listens on loopback and requires the bearer secret;
- core's tool guard classifies every actuating tool irreversible+consequential and
  parks it for approval by default (`APP_ASSISTANT_DESKTOP_APPROVAL_REQUIRED=false`
  on the `personal` profile lifts that pin — deliberate choice on a one-person install);
- every call is logged in the tool-call audit.

## API

All calls need `Authorization: Bearer $DESKTOP_AGENT_SECRET` (`/health` aside):

| Method | Path | Body | Returns |
|---|---|---|---|
| GET | `/health` | — | `{ok, platform}` |
| POST | `/screenshot` | `{}` | `{path, bytes}` — PNG under `DESKTOP_AGENT_DATA_DIR` |
| POST | `/ui` | `{app?}` | `{app, window, elements:[{role,label,x,y,w,h}]}` |
| POST | `/ui/press` | `{label, role?, app?}` | `{matched}` |
| POST | `/click` | `{x, y, button?}` | `{ok}` |
| POST | `/type` | `{text}` | `{chars}` |
| POST | `/key` | `{key}` | `{ok}` |
| POST | `/open` | `{target, application?}` | `{ok}` |
| POST | `/activate` | `{app}` | `{ok}` |
| POST | `/notify` | `{title, body}` | `{ok}` |
| GET | `/clipboard` | — | `{text}` |
| POST | `/clipboard` | `{text}` | `{ok}` |

Env: `DESKTOP_AGENT_PORT` (8787), `DESKTOP_AGENT_BIND` (127.0.0.1),
`DESKTOP_AGENT_SECRET` (required), `DESKTOP_AGENT_DATA_DIR`
(`~/vegaduta-workspace/screenshots`).

Tests: `node --test server.test.js` (route/auth/validation against a stubbed action layer).
