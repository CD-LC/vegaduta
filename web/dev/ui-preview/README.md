# UI preview (local, dev-only)

Renders the REAL console pages without signing in: `/ui-kit/pv/<route>` mounts the page component under a fake admin user
(AuthContext) with `fetch` mocked by fixtures. Nothing here ships: the generated files go to `web/app/ui-kit/pv*` and are
git-ignored; `--remove` deletes them.

```bash
cd web && bash dev/ui-preview/install.sh
NEXT_PUBLIC_SITE_MODE=oss npm run dev      # then open http://localhost:3000/ui-kit/pv  (route index)
```

How it works
- `mock.ts`: overrides `window.fetch` for `/api/*`, `/v1/*`, `/actuator/*`; unmatched GETs answer 404 and are logged to the
  console as `[preview] no fixture for GET /api/...` (use that list to add fixtures). `paged()` mimics limit/offset with
  `X-Total-Count` / `X-Has-More`.
- `fixtures/<area>.ts`: arrays of `{ method?, match, respond }`; add one file per area and register it in `fixtures/index.ts`.
- Only client-component pages are loadable (pages that export metadata or use `next/headers` are skipped).
- STATUS: the harness runs and renders pages; the shell-level fixtures (`/api/system/status`, `/v1/ip/geo.json`,
  pairing, capability probes, `/api/client-log`) and per-page fixtures still need to be written.
