package coretransport

import (
	"context"
	"net"

	"github.com/Microsoft/go-winio"
)

// Dial connects only to a local pipe. Core owns the server and its ACL.
func Dial(ctx context.Context, endpoint string) (net.Conn, error) {
	if err := validateLocalPipe(endpoint); err != nil {
		return nil, err
	}
	return winio.DialPipeContext(ctx, endpoint)
}
