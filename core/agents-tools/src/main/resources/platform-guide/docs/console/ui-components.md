# Shared controls and the UI preview

The console uses one small set of form controls so every page behaves and is labelled the same way. They live in
`web/app/components/ui/`. This page is for people who read or extend the console; end users only see the effect
(consistent dropdowns, confirm dialogs and pagers).

## Controls

| Control | File | Replaces | What it does |
|---|---|---|---|
| Select | `ui/select.tsx` | native `<select>` | Single-choice listbox. Enter, Space or Arrow Down opens; Arrow keys, Home and End move; typing jumps to a match; Enter selects; Escape or Tab closes. Options can be disabled. |
| Checkbox | `ui/checkbox.tsx` | native checkbox | Three states: checked, unchecked and indeterminate (announced as "mixed"). Used for "select all" over a partly selected list. |
| Combobox | `ui/combobox.tsx` | long selects | A searchable single-choice picker. By default it filters the options in the browser; when the page passes an `onSearch` handler it asks the server instead (with a loading state) and local filtering is off. |
| ConfirmDialog | `ui/confirm-dialog.tsx` | `window.confirm()` and `window.prompt()` | A modal confirmation with focus trapping; Escape cancels. A destructive style is available, and with a required reason the confirm button stays disabled until a non-blank reason is typed (the trimmed reason is passed on, for example to the audit log). An async confirm keeps the dialog open and busy until it finishes. |

The lint rule `native-controls/no-native-controls` (in `web/eslint.config.mjs`) forbids a native `<select>`,
`window.confirm` and `window.prompt` in console code. Files that still contain one are listed in
`web/app/components/ui/native-controls-allowlist.txt`; that list may only shrink, so when you migrate a file, remove
its line.

## Pager conventions

All lists use the one pager described in [Lists and paging](lists-and-paging.md). When you add a list:

- Use `usePagination(items)` plus `PaginationControls` for a list the API returns whole, and the server mode
  (`limit`, `offset`, `X-Total-Count`, `X-Has-More`) for a list that can grow without bound.
- Pass the noun for the range summary (`itemLabel`) and, when the server caps the list, the cap, so the "Showing the
  first N" notice appears.
- Never turn a failed load into an empty list; show the error.

## The dev-only UI preview

`web/dev/ui-preview/` renders the real console pages without signing in, for design and layout checks. Nothing in it
ships.

```bash
cd web && bash dev/ui-preview/install.sh
NEXT_PUBLIC_SITE_MODE=oss npm run dev      # then open http://localhost:3000/ui-kit/pv  (route index)
```

- It generates files under `web/app/ui-kit/pv*`, which are git-ignored; `bash dev/ui-preview/install.sh --remove`
  deletes them.
- Each page is mounted under a fake admin user with `fetch` mocked by fixtures (`mock.ts`, `fixtures/<area>.ts`).
  A request with no fixture answers 404 and logs `[preview] no fixture for GET /api/...` in the browser console;
  use that list to add fixtures. The mock mimics `limit`/`offset` and the `X-Total-Count` / `X-Has-More` headers.
- Only client-component pages can be loaded; pages that export `metadata` or use `next/headers` are skipped.
- Status (per `web/dev/ui-preview/README.md`): the harness runs and renders pages, but the shell-level fixtures
  (system status, geo, pairing, capability probes, client log) and per-page fixtures still need to be written, so
  expect many pages to show empty or error states until fixtures exist.
- Do not use it for anything but local development. The separate `/ui-kit` routes in the app are the design-system
  showcase, not this harness.
