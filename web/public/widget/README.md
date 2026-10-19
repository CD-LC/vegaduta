# VegaDūta embeddable chat widget

One `<script>` tag on a customer's own website gives their visitors a launcher
bubble that opens a chat panel wired to the customer's already-shared agent
(the same AgentShare surface `/agent/<shareId>` serves as a full page).

```html
<script src="https://app.example.com/widget/vegaduta-widget.js"
        data-share-id="YOUR_SHARE_ID"
        defer></script>
```

The share id comes from the agent's Share panel in the console — it is the
same 24-character id that appears at the end of the agent's public share link
(`https://app.example.com/agent/<shareId>`). Replace `app.example.com` with
your deployment's app host; everything else is identical (the loader derives every URL
it needs from its own `src`, so one file serves every environment — see
"Rule 0" below).

---

## ✅ RESOLVED (2026-08-25) — X-Frame-Options (was a cross-workstream dependency)

**Fixed in `deploy/caddy/Caddyfile` and `deploy/caddy/Caddyfile.gcp`**: both now
declare a `@webEmbed` handle block (matched on `path /a/embed/*`, scoped to
the app host in the staging Caddyfile and to both the apex and app hosts
in the production twin, since that file serves both hosts from
one shared site block) positioned *before* the general `@web` block, exactly
as recommended below. It carries every `(security_headers)` header except
`X-Frame-Options`, replaced with `Content-Security-Policy "frame-ancestors *"`.

**Not yet done:** this has been reviewed for Caddy syntax correctness but not
exercised against a live Caddy instance — a staging redeploy + `caddy reload`
+ an actual cross-origin iframe test (confirm no `X-Frame-Options` header and
a working `frame-ancestors *` on a response from `/a/embed/<shareId>`) is
still owed before this is trusted end-to-end. The original blocker writeup
below is kept for context on *why* this shape was chosen.

<details>
<summary>Original blocker writeup (for historical context)</summary>

**The widget did not work on third-party sites until an edge-config change
landed that was outside this workstream's file ownership (`deploy/`).**

`deploy/caddy/Caddyfile` (and `Caddyfile.gcp`) attach the shared
`(security_headers)` snippet to `handle @web`, which sends

```
X-Frame-Options "SAMEORIGIN"
```

on every response from the Next.js app — including `/a/embed/<shareId>`, the
page this widget's iframe loads. Every third-party browser will refuse to
render that iframe. Until the header is scoped away from `/a/embed/*`, the
widget works on pages served from `app.example.com` itself (e.g.
`/widget/demo.html`) and **nowhere else**.

### The exact edit required (owner: whoever holds `deploy/`)

Insert a dedicated handle block **before** `handle @web` in
`deploy/caddy/Caddyfile` (Caddy evaluates `handle` blocks in written order,
so the narrower matcher must come first), and mirror it in `Caddyfile.gcp`:

```caddyfile
# Embeddable widget frame (web/public/widget/README.md): /a/embed/* is the
# ONE path on this app that is DESIGNED to be iframed by third-party sites,
# so it gets every security header EXCEPT X-Frame-Options, plus an explicit
# frame-ancestors grant. Do NOT touch (security_headers) itself - the other
# three hosts keep SAMEORIGIN.
@webEmbed {
	host app.example.com
	path /a/embed/*
}
handle @webEmbed {
	header {
		X-Content-Type-Options "nosniff"
		Referrer-Policy "strict-origin-when-cross-origin"
		Strict-Transport-Security "max-age=31536000; includeSubDomains"
		Permissions-Policy "geolocation=(), camera=(self), microphone=(self)"
		Content-Security-Policy "frame-ancestors *"
	}
	reverse_proxy web:3000 {
		import https_proto
	}
}
```

Why **replace** X-Frame-Options with `frame-ancestors` rather than just
deleting it: XFO has no allowlist syntax (`SAMEORIGIN` or `DENY`, nothing
else), while `frame-ancestors` states the framing policy explicitly. An
explicit `frame-ancestors *` documents "any site may frame THIS path, on
purpose" instead of leaving a silent header gap for the next DAST scan
(docs/DAST-LIVE-PENTEST-2026-07-27.md D1 is why these headers exist at all)
to flag as an oversight.

