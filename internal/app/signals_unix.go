//go:build !windows

package app

import (
	"os"
	"os/signal"
	"syscall"
)

func registerLocalSignals(profileSignals, bootstrapSignals chan os.Signal) {
	signal.Notify(profileSignals, syscall.SIGHUP)
	signal.Notify(bootstrapSignals, syscall.SIGUSR1)
}
