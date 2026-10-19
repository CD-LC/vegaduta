import { apiFetch, toJson, toJsonQuietOn, jsonBody } from "./core";
import type {
  DataTableColumnType,
  DataTableImportResult,
  DataTableRow,
  DataTableRowPage,
  DataTableSummary,
  DataTablesConfig,
} from "../dataTables";

// Data Tables (core com.agenticai.core.tables, /api/data-tables/**). Off by default on the server
// (app.data-tables.enabled): while off every route except /config answers 404, so the page reads
// getDataTablesConfig() first and never calls the rest.

const enc = encodeURIComponent;

export const dataTablesApi = {
  // 404 = a core that predates the feature: an expected state, not a failure worth a toast.
  getDataTablesConfig: (): Promise<DataTablesConfig> => apiFetch(`/api/data-tables/config`).then(toJsonQuietOn([404])),
  listDataTables: (): Promise<DataTableSummary[]> => apiFetch(`/api/data-tables`).then(toJson<DataTableSummary[]>),
  getDataTable: (id: string): Promise<DataTableSummary> => apiFetch(`/api/data-tables/${enc(id)}`).then(toJson<DataTableSummary>),
  createDataTable: (payload: {
    name: string;
    description?: string;
    columns: { name: string; type: DataTableColumnType }[];
  }): Promise<DataTableSummary> => apiFetch(`/api/data-tables`, { method: "POST", ...jsonBody(payload) }).then(toJson<DataTableSummary>),
  createDataTableFromCsv: (name: string, file: File): Promise<DataTableImportResult> => {
    const formData = new FormData();
    formData.append("name", name);
    formData.append("file", file);
    return apiFetch(`/api/data-tables/import`, { method: "POST", body: formData }).then(toJson<DataTableImportResult>);
  },
  updateDataTable: (id: string, payload: { name?: string; description?: string }): Promise<DataTableSummary> =>
    apiFetch(`/api/data-tables/${enc(id)}`, { method: "PUT", ...jsonBody(payload) }).then(toJson<DataTableSummary>),
  deleteDataTable: (id: string) =>
    apiFetch(`/api/data-tables/${enc(id)}`, { method: "DELETE" }).then((res) => (res.ok ? null : toJson(res))),
  addDataTableColumn: (id: string, name: string, type: DataTableColumnType): Promise<DataTableSummary> =>
    apiFetch(`/api/data-tables/${enc(id)}/columns`, { method: "POST", ...jsonBody({ name, type }) }).then(toJson<DataTableSummary>),
  alterDataTableColumn: (id: string, column: string, patch: { newName?: string; type?: DataTableColumnType }): Promise<DataTableSummary> =>
    apiFetch(`/api/data-tables/${enc(id)}/columns/${enc(column)}`, { method: "PUT", ...jsonBody(patch) }).then(toJson<DataTableSummary>),
  dropDataTableColumn: (id: string, column: string): Promise<DataTableSummary> =>
    apiFetch(`/api/data-tables/${enc(id)}/columns/${enc(column)}`, { method: "DELETE" }).then(toJson<DataTableSummary>),
  /** `query` is the string from buildRowsQuery (filter / sort / limit / offset), without the leading "?". */
  listDataTableRows: (id: string, query = ""): Promise<DataTableRowPage> =>
    apiFetch(`/api/data-tables/${enc(id)}/rows${query ? `?${query}` : ""}`).then(toJson<DataTableRowPage>),
  insertDataTableRow: (id: string, values: Record<string, unknown>): Promise<DataTableRow> =>
    apiFetch(`/api/data-tables/${enc(id)}/rows`, { method: "POST", ...jsonBody({ values }) }).then(toJson<DataTableRow>),
  /** POST .../rows/bulk: up to DataTablesConfig.maxBulkInsert rows per call; all-or-nothing on the server. */
  insertDataTableRows: (id: string, rows: Record<string, unknown>[]): Promise<DataTableRow[]> =>
    apiFetch(`/api/data-tables/${enc(id)}/rows/bulk`, { method: "POST", ...jsonBody({ rows }) }).then(toJson<DataTableRow[]>),
  updateDataTableRow: (id: string, rowId: string, values: Record<string, unknown>): Promise<DataTableRow> =>
    apiFetch(`/api/data-tables/${enc(id)}/rows/${enc(rowId)}`, { method: "PUT", ...jsonBody({ values }) }).then(toJson<DataTableRow>),
  deleteDataTableRow: (id: string, rowId: string) =>
    apiFetch(`/api/data-tables/${enc(id)}/rows/${enc(rowId)}`, { method: "DELETE" }).then((res) => (res.ok ? null : toJson(res))),
  importDataTableCsv: (id: string, file: File): Promise<DataTableImportResult> => {
    const formData = new FormData();
    formData.append("file", file);
    return apiFetch(`/api/data-tables/${enc(id)}/import`, { method: "POST", body: formData }).then(toJson<DataTableImportResult>);
  },
  exportDataTableCsv: async (id: string): Promise<Blob> => {
    const res = await apiFetch(`/api/data-tables/${enc(id)}/export.csv`);
    if (!res.ok) await toJson(res);
    return res.blob();
  },
};
