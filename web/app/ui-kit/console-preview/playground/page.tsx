"use client";

// /ui-kit/console-preview/playground - the real /playground route rendered against
// sample data (see ../preview-harness.tsx).
import ConsolePreview from "../preview-harness";
import PlaygroundPage from "../../../playground/PlaygroundLegacy";

export default function Preview() {
  return (
    <ConsolePreview label="/playground">
      <PlaygroundPage />
    </ConsolePreview>
  );
}
