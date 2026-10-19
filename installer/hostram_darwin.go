package main

import (
	"os/exec"
	"strconv"
	"strings"
)

// hostRAMMB is the computer's physical memory, or 0 when it cannot be read.
func hostRAMMB() int64 {
	out, err := exec.Command("sysctl", "-n", "hw.memsize").Output()
	if err != nil {
		return 0
	}
	b, _ := strconv.ParseInt(strings.TrimSpace(string(out)), 10, 64)
	return b / (1 << 20)
}
