// Applying a Plan to an install tree: the choice file, the launcher's state
// and deploy/compose/.env. Everything is idempotent (an unchanged file is not
// rewritten), nothing here deletes data, and nothing outside the keys and
// lines the catalog declares is touched.
package main

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"
)

const addonsFileName = ".vegaduta-addons.json"

func composeDir(root string) string { return filepath.Join(root, "deploy", "compose") }
func envPath(root string) string    { return filepath.Join(composeDir(root), ".env") }
func statePath(root string) string  { return filepath.Join(composeDir(root), ".vegaduta-state") }
func addonsPath(root string) string { return filepath.Join(composeDir(root), addonsFileName) }

// choiceFile is what Setup remembers between runs. It sits next to the
// launcher's own .vegaduta-state (which the launcher rewrites whole, so a key
// of ours inside it would be lost) and is not part of any release tarball.
type choiceFile struct {
	Schema    int      `json:"schema"`
	Selected  []string `json:"selected"`
	UpdatedAt string   `json:"updatedAt"`
}

// saveChoice records the selection, unless it is already recorded as is.
func saveChoice(root string, c Catalog, sel Selection) error {
	ids := sel.IDs(c)
	if old, err := readChoice(root); err == nil && strings.Join(old, ",") == strings.Join(ids, ",") {
		return nil
	}
	if ids == nil {
		ids = []string{}
	}
	b, _ := json.MarshalIndent(choiceFile{Schema: 1, Selected: ids, UpdatedAt: time.Now().UTC().Format(time.RFC3339)}, "", "  ")
	return writeFileAtomic(addonsPath(root), append(b, '\n'), 0o600)
}

func readChoice(root string) ([]string, error) {
	b, err := os.ReadFile(addonsPath(root))
	if err != nil {
		return nil, err
	}
	var f choiceFile
	if err := json.Unmarshal(b, &f); err != nil {
		return nil, err
	}
	return f.Selected, nil
}

// Current is the extras an existing install has, and where that was read from.
type Current struct {
	Installed bool      // an install tree with a remembered stack exists
	Source    string    // "choice" | "state" | ""
	Sel       Selection // what is on now
}

// DetectCurrent reads the install at root. What the launcher really runs (its
// state file and .env) decides every launcher-managed add-on, so a change made
// outside Setup - `scripts/vegaduta personal --profile rag`, `--no-search`, the
// console - is what the page shows and what a re-run keeps. The remembered
// choice file only supplies what the files cannot tell: model packs, which
// live in Ollama. An install that predates the choice file is reconstructed
// from the state and .env alone.
func DetectCurrent(root string, c Catalog) Current {
	observed, haveState := composeFromState(root, c)
	if ids, err := readChoice(root); err == nil {
		sel := Selection{}
		for _, id := range ids {
			if a, ok := c.Find(id); ok && a.Selectable() {
				sel[id] = true
			}
		}
		if haveState {
			for _, a := range c.Addons {
				if !a.IsCompose() || !a.Selectable() || a.Compose.empty() {
					continue
				}
				if observed[a.ID] {
					sel[a.ID] = true
				} else {
					delete(sel, a.ID)
				}
			}
		}
		return Current{Installed: true, Source: "choice", Sel: sel}
	}
	if !haveState {
		return Current{Sel: Selection{}}
	}
	return Current{Installed: true, Source: "state", Sel: observed}
}

// empty reports a compose spec that declares nothing the launcher state could show.
func (c *ComposeSpec) empty() bool { return len(c.Profiles)+len(c.Flags)+len(c.Env) == 0 }

