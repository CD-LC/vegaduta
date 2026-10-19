# Add-ons

**Add-ons** (`/add-ons`, sidebar label "Add-ons", Configuration group) lists the install's optional extras:
the Assistant's build terminal, web search, document reading, the Assistant's own browser and free local
model packs. Each card says what the add-on gives you and what it costs this computer, and has one button to
turn it on or off. On a [personal install](../personal-mode.md) or a lite one every card applies; on the
full stack the entries its launcher cannot run are marked "not available on this installation tier" and the
commands render for the `up --full` shape. The page is a view over the add-on registry in core
(`core/providers`, package `com.agenticai.core.providers.addons`); it adds no backend of its own.

| | |
|---|---|
| Route | `/add-ons` |
| Sidebar | Configuration group, after "Data & Infrastructure". Shown on every install; on the full stack the entries its launcher cannot run (the single-box `rag`/`workspace` profiles) are marked "not available on this installation tier", `--browser` renders as the `browser` profile, and a caller without change rights sees the switches disabled rather than a 403 after the click |
| Nav needs | any signed-in user |
| Backend rule | reading needs only a signed-in user. Changing needs `ROLE_platform-admin`, or `ROLE_tenant-admin` when `app.auth.mode=local` (the personal install, where the owner is the tenant admin) or `app.addons.tenant-admin-may-change=true` (default on lite, where a tenant admin is usually the box's operator). The overview's `mayChange` carries the verdict so the console can disable the switches up front. Changes are limited to 30 a minute per caller and audited as `addon.enable` and `addon.disable` |
| API | `GET /api/addons`, `GET /api/addons/{id}`, `POST /api/addons/{id}/enable`, `POST /api/addons/{id}/disable[?cascade=true]` |
| Page guide | "Add extras to your install" (the page-guide button in the top bar) |

The `/api/addons/host/**` endpoints are for VegaDūta Setup, not the console: they take a shared secret, never a
person's sign-in.

## Who does the work

Core runs in a container and cannot start containers, so what happens on **Turn on** depends on the add-on:

- **Compose add-ons** (the build terminal, web search, documents, the Assistant browser) are started on the
  computer. Core records your wish and tells the host exactly what to do. Until the host has applied it, the card
  says it is waiting, never "On". Today you apply a change by re-running **VegaDūta Setup** and ticking the extra
  in its Extras step, or by running the command the page shows; the automatic link between core and Setup is
  built on core's side only (see "Waiting for your computer").
- **Model packs** are downloaded by core itself through Ollama, with live progress on the card.
- **Planned** add-ons (a tools runner, local image generation, local spoken replies) are shown as "Coming soon" with
  no button. They cannot be turned on.

## What a card shows

- The name and what it unlocks, in plain words (for example "Unlocks: Web search").
- A status badge, always with an icon and text, never colour alone (see the table below).
- Badges for approximate disk size, memory, whether a GPU is needed, the licence and whether it is free. A model's
  memory figure is the free memory the machine should have; for the others it is the extra memory the service may
  use on top of the base stack.
- What it needs ("Needs: ...") and what needs it ("Needed by ..."). Turning something on also turns on its
  requirements; the confirmation says so.
- A warning when this computer looks too small (low memory, no GPU). It is advice: the button stays available.
- A "Learn more" link to the matching documentation page.

### Status badge

| Badge | Meaning |
|---|---|
| Off | Not wanted. For a model, the card adds "The downloaded files are still on disk" when they are |
| Waiting to be turned on | Your choice is saved; the host has not applied it yet. The card says whether VegaDūta Setup is linked (in this release it is not) |
| Turning on | VegaDūta Setup reported it is working on it |
| On | Running: the host reported it running for your current choice, core can see it running, or (models) it is in Ollama |
| Waiting to be turned off | Switched off here, but it still runs until VegaDūta Setup stops it |
| Downloading | Core is pulling the model; a progress bar shows the percentage |
| Failed | The reason is shown. For a compose add-on, **Try again** asks the host again; for a model it downloads again |
| Coming soon | Planned; no button |

The button asks for a change and the badge reports what has been seen; they are deliberately separate.

## Turning something on or off

**Turn on** opens a confirmation with the combined disk and memory cost of the add-on and anything it also turns
on, what happens next (a model downloads now; a compose add-on is started by Setup), and a warning if the computer
looks too small. **Turn off** asks too. If other add-ons depend on the one you are turning off, the dialog says
so and offers **Turn all off**; it only does that after you confirm (the request carries `cascade=true`). If core
refuses a turn-off that the page did not foresee (`409 required_by`), the same question appears.

Turning off a model records that you do not want it. Its downloaded files stay on disk (core has no delete call for
Ollama models).

## Waiting for your computer

While any compose add-on waits for the host, a panel says what is waiting and how to apply it:

- **The Setup link.** When core has a host token (`ADDONS_HOST_TOKEN` in `.env`, passed to core as
  `APP_ADDONS_HOST_TOKEN`) the panel says Setup applies the changes while it is open and checks every 30 seconds.
  **That link is not complete in this release:** core serves the endpoints Setup would use, but nothing generates
  the token and the Setup app does not poll yet, so the panel shows "Setup is not linked" and the commands below
  are the way to apply a change. Re-running Setup and ticking the extra in its Extras step works too.
- A **copyable command** for the whole wanted set, to run in the install folder. On Windows the page leads with the
  PowerShell form (`scripts\vegaduta.ps1 personal -Profile a,b`) and says that `--search` has no PowerShell option, so
  web search must be turned on from Setup there.
- Extra **`.env` lines** (for example the Assistant browser's `CORE_IMAGE_VARIANT=-full`) to add to
  `deploy/compose/.env`, with a copy button.
- For a turn-off, the panel does **not** offer a command: the launcher only adds things. It tells you to open
  VegaDūta Setup, which stops the service, and lists env keys you may have added by hand and should remove.

The page cannot open the Setup app for you; it tells you to open it.

## Finding things

A search box (matches the visible name, description and category), category chips with counts, and a state filter
(any, on, off, in progress or needs attention, coming soon). With nothing matching, an empty state offers to clear
the filters. A line shows what this computer has (memory, CPU cores, GPU or Apple silicon) so the fit warnings make
sense.

If the local model server (Ollama) is not reachable, a banner says model packs cannot be turned on yet and links to
[local model setup](../local-models.md). Model cards say the same and their button is disabled; nothing is recorded.

## Staying current

The page re-reads the list every 2 seconds while a model downloads and every 5 seconds while a compose add-on waits
for the host, and stops when nothing is in flight. A hidden tab waits and reads once when it is visible again. If a
refresh fails, the last known state stays on screen with a note.

## Errors

Core answers errors as `{ error, message, details }`. The page shows its own translated sentence per `error` code and
falls back to core's English text only for an unknown failure:

| Code | What the page says |
|---|---|
| `unknown_addon` | The add-on no longer exists; the list was refreshed |
| `not_available` | Not available yet |
| `required_by` | Other add-ons still need it (normally handled by the turn-all-off question) |
| `conflict` | Someone changed it at the same moment; the list was refreshed |
| `rate_limited` | Too many changes in a minute |
| `too_many_downloads` | Two model downloads are already running |
| `ollama_unreachable` | The model server is down; nothing was started |

A `403` says you lack permission to change add-ons here; a `401` says the session ended.

## Language and accessibility

Names and descriptions come from the registry's catalog (English). A translation is looked up by add-on id
(`addons.items.<id>.name` and `.description`) and falls back to the catalog text, so English needs no copy of it in
`web/messages/en.json`. Status, category, unlock keys, fit, errors and every label are translated. Everything is
reachable by keyboard: the buttons and chips are native buttons, the confirmation is a focus-trapped dialog
(Escape cancels), the command blocks are focusable so long lines can be scrolled, and the download bar has
`role="progressbar"` with its value.

## Not shown

LiveKit voice agent, the MCP stdio runner (listed as "Coming soon"), the WhatsApp QR channel and a containerised
Ollama are not add-ons today, because the personal stack cannot switch them on.
