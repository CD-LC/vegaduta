// Native Ollama for personal installs: a free, open-weights model needs no
// account or key, and Ollama running on the computer itself can use the GPU
// (Apple Silicon, NVIDIA/AMD on Windows), which the Ollama container cannot on
// Docker Desktop. Where Ollama cannot be installed without admin rights
// (Linux, WSL) nothing happens here and the launcher runs the container.
package main

import (
	"context"
	"net/http"
	"os"
	"os/exec"
	"runtime"
	"time"
)

var ollamaURL = "http://127.0.0.1:11434/api/tags" // a var so tests can point it at a fake server

// ollamaUp reports whether an Ollama server answers on this computer.
func ollamaUp(ctx context.Context) bool {
	c, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(c, http.MethodGet, ollamaURL, nil)
	if err != nil {
		return false
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return false
	}
	resp.Body.Close()
	return resp.StatusCode == http.StatusOK
}

// ensureOllama installs and starts Ollama on macOS and Windows when it is not
// running. It never fails the install: without it the launcher falls back to
// the Ollama container, which downloads the same model.
func ensureOllama(ctx context.Context, emit func(Event)) {
	if runtime.GOOS != "darwin" && runtime.GOOS != "windows" {
		return
	}
	if ollamaUp(ctx) {
		emit(Event{Line: "Ollama is running on this computer - agents will use it (free, no account)."})
		return
	}
	line := func(s string) { emit(Event{Line: s}) }
	if !ollamaInstalled() {
		line("Installing Ollama, which runs free open AI models on this computer (no account or subscription)…")
		if err := ollamaInstall(ctx, emit); err != nil {
			line("Could not install Ollama (" + err.Error() + ") - the model will run in a Docker container instead.")
			return
		}
		augmentPath()
	}
	line("Starting Ollama…")
	ollamaStart(ctx)
	deadline := time.Now().Add(2 * time.Minute)
	for time.Now().Before(deadline) {
		if ollamaUp(ctx) {
			line("Ollama is running.")
			return
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(3 * time.Second):
		}
	}
	line("Ollama did not start in 2 minutes - the model will run in a Docker container instead.")
}

func ollamaInstalled() bool {
	if _, err := exec.LookPath("ollama"); err == nil {
		return true
	}
	for _, p := range ollamaAppPaths() {
		if _, err := os.Stat(p); err == nil {
			return true
		}
	}
	return false
}

func ollamaInstall(ctx context.Context, emit func(Event)) error {
	if runtime.GOOS == "windows" {
		// The Ollama installer is per-user: no administrator prompt.
		return streamCmd(ctx, emit, "winget", "install", "-e", "--id", "Ollama.Ollama", "--silent",
			"--accept-source-agreements", "--accept-package-agreements")
	}
	if _, err := exec.LookPath("brew"); err != nil {
		return errNoBrew
	}
	return streamCmdEnv(ctx, emit, []string{"HOMEBREW_NO_AUTO_UPDATE=1", "HOMEBREW_NO_ENV_HINTS=1"},
		"brew", "install", "ollama")
}

// ollamaStart launches the server in the background; Ollama keeps it running
// at sign-in from then on (the Windows app and brew services both do).
func ollamaStart(ctx context.Context) {
	if runtime.GOOS == "windows" {
		for _, p := range ollamaAppPaths() {
			if _, err := os.Stat(p); err == nil {
				_ = exec.CommandContext(ctx, "powershell", "-NoProfile", "-Command",
					`Start-Process -FilePath "`+p+`" -WindowStyle Hidden`).Run()
				return
			}
		}
		return
	}
	if _, err := os.Stat("/Applications/Ollama.app"); err == nil {
		_ = exec.CommandContext(ctx, "open", "-g", "-a", "Ollama").Run()
		return
	}
	_ = exec.CommandContext(ctx, "brew", "services", "start", "ollama").Run()
}

func ollamaAppPaths() []string {
	if runtime.GOOS == "windows" {
		if d := os.Getenv("LOCALAPPDATA"); d != "" {
			return []string{d + `\Programs\Ollama\ollama app.exe`}
		}
		return nil
	}
	return []string{"/Applications/Ollama.app"}
}
