//go:build windows

package processgroup

import (
	"context"
	"errors"
	"os/exec"
)

// Run refuses unvalidated Unix updater/deployment helpers on Windows.
// No child is started, so cancellation cannot leave descendants behind.
func Run(ctx context.Context, command *exec.Cmd, options Options) ([]byte, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return nil, errors.New("subprocess update and deployment helpers are unavailable on Windows")
}
