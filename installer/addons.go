// The add-on catalog the Extras step is built from. The catalog is NOT written
// here: addons/catalog.json is a byte-identical copy of the one core serves
// (core/providers/src/main/resources/addons/catalog.json), embedded so Setup
// works offline, and TestCatalogMatchesCore fails when the two drift. Refresh
// the copy with installer/sync-addons-catalog.sh.
//
// The file has fields Setup does not use (observe, hostCommand, unlocks...):
// they are ignored on purpose, so core can extend the catalog without breaking
// an older Setup. What Setup does use is validated before anything reaches a
// command line or .env, because values from the catalog are written unquoted
// into deploy/compose/.env and passed to the launcher.
package main

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"
)

//go:embed addons/catalog.json
var embeddedCatalog []byte

// catalogSchema is the schemaVersion this Setup understands.
const catalogSchema = 1

type Addon struct {
	ID           string       `json:"id"`
	Name         string       `json:"name"`
	Description  string       `json:"description"`
	Category     string       `json:"category"`
	Kind         string       `json:"kind"` // compose-profile | model-pull | mcp-server | planned
	Availability string       `json:"availability"`
	SizeMB       int          `json:"sizeMb"`
	MinRAMMB     int          `json:"minRamMb"`
	NeedsGPU     bool         `json:"needsGpu"`
	License      string       `json:"license"`
	Free         bool         `json:"free"`
	Requires     []string     `json:"requires"`
	DefaultOn    bool         `json:"defaultOn"`
	HostCommand  string       `json:"hostCommand"`
	LearnMore    string       `json:"learnMore"`
	Model        *string      `json:"model"`
	Compose      *ComposeSpec `json:"compose"`
}

// ComposeSpec is what a compose-profile add-on changes in the personal stack.
type ComposeSpec struct {
	Profiles []string          `json:"profiles"`
	Flags    []string          `json:"flags"`
	Env      map[string]string `json:"env"`
}

type Catalog struct {
	SchemaVersion int     `json:"schemaVersion"`
	Addons        []Addon `json:"addons"`
}

// Selectable reports whether a person can switch the add-on on today.
func (a Addon) Selectable() bool { return a.Availability == "available" && a.Kind != "planned" }

// IsModel reports a model-pull add-on (weights pulled into Ollama).
func (a Addon) IsModel() bool { return a.Kind == "model-pull" }

// IsCompose reports an add-on realised by the launcher (profiles, flags, .env).
func (a Addon) IsCompose() bool { return a.Kind == "compose-profile" && a.Compose != nil }

func (c Catalog) Find(id string) (Addon, bool) {
	for _, a := range c.Addons {
		if a.ID == id {
			return a, true
		}
	}
	return Addon{}, false
}

