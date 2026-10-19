# Plugins, apps and mobile

VegaDūta runs in more places than the browser console. This page is the reference for each one:
what it does, how you get it, and where it really stands. It mirrors the public `/plugins` page
of the console, whose status list comes from `web/app/components/plugins/surfaces.ts`.

!!! note "What is, and is not, in this repository"
    The **platform side** of plugins and apps is here: the public catalog API, the artifact
    upload and download endpoints, the console pages and the MCP server. The **client builds**
    themselves (the VS Code, JetBrains, Chrome and Edge extensions, the Eclipse plugin, the
    Android app and the Wear OS companion) are not part of this repository's source tree. They
    are built and distributed through the catalog described below, and each deployment decides
    what it publishes.

## Where each one stands

| Surface | What it does | Status | How you get it |
|---|---|---|---|
| VS Code | Chat with agents, run workflows, explain/fix/refactor selected code, a coding agent that asks before every edit and command | Available | Download from the console's `/plugins` page and install from the file. Not on the VS Code Marketplace yet |
| JetBrains IDEs | The same chat, workflow runs and code actions in IntelliJ IDEA, PyCharm, WebStorm, GoLand and other IntelliJ-based IDEs, with inline completions | Available | Download from `/plugins`, install from disk. Not on the JetBrains Marketplace yet |
| Chrome | Side panel: ask about a page or selection, run a workflow, a browser agent that asks before it clicks or types | Available | Download from `/plugins`. Not on the Chrome Web Store yet |
| Eclipse | Chat plus explain, fix, refactor, tests, docs and review commands; no coding agent | Preview | Built, not offered for download |
| Microsoft Edge | The Chrome extension packaged for Edge | Preview | Not submitted to Edge Add-ons |
| Android app | Chat, quick tasks, workflows and approvals on a phone, with on-device AI on capable devices | Beta | Direct APK download (see [Mobile](#mobile)). Not on Google Play |
| Wear OS watch | Approve or decline what an agent wants to do, from the wrist | Preview | Companion to the Android app. Not on Google Play |
| iPhone app | The same mobile experience for iPhone | In development | No build exists |
| Desktop agent | A command-line program that pairs your computer with your workspace and runs a local model for your workflows | Preview | Works from source; no packaged download |
| MCP server | Use your agents, workflows and knowledge base from any MCP client | Available | Part of every workspace: `/mcp` (Streamable HTTP), scoped API key |

"Available" means you can get it now. Everything else says plainly what is and is not ready; if
a status changes, update `surfaces.ts` and this table together.

## The plugin catalog

The public marketplace page (`/plugins`) and the plugins' own update checks read one
unauthenticated endpoint:

```http
GET /api/plugins/catalog
```

It returns the catalog version and one entry per plugin: `id`, `displayName`, `description`,
`latestVersion`, `releasedAt`, `releaseNotes`, `artifact` (`fileName`, `sizeBytes`, `sha256`) and
`externalStoreUrl`. Every nullable field is genuinely null until something is published: an entry
with `artifact: null` is shown as "coming soon", never as a dead download link. The SHA-256 is
computed by the server from the exact bytes it serves, so what the page displays is what the
download delivers; verify a download against it.

### Publishing a build (operators)

1. Upload the file (platform admin credentials):

    ```http
    POST /api/platform/plugins/artifacts
    Content-Type: multipart/form-data

    pluginId=vscode&version=1.4.0&file=@vegaduta-1.4.0.vsix
    ```

    The response carries the stored file name, size and SHA-256.

2. Point the catalog entry at that version by updating the platform setting `plugins.catalog`
   (`PUT /api/platform/settings/plugins.catalog`) with the new `latestVersion`, `releasedAt` and
   `releaseNotes`. Until the setting exists the server serves a built-in default catalog with
   entries for `vscode`, `jetbrains` and `chrome` and no artifacts.
3. The download is then served from
   `/api/plugins/artifacts/{pluginId}/{version}/{fileName}` as an attachment with an exact
   `Content-Length`.
4. When you also publish to an official store, set `externalStoreUrl` on the entry; the platform
   never invents one.

## Mobile

There are two distinct things people mean by "mobile":

**The Android app (beta).** A direct-download APK. The console shows download panels (in
**Channels → Mobile SDK** and in the `/try` studio) only when you configure the build:

| Variable | Compose name | Meaning |
|---|---|---|
| `NEXT_PUBLIC_MOBILE_APK_URL` | | Direct download URL. Empty hides every panel |
| `NEXT_PUBLIC_MOBILE_APK_SHA256` | | Checksum shown beside the download |
| `NEXT_PUBLIC_MOBILE_APK_VERSION` | | Version label |
| `NEXT_PUBLIC_MOBILE_APK_SIZE_MB` | | Approximate size, so nobody starts a large download by surprise |

These are runtime values (`window.__ENV`; see `web/docs/RUNTIME-CONFIG.md`), read by
`web/app/lib/mobileApp.ts`. There is no default: a deployment that does not publish its own APK
shows no panel.

**Device pairing (the platform side of the mobile SDK).** The core `devices` module lets a phone
or other device pair with a workspace using a short-lived pairing code, then exchange commands,
notifications and usage events. Manage paired devices and generate codes in
**Channels → Mobile SDK** (`/admin/channels?channel=mobile-sdk`; the old `/devices` route
redirects there). Devices authenticate with their own credential, not a user session.

## Editors, browsers and the MCP server in your own tooling

Anything that speaks MCP can use the platform without a plugin: point the client at the
workspace's `/mcp` endpoint with a grant key (`vmcp_...`, confined to the agents, workflows and
knowledge collections of its grant). An OAuth 2.1 mode exists but is **off by default**
(`app.mcpserver.oauth.enabled`). A request that carries an `Origin` header (a browser page) is refused
unless that origin is in `app.mcpserver.origin.allowed`; clients without an `Origin` header are
unaffected. The server speaks the handshake-based MCP revisions up to 2025-11-25, not the stateless
2026-07-28 revision. Details and open items: [MCP specification alignment](architecture/mcp-spec-alignment.md).
Grant keys are separate from the tenant REST API keys (`vdak_...`) described in
[Extending](extending.md#9-connect-other-systems-without-code). See also the MCP server entry in
[Extending](extending.md) and the registry guide in
[Registering a custom MCP server](core/mcp/REGISTERING-A-CUSTOM-MCP-SERVER.md).

## Related

- [Site modes and public pages](site-modes.md): where these surfaces are linked from.
- [SDKs and dependencies](sdk/README.md): the Java and Python Agent SDKs and the REST clients, for
  building your own integrations instead of using the first-party ones.
