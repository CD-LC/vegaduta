// Preflight mirrors `scripts/vegaduta doctor` at the machine level: is Docker
// here, is the daemon up, is compose new enough, does the VM have the memory
// and disk the chosen tier wants, are the ports the stack publishes free.
// The script stays the source of truth for the .env/secrets/URL checks; these
// are the ones a wizard has to answer before anything is downloaded.
package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

type Check struct {
	Name   string `json:"name"`
	OK     bool   `json:"ok"`
	Warn   bool   `json:"warn"`
	Detail string `json:"detail"`
	Fix    string `json:"fix,omitempty"`
}

type Report struct {
	Checks []Check `json:"checks"`
}

func (r Report) Failures() int {
	n := 0
	for _, c := range r.Checks {
		if !c.OK && !c.Warn {
			n++
		}
	}
	return n
}

func (r Report) Blocking() bool { return r.Failures() > 0 }

// Per-tier requirements, consistent with `doctor`: lite wants ~6 GB of Docker
// memory and 15 GB of disk, full 12/40. personal (feat/pa-runtime) is sized
// ~2.4 GB of container limits, so 4 GB of Docker memory and 8 GB of disk.
func tierNeeds(tier string) (memGB, diskGB int, ports []int) {
	switch tier {
	case "full":
		return 12, 40, []int{3000, 8080, 8180, 5432, 6379}
	case "personal":
		return 4, 8, []int{3000, 8080}
	default:
		return 6, 15, []int{3000, 8080, 8180}
	}
}

func runPreflight(c config) Report {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	var rep Report
	add := func(ch Check) { rep.Checks = append(rep.Checks, ch) }

	if _, err := exec.LookPath("docker"); err != nil {
		add(Check{Name: "Docker installed", Warn: true, Detail: "not installed yet",
			Fix: "Setup installs it for you when you click Install."})
		return rep // everything else needs the daemon
	}
	add(Check{Name: "Docker installed", OK: true})

	out, err := exec.CommandContext(ctx, "docker", "info", "--format", "{{.ServerVersion}} {{.MemTotal}}").Output()
	if err != nil {
		add(Check{Name: "Docker running", Warn: true, Detail: "not running yet",
			Fix: "Setup starts it for you when you click Install."})
		return rep
	}
	fields := strings.Fields(strings.TrimSpace(string(out)))
	memBytes, _ := strconv.ParseInt(fields[len(fields)-1], 10, 64)
	add(Check{Name: "Docker running", OK: true, Detail: "server " + fields[0]})

	cv, err := exec.CommandContext(ctx, "docker", "compose", "version", "--short").Output()
	if err != nil {
		add(Check{Name: "Compose v2", Detail: "docker compose plugin not found",
			Fix: "Install a current Docker Desktop / docker-compose-plugin (need >= 2.24)."})
	} else if ver := strings.TrimPrefix(strings.TrimSpace(string(cv)), "v"); versionGE(ver, "2.24.0") {
		add(Check{Name: "Compose v2", OK: true, Detail: "docker compose " + ver})
	} else {
		add(Check{Name: "Compose v2", Detail: "docker compose " + ver + " is too old",
			Fix: "Upgrade Docker Desktop or the compose plugin (need >= 2.24)."})
	}

	needGB, diskGB, ports := tierNeeds(c.tier)
	// The stack publishes *_HOST_PORT, not the defaults: honor overrides the
	// same way doctor does, so a moved port does not fail the check forever.
	if p := portEnv("WEB_HOST_PORT"); p > 0 {
		ports[0] = p
	}
	if p := portEnv("CORE_HOST_PORT"); p > 0 && len(ports) > 1 {
		ports[1] = p
	}
	memGB := memBytes / (1 << 30)
	if int(memGB) >= needGB {
		add(Check{Name: "Memory", OK: true, Detail: fmt.Sprintf("Docker has %d GB (>= %d GB for %s)", memGB, needGB, c.tier)})
	} else {
		fix := dockerMemoryHint()
		if runtime.GOOS == "windows" {
			fix = "Setup gives Docker more memory for you when you click Install, if this computer has it to spare."
		}
		add(Check{Name: "Memory", Warn: true, Detail: fmt.Sprintf("Docker has %d GB; %s wants >= %d GB", memGB, c.tier, needGB),
			Fix: fix})
	}

	freeGB := int64(diskFreeGB(existingDir(c.dir)))
	if freeGB >= int64(diskGB) {
		add(Check{Name: "Disk", OK: true, Detail: fmt.Sprintf("%d GB free", freeGB)})
	} else {
		add(Check{Name: "Disk", Warn: true, Detail: fmt.Sprintf("only %d GB free; %s wants >= %d GB for images and data", freeGB, c.tier, diskGB)})
	}

	// A port held by a container of this stack (name vegaduta-*/vegaduta-lite-*)
	// is our own running install answering, not a conflict - the wizard is often
	// re-run against a live machine, and the busy ports check is about OTHER apps.
	busy := busyPorts(ports)
	if own := vegadutaHeldPorts(ctx); len(own) > 0 {
		var other []int
		for _, p := range busy {
			if !own[p] {
				other = append(other, p)
			}
		}
		if len(other) == 0 {
			add(Check{Name: "Ports", OK: true,
				Detail: "ports " + ints(busy) + " are published by the running VegaDūta stack (this machine's own install)"})
			return rep
		}
		busy = other
	}
	if len(busy) == 0 {
		add(Check{Name: "Ports", OK: true, Detail: fmt.Sprintf("ports free: %s", ints(ports))})
	} else {
		add(Check{Name: "Ports", Detail: fmt.Sprintf("already in use: %s", ints(busy)),
			Fix: "Stop the app using that port, or change the *_HOST_PORT values in .env after install."})
	}
	return rep
}