// Vocabulary the personal launcher accepts. A catalog entry naming anything
// else is refused rather than passed on: Setup never invents profile names.
var (
	launcherProfiles = map[string]bool{"rag": true, "workspace": true, "tools": true, "browser": true}
	launcherFlags    = map[string]bool{"--search": true, "--browser": true}

	idRe    = regexp.MustCompile(`^[a-z][a-z0-9-]{1,62}$`)
	envKey  = regexp.MustCompile(`^[A-Z][A-Z0-9_]{0,63}$`)
	envVal  = regexp.MustCompile(`^[A-Za-z0-9._:/+-]{1,128}$`)
	modelRe = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}$`)
)

// ParseCatalog decodes and validates a catalog. It reports every problem, not
// just the first, so a broken sync is fixed in one pass.
func ParseCatalog(data []byte) (Catalog, error) {
	var c Catalog
	if err := json.Unmarshal(data, &c); err != nil {
		return Catalog{}, fmt.Errorf("add-on catalog is not valid JSON: %w", err)
	}
	var problems []string
	bad := func(format string, a ...any) { problems = append(problems, fmt.Sprintf(format, a...)) }
	if c.SchemaVersion != catalogSchema {
		bad("schemaVersion %d is not supported by this Setup (it reads %d); update Setup", c.SchemaVersion, catalogSchema)
	}
	seen := map[string]bool{}
	for _, a := range c.Addons {
		if !idRe.MatchString(a.ID) || a.ID == "host" {
			bad("add-on id %q is not valid", a.ID)
			continue
		}
		if seen[a.ID] {
			bad("duplicate add-on id %q", a.ID)
		}
		seen[a.ID] = true
		switch a.Kind {
		case "compose-profile", "model-pull", "mcp-server", "planned":
		default:
			bad("%s: unknown kind %q", a.ID, a.Kind)
		}
		if (a.Kind == "planned") != (a.Availability == "planned") {
			bad("%s: kind %q and availability %q disagree", a.ID, a.Kind, a.Availability)
		}
		if a.Availability != "available" && a.Availability != "planned" {
			bad("%s: unknown availability %q", a.ID, a.Availability)
		}
		if a.SizeMB < 0 || a.MinRAMMB < 0 {
			bad("%s: negative size or memory", a.ID)
		}
		if a.Selectable() && a.DefaultOn && a.IsModel() {
			bad("%s: a model pack cannot be on by default", a.ID)
		}
		switch a.Kind {
		case "compose-profile":
			if a.Compose == nil {
				bad("%s: compose-profile without compose", a.ID)
				break
			}
			for _, p := range a.Compose.Profiles {
				if !launcherProfiles[p] {
					bad("%s: %q is not a launcher profile", a.ID, p)
				}
			}
			for _, f := range a.Compose.Flags {
				if !launcherFlags[f] {
					bad("%s: %q is not a launcher flag Setup knows", a.ID, f)
				}
			}
			for k, v := range a.Compose.Env {
				if !envKey.MatchString(k) || !envVal.MatchString(v) {
					bad("%s: env %s=%q is not allowed in .env", a.ID, k, v)
				}
			}
		case "model-pull":
			if a.Model == nil || !modelRe.MatchString(*a.Model) {
				bad("%s: model-pull without a valid model tag", a.ID)
			}
		}
	}
	// Dependencies: known ids, no self reference, no cycles, no available entry
	// that needs a planned one.
	for _, a := range c.Addons {
		for _, r := range a.Requires {
			d, ok := c.Find(r)
			switch {
			case !ok:
				bad("%s requires unknown add-on %q", a.ID, r)
			case r == a.ID:
				bad("%s requires itself", a.ID)
			case a.Selectable() && !d.Selectable():
				bad("%s requires %s, which is planned", a.ID, r)
			}
		}
	}
	if len(problems) == 0 {
		if cyc := findCycle(c); cyc != "" {
			bad("dependency cycle through %s", cyc)
		}
	}
	// Two add-ons may set one .env key only to the same value.
	vals := map[string]string{}
	for _, a := range c.Addons {
		if a.Compose == nil {
			continue
		}
		for k, v := range a.Compose.Env {
			if old, ok := vals[k]; ok && old != v {
				bad("%s: env %s conflicts with another add-on (%q vs %q)", a.ID, k, old, v)
			}
			vals[k] = v
		}
	}
	if len(problems) > 0 {
		return Catalog{}, fmt.Errorf("add-on catalog rejected: %s", strings.Join(problems, "; "))
	}
	return c, nil
}

func findCycle(c Catalog) string {
	state := map[string]int{} // 1 visiting, 2 done
	var visit func(id string) string
	visit = func(id string) string {
		switch state[id] {
		case 1:
			return id
		case 2:
			return ""
		}
		state[id] = 1
		a, _ := c.Find(id)
		for _, r := range a.Requires {
			if hit := visit(r); hit != "" {
				return hit
			}
		}
		state[id] = 2
		return ""
	}
	for _, a := range c.Addons {
		if hit := visit(a.ID); hit != "" {
			return hit
		}
	}
	return ""
}

// loadCatalog parses the embedded copy.
func loadCatalog() (Catalog, error) { return ParseCatalog(embeddedCatalog) }