Why `*` is acceptable here and not a hole: the framed page carries no
session, no cookies, and no credential beyond the share id already sitting in
the embedding page's own HTML. Framing it grants an attacker nothing they
don't get from opening `/agent/<shareId>` directly. Clickjacking needs a
logged-in victim; this surface has no logged-in state to hijack. (A per-share
`allowed_origins` allowlist would be browser-enforced defense-in-depth only —
trivially bypassed by any non-browser client — and was judged not to earn a
schema migration; revisit if share owners ask for it.)

### What is *not* blocked (so nobody "fixes" the wrong layer)

- **CORS needs no change.** The chat page runs inside an iframe whose
  document origin is `app.example.com`, so its calls to
  `api.example.com/api/share/agents/**` come from a first-party origin
  already on core's exact-match allowlist (`WebConfig.java`). That is *why*
  the widget is an iframe and not an inline shadow-DOM chat — the allowlist
  stays closed.
- **The loader script itself** is fetched via `<script src>`, which neither
  CORS nor XFO governs.

</details>

---

## Configuration (`data-*` attributes on the script tag)

| Attribute | Default | Meaning |
|---|---|---|
| `data-share-id` | *(required)* | The agent's share id. Missing → loud console error, no render. |
| `data-position` | `bottom-right` | `bottom-right` or `bottom-left`. |
| `data-accent` | brand indigo `#6d5bff` | Launcher + visitor-bubble colour. Hex / `rgb()` / `hsl()` / CSS keyword; anything else falls back. |
| `data-greeting` | *(none)* | Opening message shown as the agent's first bubble. Local-only — costs the owner zero quota. |
| `data-title` | agent's name | Panel header title. |
| `data-theme` | `auto` | `light`, `dark`, or `auto` (follows each visitor's OS preference). |
| `data-launcher-label` | `Chat with us` | Accessible label on the launcher button. |
| `data-offset` | `20` | Distance from the viewport edges, px. |
| `data-z-index` | `2147483000` | Stacking level of the launcher/panel. |
| `data-auto-open` | `false` | Open the panel on load (bare attribute = true). |
| `data-base-url` | *(derived from `src`)* | Escape hatch when the script is re-served from a CDN path and the derived origin would be wrong. |

### JavaScript API

The loader exposes `window.VegaDutaWidget` with `open()`, `close()`,
`toggle()`, `isOpen()`, and `destroy()` — enough for "Talk to us" nav links
and SPA route teardown, nothing more (every extra verb is API kept stable
forever).

---

## Architecture map (for maintainers)

| Piece | Path | Notes |
|---|---|---|
| Loader (customer-pasted) | `web/public/widget/vegaduta-widget.js` | Unbundled, framework-free, served byte-for-byte. |
| Framed chat page | `web/app/a/embed/[shareId]/page.tsx` | Public via the `/a/` prefix in `PUBLIC_ROUTES`; static `embed` segment outranks the sibling `[shareId]` route, and real share ids are ≥16 chars so `embed` can never collide. |
| Chat surface | `web/app/components/widget/EmbedChat.tsx` | Reuses `web/app/agent/[shareId]/api.ts` — the real AgentShare client, NOT `/a/[shareId]`'s demo-sandbox client. |
| postMessage contract | `web/app/components/widget/embedProtocol.ts` | **Hand-maintained pair** with the loader — the loader cannot import TypeScript. Change both in the same commit; bump `PROTOCOL_VERSION`. |
| Demo harness | `web/public/widget/demo.html` | Same-origin test page; works even before the XFO fix lands. |

## Rule 0 (CLAUDE.md): why there is no baked base URL

`NEXT_PUBLIC_API_BASE_URL` is baked at build time and defaults to
`http://localhost:8080` in `web/Dockerfile` — for a widget that failure mode
is catastrophic (a customer's visitor is never on the developer's machine, so
it fails 100% silently in the field). The loader therefore derives its origin
from its own `<script src>` and refuses to render if it cannot; it also logs
a loud error if a public host page points at a loopback widget origin. Note
the *framed page* still uses the app's normal `NEXT_PUBLIC_API_BASE_URL` to
reach core — the standing rule that web must be built with the domain overlay
(`docker-compose.domain.yml` / `gcp.yml`) covers the widget exactly as much
as it covers sign-in.
