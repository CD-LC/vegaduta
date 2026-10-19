// Run flight recorder styles (run-recorder.css and its continuation sheets),
// split by area into plain CSS files. They are imported here
// in CASCADE ORDER: a later file wins ties, so keep the order and add new rules
// to the file that owns the area. They are imported from TS rather than chained
// with CSS @import on purpose: an @import makes Tailwind's PostCSS plugin compile
// the sheet (adding colour-mix fallbacks and, in production, its Lightning CSS
// pass), and these plain unlayered sheets have never gone through that.
import "./run-recorder.css"; // dock shell, resize grip, header bar, mini strip
import "./run-recorder-timeline.css"; // time axis, swim lanes, visit bars
import "./run-recorder-overlays.css"; // playhead, tooltip, status tones, phone, reduced motion
