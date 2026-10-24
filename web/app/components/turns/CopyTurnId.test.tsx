import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";

vi.mock("../../lib/i18n", async () => {
  const en = (await import("../../../messages/en.json")).default as unknown as Record<string, Record<string, string>>;
  return { useTranslations: (ns: string) => (key: string) => en[ns]?.[key] ?? `${ns}.${key}` };
});

import CopyTurnId from "./CopyTurnId";

const ID = "22222222-2222-4222-8222-222222222222";

describe("CopyTurnId", () => {
  afterEach(() => vi.restoreAllMocks());

  it("copies the id and confirms it in a live region", async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, "clipboard", { value: { writeText }, configurable: true });
    render(<CopyTurnId turnId={ID} />);
    expect(screen.getByText(ID)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Copy turn ID" }));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Turn ID copied."));
    expect(writeText).toHaveBeenCalledWith(ID);
  });

  it("tells the user to copy by hand when the clipboard refuses", async () => {
    Object.defineProperty(navigator, "clipboard", {
      value: { writeText: vi.fn().mockRejectedValue(new Error("denied")) },
      configurable: true,
    });
    render(<CopyTurnId turnId={ID} />);
    fireEvent.click(screen.getByRole("button", { name: "Copy turn ID" }));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(/Select the ID and copy it by hand/));
    expect(screen.getByText(ID)).toBeInTheDocument();
  });
});
