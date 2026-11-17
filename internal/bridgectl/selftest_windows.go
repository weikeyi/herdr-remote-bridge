package bridgectl

import (
	"bytes"
	"context"
	"crypto/rand"
	"errors"
	"os"
	"path/filepath"
	"time"

	"github.com/0cv/herdr-mobile-relay/internal/securestore"
)

// SelfTest exercises only a fresh temporary store and pipe, never a configured
// relay, Core, gateway, task, or an existing credential file.
func SelfTest(ctx context.Context) (result SelfTestResult, err error) {
	result = SelfTestResult{Supported: true, Checks: map[string]bool{}}
	root, err := os.MkdirTemp("", "herdr-bridge-self-test-")
	if err != nil {
		return result, errors.New("self-test temporary directory creation failed")
	}
	defer func() {
		if cleanupErr := os.RemoveAll(root); cleanupErr != nil {
			result.Checks["temporary_cleanup"] = false
			err = errors.New("self-test temporary cleanup failed")
		} else {
			result.Checks["temporary_cleanup"] = true
		}
		result.OK = err == nil
	}()
	// Create the protected child with an explicit owner, even when this
	// diagnostic is launched from an elevated Windows shell.
	dir := filepath.Join(root, "current-user")
	if err = securestore.EnsureDirectory(dir); err != nil {
		return result, errors.New("self-test current-user directory protection failed")
	}
	result.Checks["current_user_directory"] = true
	plain := make([]byte, 32)
	if _, err = rand.Read(plain); err != nil {
		return result, errors.New("self-test randomness failed")
	}
	defer clear(plain)
	path := filepath.Join(dir, "probe.dpapi")
	if err = securestore.WriteNewFile(path, plain); err != nil {
		return result, errors.New("self-test DPAPI write failed")
	}
	raw, readErr := os.ReadFile(path)
	if readErr != nil || bytes.Equal(raw, plain) || bytes.Contains(raw, plain) {
		return result, errors.New("self-test ciphertext protection failed")
	}
	result.Checks["dpapi_ciphertext"] = true
	clear(raw)
	recovered, readErr := securestore.ReadFile(path)
	if readErr != nil || !bytes.Equal(recovered, plain) {
		clear(recovered)
		return result, errors.New("self-test DPAPI roundtrip failed")
	}
	clear(recovered)
	result.Checks["dpapi_roundtrip"] = true
	if securestore.WriteNewFile(path, []byte("must-not-overwrite")) == nil {
		return result, errors.New("self-test overwrite was not rejected")
	}
	recovered, readErr = securestore.ReadFile(path)
	if readErr != nil || !bytes.Equal(recovered, plain) {
		clear(recovered)
		return result, errors.New("self-test original file changed")
	}
	clear(recovered)
	result.Checks["overwrite_rejected"] = true
	listener, listenErr := Listen(dir)
	if listenErr != nil {
		return result, errors.New("self-test current-user pipe creation failed")
	}
	defer listener.Close()
	if duplicate, duplicateErr := Listen(dir); duplicateErr == nil {
		duplicate.Close()
		return result, errors.New("self-test duplicate pipe was not rejected")
	}
	result.Checks["duplicate_pipe_rejected"] = true
	pipeCtx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	stopped := make(chan struct{})
	go func() {
		defer close(stopped)
		Serve(pipeCtx, listener, func(req Request) Response { return Response{OK: req.Operation == "status", Data: "self-test"} })
	}()
	response, callErr := Call(pipeCtx, dir, Request{Operation: "status"})
	cancel()
	_ = listener.Close()
	<-stopped
	if callErr != nil || !response.OK || response.Data != "self-test" {
		return result, errors.New("self-test verified local pipe roundtrip failed")
	}
	result.Checks["current_user_pipe_roundtrip"] = true
	return result, nil
}
