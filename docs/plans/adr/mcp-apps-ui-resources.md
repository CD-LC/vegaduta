# ADR: MCP Apps (ui:// resources) in chat

Status: proposed; implemented behind flags, default OFF. Originally a library-only first slice (2026-10-01). Checked 2026-10-21 (v0.1.9): the console renders `McpAppFrame` in chat (`web/app/components/agents/AgentActivityTrace.tsx`, `NEXT_PUBLIC_MCP_APPS=1`) and core serves app resources from `core/agents-tools/.../tools/mcpapps` (`McpAppsService`, `McpAppsController`, `app.mcp.apps.enabled`, default `false`).

## Premise check
A grep of core/ and web/ finds no handling of `ui://`, `resources/read`, or tool `_meta.ui`. MCP tool results reach chat as text only. So the feature is absent, not already built. The sandbox pattern in `web/app/build/SandboxPreview.tsx` was the model, but `McpAppFrame` copies the idea and shares no code with it, and it is not wired into chat.

## Context
MCP Apps lets a server attach an HTML view (`ui://server/view`, `text/html;profile=mcp-app`) to a tool. The host renders it in a sandboxed iframe and talks to it over JSON-RPC postMessage. Useful for researchers and developers (charts, tables, forms from a tool). The HTML is untrusted third-party code.

## Security review (SandboxPreview pattern applied to untrusted MCP content)
- Sandbox: `sandbox="allow-scripts"` only gives an opaque origin: no cookies, storage, or parent DOM. It must never gain `allow-same-origin` (nullifies the sandbox) nor popups, forms, modals, top-navigation. Pinned by a constant and a test.
- CSP: SandboxPreview allows esm.sh and blob imports, which is unsuitable here. The app doc gets a meta CSP: `default-src 'none'`, inline script/style, `data:` images/fonts, `connect-src 'none'`, no frames, `base-uri 'none'`, `form-action 'none'`. Meta CSP is cumulative, so app markup cannot loosen it. The parent page's `frame-src` must not be widened; srcdoc needs no extra source.
- postMessage: SandboxPreview checks `e.source` identity only. Added here: origin must be the literal `"null"`, strict JSON-RPC 2.0 shape, method allow-list (`ui/notifications/size-changed`, `ui/message`), clamped height, text length cap. The host never posts anything into the frame (no host data, no tokens). `ui/message` is surfaced only as a proposal typed `requiresConfirmation: true` via `onProposeMessage`, and a test pins that shape. The consumer (step 3) must show it and get confirmation, never auto-send, since it is a prompt-injection channel from a third-party app into the agent; the type cannot force the UI to do so.
- Resource: inline text only, mime allow-list, 256 KB cap, ui:// URI regex. The browser never fetches the HTML from a remote URL.
- Not allowed in this slice: `tools/call` from the app, `resources/read`, link opening, any credentials.

## Residual risks (known, not mitigated in this slice)
- Self-navigation: app script (`location.href`) can navigate the frame to any https page; the new document is outside our meta CSP and can make network requests. `form-action` and `connect-src` do not cover navigation and there is no `navigate-to`. The frame holds no credentials, cookies or host data, so impact is limited to a third-party page shown in the frame (phishing, UI redress) and tracking requests. We strip `<meta http-equiv=refresh>` as defense in depth only (bypassable by script). Real fix: option 3 (separate sandbox origin with CSP headers). The flag must not be enabled for untrusted servers before that.
- Clickjacking / UI redress inside the frame: a hostile app can draw a fake login or prompt. No "content from <server>" attribution exists until step 3.
- Handshake not implemented: the MCP Apps `ui/initialize` request and host reply are unsupported and the host never posts into the frame. Apps that wait for an initialize reply will hang or render empty. Step 3 must add it with a minimal, data-free reply.

## Options
1. Render only; app messages limited to resize and user-confirmed text (chosen for the first slice).
2. Also allow `tools/call` proxied by the host through core with the same per-tool approval as agent calls. Needs a core endpoint and an audit trail.
3. Serve apps from a separate sandbox origin with real CSP headers. Stronger than srcdoc but needs infra.

## Staged plan
1. (done, library-only) web lib, `McpAppFrame`, tests, flag OFF. Not rendered anywhere.
2. Core (core-3 scope, not started): carry `_meta.ui.resourceUri` on MCP tool results, fetch the resource via MCP `resources/read` on the same connection, add `app.mcp.apps.enabled` (default false) plus per-server opt-in, and emit a tool-result event with `{uri, mimeType, text}`.
3. Wire `McpAppFrame` into the chat tool-result renderer with the confirmation UI for `ui/message` and a visible "content from <server>" banner.
4. Option 2 behind its own flag, only after an approval flow exists.

## Risks
- Malicious server HTML: mitigated above; worst case is a misleading view. Attribute the frame to its server.
- Fake login forms inside the frame: the host gives it no credentials, but users may type secrets; banner in step 3.
- Resource size/CPU abuse: size cap; consider unmount-on-scroll.
- Spec drift: the MCP Apps profile is young; method names live in one file.

## Verification state
Written, not compiled, not run (tsc, eslint and vitest must pass before merge). Not deployed. Nothing renders in chat yet: steps 2 and 3 are required. The original spec's first slice (render a ui:// resource in web reusing SandboxPreview) is NOT delivered.


## Update 2026-10-05 (wave E, lane mcpapps)
Steps 2-4 of the staged plan are built behind flags that stay OFF by default (`app.mcp.apps.enabled` in core,
`NEXT_PUBLIC_MCP_APPS=1` in web): core records `_meta.ui` at discovery, fetches the resource over the same
authenticated connection, and serves it through a tenant+user-bound handle; the web wires `McpAppFrame` into the
shared activity trace with the `ui/initialize` handshake, `tools/call` (through the normal guarded tool chain, same
server only), `ui/open-link` and `ui/message` (both confirmed by the person). Option 3 (separate sandbox origin) is
still NOT done, so the self-navigation risk above is unchanged. Threat model, mitigations and residual risks:
`docs/plans/MCP-APPS-SECURITY-NOTES.md`. Unit-tested only; not run against a real server or browser, not deployed.
