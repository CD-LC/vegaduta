# Notifications

| | |
|---|---|
| Entry point | the bell in the top bar, then **View all notifications** |
| Route | `/notifications` (eyebrow "Account", title "Notifications") |
| Nav needs | none: it has no sidebar row |
| Backend rule | any signed-in tenant member; there is no permission check. Visibility is narrowed in the query: a member sees their own notifications, and anyone holding an administrative scope also sees broadcast notifications |

"Everything the platform and your agents have flagged for you."

## The bell

The bell sits in the top bar of the console. It polls `GET /api/notifications/unread-count` every 30 seconds while the
tab is visible and shows the count in its accessible name ("Notifications, N unread"). Opening it shows the latest few
notifications in a popover, with an empty state ("You're all caught up.") and a load-failure state. The popover ends
in **View all notifications**, which opens `/notifications`. The bell is not rendered when there is no signed-in
person behind the shell (for example on public pages), so it never fires calls that would only return 401.

## The history page

A list of every notification, newest first.

- **All / Unread** filter buttons (pressed state shown; switching resets to page 1).
- **Refresh** and **Mark all read** (disabled when the page has no unread item).
- Each row shows a severity dot (the severity is also announced to screen readers as Error, Warning or Info), the
  title (bold while unread), an **Agent** chip for notifications in the agent category, an **Unread** badge, the time
  as "time ago", and up to two lines of body text. Selecting a row marks it read and, when the notification carries a
  link, navigates to it.
- Empty states: "You're all caught up" for All, and "No unread notifications" with a hint to switch to All for Unread.
- A failed load shows an alert with **Retry**.
- The footer is the shared pager in **server mode**: the range reads "11-20 of N notifications", with a page-size
  selector (default 25; 10, 25, 50, 100). If you mark rows read in the Unread view and walk past the end, the page
  steps back by one. See [Lists and paging](lists-and-paging.md).

## What generates notifications

The notification categories in core are system, tool failure, billing, safety, workflow and agent. Examples that exist
in the code: an ROI budget alert at 80% and 100% of the monthly cap (category Billing, see
[Cost and ROI](cost-and-roi.md)), a mention of you in a workflow comment (see [Build](build.md)), and an hourly
"reviews due" notice from the personal tutor when it is enabled.

## API

| Call | Purpose |
|---|---|
| `GET /api/notifications?unreadOnly=&limit=&offset=` | One page. `limit` 1..200 (default 50), `offset` skips the newest rows; `X-Total-Count` counts the visible notifications that match `unreadOnly`; `X-Has-More` is also sent. A platform-operator token that has no tenant gets an empty page with `X-Total-Count: 0`. |
| `GET /api/notifications/unread-count` | The bell badge. |
| `POST /api/notifications/{id}/read` | Mark one read. |
| `POST /api/notifications/read-all` | Mark all read. |

The paging contract is in [list endpoint paging](../api/LIST-ENDPOINT-PAGING.md).