// vegadutaHeldPorts maps the host ports our own compose stacks currently publish.
func vegadutaHeldPorts(ctx context.Context) map[int]bool {
	out, err := exec.CommandContext(ctx, "docker", "ps",
		"--format", "{{.Names}}\t{{.Ports}}").Output()
	if err != nil {
		return nil
	}
	held := map[int]bool{}
	for _, line := range strings.Split(strings.TrimSpace(string(out)), "\n") {
		name, ports, ok := strings.Cut(line, "\t")
		if !ok || !strings.HasPrefix(name, "vegaduta") {
			continue
		}
		for _, pub := range strings.Split(ports, ",") {
			// e.g. "127.0.0.1:3000->3000/tcp" or ":::3000->3000/tcp"
			if i := strings.Index(pub, "->"); i >= 0 {
				host := strings.TrimSpace(pub[:i])
				if j := strings.LastIndex(host, ":"); j >= 0 {
					if n, err := strconv.Atoi(host[j+1:]); err == nil {
						held[n] = true
					}
				}
			}
		}
	}
	return held
}

// existingDir walks up to the first ancestor that exists: the install dir
// itself usually does not yet, and Statfs needs a real path.
func existingDir(dir string) string {
	for {
		if st, err := os.Stat(dir); err == nil && st.IsDir() {
			return dir
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			return dir
		}
		dir = parent
	}
}

func portEnv(key string) int {
	n, _ := strconv.Atoi(os.Getenv(key))
	return n
}

func busyPorts(ports []int) []int {
	var busy []int
	for _, p := range ports {
		addr := net.JoinHostPort("127.0.0.1", strconv.Itoa(p))
		conn, err := net.DialTimeout("tcp", addr, 300*time.Millisecond)
		if err == nil {
			conn.Close()
			busy = append(busy, p)
			continue
		}
		// Nothing answers, but the port may still be refused: Windows reserves
		// ranges for Hyper-V/WinNAT, and Docker then cannot publish there.
		if l, err := net.Listen("tcp", addr); err != nil {
			busy = append(busy, p)
		} else {
			l.Close()
		}
	}
	return busy
}

// versionGE reports whether a dotted version a is >= b ("5.5.1" >= "2.24.0").
func versionGE(a, b string) bool {
	pa, pb := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < len(pb); i++ {
		ai, bi := 0, mustAtoi(pb[i])
		if i < len(pa) {
			ai = mustAtoi(strings.TrimRight(pa[i], "+-abz"))
		}
		if ai != bi {
			return ai > bi
		}
	}
	return true
}

func mustAtoi(s string) int {
	s = strings.TrimLeft(s, "v")
	end := strings.IndexFunc(s, func(r rune) bool { return r < '0' || r > '9' })
	if end >= 0 {
		s = s[:end]
	}
	n, _ := strconv.Atoi(s)
	return n
}

func ints(v []int) string {
	s := make([]string, len(v))
	for i, n := range v {
		s[i] = strconv.Itoa(n)
	}
	return strings.Join(s, ", ")
}

func printReport(r Report) {
	for _, c := range r.Checks {
		mark := "[ok]  "
		switch {
		case c.Warn:
			mark = "[warn]"
		case !c.OK:
			mark = "[fail]"
		}
		fmt.Printf("  %s %s  %s\n", mark, c.Name, c.Detail)
		if c.Fix != "" {
			fmt.Printf("         fix: %s\n", c.Fix)
		}
	}
}
