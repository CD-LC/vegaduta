// Number / date formatters for the demo sandbox console (page.tsx).

type Nullable<T> = T | null | undefined;

export function fmtInt(value: Nullable<number>) {
  return Number(value ?? 0).toLocaleString();
}

// The zero-denominator case is the whole reason this helper exists: 0/0 is not
// 0%, and a dashboard that says "0% conversion" on a platform with no sandboxes
// yet is telling the owner something false.
export function fmtRate(rate: Nullable<number>) {
  if (rate === null || rate === undefined) return "n/a";
  return `${(rate * 100).toFixed(1)}%`;
}

export function fmtAvg(value: Nullable<number>) {
  if (value === null || value === undefined) return "n/a";
  return value.toFixed(1);
}

export function fmtDateTime(iso: Nullable<string>) {
  if (!iso) return "—";
  return new Date(iso).toLocaleString();
}
