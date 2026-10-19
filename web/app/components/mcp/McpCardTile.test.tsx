// Regression: the "Connect Google" tile (kind google-connect) has data === null; the pin badge must not read data.id.
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

vi.mock("../../lib/i18n", () => ({
  useTranslations: () => (key: string) => key,
}));

import McpCardTile from "./McpCardTile";

const common = {
  isSelected: false,
  pins: { statuses: {}, failed: {}, loading: false },
  addingKey: null as string | null,
  connectingKey: null as string | null,
  registerRef: (): void => undefined,
  onSelect: (): void => undefined,
  onToggleServer: (): void => undefined,
  onRequestEnable: (): void => undefined,
  onAddFromCatalog: (): void => undefined,
  onConnectOAuth: (): void => undefined,
};

afterEach(cleanup);

describe("McpCardTile", () => {
  it("renders the Connect Google tile (no server data) without throwing", () => {
    const card = { selKey: "google:connect", category: "google", kind: "google-connect", data: null } as never;
    expect(() => render(<McpCardTile card={card} {...common} />)).not.toThrow();
  });

  it("renders a registered MCP server tile and keeps its pin badge", () => {
    const server = { id: "srv-1", name: "Files", enabled: true, status: "CONNECTED", transport: "STREAMABLE_HTTP", url: "https://example.com/mcp" };
    const card = { selKey: "custom:srv-1", category: "custom", kind: "mcp", data: server, catalogEntry: null } as never;
    expect(() => render(<McpCardTile card={card} {...common} pins={{ statuses: { "srv-1": { state: "CHANGED" } }, failed: {}, loading: false }} />)).not.toThrow();
    expect(document.body.textContent).toContain("Files");
  });
});
