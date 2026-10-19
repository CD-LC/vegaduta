// agentkit — the terminal command gate.
//
// NOTE FIRST: the browser host has no terminal, so on the hosted web app today this
// file gates nothing at run time. It exists, tested, because the gate has to be
// in place BEFORE a native host is attached, not bolted on after — the loop
// calls it for every run_command regardless of host.
//
// Three verdicts, not two:
//   "blocked"   — never runs, approval or not. Commands no coding task needs
//                 and whose damage is total (wipe the root, format a disk,
//                 power off).
//   "dangerous" — a person may approve it, looking at the exact command line,
//                 but it is NEVER covered by "allow all commands this run".
//                 Each one is asked for individually, every time.
//   "normal"    — still needs approval (every command does), and "allow all"
//                 may cover it.
//
// A string deny-list is a backstop under the approval prompt, NOT a security
// boundary (same stance as clients/vscode workspaceTools REFUSED_COMMAND_
// PATTERNS): a shell offers endless ways to spell the same thing. The boundary
// is the person reading the command before it runs. This list only makes sure
// the obviously destructive spellings cannot slip through on an "allow all".

export type CommandVerdict =
  | { verdict: "normal" }
  | { verdict: "dangerous"; why: string }
  | { verdict: "blocked"; why: string };

interface Rule {
  pattern: RegExp;
  why: string;
}

