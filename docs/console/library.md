# Library

Reusable things you browse and pull in, split out of Build so that Build holds only what you author.

| Label | Route | Nav needs | Backend rule |
|---|---|---|---|
| Community Templates | `/templates` | any member (page is also public) | gallery reads are public; importing one needs `PERM_MANAGE_AGENTS`; submitting your own needs `PERM_MANAGE_TEMPLATES` |
| Knowledge | `/knowledge` | any member | reads and search are open; authoring needs `PERM_MANAGE_AGENTS` |
| Shared with me | `/shared-with-me` | any member | the caller's own grants |

## Community Templates (`/templates`)

The gallery of admin-approved agent and workflow templates, with one-click import into the workspace. The first
page of results is rendered on the server so crawlers see real HTML, then the client gallery re-fetches as filters
change and uses the shared pager. The page lives under the public `/templates` prefix and also renders for signed-out
visitors in the public chrome.

- `/templates/{id}` is the read-only detail page for one approved template, with the Import action.
- `/templates/mine` ("My submissions") lists the signed-in user's own submissions. It renders in the public chrome and
  checks the session itself, prompting sign-in if there is none.
- Moderation is not here: approving or rejecting submissions is a platform-operator task (see
  [Platform admin](platform-admin.md)). Nothing a member submits is public until approved.

API: `GET /api/templates/gallery`, `GET /api/templates/gallery/{id}` (public), `POST /api/templates/{id}/import`,
`POST /api/templates` and `POST /api/templates/{id}/submit`.

## Knowledge (`/knowledge`)

The knowledge base. A page-owned action row shows the collection count, **New collection** and **Upload files**.
Below it is a three-column body:

1. **Collections**: the list of collections, with create, edit and delete.
2. **Collection detail**: the documents in the selected collection (server-paged with `limit`/`offset` and
   `X-Total-Count`, see [Lists and paging](lists-and-paging.md)), upload (documents and audio), delete, retrieval
   settings, the OCR provider, the RAG evaluation run and face-enrollment controls where an agent has biometric
   consent.
3. **Semantic search**: a test panel that queries the selected collection so you can see what an agent would retrieve.

Document ingestion uses the ML sidecar; on the Lite tier start it with the `rag` profile
([getting started](../getting-started.md)). API: `/api/knowledge/collections`, `/api/knowledge/collections/{id}/documents`,
`/api/knowledge/upload`, `/api/knowledge/upload-audio`, `/api/knowledge/search`, `/api/knowledge/stats`,
`/api/knowledge/{collectionId}/eval`, `/api/knowledge/ocr-provider` (that one needs `PERM_MANAGE_INTEGRATIONS`),
`/api/face-enrollments`. Writes under `/api/knowledge/**` (ingest, upload, collection create, update and delete,
document delete, re-embedding) need `PERM_MANAGE_AGENTS`; reads and search stay open to every member.

## Shared with me (`/shared-with-me`)

The receiving side of cross-user sharing: agents and workflows that teammates granted the signed-in user access to.
It is the only discovery surface for them (named invites only; there is no browse of other people's private
resources). The API returns the resource id only, so the page enriches each row by matching it against the agent
and workflow lists and falls back to a shortened id when the resource was deleted. The list is requested with
`limit`/`offset` and uses the shared pager. Granting access is done on each agent's or workflow's own Sharing
panel. API: `GET /api/shared-with-me`.
