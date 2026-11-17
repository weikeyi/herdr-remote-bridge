package app

import "os"

// Windows has no Unix profile-reload or pairing-rearm signals. Restart the
// relay with explicit bootstrap-role configuration until secure local IPC exists.
func registerLocalSignals(profileSignals, bootstrapSignals chan os.Signal) {}
