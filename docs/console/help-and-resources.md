# Help and resources

The learning, download and transparency rows, visible to every member. None of their writes are admin-scoped.

| Label | Route | Nav needs | Backend rule |
|---|---|---|---|
| Guide | `/help` | any member | none (static content) |
| Docs | `/help/docs` | any member | none (static content) |
| Apps & Plugins | `/plugins` | any member (page is also public) | `GET /api/plugins/catalog` is public |

## Guide (`/help`)

The in-console reference: how the product works. A pure reading surface with one page heading, a sticky "On this
page" rail on wide screens (a row of jump pills on narrow ones) and the copy in the `helpPage` message namespace.
Two related pages are public and linked from it: workflow recipes (`/help/workflow-recipes`, with per-industry
pages) and workflow expressions (`/help/workflow-expressions`).

## The Academy and the personal tutor

The Academy, the free course site that teaches the concepts behind the screens, is a separate site at
[academy.vegaduta.ai](https://academy.vegaduta.ai). The console links to it but carries none of its lessons: the
old in-console `/learn` index was removed in 0.1.8. Many console pages have a **Learn this in the Academy** link in
their page guide, and the Assistant's **About this assistant** popover links there too. The address comes from
`NEXT_PUBLIC_ACADEMY_URL` (Compose: `WEB_ACADEMY_URL`), which defaults to the hosted Academy; an empty value hides
the links.

### Personal tutor (`/learn/tutor`)

Type any topic, get a course (modules and lessons), study a lesson, answer its quiz, and come back for the questions you
missed (spaced-repetition review). Everything is stored and graded by core under `/api/tutor/**`, scoped to the
signed-in member. The tutor is behind a server flag (`APP_TUTOR_ENABLED`, see [configuration](../configuration.md)).
With it off, core maps no `/api/tutor` route, the status probe answers 404 and the page shows a plain "not available
here" state; the command palette also omits the tutor row. There is no sidebar row for it. API:
`/api/tutor/status`, `/api/tutor/plans/generate`, `/api/tutor/plans/{planId}/lessons/{lessonId}/generate`,
`.../complete`, `.../answers`, `/api/tutor/reviews/{planId}/{cardId}/answer`, `/api/tutor/agent`.

## Docs (`/help/docs`)

The authenticated twin of the public `/docs` page. It shows the same shared content in the console chrome, follows the
console theme, and enables per-topic deep links so that every reader here, who has a session, can jump straight to
`/agents`, `/knowledge` and the other screens.

## Apps & Plugins (`/plugins`)

The page for every first-party client plugin and app: the downloadable IDE and browser plugins (a catalog-driven gallery) and a status
list of the apps, wearables and tools the catalog does not hold. It shows real store links where something is
published and an honest "not yet published" state otherwise. The page is public and renders in the standalone chrome
for signed-out visitors. The catalog is a platform setting that operators publish to in
[Platform admin](platform-admin.md); see also [plugins and apps](../plugins-and-apps.md).

## Downloads (`/downloads`)

A public page, linked from the public site's header and footer rather than the sidebar. It lists the installers of
the latest release (the Setup app for macOS, Windows and Linux), resolved from the release itself, plus the source
tarball, container images, the Helm chart and the SDKs. When no release can be read it sends you to the releases
page instead of showing broken links.
