package processgroup

import "time"

// Options controls the caller-specific cancellation policy.
type Options struct {
	PostKillWait         time.Duration
	ReturnOutputOnCancel bool
}
