// The Extras step, pure logic: which add-ons a person picked, what that drags
// in, and what it means for the launcher and .env. No I/O here - the files and
// processes are in extras_apply.go, the HTTP surface in extras_http.go - so
// every rule below is unit tested without a machine.
package main

import (
	"fmt"
	"sort"
	"strings"
)

// Selection is a set of add-on ids.
type Selection map[string]bool

func (s Selection) IDs(c Catalog) []string {
	var out []string
	for _, a := range c.Addons { // catalog order, so output is deterministic
		if s[a.ID] {
			out = append(out, a.ID)
		}
	}
	return out
}

func (s Selection) clone() Selection {
	o := Selection{}
	for k, v := range s {
		if v {
			o[k] = true
		}
	}
	return o
}

func selectionOf(ids ...string) Selection {
	s := Selection{}
	for _, id := range ids {
		s[id] = true
	}
	return s
}

// launcherDefaultOn lists add-ons that scripts/vegaduta turns on by itself for a
// fresh personal install (personal_apply_defaults: private web search and the MCP
// tools runner). Setup preselects them too: an unticked box is passed as
// --no-search / --no-tools, so leaving this out would make a Setup install the one
// that quietly lacks the day-one default.
// Keep it in step with the launcher. The catalog's own defaultOn stays false for
// them because core reads defaultOn as "assume wanted" on every install, and an
// install that opted out of search must not show it as waiting to be turned on.
var launcherDefaultOn = []string{"search", "tools"}

// Defaults is what a fresh install preselects: every selectable defaultOn entry,
// plus what the launcher turns on for a fresh personal install.
func Defaults(c Catalog) Selection {
	s := Selection{}
	for _, a := range c.Addons {
		if a.Selectable() && a.DefaultOn {
			s[a.ID] = true
		}
	}
	for _, id := range launcherDefaultOn {
		if a, ok := c.Find(id); ok && a.Selectable() {
			s[id] = true
		}
	}
	return s
}

// Note explains one automatic change to the selection, in words for the page.
type Note struct {
	ID      string   `json:"id"`
	Kind    string   `json:"kind"` // "added" | "dropped"
	Because []string `json:"because"`
	Text    string   `json:"text"`
}

func nameOf(c Catalog, id string) string {
	if a, ok := c.Find(id); ok {
		return a.Name
	}
	return id
}

// Resolve drops anything that cannot be switched on (unknown, planned) and adds
// every transitive requirement, in catalog order. It returns the closure plus
// one note per automatically added add-on.
func Resolve(c Catalog, sel Selection) (Selection, []Note) {
	out := Selection{}
	for _, a := range c.Addons {
		if sel[a.ID] && a.Selectable() {
			out[a.ID] = true
		}
	}
	var notes []Note
	for changed := true; changed; {
		changed = false
		for _, a := range c.Addons {
			if !out[a.ID] {
				continue
			}
			for _, r := range a.Requires {
				d, ok := c.Find(r)
				if !ok || !d.Selectable() || out[r] {
					continue
				}
				out[r] = true
				changed = true
				notes = append(notes, Note{ID: r, Kind: "added", Because: []string{a.ID},
					Text: fmt.Sprintf("%s was added because %s needs it.", d.Name, a.Name)})
			}
		}
	}
	sort.SliceStable(notes, func(i, j int) bool { return indexOf(c, notes[i].ID) < indexOf(c, notes[j].ID) })
	return out, notes
}

func indexOf(c Catalog, id string) int {
	for i, a := range c.Addons {
		if a.ID == id {
			return i
		}
	}
	return len(c.Addons)
}

// Toggle switches one add-on and keeps the selection consistent: turning one on
// pulls in its requirements; turning one off turns off everything selected that
// (transitively) requires it. Both directions come back with notes to show.
func Toggle(c Catalog, sel Selection, id string, on bool) (Selection, []Note, error) {
	a, ok := c.Find(id)
	if !ok {
		return nil, nil, fmt.Errorf("unknown add-on %q", id)
	}
	next := sel.clone()
	if on {
		if !a.Selectable() {
			return nil, nil, fmt.Errorf("%s is planned and cannot be switched on yet", a.Name)
		}
		next[id] = true
		out, notes := Resolve(c, next)
		return out, notes, nil
	}
	delete(next, id)
	var notes []Note
	for changed := true; changed; {
		changed = false
		for _, b := range c.Addons {
			if !next[b.ID] {
				continue
			}
			for _, r := range b.Requires {
				if !next[r] {
					delete(next, b.ID)
					changed = true
					notes = append(notes, Note{ID: b.ID, Kind: "dropped", Because: []string{r},
						Text: fmt.Sprintf("%s was turned off because it needs %s.", b.Name, nameOf(c, r))})
					break
				}
			}
		}
	}
	out, _ := Resolve(c, next)
	return out, notes, nil
}

