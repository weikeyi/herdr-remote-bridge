//go:build !windows

package bridgectl

import (
	"context"
	"net"
)

func Listen(runtimeDir string) (net.Listener, error)                { return nil, ErrUnsupported }
func dial(ctx context.Context, runtimeDir string) (net.Conn, error) { return nil, ErrUnsupported }
