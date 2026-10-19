// Docker bootstrap: what the wizard can do for the two most common failures -
// Docker is not installed, or it is installed but the daemon is not running.
// Anything that needs admin rights we cannot get non-interactively is emitted
// as an instruction line instead of pretending to work.
package main

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"time"
)

// dockerInstall runs the best automatable install path for this OS and
// reports each step to emit. Admin elevation is never performed silently.
func dockerInstall(ctx context.Context, emit func(Event)) error {
	line := func(s string) { emit(Event{Line: s}) }
	switch runtime.GOOS {
	case "darwin":
		// Docker Desktop already present? Skip brew: upgrading the cask removes
		// privileged helpers through sudo, which cannot prompt for a password
		// here ("sudo: a terminal is required") and fails the whole step on a
		// machine that was already fine. Just start it.
		if _, err := os.Stat("/Applications/Docker.app"); err == nil {
			line("Docker Desktop is already installed - starting it…")
			return dockerStart(ctx, emit)
		}
		if _, err := exec.LookPath("brew"); err == nil {
			line("Installing Docker Desktop via Homebrew (brew install --cask docker-desktop)…")
			// NO_AUTO_UPDATE keeps a brew upgrade of itself out of the log (it
			// can take minutes and looks like a stall); env hints are noise.
			if err := streamCmdEnv(ctx, emit,
				[]string{"HOMEBREW_NO_AUTO_UPDATE=1", "HOMEBREW_NO_ENV_HINTS=1"},
				"brew", "install", "--cask", "docker-desktop"); err != nil {
				line("brew could not finish. If it stopped on leftover files from an older Docker")
				line("install, clear them in Terminal and let the wizard retry:")
				line("  sudo rm -rf /opt/homebrew/Caskroom/docker-desktop /Library/PrivilegedHelperTools/com.docker.* /usr/local/cli-plugins")
				line("Or install Docker Desktop by hand: https://docs.docker.com/desktop/install/mac-install/")
				// A cask failure can still leave a usable Docker.app behind.
				if _, serr := os.Stat("/Applications/Docker.app"); serr == nil {
					line("Docker Desktop is on disk anyway - trying to start it…")
					return dockerStart(ctx, emit)
				}
				return fmt.Errorf("brew install failed: %w", err)
			}
			line("Docker Desktop installed - starting it…")
			return dockerStart(ctx, emit)
		}
		line("Homebrew is not installed. Download Docker Desktop from https://docs.docker.com/desktop/install/mac-install/ - the wizard detects it once the engine is running.")
		return nil
	case "windows":
		if _, err := exec.LookPath("winget"); err != nil {
			line("winget is not available (needs Windows 10 1809+ with App Installer). Download Docker Desktop from https://docs.docker.com/desktop/install/windows-install/")
			return nil
		}
		line("Installing Docker Desktop via winget…")
		if err := streamCmd(ctx, emit, "winget", "install", "-e", "--id", "Docker.DockerDesktop",
			"--accept-source-agreements", "--accept-package-agreements"); err != nil {
			return fmt.Errorf("winget failed: %w (WSL2 may need enabling - run 'wsl --install' in an admin PowerShell, reboot, then start Docker Desktop)", err)
		}
		line("Docker Desktop installed. Start it from the Start menu; first launch can take a minute and may ask to finish WSL2 setup.")
		return nil
	default:
		line("On Linux the engine install needs root. In a terminal run:")
		line("  curl -fsSL https://get.docker.com | sudo sh   # then: sudo usermod -aG docker $USER, log out and back in")
		return nil
	}
}

// dockerStart tries to bring the daemon up and waits until `docker info`
// answers (or ~3 minutes, which covers Docker Desktop's first boot).
func dockerStart(ctx context.Context, emit func(Event)) error {
	line := func(s string) { emit(Event{Line: s}) }
	switch runtime.GOOS {
	case "darwin":
		line("Starting Docker Desktop…")
		exec.CommandContext(ctx, "open", "-a", "Docker").Run()
	case "windows":
		exec.CommandContext(ctx, "powershell", "-NoProfile", "-Command",
			`Start-Process "$env:ProgramFiles\Docker\Docker\Docker Desktop.exe" -ErrorAction SilentlyContinue`).Run()
	default:
		if err := exec.CommandContext(ctx, "systemctl", "--user", "start", "docker-desktop").Run(); err != nil {
			line("Could not start Docker without admin rights. Run: sudo systemctl start docker")
		}
	}
	deadline := time.Now().Add(3 * time.Minute)
	for time.Now().Before(deadline) {
		if exec.CommandContext(ctx, "docker", "info").Run() == nil {
			line("Docker engine is running.")
			return nil
		}
		line("waiting for the Docker engine…")
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(5 * time.Second):
		}
	}
	return fmt.Errorf("the Docker engine did not come up in 3 minutes - open Docker Desktop and check its status")
}

// streamCmd runs a command, forwarding output lines as events.
func streamCmd(ctx context.Context, emit func(Event), name string, args ...string) error {
	return streamCmdEnv(ctx, emit, nil, name, args...)
}

// streamCmdEnv is streamCmd with extra environment (e.g. HOMEBREW_NO_*).
func streamCmdEnv(ctx context.Context, emit func(Event), env []string, name string, args ...string) error {
	cmd := exec.CommandContext(ctx, name, args...)
	if len(env) > 0 {
		cmd.Env = append(os.Environ(), env...)
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return err
	}
	cmd.Stderr = cmd.Stdout
	if err := cmd.Start(); err != nil {
		return err
	}
	scanLines(stdout, func(s string) {
		if t := strings.TrimSpace(s); t != "" {
			emit(Event{Line: t})
		}
	})
	return cmd.Wait()
}