// composeFromState reads which compose add-ons the launcher's remembered state
// and .env say are on. ok is false when there is no personal state to read.
func composeFromState(root string, c Catalog) (sel Selection, ok bool) {
	st, err := os.ReadFile(statePath(root))
	if err != nil {
		return nil, false
	}
	kv := parseKV(string(st))
	if kv["tier"] != "personal" {
		return nil, false
	}
	profiles := strings.Fields(kv["profiles"])
	env := map[string]string{}
	if b, err := os.ReadFile(envPath(root)); err == nil {
		env = parseKV(string(b))
	}
	sel = Selection{}
	for _, a := range c.Addons {
		if !a.IsCompose() || !a.Selectable() || a.Compose.empty() {
			continue
		}
		cs := a.Compose
		on := true
		for _, p := range cs.Profiles {
			// The launcher keeps tools/browser in their own state keys, not in profiles=.
			switch p {
			case "tools", "browser":
				on = on && kv[p] == "1"
			default:
				on = on && contains(profiles, p)
			}
		}
		for _, f := range cs.Flags {
			on = on && kv[strings.TrimPrefix(f, "--")] == "1"
		}
		for k, v := range cs.Env {
			on = on && unquote(env[k]) == v
		}
		if on {
			sel[a.ID] = true
		}
	}
	return sel, true
}

func parseKV(s string) map[string]string {
	m := map[string]string{}
	for line := range strings.Lines(s) {
		line = strings.TrimSpace(line)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		if k, v, ok := strings.Cut(line, "="); ok {
			m[strings.TrimSpace(k)] = strings.TrimSpace(v)
		}
	}
	return m
}

func unquote(v string) string { return strings.Trim(strings.TrimSpace(v), `"'`) }

// ChooseSelection decides what an install run will apply. Precedence: the
// page's explicit list, then --extras, then what the install already has, then
// the catalog defaults. The legacy --documents/--browser flags only add to a
// list that was not given explicitly.
func ChooseSelection(c Catalog, cur Current, explicit Selection, haveExplicit bool, legacy ...string) Selection {
	var base Selection
	switch {
	case haveExplicit:
		base = explicit.clone()
	case cur.Installed:
		base = cur.Sel.clone()
	default:
		base = Defaults(c)
	}
	if !haveExplicit { // an explicit list is the person's final word; the old flags only seed the default
		for _, id := range legacy {
			if a, ok := c.Find(id); ok && a.Selectable() {
				base[id] = true
			}
		}
	}
	out, _ := Resolve(c, base)
	return out
}

// ---------------------------------------------------------------- .env

// setEnvKey sets KEY=value in .env text: the first uncommented assignment is
// replaced in place, otherwise the line is appended. Other lines, comments and
// line endings are kept byte for byte.
func setEnvKey(in []byte, key, val string) ([]byte, bool) {
	lines := bytes.SplitAfter(in, []byte("\n"))
	want := key + "=" + val
	for i, ln := range lines {
		body, eol := splitEOL(ln)
		t := strings.TrimSpace(string(body))
		if k, _, ok := strings.Cut(t, "="); ok && strings.TrimSpace(k) == key && !strings.HasPrefix(t, "#") {
			if string(body) == want {
				return in, false
			}
			lines[i] = append([]byte(want), eol...)
			return bytes.Join(lines, nil), true
		}
	}
	out := append([]byte(nil), in...)
	if len(out) > 0 && out[len(out)-1] != '\n' {
		out = append(out, '\n')
	}
	return append(out, []byte(want+"\n")...), true
}

// clearEnvKey blanks KEY (the default in .env.example) when it holds the value
// the catalog set, or nothing. A value the person typed themselves is theirs
// and stays; the second result says so.
func clearEnvKey(in []byte, key, ours string) (out []byte, changed, keptTheirs bool) {
	lines := bytes.SplitAfter(in, []byte("\n"))
	for i, ln := range lines {
		body, eol := splitEOL(ln)
		t := strings.TrimSpace(string(body))
		k, v, ok := strings.Cut(t, "=")
		if !ok || strings.HasPrefix(t, "#") || strings.TrimSpace(k) != key {
			continue
		}
		cur := unquote(v)
		switch cur {
		case "":
			return in, false, false
		case ours:
			lines[i] = append([]byte(key+"="), eol...)
			return bytes.Join(lines, nil), true, false
		default:
			return in, false, true
		}
	}
	return in, false, false
}

func splitEOL(ln []byte) (body, eol []byte) {
	switch {
	case bytes.HasSuffix(ln, []byte("\r\n")):
		return ln[:len(ln)-2], ln[len(ln)-2:]
	case bytes.HasSuffix(ln, []byte("\n")):
		return ln[:len(ln)-1], ln[len(ln)-1:]
	}
	return ln, nil
}

