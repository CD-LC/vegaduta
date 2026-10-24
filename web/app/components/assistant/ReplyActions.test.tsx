import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => ({
  useTranslations: (ns: string) => (key: string) =>
    ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key,
}));
vi.mock("./speakText", () => ({ isSpeaking: () => false, speakText: vi.fn(), stopSpeaking: vi.fn() }));

import ReplyActions from "./ReplyActions";
import SourceChips from "./SourceChips";

afterEach(() => cleanup());

describe("ReplyActions", () => {
  it("copies the reply and says so, and offers Regenerate only when given", () => {
    const writeText = vi.fn(async () => undefined);
    Object.assign(navigator, { clipboard: { writeText } });
    const { rerender } = render(<ReplyActions text="The answer." />);
    expect(screen.queryByRole("button", { name: "Regenerate" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Copy" }));
    expect(writeText).toHaveBeenCalledWith("The answer.");
    expect(screen.getByRole("status")).toHaveTextContent("Copied");

    const onRegenerate = vi.fn();
    rerender(<ReplyActions text="The answer." onRegenerate={onRegenerate} />);
    fireEvent.click(screen.getByRole("button", { name: "Regenerate" }));
    expect(onRegenerate).toHaveBeenCalledTimes(1);
  });
});

describe("SourceChips", () => {
  it("lists each source as a numbered link that opens in a new tab", () => {
    render(
      <SourceChips
        sources={[
          { url: "https://a.example.com/x", site: "a.example.com", title: "A page" },
          { url: "https://b.example.org/", site: "b.example.org" },
        ]}
      />
    );
    const nav = screen.getByRole("navigation", { name: "Sources" });
    const links = nav.querySelectorAll("a");
    expect(links).toHaveLength(2);
    expect(links[0]).toHaveAttribute("href", "https://a.example.com/x");
    expect(links[0]).toHaveAttribute("target", "_blank");
    expect(links[0]).toHaveAttribute("rel", "noopener noreferrer");
    expect(links[0]).toHaveTextContent("1a.example.com · A page");
  });

  it("renders nothing without sources", () => {
    const { container } = render(<SourceChips sources={[]} />);
    expect(container.firstChild).toBeNull();
  });
});
