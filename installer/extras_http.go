// The wizard's Extras endpoints. The page is thin: every rule (dependencies,
// totals, warnings) is computed here from the catalog, so the page, --extras
// and the install can never disagree.
package main

import (
	"context"
	"encoding/json"
	"net/http"
	"os/exec"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"
)

type extraItem struct {
	ID          string   `json:"id"`
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Category    string   `json:"category"`
	Kind        string   `json:"kind"`
	SizeMB      int      `json:"sizeMb"`
	MinRAMMB    int      `json:"minRamMb"`
	NeedsGPU    bool     `json:"needsGpu"`
	License     string   `json:"license"`
	Free        bool     `json:"free"`
	Requires    []string `json:"requires"`
	RequiresNm  []string `json:"requiresNames"`
	Selectable  bool     `json:"selectable"`
	Installed   bool     `json:"installed"`
	Unsupported string   `json:"unsupported,omitempty"`
	LearnMore   string   `json:"learnMore,omitempty"`
}

type extrasView struct {
	Applicable bool        `json:"applicable"`
	Error      string      `json:"error,omitempty"`
	Installed  bool        `json:"installed"`
	Source     string      `json:"source,omitempty"`
	OS         string      `json:"os"`
	Items      []extraItem `json:"items"`
	Selected   []string    `json:"selected"`
	Notes      []Note      `json:"notes"`
	Totals     Totals      `json:"totals"`
	Warnings   []Warning   `json:"warnings"`
	Plan       Plan        `json:"plan"`
	Hardware   Hardware    `json:"hardware"`
	Kept       string      `json:"kept"`
}

const keptCopy = "Switching an extra off only stops it. Your chats, documents, projects, saved sign-ins and downloaded models are never deleted."

var hwCache struct {
	sync.Mutex
	at  time.Time
	dir string
	hw  Hardware
}

// readHardware measures the machine; cached briefly because the page asks on
// every tick of a checkbox. Unknown values stay 0 (no warning is raised on 0).
func readHardware(dir string) Hardware {
	hwCache.Lock()
	defer hwCache.Unlock()
	if hwCache.dir == dir && time.Since(hwCache.at) < 20*time.Second {
		return hwCache.hw
	}
	hw := Hardware{HostRAMMB: hostRAMMB(), DiskFreeMB: int64(diskFreeGB(existingDir(dir))) * 1024}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if out, err := exec.CommandContext(ctx, "docker", "info", "--format", "{{.MemTotal}}").Output(); err == nil {
		if n, err := strconv.ParseInt(strings.TrimSpace(string(out)), 10, 64); err == nil {
			hw.DockerMemMB = n / (1 << 20)
		}
	}
	hwCache.dir, hwCache.at, hwCache.hw = dir, time.Now(), hw
	return hw
}

func installRoot(c config) string {
	if c.from != "" {
		return c.from
	}
	return c.dir
}

func buildExtrasView(c config, cat Catalog, cur Current, sel Selection, notes []Note, hw Hardware) extrasView {
	v := extrasView{Applicable: true, Installed: cur.Installed, Source: cur.Source, OS: runtime.GOOS,
		Notes: notes, Hardware: hw, Kept: keptCopy, Selected: sel.IDs(cat)}
	if v.Notes == nil {
		v.Notes = []Note{}
	}
	if v.Selected == nil {
		v.Selected = []string{}
	}
	for _, a := range cat.Addons {
		it := extraItem{ID: a.ID, Name: a.Name, Description: a.Description, Category: a.Category, Kind: a.Kind,
			SizeMB: a.SizeMB, MinRAMMB: a.MinRAMMB, NeedsGPU: a.NeedsGPU, License: a.License, Free: a.Free,
			Requires: a.Requires, Selectable: a.Selectable(), Installed: cur.Sel[a.ID],
			Unsupported: Unsupported(a, runtime.GOOS), LearnMore: a.LearnMore}
		if it.Requires == nil {
			it.Requires = []string{}
		}
		for _, r := range a.Requires {
			it.RequiresNm = append(it.RequiresNm, nameOf(cat, r))
		}
		if it.RequiresNm == nil {
			it.RequiresNm = []string{}
		}
		v.Items = append(v.Items, it)
	}
	eff := StripUnsupported(cat, sel, runtime.GOOS)
	v.Plan = BuildPlan(cat, eff, cur.Sel)
	v.Totals, v.Warnings = Assess(cat, eff, cur.Sel, hw, !cur.Installed)
	if v.Warnings == nil {
		v.Warnings = []Warning{}
	}
	return v
}

func registerExtras(mux *http.ServeMux, c config) {
	view := func(sel func(Catalog, Current) (Selection, []Note, error)) (extrasView, int, string) {
		if c.tier != "personal" {
			return extrasView{Applicable: false, OS: runtime.GOOS, Items: []extraItem{}, Selected: []string{},
				Notes: []Note{}, Warnings: []Warning{}}, 200, ""
		}
		cat, err := loadCatalog()
		if err != nil {
			return extrasView{Applicable: false, Error: err.Error(), OS: runtime.GOOS, Items: []extraItem{},
				Selected: []string{}, Notes: []Note{}, Warnings: []Warning{}}, 200, ""
		}
		cur := DetectCurrent(installRoot(c), cat)
		s, notes, err := sel(cat, cur)
		if err != nil {
			return extrasView{}, 400, err.Error()
		}
		return buildExtrasView(c, cat, cur, s, notes, readHardware(installRoot(c))), 200, ""
	}
	mux.HandleFunc("GET /api/extras", func(w http.ResponseWriter, r *http.Request) {
		v, _, _ := view(func(cat Catalog, cur Current) (Selection, []Note, error) {
			return initialSelection(c, cat, cur), nil, nil
		})
		writeJSON(w, v)
	})
	mux.HandleFunc("POST /api/extras/plan", func(w http.ResponseWriter, r *http.Request) {
		var body struct {
			Selected []string `json:"selected"`
			Toggle   *struct {
				ID string `json:"id"`
				On bool   `json:"on"`
			} `json:"toggle"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<16)).Decode(&body); err != nil {
			http.Error(w, "bad request", http.StatusBadRequest)
			return
		}
		v, code, msg := view(func(cat Catalog, cur Current) (Selection, []Note, error) {
			sel, err := ParseIDs(cat, strings.Join(body.Selected, ","))
			if err != nil {
				return nil, nil, err
			}
			if body.Toggle != nil {
				return Toggle(cat, sel, body.Toggle.ID, body.Toggle.On)
			}
			out, notes := Resolve(cat, sel)
			return out, notes, nil
		})
		if code != 200 {
			http.Error(w, msg, code)
			return
		}
		writeJSON(w, v)
	})
}

// selectionFromPage validates the list the page sends with /api/install.
func selectionFromPage(ids []string) (Selection, error) {
	cat, err := loadCatalog()
	if err != nil {
		return nil, err
	}
	return ParseIDs(cat, strings.Join(ids, ","))
}
