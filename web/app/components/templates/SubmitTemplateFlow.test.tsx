// The submit-a-template modal goes to the shared gallery's moderation queue; a personal install has
// neither, so the flow mounts nothing and loads nothing even when asked to be open.
import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

const api = vi.hoisted(
  () => new Proxy({}, { get: (_t, prop) => (prop === "then" ? undefined : vi.fn(() => Promise.resolve([]))) })
);
vi.mock("../../lib/api", () => ({ api }));
vi.mock("../../lib/toastBus", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
vi.mock("../../lib/i18n", () => ({ useTranslations: () => (key: string) => key }));

import SubmitTemplateFlow from "./SubmitTemplateFlow";

afterEach(() => {
  cleanup();
  vi.unstubAllEnvs();
});

describe("SubmitTemplateFlow", () => {
  it("renders nothing on a personal install, even when open", () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "personal");
    const { container } = render(<SubmitTemplateFlow open onClose={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("renders the dialog on a shared install when open", () => {
    vi.stubEnv("NEXT_PUBLIC_EDITION", "full");
    const { container } = render(<SubmitTemplateFlow open onClose={vi.fn()} />);
    expect(container.querySelector("[role='dialog']")).not.toBeNull();
  });
});
