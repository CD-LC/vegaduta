// Every t("...") the Data Tables screens read must exist in messages/en.json's "dataTables" namespace: t() returns
// the dotted path for a missing key (it never falls back), so a typo would render "dataTables.xyz" on screen.

import { describe, expect, it } from "vitest";
import { readFileSync } from "node:fs";
import path from "node:path";
import en from "../../../messages/en.json";

const ns = (en as any).dataTables as Record<string, string>;

const FILES = [
  "../../data-tables/page.tsx",
  "./DataTablesPanel.tsx",
  "./DataTableGrid.tsx",
  "./ColumnsPanel.tsx",
  "./DataTableCapacity.tsx",
  "./DataTableExplainer.tsx",
  "./ImportWizard.tsx",
  "./TemplatesGallery.tsx",
  "./TableInsights.tsx",
  "./UseTablePanel.tsx",
  "../workflow/DataTableNodeConfig.tsx",
];

function literalKeys(source: string): string[] {
  const keys = new Set<string>();
  for (const m of source.matchAll(/\bt\(\s*"([A-Za-z0-9_]+)"/g)) keys.add(m[1]);
  return [...keys];
}

describe("dataTables i18n", () => {
  it("has the namespace", () => {
    expect(ns).toBeTruthy();
  });

  for (const file of FILES) {
    it(`defines every literal key used by ${file}`, () => {
      const source = readFileSync(path.resolve(__dirname, file), "utf8");
      const keys = literalKeys(source);
      expect(keys.length).toBeGreaterThan(0);
      const missing = keys.filter((k) => !(k in ns));
      expect(missing).toEqual([]);
    });
  }

  it("defines every dynamically built key", () => {
    const dynamic = [
      ...["text", "number", "boolean", "date", "json"].map((x) => `type_${x}`),
      ...["eq", "ne", "gt", "gte", "lt", "lte", "contains", "starts_with", "in", "is_null", "not_null"].map((x) => `op_${x}`),
      ...["number", "boolean", "date", "json"].map((x) => `cellError_${x}`),
      ...["get_rows", "insert_row", "update_rows", "upsert_row", "delete_rows", "count"].map((x) => `nodeOp_${x}`),
      ...["workflowRead", "workflowUpsert", "agentRead", "agentWrite", "agentUpsert"].map((x) => `useSnippet_${x}`),
      ...["useWorkflowHeading", "useAgentHeading", "useWorkflowHint", "useAgentHint"],
      ...["crm", "expenses", "content", "notebook", "ledger", "bugs"].flatMap((x) => [`tpl_${x}_title`, `tpl_${x}_desc`]),
      // DataTableCapacity reads these through its level -> key map.
      ...["capacityOk", "capacityNear", "capacityFull", "capacityUnknown"],
    ];
    expect(dynamic.filter((k) => !(k in ns))).toEqual([]);
  });

  it("keeps the placeholders the code passes values for", () => {
    expect(ns.usage).toContain("{count}");
    expect(ns.usage).toContain("{max}");
    expect(ns.rowsRange).toContain("{from}");
    expect(ns.rowsRange).toContain("{to}");
    expect(ns.rowsRange).toContain("{total}");
    expect(ns.importDone).toContain("{inserted}");
    expect(ns.importDone).toContain("{skipped}");
    expect(ns.dropColumnTitle).toContain("{name}");
    expect(ns.editCell).toContain("{column}");
    expect(ns.sortBy).toContain("{column}");
    expect(ns.tableLimit).toContain("{max}");
    expect(ns.columnLimit).toContain("{max}");
    expect(ns.importIgnored).toContain("{names}");
    expect(ns.impSummary).toContain("{rows}");
    expect(ns.impProblem).toContain("{message}");
    expect(ns.insightRange).toContain("{mean}");
    expect(ns.useAgentHint).toContain("{query}");
    expect(ns.useIntro).toContain("{name}");
  });

  it("keeps the placeholders of the capacity figures", () => {
    expect(ns.capacityTables).toContain("{count}");
    expect(ns.capacityTables).toContain("{max}");
    expect(ns.capacityTablesNoLimit).toContain("{count}");
    expect(ns.capacityTablesFree).toContain("{count}");
    expect(ns.capacityRowsOk).toContain("{max}");
    for (const slot of ["{near}", "{full}", "{max}"]) expect(ns.capacityRowsFlagged).toContain(slot);
    for (const slot of ["{name}", "{count}", "{max}"]) expect(ns.rowMeterLabel).toContain(slot);
  });

  it("adds no em dash in the new strings", () => {
    for (const [key, value] of Object.entries(ns)) {
      if (/^(capacity|rowMeter|explain)/.test(key)) expect(value, key).not.toMatch(/—/);
    }
  });
});
