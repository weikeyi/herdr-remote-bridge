package coretransport

import (
	"errors"
	"strings"
)

func validateLocalPipe(endpoint string) error {
	const prefix = `\\.\pipe\`
	if !strings.HasPrefix(strings.ToLower(endpoint), prefix) || len(endpoint) <= len(prefix) || len(endpoint) > 256 || strings.ContainsAny(endpoint[len(prefix):], "\\/\x00\r\n") {
		return errors.New("Core endpoint must be a local Windows named pipe")
	}
	return nil
}
