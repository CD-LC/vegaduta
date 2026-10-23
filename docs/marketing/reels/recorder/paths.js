// Where the recorder reads and writes; the same layout as pipeline/paths.py.
const path = require('path'); const fs = require('fs'); const os = require('os');
const REELS = path.resolve(__dirname, '..');
const WORK = process.env.VD_REELS_WORK || path.join(REELS, 'out');
const REC = path.join(WORK, 'rec'), SHOTS = path.join(WORK, 'shots');
const STACK = path.join(REELS, 'stack'), DEMO = path.join(REELS, 'demo-knowledge');
for (const d of [REC, SHOTS]) fs.mkdirSync(d, { recursive: true });
module.exports = {
  REELS, WORK, REC, SHOTS, STACK, DEMO,
  BASE_URL: process.env.VD_CONSOLE_URL || 'http://localhost:3000',
  PASSWORD_FILE: path.join(STACK, 'owner-password.txt'),   // git-ignored; the demo owner's password
  STATE_FILE: path.join(STACK, 'owner-state.json'),         // git-ignored; Playwright storage state
  rec: name => path.join(REC, name),
  shot: name => path.join(SHOTS, name),
  demo: name => path.join(DEMO, name),
  password: () => fs.readFileSync(module.exports.PASSWORD_FILE, 'utf8').trim(),
  // The first-run setup link: VD_SETUP_URL, else the file core keeps until the owner has a password
  // (host JVM: ~/.vegaduta/auth; container: printed by `scripts/vegaduta personal`).
  setupLink: () => {
    if (process.env.VD_SETUP_URL) return process.env.VD_SETUP_URL;
    const f = path.join(process.env.APP_AUTH_LOCAL_DATA_DIR || path.join(os.homedir(), '.vegaduta', 'auth'), 'first-run-setup.txt');
    const m = fs.existsSync(f) && fs.readFileSync(f, 'utf8').match(/https?:\/\/[^\s]*\/login\?setup=[\w-]+/g);
    if (!m) throw new Error('no setup link: set VD_SETUP_URL to the link scripts/vegaduta printed');
    return m[m.length - 1];
  },
};