// writeFileAtomic writes next to the target and renames, keeping the target's
// mode (the .env holds secrets; it must never become world readable).
func writeFileAtomic(path string, data []byte, defMode os.FileMode) error {
	mode := defMode
	if st, err := os.Stat(path); err == nil {
		mode = st.Mode().Perm()
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), ".vegaduta-tmp-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		os.Remove(name)
		return err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(name)
		return err
	}
	_ = os.Chmod(name, mode)
	if err := os.Rename(name, path); err != nil {
		os.Remove(name)
		return err
	}
	return nil
}

// ApplyEnv writes the plan's .env values. catalogVals maps each key to the value
// the catalog gives it, so a key is only blanked when it still holds that value.
// It returns one line per change for the log.
func ApplyEnv(root string, p Plan, catalogVals map[string]string) ([]string, error) {
	path := envPath(root)
	data, err := os.ReadFile(path)
	if err != nil {
		if len(p.EnvSet) == 0 && len(p.EnvUnset) == 0 {
			return nil, nil
		}
		return nil, fmt.Errorf("reading %s: %w", path, err)
	}
	var log []string
	for _, k := range sortedKeys(p.EnvSet) {
		var ch bool
		if data, ch = setEnvKey(data, k, p.EnvSet[k]); ch {
			log = append(log, fmt.Sprintf("set %s=%s in deploy/compose/.env", k, p.EnvSet[k]))
		}
	}
	for _, k := range p.EnvUnset {
		var ch, kept bool
		if data, ch, kept = clearEnvKey(data, k, catalogVals[k]); ch {
			log = append(log, fmt.Sprintf("cleared %s in deploy/compose/.env (back to the default)", k))
		} else if kept {
			log = append(log, fmt.Sprintf("left %s alone: it holds a value you set yourself", k))
		}
	}
	if len(log) == 0 {
		return nil, nil
	}
	return log, writeFileAtomic(path, data, 0o600)
}

// catalogEnvValues is every env key the catalog declares and its value.
func catalogEnvValues(c Catalog) map[string]string {
	m := map[string]string{}
	for _, a := range c.Addons {
		if a.Compose != nil {
			for k, v := range a.Compose.Env {
				m[k] = v
			}
		}
	}
	return m
}

// ---------------------------------------------------------------- launcher state

// SyncStateProfiles makes the launcher's remembered profile list exactly the
// wanted one. `scripts/vegaduta personal --profile X` ADDS to the remembered
// list, so without this a deselected profile would come back on every run (and
// an empty selection could never remove the last one). Only a personal state
// file is touched, and only its profiles= line.
func SyncStateProfiles(root string, profiles []string) (bool, error) {
	data, err := os.ReadFile(statePath(root))
	if err != nil {
		return false, nil // no state yet: nothing remembered, nothing to fix
	}
	kv := parseKV(string(data))
	if kv["tier"] != "personal" {
		return false, nil
	}
	want := "profiles=" + strings.Join(profiles, " ")
	lines := bytes.SplitAfter(data, []byte("\n"))
	for i, ln := range lines {
		body, eol := splitEOL(ln)
		if strings.HasPrefix(string(body), "profiles=") {
			if string(body) == want {
				return false, nil
			}
			lines[i] = append([]byte(want), eol...)
			return true, writeFileAtomic(statePath(root), bytes.Join(lines, nil), 0o600)
		}
	}
	out := append([]byte(nil), data...)
	if len(out) > 0 && out[len(out)-1] != '\n' {
		out = append(out, '\n')
	}
	return true, writeFileAtomic(statePath(root), append(out, []byte(want+"\n")...), 0o600)
}

// launcherCaps are the optional flags the unpacked launcher understands. Setup
// may be newer than the release it installs, and an unknown flag would be
// handed to docker compose and fail the whole start, so each one is checked
// against the script itself.
type launcherCaps struct{ NoSearch, NoBrowser, NoTools, Build bool }

