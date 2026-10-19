import { describe, expect, it } from "vitest";
import en from "../../../messages/en.json";
import { EXPRESSION_FIELDS, NODE_DEFS, nodeDef, nodeDescriptionKey, nodeLabelKey } from "./nodeDefs";
import {
  DATA_TABLE_OPERATIONS,
  buildValuesTemplate,
  dataTableMissing,
  dataTableSubtitle,
  isWriteOperation,
  newFilterRow,
  operationNeedsConfirmAll,
  operationOf,
  operationUsesFilter,
  operationUsesKey,
  operationUsesSortAndLimit,
  operationUsesValues,
} from "./dataTableNode";

describe("operations", () => {
  it("defaults a blank operation to the read-only one and normalises spelling", () => {
    expect(operationOf(undefined)).toBe("get_rows");
    expect(operationOf({ operation: "  " })).toBe("get_rows");
    expect(operationOf({ operation: "Upsert-Row" })).toBe("upsert_row");
  });

  it("mirrors the server's write classification", () => {
    for (const w of ["insert_row", "update_rows", "upsert_row", "delete_rows", "DELETE_ROWS"]) expect(isWriteOperation(w)).toBe(true);
    for (const r of ["get_rows", "count", undefined, ""]) expect(isWriteOperation(r as string | undefined)).toBe(false);
    // an operation nobody can classify is never treated as a harmless read
    expect(isWriteOperation("truncate")).toBe(true);
  });

  it("knows which fields each operation uses", () => {
    expect(operationUsesFilter("get_rows")).toBe(true);
    expect(operationUsesFilter("insert_row")).toBe(false);
    expect(operationUsesFilter("upsert_row")).toBe(false);
    expect(operationUsesValues("update_rows")).toBe(true);
    expect(operationUsesValues("delete_rows")).toBe(false);
    expect(operationUsesKey("upsert_row")).toBe(true);
    expect(operationUsesKey("insert_row")).toBe(false);
    expect(operationUsesSortAndLimit("get_rows")).toBe(true);
    expect(operationUsesSortAndLimit("count")).toBe(false);
    expect(operationNeedsConfirmAll("delete_rows")).toBe(true);
    expect(operationNeedsConfirmAll("update_rows")).toBe(true);
    expect(operationNeedsConfirmAll("get_rows")).toBe(false);
  });

  it("lists exactly the six server operations", () => {
    expect([...DATA_TABLE_OPERATIONS]).toEqual(["get_rows", "insert_row", "update_rows", "upsert_row", "delete_rows", "count"]);
  });
});

describe("validity", () => {
  it("needs a table, and a key column for upsert", () => {
    expect(dataTableMissing(undefined)).toBe("table");
    expect(dataTableMissing({ table: "  " })).toBe("table");
    expect(dataTableMissing({ table: "orders" })).toBeNull();
    expect(dataTableMissing({ table: "orders", operation: "upsert_row" })).toBe("key");
    expect(dataTableMissing({ table: "orders", operation: "upsert_row", keyColumn: "email" })).toBeNull();
  });
});

describe("templates and rows", () => {
  it("maps every column to its own expression", () => {
    const text = buildValuesTemplate([
      { name: "email", type: "text" },
      { name: "qty", type: "number" },
    ]);
    expect(JSON.parse(text)).toEqual({ email: "{{email}}", qty: "{{qty}}" });
    expect(buildValuesTemplate([])).toBe("");
  });

  it("starts a new filter row on the first column", () => {
    expect(newFilterRow([{ name: "email", type: "text" }])).toEqual({ column: "email", op: "eq", value: "" });
    expect(newFilterRow([])).toEqual({ column: "", op: "eq", value: "" });
  });
});

describe("canvas subtitle", () => {
  it("nudges when no table is chosen, else names the operation and table", () => {
    expect(dataTableSubtitle(undefined)).toEqual({ key: "dataTableNoTable" });
    expect(dataTableSubtitle({ table: "orders" })).toEqual({ key: "dataTable_get_rows", values: { table: "orders" } });
    expect(dataTableSubtitle({ table: "orders", operation: "delete_rows" })).toEqual({ key: "dataTable_delete_rows", values: { table: "orders" } });
    expect(dataTableSubtitle({ table: "orders", operation: "nonsense" })).toEqual({ key: "dataTable_get_rows", values: { table: "orders" } });
  });
});

describe("registration", () => {
  it("is on the palette as a supported node with a default config", () => {
    const def = NODE_DEFS.find((d) => d.type === "data_table");
    expect(def).toBeDefined();
    expect(def!.supported).toBe(true);
    expect(nodeDef("data_table").type).toBe("data_table");
    expect(def!.defaultConfig).toMatchObject({ operation: "get_rows" });
  });

  it("lists the expression fields the server resolves, in the same order", () => {
    // core ExpressionFields.java is pinned to this list by ExpressionFieldsWebParityTest
    expect(EXPRESSION_FIELDS.data_table).toEqual(["table", "keyColumn", "valuesJson", "filter[].value", "sortColumn", "limit"]);
  });

  it("has every i18n key the node reads", () => {
    const nodeDefs = (en as any).nodeDefs;
    expect(nodeDefs[nodeLabelKey("data_table")]).toBeTruthy();
    expect(nodeDefs[nodeDescriptionKey("data_table")]).toBeTruthy();
    const workflowNode = (en as any).workflowNode;
    expect(workflowNode.dataTableNoTable).toBeTruthy();
    for (const op of DATA_TABLE_OPERATIONS) {
      expect(workflowNode[`dataTable_${op}`], op).toContain("{table}");
      expect((en as any).dataTables[`nodeOp_${op}`], op).toBeTruthy();
    }
    expect((en as any).nav.railDataTables).toBeTruthy();
  });
});
