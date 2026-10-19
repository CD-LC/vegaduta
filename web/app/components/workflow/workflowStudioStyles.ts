// Workflow designer STUDIO layout (workflow-studio.css and its continuation sheets),
// split by area into plain CSS files. They are imported here
// in CASCADE ORDER: a later file wins ties, so keep the order and add new rules
// to the file that owns the area. They are imported from TS rather than chained
// with CSS @import on purpose: an @import makes Tailwind's PostCSS plugin compile
// the sheet (adding colour-mix fallbacks and, in production, its Lightning CSS
// pass), and these plain unlayered sheets have never gone through that.
import "./workflow-studio.css"; // shell tokens, glass, canvas, floating top bar, menus
import "./workflow-studio-overlays.css"; // notices tray, canvas overlays, observe mode, palette
import "./workflow-studio-panels.css"; // floating side panels, see-through, small screens
