//go:build !windows

package herdr

import (
	"context"
	"errors"
	"fmt"
	"github.com/0cv/herdr-mobile-relay/internal/childenv"
	"strings"
	"syscall"
	"time"
)

func (c *Client) runCommand(parent context.Context, args ...string) ([]byte, error) {
	ctx := parent
	cancel := func() {}
	if _, ok := parent.Deadline(); !ok {
		ctx, cancel = context.WithTimeout(parent, defaultTimeout)
	}
	defer cancel()

	if err := ctx.Err(); err != nil {
		return nil, &OutcomeError{Started: false, Err: err}
	}

	select {
	case c.sem <- struct{}{}:
		defer func() { <-c.sem }()
	case <-ctx.Done():
		return nil, &OutcomeError{Started: false, Err: ctx.Err()}
	}

	if err := ctx.Err(); err != nil {
		return nil, &OutcomeError{Started: false, Err: err}
	}
	if err := CheckDispatch(ctx); err != nil {
		return nil, &OutcomeError{Started: false, Err: err}
	}

	cmd := childenv.Command(c.bin, args...)
	environment := cmd.Env[:0]
	for _, variable := range cmd.Env {
		key, _, _ := strings.Cut(variable, "=")
		if key != "HERDR_SOCKET_PATH" {
			environment = append(environment, variable)
		}
	}
	cmd.Env = append(environment, "HERDR_SOCKET_PATH="+c.socketPath)
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	// Process-group termination owns cancellation. WaitDelay is the final
	// backstop for inherited stdout/stderr descriptors held by a descendant
	// that escaped the group before cancellation.
	cmd.WaitDelay = waitDelay

	stdout := &limitedBuffer{limit: maxOutputBytes}
	stderr := &limitedBuffer{limit: maxOutputBytes}
	cmd.Stdout = stdout
	cmd.Stderr = stderr

	if err := cmd.Start(); err != nil {
		return nil, &OutcomeError{Started: false, Err: fmt.Errorf("start: %w", err)}
	}

	waitCh := make(chan error, 1)
	go func() {
		waitCh <- cmd.Wait()
	}()

	select {
	case err := <-waitCh:
		return commandResult(stdout, stderr, err)
	case <-ctx.Done():
		terminateProcessGroup(cmd.Process.Pid, waitCh)
		return nil, &OutcomeError{Started: true, Err: ctx.Err()}
	}
}

func terminateProcessGroup(pgid int, waitCh <-chan error) {
	_ = syscall.Kill(-pgid, syscall.SIGTERM)
	timer := time.NewTimer(termGrace)
	defer timer.Stop()
	leaderDone := false
	select {
	case <-waitCh:
		leaderDone = true
		if !GroupAlive(pgid) {
			return
		}
		<-timer.C
	case <-timer.C:
	}
	_ = syscall.Kill(-pgid, syscall.SIGKILL)

	if !leaderDone {
		select {
		case <-waitCh:
		case <-time.After(2 * time.Second):
		}
	}
	deadline := time.Now().Add(2 * time.Second)
	for GroupAlive(pgid) && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
}

// GroupAlive is exported only for process-boundary tests and diagnostics.
func GroupAlive(pgid int) bool {
	err := syscall.Kill(-pgid, 0)
	return err == nil || errors.Is(err, syscall.EPERM)
}
