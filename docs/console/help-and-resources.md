# Help and resources

The learning, download and transparency rows, visible to every member. None of their writes are admin-scoped.

| Label | Route | Nav needs | Backend rule |
|---|---|---|---|
| Guide | `/help` | any member | none (static content) |
| Learn | `/learn` | any member | none (static map) |
| Docs | `/help/docs` | any member | none (static content) |
| Apps & Plugins | `/plugins` | any member (page is also public) | `GET /api/plugins/catalog` is public |

## Guide (`/help`)

The in-console reference: how the product works. A pure reading surface with one page heading, a sticky "On this
page" rail on wide screens (a row of jump pills on narrow ones) and the copy in the `helpPage` message namespace.
Two related pages are public and linked from it: workflow recipes (`/help/workflow-recipes`, with per-industry
pages) and workflow expressions (`/help/workflow-expressions`).

## Learn (`/learn`)

The Academy, indexed inside the console. `/help` answers "how does this product work"; `/learn` answers "teach me the
concept behind the screen I am looking at". It maps console routes to Academy lessons (a "which lesson explains this
screen" map) rather than restating the course, and it filters out admin-only screens for non-admins. Many console
pages also carry a "Learn this" button that deep-links to the matching lesson. The Academy is a separate deployable
whose address is `NEXT_PUBLIC_ACADEMY_URL`; when that is unset (the open-source default) the Academy links are hidden
rather than pointing at a dead address.

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

The page for every first-party client: the downloadable IDE and browser plugins (a catalog-driven gallery) and a status
list of the apps, wearables and tools the catalog does not hold. It shows real store links where something is
published and an honest "not yet published" state otherwise. The page is public and renders in the standalone chrome
for signed-out visitors. The catalog is a platform setting that operators publish to in
[Platform admin](platform-admin.md); see also [plugins and apps](../plugins-and-apps.md).
