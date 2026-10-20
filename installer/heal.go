// Self-repair: the steps a non-technical person cannot be expected to do by
// hand. Before the stack starts, make sure Docker is installed, running and
// has enough memory; when the stack fails to come up, recognise the common
// causes (stale registry login, full disk, engine stopped, slow first boot,
// flaky network), repair what can be repaired and try again.
package main

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"regexp"
	"strconv"
	"strings"
	"time"
)

// errRestartNeeded stops an install that can only continue after a reboot
// (Windows enabling WSL2). The setup app is registered to reopen by itself.
var errRestartNeeded = errors.New("restart Windows to finish setting up - VegaDūta Setup reopens by itself afterwards")

// upAttempts bounds how often a failed start is repaired and retried.
const upAttempts = 3

// ensureDocker installs and starts Docker when needed, then gives its VM
// enough memory for the tier. Each step reports progress through emit.
func ensureDocker(ctx context.Context, tier string, emit func(Event)) error {
	line := func(s string) { emit(Event{Line: s}) }
	if err := ensurePlatformPrereqs(ctx, emit); err != nil {
		return err
	}
	if _, err := exec.LookPath("docker"); err != nil {
		line("Docker is not installed - installing it for you…")
		if err := dockerInstall(ctx, emit); err != nil {
			return err
		}
		augmentPath()
		if _, err := exec.LookPath("docker"); err != nil {
			return errors.New("Docker could not be installed automatically on this computer - follow the steps above, then click Install again")
		}
	}
	if !dockerUp(ctx) {
		if err := dockerStart(ctx, emit); err != nil {
			return err
		}
	}
	enableDockerAutostart(emit)
	needGB, _, _ := tierNeeds(tier)
	have := dockerMemGB(ctx)
	if have >= int64(needGB) {
		return nil
	}
	target, ok := memoryTarget(int64(needGB), hostRAMMB()/1024)
	if !ok {
		line(fmt.Sprintf("Docker has %d GB of memory and this computer cannot spare the %d GB VegaDūta wants - it may run slowly.", have, needGB))
		return nil
	}
	changed, err := raiseDockerMemory(ctx, target, emit)
	if err != nil {
		line("Could not raise Docker's memory automatically (" + err.Error() + ") - continuing.")
		return nil
	}
	if changed {
		line(fmt.Sprintf("Gave Docker %d GB of memory.", target))
	}
	return nil
}

// memoryTarget picks how much memory to give Docker: the tier's need plus
// 2 GB of headroom, never leaving the host itself less than 2 GB.
func memoryTarget(needGB, hostGB int64) (int64, bool) {
	if hostGB <= 0 {
		return 0, false
	}
	target := needGB + 2
	if limit := hostGB - 2; target > limit {
		target = limit
	}
	return target, target >= needGB
}

func dockerUp(ctx context.Context) bool {
	c, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	return exec.CommandContext(c, "docker", "info").Run() == nil
}

func dockerMemGB(ctx context.Context) int64 {
	c, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	out, err := exec.CommandContext(c, "docker", "info", "--format", "{{.MemTotal}}").Output()
	if err != nil {
		return 0
	}
	b, _ := strconv.ParseInt(strings.TrimSpace(string(out)), 10, 64)
	return b / (1 << 30)
}

// failureKind classifies a failed start from its output.
type failureKind int

const (
	failUnknown failureKind = iota
	failAuth                // stale/bad registry login answering "unauthorized"
	failDisk                // no space left on device
	failEngine              // the Docker engine stopped answering
	failNetwork             // pull interrupted, timeouts, rate limits
	failSlow                // services still starting when the health wait ended
)

var failurePatterns = []struct {
	kind failureKind
	re   *regexp.Regexp
}{
	{failAuth, regexp.MustCompile(`(?i)unauthorized|denied: (requested access|permission)|authentication required`)},
	{failDisk, regexp.MustCompile(`(?i)no space left on device`)},
	{failEngine, regexp.MustCompile(`(?i)cannot connect to the docker daemon|error during connect|dockerDesktopLinuxEngine|docker daemon is not running|is the docker daemon running`)},
	{failNetwork, regexp.MustCompile(`(?i)tls handshake timeout|i/o timeout|connection reset|unexpected eof|toomanyrequests|interrupted|temporary failure in name resolution|context deadline exceeded`)},
	{failSlow, regexp.MustCompile(`(?i)did not become healthy|is unhealthy|dependency failed to start`)},
}

func classifyFailure(output string) failureKind {
	for _, p := range failurePatterns {
		if p.re.MatchString(output) {
			return p.kind
		}
	}
	return failUnknown
}

