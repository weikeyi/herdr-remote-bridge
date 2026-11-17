package filelock

import (
	"golang.org/x/sys/windows"
	"os"
)

func Try(file *os.File) error {
	var ov windows.Overlapped
	return windows.LockFileEx(windows.Handle(file.Fd()), windows.LOCKFILE_EXCLUSIVE_LOCK|windows.LOCKFILE_FAIL_IMMEDIATELY, 0, 1, 0, &ov)
}
func Unlock(file *os.File) error {
	var ov windows.Overlapped
	return windows.UnlockFileEx(windows.Handle(file.Fd()), 0, 1, 0, &ov)
}

var ErrLocked = windows.ERROR_LOCK_VIOLATION
