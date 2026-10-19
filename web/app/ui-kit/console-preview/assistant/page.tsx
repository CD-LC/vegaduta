"use client";

// /ui-kit/console-preview/assistant - the real /assistant route rendered against sample data
// (see ../preview-harness.tsx). The base mock is installed by the harness import; the assistant's
// own answers (the Assistant agent, a streamed turn with tool steps, a stored thread) layer on top.
import ConsolePreview from "../preview-harness";
import { installAssistantMock } from "./assistantMock";
import AssistantPage from "../../../assistant/page";

if (typeof window !== "undefined") installAssistantMock();

export default function Preview() {
  return (
    <ConsolePreview label="/assistant">
      <AssistantPage />
    </ConsolePreview>
  );
}
