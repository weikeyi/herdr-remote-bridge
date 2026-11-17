//go:build !windows

// Package coretransport connects to the already-running local Core, never starting it.
package coretransport

import (
	"context"
	"net"
)

func Dial(ctx context.Context, endpoint string) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, "unix", endpoint)
}
