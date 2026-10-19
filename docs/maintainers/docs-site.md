# Publishing these docs

The documentation site is built from `docs/` with MkDocs Material (`mkdocs.yml` at the
repository root) and published to GitHub Pages by `.github/workflows/docs.yml`.

## One-time setup

1. Repository **Settings → Pages → Build and deployment → Source**: choose **GitHub Actions**.
2. Repository **Settings → Secrets and variables → Actions → Variables**: add
   `DOCS_PUBLISH_PAGES` = `true`. Until it is set the workflow builds the site (so a broken
   page still fails the pull request) but skips the deploy job, so `main` does not show a
   failed run merely because Pages is off.
3. Optional custom domain: add it under the same Pages settings and set `site_url` in
   `mkdocs.yml` to match. Without one the site is served at
   `https://<owner>.github.io/<repository>/`.
4. Set `WEB_DOCS_URL` (Compose) or `NEXT_PUBLIC_DOCS_URL` (web) on your deployment so the
   console's "Docs" links point at the published site instead of the in-app help
   ([Site modes and public pages](../site-modes.md)).

## How it builds

- The workflow builds with `mkdocs build --strict` on every pull request and push to `main` that
  touches `docs/`, `mkdocs.yml` or the embedded SDK READMEs, and on demand from the Actions tab.
  A broken link or a missing snippet fails the run. It deploys to Pages when
  `DOCS_PUBLISH_PAGES` is `true`. `make docs-build` runs the same strict build locally, and
  `scripts/ci/docs-link-check.sh` (part of `make gates`) checks links offline.
- Pages outside `docs/` (the Java Agent SDK README, the example README, the client SDKs
  README) are embedded by `scripts/docs/mkdocs_hooks.py`: a line `--8<-- "path/from/repo/root.md"`
  is replaced by that file's content, so there is one source.
- `docs/platform-documentation/` (the HTML handbook) is copied as static files and linked from
  the Architecture tab.
- Links from a page to files outside `docs/` (for example `../CONTRIBUTING.md`) work on GitHub
  but have no target on the site; the same hook rewrites them to the file's GitHub URL (blob for
  files, tree for directories) at build time. The Markdown on disk is unchanged. A link whose
  target does not exist is left as is and fails the strict build.

## Locally

```bash
make docs-serve     # http://127.0.0.1:8000, live reload
make docs-build     # strict build into site/
```

Both targets create a virtual environment in `.venv-docs/` from `docs/requirements.txt`
(Python 3.9 or newer).
