"use client";

// /ui-kit/console-preview/tools - the real /tools route rendered against
// sample data (see ../preview-harness.tsx).
import ConsolePreview from "../preview-harness";
import ToolsPage from "../../../tools/page";

export default function Preview() {
  return (
    <ConsolePreview label="/tools">
      <ToolsPage />
    </ConsolePreview>
  );
}
