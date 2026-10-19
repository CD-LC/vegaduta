// RunStepInspector styles (run-inspector.css and its continuation sheets),
// split by area into plain CSS files. They are imported here
// in CASCADE ORDER: a later file wins ties, so keep the order and add new rules
// to the file that owns the area. They are imported from TS rather than chained
// with CSS @import on purpose: an @import makes Tailwind's PostCSS plugin compile
// the sheet (adding colour-mix fallbacks and, in production, its Lightning CSS
// pass), and these plain unlayered sheets have never gone through that.
import "./run-inspector.css"; // card shell, status tones, chrome, header, buttons, tabs
import "./run-inspector-body.css"; // body readouts and the logs tab
import "./run-inspector-data.css"; // data (JSON tree), cost and footer
import "./run-inspector-responsive.css"; // keyframes, phone bottom sheet, reduced motion
