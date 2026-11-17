//go:build !windows

package main

import (
	"errors"
	"github.com/0cv/herdr-mobile-relay/internal/bridgectl"
	"testing"
)

func TestBridgeSelfTestDoesNotLoadConfiguration(t *testing.T) {
	t.Setenv("HERDR_RELAY_BACKEND", "invalid-for-normal-startup")
	t.Setenv("HERDR_RELAY_TOKEN", "invalid")
	code, err := runBridge([]string{"self-test"})
	if code != 1 || !errors.Is(err, bridgectl.ErrUnsupported) {
		t.Fatalf("code %d error %v", code, err)
	}
}
