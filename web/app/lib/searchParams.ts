/** The `searchParams` a Next page receives (a Promise in this Next version; see node_modules/next/dist/docs). */
export type SearchParams = Record<string, string | string[] | undefined>;

/** The first value of a query parameter, or null when absent or blank. */
export function firstParam(value: string | string[] | undefined): string | null {
  const first = Array.isArray(value) ? value[0] : value;
  return first && first.trim() ? first : null;
}
