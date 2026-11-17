//go:build !windows

package filelock

import (
	"os"
	"syscall"
)

func Try(file *os.File) error    { return syscall.Flock(int(file.Fd()), syscall.LOCK_EX|syscall.LOCK_NB) }
func Unlock(file *os.File) error { return syscall.Flock(int(file.Fd()), syscall.LOCK_UN) }

var ErrLocked = syscall.EWOULDBLOCK
