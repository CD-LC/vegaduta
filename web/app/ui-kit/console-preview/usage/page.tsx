"use client";

// /ui-kit/console-preview/usage - the real /usage route rendered against
// sample data (see ../preview-harness.tsx).
import ConsolePreview from "../preview-harness";
import UsagePage from "../../../usage/page";

export default function Preview() {
  return (
    <ConsolePreview label="/usage">
      <UsagePage />
    </ConsolePreview>
  );
}
