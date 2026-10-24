// Downloading the model packs a person ticked in the Extras step, after the
// stack is up.
//
// POSIX asks the launcher (`scripts/vegaduta models pull TAG`). vegaduta.ps1
// has no `models` command (its unknown-command branch prints help and exits 0,
// which looks exactly like a successful pull), so on Windows Setup talks to the
// same Ollama the launcher set up: the one on this computer through its HTTP
// API, or the compose `ollama` container when the install's state says it runs
// the model there. A pull only counts when Ollama says it finished.
package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"strings"
)

// PullModels downloads the picked models and returns the ones that did not
// arrive. A failed pull is reported loudly and does not fail the install (the
// stack is fine and the console's Extras page can retry), but the caller must
// not record the pack as installed.
func PullModels(ctx context.Context, root, goos string, pulls []Pull, emit func(Event)) (failed []Pull) {
	for _, p := range pulls {
		emit(Event{Phase: "models", Line: "Downloading model " + p.Tag + " (this can take a while)"})
		var err error
		retry := "run: scripts/vegaduta models pull " + p.Tag
		if goos == "windows" {
			err = pullNative(ctx, root, p.Tag, emit)
			retry = "press Install again, or run: ollama pull " + p.Tag
		} else {
			err = runCLI(ctx, root, func(s string) { emit(Event{Line: s}) }, "models", "pull", p.Tag)
		}
		if err != nil {
			failed = append(failed, p)
			emit(Event{Line: "Could not download " + p.Tag + ": " + err.Error() +
				". The install itself is fine, but the model is NOT installed. Turn it on later from the console (Extras) or " + retry})
		}
	}
	return failed
}

// pullViaContainer runs the pull inside the compose `ollama` container; a var
// so tests do not need Docker.
var pullViaContainer = pullInOllamaContainer

// pullNative downloads tag into the Ollama the install uses: the container when
// the launcher's state says the model runs there, else the one on this computer.
func pullNative(ctx context.Context, root, tag string, emit func(Event)) error {
	mode := ""
	if b, err := os.ReadFile(statePath(root)); err == nil {
		mode = parseKV(string(b))["local_ai"]
	}
	if mode == "container" {
		return pullViaContainer(ctx, root, tag, emit)
	}
	if !ollamaUp(ctx) {
		return errors.New("Ollama is not running on this computer, and this install does not run it in a container. " +
			"Start Ollama (https://ollama.com/download)")
	}
	return pullViaOllamaHTTP(ctx, strings.TrimSuffix(ollamaURL, "/api/tags"), tag, emit)
}

// pullViaOllamaHTTP streams POST /api/pull and succeeds only on Ollama's final
// {"status":"success"}; an {"error":...} line or a stream that just ends fails.
func pullViaOllamaHTTP(ctx context.Context, base, tag string, emit func(Event)) error {
	body, _ := json.Marshal(map[string]any{"model": tag, "stream": true})
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, base+"/api/pull", bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := http.DefaultClient.Do(req) // no overall timeout: a model is gigabytes; ctx cancels it
	if err != nil {
		return fmt.Errorf("Ollama did not answer: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 2048))
		return fmt.Errorf("Ollama refused the download (HTTP %d): %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	sc := bufio.NewScanner(resp.Body)
	sc.Buffer(make([]byte, 64*1024), 1024*1024)
	var lastStatus string
	lastPct := -1
	done := false
	for sc.Scan() {
		var m struct {
			Status    string `json:"status"`
			Error     string `json:"error"`
			Total     int64  `json:"total"`
			Completed int64  `json:"completed"`
		}
		if json.Unmarshal(sc.Bytes(), &m) != nil {
			continue
		}
		if m.Error != "" {
			return errors.New(m.Error)
		}
		if m.Status == "success" {
			done = true
		}
		pct := -1
		if m.Total > 0 {
			pct = int(m.Completed * 100 / m.Total)
		}
		// One line per step, and one per 10% of a layer: the log is read by a person.
		if m.Status != lastStatus || (pct >= 0 && pct/10 != lastPct/10) {
			line := m.Status
			if pct >= 0 {
				line = fmt.Sprintf("%s: %d%%", m.Status, pct)
			}
			emit(Event{Line: line})
			lastStatus, lastPct = m.Status, pct
		}
	}
	if err := sc.Err(); err != nil {
		return fmt.Errorf("the download was interrupted: %w", err)
	}
	if !done {
		return errors.New("Ollama closed the download before it finished")
	}
	return nil
}

var ansiRe = regexp.MustCompile(`\x1b\[[0-9;?]*[A-Za-z]`)

func pullInOllamaContainer(ctx context.Context, root, tag string, emit func(Event)) error {
	cid := ""
	for _, dir := range workingDirs(root) {
		out, err := exec.CommandContext(ctx, "docker", "ps", "-q",
			"--filter", "label=com.docker.compose.service=ollama",
			"--filter", "label=com.docker.compose.project.working_dir="+dir).Output()
		if err != nil {
			return fmt.Errorf("could not look for the Ollama container: %w", err)
		}
		if f := strings.Fields(string(out)); len(f) > 0 {
			cid = f[0]
			break
		}
	}
	if cid == "" {
		return errors.New("the Ollama container is not running")
	}
	return streamCmd(ctx, func(e Event) {
		if l := strings.TrimSpace(ansiRe.ReplaceAllString(e.Line, "")); l != "" {
			emit(Event{Line: l})
		}
	}, "docker", "exec", cid, "ollama", "pull", tag)
}
