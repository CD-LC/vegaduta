import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import en from "../../../messages/en.json";

vi.mock("../../lib/i18n", () => {
  const cache: Record<string, (k: string) => string> = {};
  return {
    useTranslations: (ns: string) =>
      (cache[ns] ??= (key) => ((en as unknown as Record<string, Record<string, string>>)[ns] ?? {})[key] ?? key),
  };
});
vi.mock("next/link", () => ({
  default: ({ href, children, ...rest }: { href: string; children: React.ReactNode }) => (
    <a href={href} {...rest}>
      {children}
    </a>
  ),
}));
vi.mock("../MediaCard", () => ({ default: ({ text }: { text: string }): React.ReactElement => <div>{text}</div> }));
vi.mock("./WorkedForBlock", () => ({ default: (): null => null }));

import TaskCard, { type TaskCardHandlers } from "./TaskCard";
import type { AssistantTask, AssistantTurn } from "./assistantTypes";

const turn = { id: "a1", role: "assistant", content: "", at: 0 } as AssistantTurn;
const task = (patch: Partial<AssistantTask> = {}): AssistantTask => ({
  message: "text me the weather",
  agentId: null,
  channel: "LOG",
  status: "confirm",
  preview: null,
  needsPhone: true,
  ...patch,
});
const handlers = (over: Partial<TaskCardHandlers> = {}): TaskCardHandlers => ({
  onConfirm: vi.fn(),
  onCancel: vi.fn(),
  onChannel: vi.fn(),
  onSavePhone: vi.fn().mockResolvedValue(undefined),
  ...over,
});

describe("TaskCard phone number prompt", () => {
  it("does not link to /account (no such route): it asks for the number inline", () => {
    const { container } = render(<TaskCard turn={turn} task={task()} busy={false} handlers={handlers()} />);
    expect(container.querySelector('a[href="/account"]')).toBeNull();
    expect(screen.getByLabelText(en.assistant.taskPhoneLabel)).toBeTruthy();
  });

  it("saves the typed number through onSavePhone", async () => {
    const h = handlers();
    render(<TaskCard turn={turn} task={task()} busy={false} handlers={h} />);
    fireEvent.change(screen.getByLabelText(en.assistant.taskPhoneLabel), { target: { value: " +14155552671 " } });
    fireEvent.click(screen.getByRole("button", { name: en.assistant.taskAddPhone }));
    await waitFor(() => expect(h.onSavePhone).toHaveBeenCalledWith("+14155552671"));
  });

  it("shows why saving failed and keeps the form", async () => {
    const h = handlers({ onSavePhone: vi.fn().mockRejectedValue(new Error("Phone number is invalid")) });
    render(<TaskCard turn={turn} task={task()} busy={false} handlers={h} />);
    fireEvent.change(screen.getByLabelText(en.assistant.taskPhoneLabel), { target: { value: "nope" } });
    fireEvent.click(screen.getByRole("button", { name: en.assistant.taskAddPhone }));
    expect((await screen.findByRole("alert")).textContent).toContain("Phone number is invalid");
    expect(screen.getByLabelText(en.assistant.taskPhoneLabel)).toBeTruthy();
  });

  it("shows no prompt once a number is on file", () => {
    render(<TaskCard turn={turn} task={task({ needsPhone: false })} busy={false} handlers={handlers()} />);
    expect(screen.queryByLabelText(en.assistant.taskPhoneLabel)).toBeNull();
  });
});
