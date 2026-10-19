import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => {
  const cache: Record<string, (k: string, v?: Record<string, unknown>) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key, values) => {
        const raw = ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key;
        return Object.entries(values ?? {}).reduce((s, [k, v]) => s.replace(`{${k}}`, String(v)), raw);
      }),
  };
});
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn() }) }));

const listNotificationsPage = vi.fn();
vi.mock("../../lib/api/notificationsPaged", () => ({
  listNotificationsPage: (...a: unknown[]) => listNotificationsPage(...a),
}));
const markNotificationRead = vi.fn().mockResolvedValue(undefined);
const markAllNotificationsRead = vi.fn().mockResolvedValue(undefined);
vi.mock("../../lib/api", () => ({
  api: {
    markNotificationRead: (...a: unknown[]) => markNotificationRead(...a),
    markAllNotificationsRead: (...a: unknown[]) => markAllNotificationsRead(...a),
  },
}));

import NotificationsList from "./NotificationsList";

const row = (id: string, read = false) => ({
  id,
  title: "Title " + id,
  body: "Body",
  category: "SYSTEM",
  severity: "INFO",
  read,
  link: null as string | null,
  createdAt: new Date().toISOString(),
});

describe("NotificationsList", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    listNotificationsPage.mockResolvedValue({ rows: [row("1"), row("2", true)], total: 60, hasMore: true });
  });

  it("loads the first server page and shows the total", async () => {
    render(<NotificationsList />);
    await screen.findByText("Title 1");
    expect(listNotificationsPage).toHaveBeenCalledWith({ unreadOnly: false, limit: 25, offset: 0 });
    expect(screen.getByText(/60/)).toBeInTheDocument();
  });

  it("requests unread only from offset 0 when the Unread filter is chosen", async () => {
    render(<NotificationsList />);
    await screen.findByText("Title 1");
    fireEvent.click(screen.getByRole("button", { name: "Unread" }));
    await waitFor(() =>
      expect(listNotificationsPage).toHaveBeenLastCalledWith({ unreadOnly: true, limit: 25, offset: 0 })
    );
  });

  it("marks a row read when opened", async () => {
    render(<NotificationsList />);
    fireEvent.click(await screen.findByText("Title 1"));
    await waitFor(() => expect(markNotificationRead).toHaveBeenCalledWith("1"));
  });

  it("shows an error with Retry when the load fails", async () => {
    listNotificationsPage.mockRejectedValueOnce(new Error("boom"));
    render(<NotificationsList />);
    const retry = await screen.findByRole("button", { name: "Retry" });
    fireEvent.click(retry);
    await screen.findByText("Title 1");
  });

  it("shows the empty state", async () => {
    listNotificationsPage.mockResolvedValue({ rows: [], total: 0, hasMore: false });
    render(<NotificationsList />);
    await screen.findByText("You're all caught up");
  });
});
