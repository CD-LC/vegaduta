"use client";

// /ui-kit/console-preview/quick-task - the real /quick-task route rendered against
// sample data (see ../preview-harness.tsx).
import ConsolePreview from "../preview-harness";
import QuickTaskPage from "../../../quick-task/QuickTaskLegacy";

export default function Preview() {
  return (
    <ConsolePreview label="/quick-task">
      <QuickTaskPage />
    </ConsolePreview>
  );
}