const BLOCKED: Rule[] = [
  // rm -rf / , rm -rf /* , rm -rf ~ , rm -rf $HOME — flags in any order/split.
  {
    pattern: /\brm\s+(?:-{1,2}[\w-]+\s+)*-{1,2}[\w-]*[rR][\w-]*\s+(?:-{1,2}[\w-]+\s+)*(?:--\s+)?(?:\/\*?|~\/?|\$HOME\/?|\$\{HOME\}\/?)(?:\s|$|;|&|\|)/,
    why: "recursive delete of the filesystem root or your home directory",
  },
  { pattern: /--no-preserve-root/i, why: "recursive delete of the filesystem root" },
  { pattern: /\b(mkfs(\.\w+)?|fdisk|diskpart|wipefs)\b/i, why: "disk formatting or partitioning" },
  { pattern: /(^|[\s;&|])format\s+[a-zA-Z]:/i, why: "formatting a drive" },
  { pattern: /\bdd\s+[^|;&]*\bof=\/dev\//i, why: "raw write to a block device" },
  { pattern: />\s*\/dev\/(sd[a-z]|nvme\d|hd[a-z]|disk\d)/i, why: "raw write to a block device" },
  { pattern: /:\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:/, why: "fork bomb" },
  { pattern: /(^|[\s;&|])(shutdown|reboot|halt|poweroff)(\s|$)|\binit\s+[06]\b/i, why: "power state change" },
  { pattern: /\b(rd|rmdir)\s+\/s\b[^\n]*\s[a-zA-Z]:\\?(\s|$)/i, why: "recursive delete of a whole drive" },
  { pattern: /\bdel\s+(\/\w\s+)*[a-zA-Z]:\\\*?(\s|$)/i, why: "recursive delete of a whole drive" },
];

const DANGEROUS: Rule[] = [
  { pattern: /\brm\s+(?:-{1,2}[\w-]+\s+)*-{1,2}[\w-]*[rRfF]/, why: "force or recursive delete" },
  { pattern: /\b(rd|rmdir)\s+\/s\b|\bdel\s+\/[sfq]\b/i, why: "recursive delete" },
  { pattern: /\bRemove-Item\b[^\n]*-(Recurse|Force)\b/i, why: "recursive or forced delete" },
  { pattern: /\bgit\s+push\b[^\n]*(--force\b|--force-with-lease\b|\s-f\b|\s\+\S+)/i, why: "force push rewrites remote history" },
  { pattern: /\bgit\s+reset\b[^\n]*--hard\b/i, why: "discards uncommitted changes" },
  { pattern: /\bgit\s+clean\b[^\n]*\s-[a-zA-Z]*[fdxX]/i, why: "deletes untracked files" },
  { pattern: /\bgit\s+(checkout|restore)\s+(--\s+)?\./i, why: "discards uncommitted changes" },
  { pattern: /\bgit\s+branch\s+-D\b|\bgit\s+push\b[^\n]*--delete\b/i, why: "deletes a branch" },
  { pattern: /\bgit\s+filter-(branch|repo)\b/i, why: "rewrites repository history" },
  // Download-and-execute: curl … | sh, wget … | bash, iwr … | iex, bash <(curl …)
  {
    pattern: /\b(curl|wget|iwr|Invoke-WebRequest|Invoke-RestMethod|irm)\b[^\n|]*\|\s*(sudo\s+)?(sh|bash|zsh|dash|ksh|python\d?|node|perl|ruby|iex|Invoke-Expression|powershell|pwsh)\b/i,
    why: "downloads a script and runs it unseen",
  },
  { pattern: /\b(sh|bash|zsh)\s+<\(\s*(curl|wget)\b/i, why: "downloads a script and runs it unseen" },
  { pattern: /\b(iex|Invoke-Expression)\b[^\n]*\b(DownloadString|iwr|irm|Invoke-WebRequest)\b/i, why: "downloads a script and runs it unseen" },
  { pattern: /(^|[\s;&|(])(sudo|doas|su)\s/i, why: "runs with administrator rights" },
  { pattern: /\brunas\b|\bStart-Process\b[^\n]*-Verb\s+RunAs/i, why: "runs with administrator rights" },
  { pattern: /\bchmod\s+(-[a-zA-Z]+\s+)*(0?777|a\+rwx)\b|\bchmod\s+-[a-zA-Z]*R/i, why: "broad permission change" },
  { pattern: /\bchown\s+-[a-zA-Z]*R/i, why: "recursive ownership change" },
  { pattern: /\b(npm|pnpm|yarn)\s+publish\b|\bcargo\s+publish\b|\btwine\s+upload\b|\bgem\s+push\b/i, why: "publishes a package publicly" },
  { pattern: /\b(terraform|tofu)\s+(destroy|apply)\b/i, why: "changes or destroys real infrastructure" },
  { pattern: /\bkubectl\s+delete\b|\bhelm\s+(uninstall|delete)\b/i, why: "deletes cluster resources" },
  { pattern: /\bdocker\s+(system\s+prune|volume\s+(rm|prune))\b|\bdocker\s+compose\s+down\b[^\n]*(-v|--volumes)\b/i, why: "deletes container data" },
  { pattern: /\bdrop\s+(database|table|schema)\b|\btruncate\s+table\b/i, why: "destroys database data" },
  { pattern: /\b(kill|pkill|killall|taskkill)\b[^\n]*(\s-9\b|\s\/f\b|\s-1\b)/i, why: "force-kills processes" },
  { pattern: /\bcrontab\s+-r\b/i, why: "removes all scheduled jobs" },
  { pattern: /\breg\s+(delete|add)\b|\bSet-ExecutionPolicy\b/i, why: "changes system settings" },
  { pattern: />\s*~?\/?\.(bashrc|zshrc|profile|bash_profile|ssh\/)/i, why: "overwrites shell or SSH configuration" },
];

/**
 * Classify one command line. Never throws. Anything that is not a non-empty
 * string is "blocked" — a command the gate cannot read is not one it can let
 * a person be asked about.
 */
export function classifyCommand(command: unknown): CommandVerdict {
  if (typeof command !== "string" || !command.trim()) {
    return { verdict: "blocked", why: "there is no command to run" };
  }
  if (command.length > 4000) {
    return { verdict: "blocked", why: "the command line is too long to review" };
  }
  // Collapse line continuations and runs of whitespace so "rm \\\n -rf /" is
  // judged as the command the shell will actually see.
  const flat = command.replace(/\\\r?\n/g, " ").replace(/\s+/g, " ").trim();
  for (const rule of BLOCKED) {
    if (rule.pattern.test(flat)) return { verdict: "blocked", why: rule.why };
  }
  for (const rule of DANGEROUS) {
    if (rule.pattern.test(flat)) return { verdict: "dangerous", why: rule.why };
  }
  return { verdict: "normal" };
}
