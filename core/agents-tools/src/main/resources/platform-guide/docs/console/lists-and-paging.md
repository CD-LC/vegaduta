# Lists and paging

Every list, table and grid in the console uses one pager component,
`web/app/components/PaginationControls.tsx`. It has two modes. This page describes what you see and how the two modes
differ; the endpoint contract is in [list endpoint paging](../api/LIST-ENDPOINT-PAGING.md), which is the authoritative
list of which endpoints accept `limit` and `offset`.

Conventions for adding a new list, and the shared controls, are on [Shared controls](ui-components.md).

## What the pager looks like

A thin bar under the list, separated by a rule:

- **Range summary** on the left: "11-20 of 53 records" (the noun changes per list, for example "notifications",
  "agents" or "invoices"). It is a live region, so screen readers hear the change.
- **Show** (a "Rows per page" select) with **10, 25, 50, 100**. A list may offer its own set (for example Data Tables,
  scheduled tasks and the Code Intel search offer smaller or larger sets), and the current size is always kept in the
  options.
- **Navigation**: first, previous, "Page 2 of 6", next, last. With many pages (more than five) a **Jump to page**
  number field with a **Go** button appears.
- Buttons and the size select are disabled while a page is being fetched.
- The pager renders nothing for an empty list, and the navigation is hidden when everything fits on one page.

## Client mode

The list was fetched whole and is sliced in the browser. The hook `usePagination(items)` (default page size 10) holds
the page and size, snaps back into range when the list shrinks (a search narrows it, a row is deleted), and when you
change the page size it keeps the first visible row on screen instead of jumping to page 1. Most lists that the API
returns in full use this mode, for example the agents index, workflows, API keys, SCIM tokens, retention rules, the
Cost & ROI agents table and price list.

## Server mode

The API pages for you. The page keeps `page` and `pageSize` in state and requests
`offset = (page - 1) x pageSize`, `limit = pageSize`. The response is still a plain JSON array; the truth about the
rest of the list is in two headers:

- `X-Total-Count`: how many rows match (when the server can count cheaply). With it the pager shows "of N" and the
  last page.
- `X-Has-More`: whether older rows exist.

If the total is unknown the range reads "11-20 (more available)" and the navigation shows only "Page N" with next and
previous. A short first page already is the whole list, so the server only runs a count query when it can change the
answer. When the headers are missing (an older core) a full page is taken to mean "there may be more".

Server mode is used today by the audit log, the live activity feed, notifications, the Quick Task history, contacts,
knowledge-base documents, workflow runs, the Jobs list, scheduled-task runs, Issues, payment transactions, the wallet
ledger and the platform operator's users and AI content reports. Where a list was moved to server paging recently,
the page detects an older core that ignores `offset` (no `X-Total-Count`), loads the complete list once up to a fixed
number of rows and pages it in the browser, so paging still works but there is no true total. The newer endpoints are listed with their limits in
[list endpoint paging](../api/LIST-ENDPOINT-PAGING.md).

Behaviours you can rely on:

- A negative `offset` is treated as 0; a huge `offset` returns an empty page (200), never an error.
- On the paged endpoints the order is newest first with the id as a tiebreaker, so consecutive pages never repeat or
  skip rows.
- Calling a list endpoint without `limit` keeps its old behaviour (the complete list, or the old fixed cap), so mobile
  apps, the outreach MCP server and the generated SDK are unaffected.

## The capped notice

Some lists are cut off by the server before the page can see the rest. When the page knows the cap it passes it to the
pager, and once the list reaches that cap (or the total is unknown) a note appears above the bar: **"Showing the
first N. Narrow the filters to see more."** The notice is the console's way of saying "this is not everything" so a
truncated list is never mistaken for a complete one. Endpoints that still silently cap are listed at the bottom of
[list endpoint paging](../api/LIST-ENDPOINT-PAGING.md) (for example the coding tasks list, the newest 20 from Redis).

## Append-style feeds

A few feeds are not numbered pages. `LoadMoreBar` shows "Showing the newest N of TOTAL" with a **Load more** button
under a server-capped list, and renders nothing when everything is loaded. Use the pager for numbered pages.

## Files and mixed sources

The Files page merges six sources. Knowledge documents have a real list endpoint; images, speech and video do not
page, so they are fetched whole and paged in the browser, and a notice tells you when one of those lists hit its cap.