// Pull is one model to download after the stack is up.
type Pull struct {
	ID  string `json:"id"`
	Tag string `json:"tag"`
}

// Plan is a selection turned into what the existing machinery understands:
// launcher profiles and flags, .env values, and model pulls. Everything comes
// from the catalog; nothing is derived from the add-on's name.
type Plan struct {
	Profiles []string          `json:"profiles"`            // launcher --profile values, catalog order
	Flags    []string          `json:"flags"`               // launcher flags to turn ON
	FlagsOff []string          `json:"flagsOff"`            // launcher flags a deselected add-on owns
	EnvSet   map[string]string `json:"envSet"`              // .env keys to write
	EnvUnset []string          `json:"envUnset"`            // .env keys declared only by deselected add-ons
	Pulls    []Pull            `json:"pulls"`               // models to pull
	Turned   []string          `json:"turnedOff,omitempty"` // compose add-ons that were on and are now off
}

// BuildPlan maps a (resolved) selection to a Plan. current is what is installed
// now (nil on a fresh install); it only feeds Turned, which drives stopping.
func BuildPlan(c Catalog, sel, current Selection) Plan {
	p := Plan{EnvSet: map[string]string{}}
	seenP, seenF := map[string]bool{}, map[string]bool{}
	wantedEnv := map[string]bool{}
	for _, a := range c.Addons {
		if !sel[a.ID] || !a.Selectable() {
			continue
		}
		switch {
		case a.IsCompose():
			for _, pr := range a.Compose.Profiles {
				if !seenP[pr] {
					seenP[pr] = true
					p.Profiles = append(p.Profiles, pr)
				}
			}
			for _, f := range a.Compose.Flags {
				if !seenF[f] {
					seenF[f] = true
					p.Flags = append(p.Flags, f)
				}
			}
			for _, k := range sortedKeys(a.Compose.Env) {
				p.EnvSet[k] = a.Compose.Env[k]
				wantedEnv[k] = true
			}
		case a.IsModel() && a.Model != nil:
			p.Pulls = append(p.Pulls, Pull{ID: a.ID, Tag: *a.Model})
		}
	}
	seenU := map[string]bool{}
	for _, a := range c.Addons {
		if sel[a.ID] || !a.IsCompose() || !a.Selectable() {
			continue
		}
		for _, f := range a.Compose.Flags {
			if !seenF[f] && !contains(p.FlagsOff, f) {
				p.FlagsOff = append(p.FlagsOff, f)
			}
		}
		// A profile the launcher keeps as a choice of its own (and switches on by default for a
		// fresh install) needs the same: leaving it out of the profile list does not turn it off.
		for _, pr := range a.Compose.Profiles {
			if key := profileOffKey(pr); profileHasOffFlag[pr] && !seenP[pr] && !contains(p.FlagsOff, key) {
				p.FlagsOff = append(p.FlagsOff, key)
			}
		}
		for _, k := range sortedKeys(a.Compose.Env) {
			if !wantedEnv[k] && !seenU[k] {
				seenU[k] = true
				p.EnvUnset = append(p.EnvUnset, k)
			}
		}
		if current[a.ID] {
			p.Turned = append(p.Turned, a.ID)
		}
	}
	return p
}

// profileHasOffFlag lists the launcher profiles that are remembered as a choice (TOOLS in
// scripts/vegaduta) and have a --no-* switch. Their off entry in Plan.FlagsOff is
// profileOffKey(profile), which flagOff maps to that switch.
var profileHasOffFlag = map[string]bool{"tools": true}

func profileOffKey(profile string) string { return "profile:" + profile }

func sortedKeys(m map[string]string) []string {
	ks := make([]string, 0, len(m))
	for k := range m {
		ks = append(ks, k)
	}
	sort.Strings(ks)
	return ks
}

func contains(s []string, v string) bool {
	for _, x := range s {
		if x == v {
			return true
		}
	}
	return false
}

// Hardware is what Assess compares the selection with. Zero means unknown, and
// unknown never produces a warning (an invented shortfall is worse than none).
type Hardware struct {
	DockerMemMB int64 `json:"dockerMemMb"`
	HostRAMMB   int64 `json:"hostRamMb"`
	DiskFreeMB  int64 `json:"diskFreeMb"`
}

type Warning struct {
	Code string `json:"code"` // disk_short | docker_memory_short | model_memory_short | needs_gpu | unsupported
	ID   string `json:"id,omitempty"`
	Text string `json:"text"`
}

