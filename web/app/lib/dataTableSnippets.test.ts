import { describe, expect, it } from "vitest";
import { buildSnippets, isoToday, keyColumnOf, sampleValue } from "./dataTableSnippets";

const table = {
  name: "Leads",
  columns: [
    { name: "meta", type: "json" as const },
    { name: "email", type: "text" as const },
    { name: "deal", type: "number" as const },
    { name: "follow_up", type: "date" as const },
  ],
};

const byId = (id: string) => JSON.parse(buildSnippets(table, "2026-10-07").find((s) => s.id === id)!.text);

describe("buildSnippets", () => {
  it("is empty for a table with no columns", () => {
    expect(buildSnippets({ name: "x", columns: [] }, "2026-10-07")).toEqual([]);
  });

  it("builds the workflow read step with the real table, a date sort and string limit", () => {
    expect(byId("workflowRead")).toEqual({
      table: "Leads",
      operation: "get_rows",
      filter: [{ column: "email", op: "eq", value: "example" }],
      sortColumn: "follow_up",
      sortDirection: "desc",
      limit: "20",
    });
  });

  it("keys the upsert on the first non-json column and templates the values as a JSON string", () => {
    const s = byId("workflowUpsert");
    expect(s).toMatchObject({ table: "Leads", operation: "upsert_row", keyColumn: "email" });
    expect(JSON.parse(s.valuesJson)).toEqual({ meta: "{{meta}}", email: "{{email}}", deal: "{{deal}}", follow_up: "{{follow_up}}" });
  });

  it("builds the agent tool arguments with the exact action names", () => {
    expect(byId("agentRead")).toMatchObject({ action: "get_rows", table: "Leads", limit: 20 });
    expect(byId("agentWrite")).toMatchObject({ action: "insert_row", table: "Leads", values: { meta: { note: "example" }, email: "example", deal: 42, follow_up: "2026-10-07" } });
    expect(byId("agentUpsert")).toMatchObject({ action: "upsert_row", keyColumn: "email" });
  });

  it("uses a range filter for number keys", () => {
    const t = { name: "T", columns: [{ name: "n", type: "number" as const }] };
    expect(JSON.parse(buildSnippets(t, "2026-10-07")[0].text).filter).toEqual([{ column: "n", op: "gte", value: 1 }]);
  });
});

describe("helpers", () => {
  it("picks sample values per type", () => {
    expect(sampleValue("boolean", "d")).toBe(true);
    expect(sampleValue("date", "2026-01-01")).toBe("2026-01-01");
  });

  it("chooses the key column", () => {
    expect(keyColumnOf(table.columns)?.name).toBe("email");
    expect(keyColumnOf([{ name: "j", type: "json" }])?.name).toBe("j");
    expect(keyColumnOf([])).toBeUndefined();
  });

  it("formats the local date", () => {
    expect(isoToday(new Date(2026, 0, 5))).toBe("2026-01-05");
  });
});
