//go:build windows

package herdr

import (
	"context"
	"errors"
)

// Windows Bridge only supports the hc backend. Do not spawn Unix CLI helpers
// without a validated Windows process-tree cancellation implementation.
func (c *Client) runCommand(ctx context.Context, args ...string) ([]byte, error) {
	if err := ctx.Err(); err != nil {
		return nil, &OutcomeError{Started: false, Err: err}
	}
	return nil, &OutcomeError{Started: false, Err: errors.New("Herdr TUI subprocesses are unavailable on Windows; use the hc backend")}
}