// repair fixes what a failure kind allows and reports whether a retry is
// worth it.
func repair(ctx context.Context, kind failureKind, emit func(Event)) bool {
	line := func(s string) { emit(Event{Line: s}) }
	run := func(args ...string) { _ = exec.CommandContext(ctx, "docker", args...).Run() }
	switch kind {
	case failAuth:
		line("A saved registry login was rejected - signing out of it (the images are public) and retrying…")
		run("logout", "ghcr.io")
		run("logout")
	case failDisk:
		line("Docker ran out of disk space - removing unused images and build cache, then retrying…")
		run("image", "prune", "-f")
		run("builder", "prune", "-f")
	case failEngine:
		line("The Docker engine stopped - restarting it, then retrying…")
		if err := dockerStart(ctx, emit); err != nil {
			return false
		}
	case failNetwork:
		line("The download was interrupted - retrying…")
	case failSlow:
		line("Some services were still starting - giving them more time…")
	default:
		line("Starting failed - retrying once more…")
	}
	return true
}

// upWithRepair runs the stack start, repairing and retrying failures. tail
// keeps the recent output so a failure can be classified.
func upWithRepair(ctx context.Context, start func(onLine func(string)) error, emit func(Event), emitLine func(string)) error {
	return upWithRepairWait(ctx, start, emit, emitLine, 10*time.Second)
}

// upWithRepairWait is upWithRepair with the back-off step (attempt n waits
// n*step) as a parameter, so tests need not sleep.
func upWithRepairWait(ctx context.Context, start func(onLine func(string)) error, emit func(Event), emitLine func(string), step time.Duration) error {
	var err error
	for attempt := 1; attempt <= upAttempts; attempt++ {
		var tail []string
		err = start(func(s string) {
			tail = append(tail, s)
			if len(tail) > 300 {
				tail = tail[len(tail)-300:]
			}
			emitLine(s)
		})
		if err == nil {
			return nil
		}
		if attempt == upAttempts || ctx.Err() != nil {
			break
		}
		if !repair(ctx, classifyFailure(strings.Join(tail, "\n")), emit) {
			break
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(time.Duration(attempt) * step):
		}
	}
	return err
}

// mergeWSLConfig sets memory=<gb>GB under [wsl2] in a .wslconfig, keeping
// every other line. It reports whether the text changed.
func mergeWSLConfig(text string, gb int64) (string, bool) {
	want := fmt.Sprintf("memory=%dGB", gb)
	lines := strings.Split(strings.ReplaceAll(text, "\r\n", "\n"), "\n")
	if len(lines) > 0 && lines[len(lines)-1] == "" {
		lines = lines[:len(lines)-1]
	}
	section, inWSL2, sawWSL2 := -1, false, false
	for i, l := range lines {
		t := strings.TrimSpace(l)
		if strings.HasPrefix(t, "[") {
			inWSL2 = strings.EqualFold(t, "[wsl2]")
			if inWSL2 {
				sawWSL2, section = true, i
			}
			continue
		}
		if inWSL2 {
			if k, _, ok := strings.Cut(t, "="); ok && strings.EqualFold(strings.TrimSpace(k), "memory") {
				if t == want {
					return text, false
				}
				lines[i] = want
				return strings.Join(lines, "\r\n") + "\r\n", true
			}
		}
	}
	if sawWSL2 {
		lines = append(lines[:section+1], append([]string{want}, lines[section+1:]...)...)
	} else {
		if len(lines) > 0 {
			lines = append(lines, "")
		}
		lines = append(lines, "[wsl2]", want)
	}
	return strings.Join(lines, "\r\n") + "\r\n", true
}

// dockerSetting is one Docker Desktop settings file and its auto-start key
// (settings-store.json on current releases, settings.json on older ones).
type dockerSetting struct{ path, key string }

// enableDockerAutostart turns on Docker Desktop's "start when you sign in", so
// VegaDūta (restart: unless-stopped) comes back by itself after a reboot.
func enableDockerAutostart(emit func(Event)) {
	for _, s := range dockerSettingsFiles() {
		data, err := os.ReadFile(s.path)
		if err != nil {
			continue
		}
		out, changed, err := setJSONTrue(data, s.key)
		if err != nil || !changed {
			return
		}
		if os.WriteFile(s.path, out, 0o644) == nil {
			emit(Event{Line: "Docker will now start when you sign in, so VegaDūta comes back after a restart."})
		}
		return
	}
}

// setJSONTrue sets a top-level boolean key in a JSON object to true, keeping
// every other key.
func setJSONTrue(data []byte, key string) ([]byte, bool, error) {
	var m map[string]any
	if err := json.Unmarshal(data, &m); err != nil {
		return nil, false, err
	}
	if v, ok := m[key].(bool); ok && v {
		return data, false, nil
	}
	m[key] = true
	out, err := json.MarshalIndent(m, "", "  ")
	return out, err == nil, err
}
