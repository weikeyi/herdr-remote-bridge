//go:build windows

package processgroup

import (
	"context"
	"errors"
	"os/exec"
	"testing"
)

func TestWindowsRunDoesNotStartUnsupportedHelper(t *testing.T) {
	command := exec.Command("cmd.exe", "/c", "exit", "0")
	if _, err := Run(context.Background(), command, Options{}); err == nil {
		t.Fatal("unsupported helper accepted")
	}
	if command.Process != nil {
		t.Fatal("unsupported helper started")
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := Run(ctx, command, Options{}); !errors.Is(err, context.Canceled) {
		t.Fatalf("cancel: %v", err)
	}
}
