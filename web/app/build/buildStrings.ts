// User-facing English strings for /build (page.tsx) that have no key in the
// appBuilder.* catalog yet. They live here, typed and in one place, so the
// move to the message catalog is a one-to-one swap: each constant becomes
// t("<same-name>"), functions become t(name, { vars }). Strings that already
// have an appBuilder.* key (tabPreview, tabBackend, deleteProjectButton,
// lifecycleEphemeral, ...) are rendered with t() in the page instead.
//
// Do not use the t(key) || fallback idiom: useTranslations returns the key
// for a missing message, so the fallback never runs.

export type SurfaceTabValue = "create" | "studio" | "apps";

export interface SurfaceTabText {
  value: SurfaceTabValue;
  label: string;
  line: string;
  startHere?: boolean;
}

export const BUILD_STRINGS = {
  // Tab order is the story: agent first, then the studio, then code projects.
  surfaceTabs: [
    { value: "create", label: "Agent builder", line: "Create the intelligence", startHere: true },
    { value: "studio", label: "AI app studio", line: "Put it in people's hands" },
    { value: "apps", label: "App projects", line: "Build and manage code" },
  ] satisfies readonly SurfaceTabText[],
  startHere: "Start here",

  // Project picker / header
  projectOptionSuffix: (state: "archived" | "ephemeral") => ` · ${state}`,
  githubConnected: "Connected",
  githubConnect: "Connect GitHub",
  dismiss: "Dismiss",

  // Project tabs (Preview / Backend use appBuilder.tabPreview / tabBackend)
  tabDesign: "Design",
  tabGithub: "GitHub",
  tabStore: "Store",

  // Delete-project dialog (title button uses appBuilder.deleteProjectButton)
  deleteProjectTitle: (id: string) => `Delete “${id}”?`,
  deleteProjectBody: "This permanently deletes the project, its files and any live preview. This can’t be undone.",
  cancel: "Cancel",
  deleting: "Deleting...",

  // Delete-asset dialog
  deleteAssetTitle: (path: string) => `Delete “${path}”?`,
  deleteAssetBody: "This removes the asset from the assets/ folder on your branch. Anything referencing it will break.",
  deleteAsset: "Delete",
  deleteAssetLabel: (path: string) => `Delete ${path}`,

  // Chat composer + change list
  diffLabel: "Diff of this change",
  copyFileLabel: (path: string) => `Copy ${path}`,
  removeDesignLabel: (label: string) => `Remove ${label}`,
  suggestedChanges: "Suggested changes",
  attachDesignTitle: "Attach a design — screenshot, sketch photo, or Figma SVG export",
  attachDesignLabel: "Attach a design image",
  uploadAssetTitle: "Upload an asset to the repo (logo, font, image) — usable in prompts by name",
  uploadAssetLabel: "Upload an asset to the repo",
  sketchTitle: "Sketch the layout — drag blocks onto a wireframe",
  sketchLabel: "Sketch the layout",
  dictationStopTitle: "Stop dictation",
  dictationStartTitle: "Dictate your request",
  dictationStopLabel: "Stop dictation",
  dictationStartLabel: "Start dictation",
  sendLabel: "Send",
  sendTitle: "Send (Enter or Ctrl/Cmd+Enter)",
} as const;
