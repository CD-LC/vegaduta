/** Next-step chips under the request box, derived from what the app already
 * has so they are not the same three ideas every time. Each suggestion is a
 * one-line change request the user can send or edit - cheap, local, no model
 * call. Ordered so the most structural gap comes first. */
export interface StudioSuggestion {
  label: string;
  prompt: string;
}

const RULES: { missing: RegExp; label: string; prompt: string }[] = [
  {
    missing: /localStorage|save\(|load\(/i,
    label: "Remember my data",
    prompt: "Save the app's data with the existing load/save helpers so it survives a reload.",
  },
  {
    missing: /prefers-color-scheme|dark/i,
    label: "Add a dark mode",
    prompt: "Add a dark mode toggle in the header that switches the app's colours to a dark theme.",
  },
  {
    missing: /<form|type="submit"|addItem|onAdd/i,
    label: "Add an Add form",
    prompt: "Add a form (or a + button opening a bottom sheet) so I can add new items myself.",
  },
  {
    missing: /filter|search|searchInput/i,
    label: "Add a search box",
    prompt: "Add a search box at the top that filters the list live as I type.",
  },
  {
    missing: /setting|gear|⚙|sliders/i,
    label: "Add a settings screen",
    prompt: "Add a settings tab/screen with a few options that actually change the app (name, theme colour, reset data).",
  },
  {
    missing: /confirm|undo|delete/i,
    label: "Safer deletes",
    prompt: "Add a confirm step (or an undo toast) when I delete an item.",
  },
];

export function studioSuggestions(html: string, max = 3): StudioSuggestion[] {
  const out: StudioSuggestion[] = [];
  for (const r of RULES) {
    if (!r.missing.test(html)) out.push({ label: r.label, prompt: r.prompt });
    if (out.length >= max) break;
  }
  return out;
}
