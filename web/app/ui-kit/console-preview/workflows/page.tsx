"use client";

// /ui-kit/console-preview/workflows - the real /workflows route rendered against
// sample data (see ../preview-harness.tsx).
import ConsolePreview from "../preview-harness";
import WorkflowsPage from "../../../workflows/page";

export default function Preview() {
  return (
    <ConsolePreview label="/workflows">
      <WorkflowsPage />
    </ConsolePreview>
  );
}