func readLauncherCaps(root string) launcherCaps {
	if runtime.GOOS == "windows" {
		// vegaduta.ps1 takes no --flags, but it does understand -Build.
		b, err := os.ReadFile(filepath.Join(root, "scripts", "vegaduta.ps1"))
		return launcherCaps{Build: err == nil && strings.Contains(string(b), "$Build")}
	}
	b, err := os.ReadFile(filepath.Join(root, "scripts", "vegaduta"))
	if err != nil {
		return launcherCaps{}
	}
	s := string(b)
	return launcherCaps{
		NoSearch:  strings.Contains(s, "--no-search)"),
		NoBrowser: strings.Contains(s, "--no-browser)"),
		NoTools:   strings.Contains(s, "--no-tools)"),
		Build:     strings.Contains(s, "--build)"),
	}
}

// buildArg is the launcher's "build the images from source" switch, or "" when
// the unpacked launcher predates it.
func (c launcherCaps) buildArg() string {
	if !c.Build {
		return ""
	}
	if runtime.GOOS == "windows" {
		return "-Build"
	}
	return "--build"
}

// flagOff maps a launcher flag to the one that switches it off, when it has one.
func flagOff(flag string, caps launcherCaps) (string, bool) {
	switch flag {
	case "--search":
		return "--no-search", caps.NoSearch
	case "--browser":
		return "--no-browser", caps.NoBrowser
	case profileOffKey("tools"):
		return "--no-tools", caps.NoTools
	}
	return "", false
}

// ---------------------------------------------------------------- side effects

// services lists the container service (and its compose profile) behind each
// compose add-on that has one, to stop it when the add-on is switched off.
// Names are the ones in deploy/compose; TestServiceNamesExistInCompose keeps
// them honest.
var addonServices = map[string]struct{ Service, Profile string }{
	"workspace": {"workspace-runner", "workspace"},
	"search":    {"searxng", "search"},
	"documents": {"sidecar", "rag"},
}

// StopTurnedOff stops the containers of add-ons that were switched off. It only
// ever runs `docker stop`: containers, volumes, projects, documents and models
// stay where they are, so switching an add-on back on resumes with its data.
func StopTurnedOff(ctx context.Context, root string, ids []string, emit func(Event)) {
	for _, id := range ids {
		svc, ok := addonServices[id]
		if !ok {
			continue
		}
		for _, dir := range workingDirs(root) {
			out, err := exec.CommandContext(ctx, "docker", "ps", "-q",
				"--filter", "label=com.docker.compose.service="+svc.Service,
				"--filter", "label=com.docker.compose.project.working_dir="+dir).Output()
			if err != nil {
				emit(Event{Line: "could not look for the " + svc.Service + " container: " + err.Error()})
				break
			}
			if cids := strings.Fields(string(out)); len(cids) > 0 {
				args := append([]string{"stop"}, cids...)
				if err := exec.CommandContext(ctx, "docker", args...).Run(); err != nil {
					emit(Event{Line: "could not stop " + svc.Service + ": " + err.Error()})
				} else {
					emit(Event{Line: "Stopped " + svc.Service + " (its data is kept)"})
				}
				break
			}
		}
	}
}

func workingDirs(root string) []string {
	d := composeDir(root)
	dirs := []string{d}
	if r, err := filepath.EvalSymlinks(d); err == nil && r != d {
		dirs = append(dirs, r)
	}
	return dirs
}

// PullModels downloads the picked models through the launcher's own
// `models pull`, after the stack is up. A failed pull is reported and does not
// fail the install: the console's Extras page can retry it.
func PullModels(ctx context.Context, root string, pulls []Pull, emit func(Event)) {
	for _, p := range pulls {
		emit(Event{Phase: "models", Line: "Downloading model " + p.Tag + " (this can take a while)"})
		if err := runCLI(ctx, root, func(s string) { emit(Event{Line: s}) }, "models", "pull", p.Tag); err != nil {
			emit(Event{Line: "Could not download " + p.Tag + ": " + err.Error() +
				". The install itself is fine; turn the model on later from the console (Extras) or run: scripts/vegaduta models pull " + p.Tag})
		}
	}
}
