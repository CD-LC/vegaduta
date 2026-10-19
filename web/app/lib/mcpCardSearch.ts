// The text the Tools/Servers search box matches against, for one card in McpPanel's grid.
//
// McpPanel's cards are not all alike: built-in tools and Google tools carry an i18n key, MCP servers and catalog
// entries carry a plain name, and the placeholder "Connect Google" card (shown while no Google account is
// connected) carries NO data at all. The filter used to read `card.data.name` for everything it did not
// recognise, so with Google unconnected the first typed character threw "Cannot read properties of null" and the
// whole page fell over. This never throws: an unnamed card simply matches nothing.
export interface SearchableCard {
  kind: string;
  data?: { name?: unknown; nameKey?: unknown } | null;
}

type Translate = (key: string) => string;

export function cardSearchName(card: SearchableCard, t: Translate): string {
  const data = card.data ?? null;
  let name: unknown;
  switch (card.kind) {
    case "builtin":
      name = data?.nameKey ? t(String(data.nameKey)) : data?.name;
      break;
    case "google":
      name = data?.nameKey ? t(String(data.nameKey)) : t("googleWorkspace");
      break;
    case "google-connect":
      name = t("googleWorkspace");
      break;
    default:
      name = data?.name;
  }
  return typeof name === "string" ? name : "";
}
