package piintegration

import (
	"errors"
	"os"
)

// This optional Unix bridge must not silently lose no-follow protection.
func openRegular(*os.Root, string) (*os.File, error) {
	return nil, errors.New("Pi integration unavailable on Windows")
}
