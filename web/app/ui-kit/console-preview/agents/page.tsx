"use client";

// /ui-kit/console-preview/agents - the real /agents route rendered against
// sample data (see ../preview-harness.tsx).
import ConsolePreview from "../preview-harness";
import AgentsPage from "../../../agents/page";

export default function Preview() {
  return (
    <ConsolePreview label="/agents">
      <AgentsPage />
    </ConsolePreview>
  );
}
