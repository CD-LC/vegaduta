// Artifacts pane - a small, total CSV/TSV parser for the table preview.
//
// Output is rendered as React text nodes only (never HTML, never evaluated), so
// a cell such as `=HYPERLINK(...)` is just text in the preview. Download keeps
// the original body byte for byte; a spreadsheet application that opens the
// downloaded file may interpret leading =, +, - or @ cells as formulas
// ("CSV injection") - that is the person's application, not this page.
//
// Caps keep a hostile or huge body from freezing the tab.

export const CSV_MAX_ROWS = 2000;
export const CSV_MAX_COLS = 60;
export const CSV_MAX_CELL_CHARS = 2000;

export interface CsvTable {
  rows: string[][];
  delimiter: "," | "\t" | ";";
  /** True when rows, columns or a cell were cut by the caps. */
  truncated: boolean;
}

/** Comma unless the first line clearly uses tabs or semicolons. */
export function detectDelimiter(text: string): "," | "\t" | ";" {
  const nl = text.search(/[\r\n]/);
  const firstLine = nl < 0 ? text : text.slice(0, nl);
  const count = (c: string) => firstLine.split(c).length - 1;
  const commas = count(",");
  const tabs = count("\t");
  const semis = count(";");
  if (tabs > commas && tabs >= semis) return "\t";
  if (semis > commas) return ";";
  return ",";
}

/** RFC 4180-style: quoted fields, doubled quotes, CRLF/LF/CR line ends. Never throws. */
export function parseCsv(input: unknown): CsvTable {
  const text = typeof input === "string" ? input : "";
  const delimiter = detectDelimiter(text);
  const rows: string[][] = [];
  let truncated = false;
  let row: string[] = [];
  let cell = "";
  let inQuotes = false;
  let cellCut = false;

  const pushCell = () => {
    if (row.length < CSV_MAX_COLS) row.push(cell);
    else truncated = true;
    cell = "";
    cellCut = false;
  };
  const pushRow = () => {
    if (row.length === 0 && cell === "") return; // blank line
    pushCell();
    if (rows.length < CSV_MAX_ROWS) rows.push(row);
    else truncated = true;
    row = [];
  };
  const add = (ch: string) => {
    if (cell.length < CSV_MAX_CELL_CHARS) cell += ch;
    else if (!cellCut) {
      cellCut = true;
      truncated = true;
    }
  };

  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inQuotes) {
      if (ch === '"') {
        if (text[i + 1] === '"') {
          add('"');
          i++;
        } else {
          inQuotes = false;
        }
      } else {
        add(ch);
      }
      continue;
    }
    if (ch === '"' && cell === "") {
      inQuotes = true;
    } else if (ch === delimiter) {
      pushCell();
    } else if (ch === "\n" || ch === "\r") {
      if (ch === "\r" && text[i + 1] === "\n") i++;
      pushRow();
      if (rows.length >= CSV_MAX_ROWS && i < text.length - 1) {
        truncated = true;
        break;
      }
    } else {
      add(ch);
    }
  }
  // Final record without a trailing newline (skip a lone empty trailing cell).
  if (cell !== "" || row.length > 0 || inQuotes) pushRow();
  return { rows, delimiter, truncated };
}