// Totals are the honest numbers behind the page's summary. All sizes are the
// catalog's approximations (on-disk image sizes, model download sizes).
type Totals struct {
	SelectedDiskMB int `json:"selectedDiskMb"` // every picked add-on, installed or not
	DownloadMB     int `json:"downloadMb"`     // picked and not installed yet
	DockerRAMMB    int `json:"dockerRamMb"`    // base personal stack + picked containers
	ModelRAMMB     int `json:"modelRamMb"`     // the largest picked model (models run one at a time)
	NeedDiskMB     int `json:"needDiskMb"`     // download + the base stack on a fresh install
}

func gb(mb int) string { return fmt.Sprintf("%.1f GB", float64(mb)/1024) }

// Assess sums sizes and compares them with the machine. fresh = no install yet,
// which adds the base stack's disk (tierNeeds, the same figure preflight uses).
func Assess(c Catalog, sel, current Selection, hw Hardware, fresh bool) (Totals, []Warning) {
	var t Totals
	baseMem, baseDisk, _ := tierNeeds("personal")
	t.DockerRAMMB = baseMem * 1024
	var warns []Warning
	for _, a := range c.Addons {
		if !sel[a.ID] || !a.Selectable() {
			continue
		}
		t.SelectedDiskMB += a.SizeMB
		if !current[a.ID] {
			t.DownloadMB += a.SizeMB
		}
		if a.IsModel() {
			if a.MinRAMMB > t.ModelRAMMB {
				t.ModelRAMMB = a.MinRAMMB
			}
			if hw.HostRAMMB > 0 && int64(a.MinRAMMB) > hw.HostRAMMB {
				warns = append(warns, Warning{Code: "model_memory_short", ID: a.ID, Text: fmt.Sprintf(
					"%s wants about %s of free memory; this computer has %s in total. It may not run, or will be very slow.",
					a.Name, gb(a.MinRAMMB), gb(int(hw.HostRAMMB)))})
			}
		} else {
			t.DockerRAMMB += a.MinRAMMB
		}
		if a.NeedsGPU {
			warns = append(warns, Warning{Code: "needs_gpu", ID: a.ID, Text: a.Name +
				" needs a graphics card; Setup cannot check whether this computer has one."})
		}
	}
	t.NeedDiskMB = t.DownloadMB
	if fresh {
		t.NeedDiskMB += baseDisk * 1024
	}
	if hw.DiskFreeMB > 0 && int64(t.NeedDiskMB) > hw.DiskFreeMB {
		warns = append(warns, Warning{Code: "disk_short", Text: fmt.Sprintf(
			"This needs about %s of disk and %s is free. Free some space, or pick fewer extras.",
			gb(t.NeedDiskMB), gb(int(hw.DiskFreeMB)))})
	}
	if hw.DockerMemMB > 0 && int64(t.DockerRAMMB) > hw.DockerMemMB {
		warns = append(warns, Warning{Code: "docker_memory_short", Text: fmt.Sprintf(
			"Worst case, the containers could use up to %s of memory and Docker has %s. Real usage is usually lower; to be safe, raise Docker's limit (Docker Desktop > Settings > Resources > Memory) or untick an extra.",
			gb(t.DockerRAMMB), gb(int(hw.DockerMemMB)))})
	}
	return t, warns
}

// Unsupported says why an add-on cannot be applied on this OS with this
// launcher, or "" when it can. The Windows launcher takes profiles but no flags.
func Unsupported(a Addon, goos string) string {
	if !a.IsCompose() || goos != "windows" {
		return ""
	}
	if len(a.Compose.Flags) > 0 {
		return "The Windows launcher cannot switch this on yet. Use macOS or Linux, or run it from a terminal in the install folder."
	}
	return ""
}

// StripUnsupported removes add-ons this OS cannot apply, so the plan never
// promises something the launcher will not do.
func StripUnsupported(c Catalog, sel Selection, goos string) Selection {
	out := sel.clone()
	for _, a := range c.Addons {
		if out[a.ID] && Unsupported(a, goos) != "" {
			delete(out, a.ID)
		}
	}
	return out
}

// ParseIDs splits "a, b,c" and rejects unknown or planned ids by name.
func ParseIDs(c Catalog, list string) (Selection, error) {
	sel := Selection{}
	for _, raw := range strings.Split(list, ",") {
		id := strings.TrimSpace(raw)
		if id == "" {
			continue
		}
		a, ok := c.Find(id)
		if !ok {
			return nil, fmt.Errorf("unknown extra %q (valid: %s)", id, strings.Join(selectableIDs(c), ", "))
		}
		if !a.Selectable() {
			return nil, fmt.Errorf("extra %q is planned and cannot be installed yet", id)
		}
		sel[id] = true
	}
	return sel, nil
}

func selectableIDs(c Catalog) []string {
	var ids []string
	for _, a := range c.Addons {
		if a.Selectable() {
			ids = append(ids, a.ID)
		}
	}
	return ids
}
