//go:build !windows

package main

import "syscall"

// diskFreeGB returns free GB on the filesystem holding dir.
func diskFreeGB(dir string) uint64 {
	var st syscall.Statfs_t
	if err := syscall.Statfs(dir, &st); err != nil {
		return 0
	}
	return st.Bavail * uint64(st.Bsize) / (1 << 30)
}
