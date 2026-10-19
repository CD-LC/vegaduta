"use client";

// /ui-kit/console-preview/providers - the real /providers route rendered against
// sample data (see ../preview-harness.tsx).
import ConsolePreview from "../preview-harness";
import ProvidersPage from "../../../providers/page";

export default function Preview() {
  return (
    <ConsolePreview label="/providers">
      <ProvidersPage />
    </ConsolePreview>
  );
}
