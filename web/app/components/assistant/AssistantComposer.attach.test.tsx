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

import AssistantComposer, { type ComposerAttachments } from "./AssistantComposer";

const attachments = (over: Partial<ComposerAttachments> = {}): ComposerAttachments => ({
  modalities: null,
  searchable: true,
  items: [],
  error: null,
  attaching: false,
  onFiles: vi.fn(),
  onRemove: vi.fn(),
  ...over,
});

function mount(over: { value?: string; sending?: boolean; att?: ComposerAttachments; onCall?: (() => void) | null; onCamera?: (() => void) | null } = {}) {
  const att = over.att ?? attachments();
  render(
    <AssistantComposer
      value={over.value ?? ""}
      onChange={vi.fn()}
      onSend={vi.fn()}
      onStop={vi.fn()}
      sending={over.sending ?? false}
      disabled={false}
      attachments={att}
      snapshot={null}
      reconnecting={null}
      onCamera={over.onCamera === undefined ? vi.fn() : over.onCamera}
      onCall={over.onCall === undefined ? vi.fn() : over.onCall}
    />
  );
  return att;
}

describe("AssistantComposer attach, camera and call", () => {
  it("offers a call button while the box is empty and swaps it for Send once there is text", () => {
    const onCall = vi.fn();
    mount({ onCall });
    fireEvent.click(screen.getByTestId("assistant-call"));
    expect(onCall).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole("button", { name: en.assistant.send })).toBeNull();
  });

  it("shows Send instead of the call button when there is text", () => {
    mount({ value: "hello" });
    expect(screen.queryByTestId("assistant-call")).toBeNull();
    expect(screen.getByRole("button", { name: en.assistant.send })).toBeTruthy();
  });

  it("hides the call button while a turn streams", () => {
    mount({ sending: true });
    expect(screen.queryByTestId("assistant-call")).toBeNull();
  });

  it("attaches a pasted file instead of pasting text", () => {
    const att = mount();
    const file = new File(["x"], "shot.png", { type: "image/png" });
    fireEvent.paste(screen.getByRole("textbox"), { clipboardData: { files: [file], getData: () => "" } });
    expect(att.onFiles).toHaveBeenCalledWith([file]);
  });

  it("leaves a plain-text paste alone", () => {
    const att = mount();
    fireEvent.paste(screen.getByRole("textbox"), { clipboardData: { files: [], getData: () => "hi" } });
    expect(att.onFiles).not.toHaveBeenCalled();
  });

  it("lists upload, camera and call in the + menu", async () => {
    mount();
    fireEvent.click(screen.getByRole("button", { name: en.assistant.attachMenu }));
    expect(await screen.findByText(en.assistant.attachUpload)).toBeTruthy();
    expect(screen.getByText(en.assistant.attachCamera)).toBeTruthy();
    expect(screen.getByText(en.assistant.attachCall)).toBeTruthy();
    expect(screen.getByText(en.assistant.attachUploadSearchable)).toBeTruthy();
  });
});
