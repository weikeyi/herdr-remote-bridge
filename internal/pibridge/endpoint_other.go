//go:build !darwin && !linux

package pibridge

import (
	"errors"
	"net"
	"os"
)

// Do not weaken Unix peer authentication to make unsupported platforms work.
func Instance(string) (string, error) {
	return "", errors.New("Pi command bridge unavailable on this platform")
}
func owned(string, bool) (os.FileInfo, error) {
	return nil, errors.New("Pi command bridge unavailable on this platform")
}
func checkPeer(*net.UnixConn, int) error {
	return errors.New("Pi peer authentication unavailable on this platform")
}
