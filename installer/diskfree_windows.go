//go:build windows

package main

import (
	"path/filepath"
	"syscall"
	"unsafe"
)

// diskFreeGB returns free GB on the volume holding dir via GetDiskFreeSpaceExW.
func diskFreeGB(dir string) uint64 {
	abs, err := filepath.Abs(dir)
	if err != nil {
		return 0
	}
	p, err := syscall.UTF16PtrFromString(abs)
	if err != nil {
		return 0
	}
	var free int64
	proc := syscall.NewLazyDLL("kernel32.dll").NewProc("GetDiskFreeSpaceExW")
	r, _, _ := proc.Call(uintptr(unsafe.Pointer(p)), uintptr(unsafe.Pointer(&free)), 0, 0)
	if r == 0 {
		return 0
	}
	return uint64(free) / (1 << 30)
}
