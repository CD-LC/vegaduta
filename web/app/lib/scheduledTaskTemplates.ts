// One-click starting points for /scheduled. Picking one only pre-fills the create form (the person still chooses the
// agent, delivery and can edit everything) - nothing is created until they save. `name` and `prompt` become the real
// task's fields, so they are literal English like workflow templates' node labels; the card's title/hint are UI
// chrome and live in the "scheduledTasks" namespace as tpl_<key>_title / tpl_<key>_hint.
import type { ScheduleKind } from "./scheduledTasks";

export interface ScheduledTaskTemplate {
  key: string;
  name: string;
  prompt: string;
  kind: Exclude<ScheduleKind, "custom">;
  time: string;
  /** weekly: MON..SUN; monthly: "1".."28" or "LAST". */
  day?: string;
}

export const SCHEDULED_TASK_TEMPLATES: readonly ScheduledTaskTemplate[] = [
  {
    key: "morning_brief",
    name: "Morning briefing",
    prompt:
      "Give me a morning briefing: today's calendar events, the top unread emails that need a reply, and three " +
      "priorities for the day. Keep it under 200 words.",
    kind: "weekdays",
    time: "08:00",
  },
  {
    key: "inbox_digest",
    name: "End-of-day inbox digest",
    prompt:
      "Summarise today's unanswered emails as a prioritised list (urgent / can wait) and suggest a one-line reply " +
      "for each urgent one. Do not send anything.",
    kind: "weekdays",
    time: "17:30",
  },
  {
    key: "daily_news",
    name: "Industry news digest",
    prompt:
      "Search the web for the most important news in my industry from the last 24 hours. Give me five headlines, " +
      "each with a one-line summary and a link.",
    kind: "daily",
    time: "07:30",
  },
  {
    key: "weekly_review",
    name: "Weekly review",
    prompt:
      "Summarise what was accomplished this week, list what is still open, and suggest the top three priorities for " +
      "next week.",
    kind: "weekly",
    time: "17:00",
    day: "FRI",
  },
  {
    key: "competitor_watch",
    name: "Competitor watch",
    prompt:
      "Check my competitors' websites and recent news for changes in pricing, features or announcements over the " +
      "past week. Summarise what changed and why it matters to us.",
    kind: "weekly",
    time: "09:00",
    day: "MON",
  },
  {
    key: "invoice_reminders",
    name: "Monthly invoice reminders",
    prompt:
      "List customers with unpaid or overdue invoices and draft a short, polite reminder for each. Do not send " +
      "anything - just give me the drafts.",
    kind: "monthly",
    time: "09:00",
    day: "1",
  },
  // Engineering shift. Each prompt tells the agent to say when a number is
  // unavailable rather than estimate, because a scheduled task runs unattended
  // and a confident wrong figure is worse than a gap.
  {
    key: "batch_window_digest",
    name: "Overnight batch window digest",
    prompt:
      "Review last night's batch window using the job logs, scheduler alerts and runbooks you have access to. List " +
      "every job that abended or ran past its SLA with the abend or return code, the likely cause in one line, and " +
      "whether a rerun is still needed before business opens. Flag anything that already posted and must not be " +
      "rerun. If you cannot see a job's status, say so instead of assuming it succeeded.",
    kind: "weekdays",
    time: "06:30",
  },
  {
    key: "dora_weekly",
    name: "Weekly DORA metrics",
    prompt:
      "Compile last week's DORA metrics for our team: deployment frequency, lead time for changes, change failure " +
      "rate and time to restore service. Use the repository, pipeline and incident data you have access to, show how " +
      "each number was derived, and compare it with the week before. Where a number is unavailable, say so rather " +
      "than estimating. End with one concrete improvement to try this week.",
    kind: "weekly",
    time: "09:00",
    day: "MON",
  },
  {
    key: "cve_patch_watch",
    name: "CVE and patch watch",
    prompt:
      "Search for security advisories and CVEs published in the last 24 hours that affect the frameworks, " +
      "databases, base images and SAP or mainframe components listed in my knowledge base. For each relevant one " +
      "give the CVSS score, whether it is known to be exploited, the fixed version, and which of our systems it " +
      "touches. Skip anything that does not apply to our stack, and say when the list is empty.",
    kind: "daily",
    time: "07:00",
  },
];
