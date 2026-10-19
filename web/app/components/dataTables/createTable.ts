import { api } from "../../lib/api";
import { chunk } from "../../lib/dataTableCsv";
import type { DataTableColumnType, DataTableSummary } from "../../lib/dataTables";

export interface CreateWithRowsResult {
  table: DataTableSummary;
  inserted: number;
  /** True when a bulk insert was refused part-way: the table exists with the rows that went in before it. */
  incomplete: boolean;
}

/**
 * Creates a table (POST /api/data-tables) and then loads rows into it in bulk batches (POST .../rows/bulk, which is
 * all-or-nothing per call). A refused batch has already been reported by the API layer's toast, so this only stops
 * and says how far it got; the table is kept (deleting it would throw away rows that did go in).
 * Throws only when the table itself could not be created.
 */
export async function createTableWithRows(args: {
  name: string;
  description?: string;
  columns: { name: string; type: DataTableColumnType }[];
  rows: Record<string, unknown>[];
  batchSize: number;
}): Promise<CreateWithRowsResult> {
  const created = await api.createDataTable({ name: args.name, description: args.description, columns: args.columns });
  let inserted = 0;
  let incomplete = false;
  for (const batch of chunk(args.rows, args.batchSize)) {
    try {
      const saved = await api.insertDataTableRows(created.id, batch);
      inserted += Array.isArray(saved) ? saved.length : batch.length;
    } catch {
      incomplete = true;
      break;
    }
  }
  if (args.rows.length === 0) return { table: created, inserted: 0, incomplete: false };
  try {
    return { table: await api.getDataTable(created.id), inserted, incomplete };
  } catch {
    return { table: { ...created, rowCount: inserted }, inserted, incomplete };
  }
}
