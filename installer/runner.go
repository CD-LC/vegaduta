// Stack steps are delegated to the unpacked tree's own CLI so the installer
// and scripts/vegaduta share one install contract. POSIX runs
// `bash scripts/vegaduta ...`; Windows runs scripts/vegaduta.ps1 (Docker
// Desktop + PowerShell are already prerequisites there).
package main

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
)

// upArgs returns the vegaduta invocation that starts the chosen tier. For the
// personal tier the Extras plan (catalog.json, via BuildPlan) supplies the
// profiles and flags; Setup adds no profile names of its own. A flag the person
// left off is passed as its --no-* twin when this release's launcher has one:
// a fresh personal install otherwise starts private web search by default, and
// an unticked box has to win over that.
func upArgs(tier string, p Plan, caps launcherCaps) []string {
	if tier == "personal" {
		if runtime.GOOS == "windows" {
			// vegaduta.ps1 is a PowerShell script: `--profile` would be passed through to docker compose,
			// and it has no flags (Unsupported keeps flag-based extras out of the plan on Windows).
			args := []string{"personal"}
			if len(p.Profiles) > 0 {
				args = append(args, "-Profile", strings.Join(p.Profiles, ","))
			}
			return args
		}
		args := []string{"personal"}
		for _, pr := range p.Profiles {
			args = append(args, "--profile", pr)
		}
		args = append(args, p.Flags...)
		for _, f := range p.FlagsOff {
			if off, ok := flagOff(f, caps); ok {
				args = append(args, off)
			}
		}
		return args
	}
	if runtime.GOOS == "windows" {
		return []string{"up", "-" + strings.ToUpper(tier[:1]) + tier[1:]} // -Lite / -Full
	}
	return []string{"up", "--" + tier}
}

// cliCommand builds the command for one CLI call inside root.
func cliCommand(ctx context.Context, root string, args ...string) *exec.Cmd {
	var cmd *exec.Cmd
	if runtime.GOOS == "windows" {
		ps := filepath.Join(root, "scripts", "vegaduta.ps1")
		cmd = exec.CommandContext(ctx, "powershell",
			"-NoProfile", "-ExecutionPolicy", "Bypass", "-File", ps)
		cmd.Args = append(cmd.Args, args...)
	} else {
		sh := filepath.Join(root, "scripts", "vegaduta")
		cmd = exec.CommandContext(ctx, "bash", sh)
		cmd.Args = append(cmd.Args, args...)
	}
	cmd.Dir = root
	return cmd
}

// runCLI runs one vegaduta command, forwarding every output line to onLine.
func runCLI(ctx context.Context, root string, onLine func(string), args ...string) error {
	cmd := cliCommand(ctx, root, args...)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	cmd.Stderr = cmd.Stdout // the script prints diagnostics to stderr; merge both
	if err := cmd.Start(); err != nil {
		return fmt.Errorf("starting %s: %w", cmd.Args[0], err)
	}
	scanLines(stdout, onLine)
	return cmd.Wait()
}

func scanLines(r io.Reader, onLine func(string)) {
	sc := bufio.NewScanner(r)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	for sc.Scan() {
		s := sc.Text()
		// Carriage-return redraws (brew's "#== 42%" progress bars) arrive as one
		// long segment between newlines; only the final redraw is readable text.
		if i := strings.LastIndexByte(s, '\r'); i >= 0 {
			s = s[i+1:]
		}
		onLine(s)
	}
}
