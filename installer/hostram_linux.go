package main

import (
	"os"
	"strconv"
	"strings"
)

// hostRAMMB is the computer's physical memory, or 0 when it cannot be read.
func hostRAMMB() int64 {
	b, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return 0
	}
	for line := range strings.Lines(string(b)) {
		if rest, ok := strings.CutPrefix(line, "MemTotal:"); ok {
			f := strings.Fields(rest) // "16316412 kB"
			if len(f) > 0 {
				kb, _ := strconv.ParseInt(f[0], 10, 64)
				return kb / 1024
			}
		}
	}
	return 0
}
