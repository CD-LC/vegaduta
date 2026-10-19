// Starter Data Table templates for the gallery on /data-tables. A template is a name, a column list and a few
// optional sample rows; creating one is POST /api/data-tables (columns) followed by POST .../rows/bulk (samples).
// Column names are identifiers (they appear in workflow expressions and agent tool calls), so they are not
// translated; the template's title and description live in messages/en.json as tpl_<id>_title / tpl_<id>_desc.
// Pure (dataTableTemplates.test.ts).

import { isValidColumnName, type DataTableColumnType } from "./dataTables";

export type TemplateId = "crm" | "expenses" | "content" | "notebook" | "ledger" | "bugs";

export interface TableTemplate {
  id: TemplateId;
  /** Default table name (editable before creating). */
  name: string;
  columns: { name: string; type: DataTableColumnType }[];
  /** Sample rows for a given "today" (ISO date), so dates are always recent. */
  samples: (today: string) => Record<string, unknown>[];
}

/** `today` shifted by whole days, as yyyy-MM-dd (UTC arithmetic: a date-only value has no zone). */
export function shiftDate(today: string, days: number): string {
  const [y, m, d] = today.split("-").map(Number);
  const t = new Date(Date.UTC(y, m - 1, d + days));
  return t.toISOString().slice(0, 10);
}

export const TABLE_TEMPLATES: readonly TableTemplate[] = [
  {
    id: "crm",
    name: "Leads",
    columns: [
      { name: "name", type: "text" },
      { name: "email", type: "text" },
      { name: "company", type: "text" },
      { name: "status", type: "text" },
      { name: "deal_value", type: "number" },
      { name: "next_follow_up", type: "date" },
      { name: "notes", type: "text" },
    ],
    samples: (today) => [
      { name: "Asha Rao", email: "asha@example.com", company: "Northwind", status: "new", deal_value: 1200, next_follow_up: shiftDate(today, 2) },
      { name: "Marco Silva", email: "marco@example.com", company: "Globex", status: "contacted", deal_value: 4800, next_follow_up: shiftDate(today, 5), notes: "Wants a demo" },
      { name: "Lena Fischer", email: "lena@example.com", company: "Initech", status: "won", deal_value: 9500, next_follow_up: shiftDate(today, 30) },
    ],
  },
  {
    id: "expenses",
    name: "Expenses",
    columns: [
      { name: "spent_on", type: "date" },
      { name: "merchant", type: "text" },
      { name: "category", type: "text" },
      { name: "amount", type: "number" },
      { name: "reimbursable", type: "boolean" },
      { name: "receipt_url", type: "text" },
    ],
    samples: (today) => [
      { spent_on: shiftDate(today, -3), merchant: "Cafe Nero", category: "meals", amount: 14.5, reimbursable: false },
      { spent_on: shiftDate(today, -2), merchant: "Uber", category: "travel", amount: 32, reimbursable: true },
      { spent_on: shiftDate(today, -1), merchant: "Notion", category: "software", amount: 10, reimbursable: false },
    ],
  },
  {
    id: "content",
    name: "Content calendar",
    columns: [
      { name: "title", type: "text" },
      { name: "channel", type: "text" },
      { name: "publish_on", type: "date" },
      { name: "status", type: "text" },
      { name: "owner", type: "text" },
      { name: "link", type: "text" },
    ],
    samples: (today) => [
      { title: "Launch announcement", channel: "blog", publish_on: shiftDate(today, 3), status: "draft", owner: "Priya" },
      { title: "Behind the scenes", channel: "newsletter", publish_on: shiftDate(today, 7), status: "idea", owner: "Sam" },
    ],
  },
  {
    id: "notebook",
    name: "Agent notebook",
    columns: [
      { name: "key", type: "text" },
      { name: "value", type: "text" },
      { name: "source", type: "text" },
      { name: "updated_on", type: "date" },
    ],
    samples: (today) => [{ key: "preferred_language", value: "English", source: "onboarding chat", updated_on: today }],
  },
  {
    id: "ledger",
    name: "Processed items",
    columns: [
      { name: "external_id", type: "text" },
      { name: "outcome", type: "text" },
      { name: "processed_on", type: "date" },
      { name: "detail", type: "json" },
    ],
    samples: () => [],
  },
  {
    id: "bugs",
    name: "Bug tracker",
    columns: [
      { name: "title", type: "text" },
      { name: "severity", type: "text" },
      { name: "status", type: "text" },
      { name: "assignee", type: "text" },
      { name: "reported_on", type: "date" },
      { name: "resolved", type: "boolean" },
    ],
    samples: (today) => [
      { title: "Login button unresponsive on mobile", severity: "high", status: "open", assignee: "Dev", reported_on: shiftDate(today, -4), resolved: false },
      { title: "Typo on pricing page", severity: "low", status: "closed", assignee: "Sam", reported_on: shiftDate(today, -9), resolved: true },
    ],
  },
];

/** True when every column name in every template satisfies the server's grammar (guards the template data itself). */
export function templatesAreValid(): boolean {
  return TABLE_TEMPLATES.every((t) => t.columns.length > 0 && t.columns.every((c) => isValidColumnName(c.name)));
}
