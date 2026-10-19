import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
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
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));

import AssistantLaunchpad, { dayPart } from "./AssistantLaunchpad";
import AssistantPalette from "./AssistantPalette";

describe("AssistantLaunchpad", () => {
  it("drops a starter prompt into the box", () => {
    const onPrompt = vi.fn();
    render(<AssistantLaunchpad onPrompt={onPrompt} />);
    fireEvent.click(screen.getByRole("button", { name: "Remind me" }));
    expect(onPrompt).toHaveBeenCalledWith(en.assistant.starter_remind_prompt);
  });

  it("greets by the hour", () => {
    expect([dayPart(6), dayPart(12), dayPart(16), dayPart(17), dayPart(23)]).toEqual([
      "morning",
      "afternoon",
      "afternoon",
      "evening",
      "evening",
    ]);
  });
});

describe("AssistantPalette", () => {
  it("filters, runs the highlighted item with Enter and closes", () => {
    const a = vi.fn();
    const b = vi.fn();
    const onClose = vi.fn();
    render(
      <AssistantPalette
        onClose={onClose}
        items={[
          { id: "a", group: "G", label: "New thread", run: a },
          { id: "b", group: "G", label: "Build an app", run: b },
        ]}
      />
    );
    fireEvent.change(screen.getByRole("textbox"), { target: { value: "build" } });
    fireEvent.keyDown(window, { key: "Enter" });
    expect(b).toHaveBeenCalled();
    expect(a).not.toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });
});
