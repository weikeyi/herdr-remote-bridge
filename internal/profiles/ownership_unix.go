//go:build !windows

package profiles

import (
	"os"
	"syscall"
)

func privateOwnershipFile(info os.FileInfo) bool {
	stat, owned := info.Sys().(*syscall.Stat_t)
	return owned && stat.Uid == uint32(os.Geteuid()) && stat.Nlink == 1 && info.Mode().IsRegular() && info.Mode().Perm() == 0600 && info.Size() <= 1024*1024
}
