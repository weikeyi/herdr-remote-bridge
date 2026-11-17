package coretransport

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"testing"
	"time"

	"github.com/Microsoft/go-winio"
)

func TestWindowsPipeRoundTripAndDeadline(t *testing.T) {
	endpoint := fmt.Sprintf(`\\.\pipe\herdr-relay-test-%d-%d`, os.Getpid(), time.Now().UnixNano())
	listener, err := winio.ListenPipe(endpoint, &winio.PipeConfig{})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	done := make(chan error, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			done <- err
			return
		}
		defer conn.Close()
		buf := make([]byte, 4)
		_, err = io.ReadFull(conn, buf)
		if err == nil {
			_, err = conn.Write(buf)
		}
		done <- err
	}()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	conn, err := Dial(ctx, endpoint)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	if err := conn.SetDeadline(time.Now().Add(time.Second)); err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Write([]byte("core")); err != nil {
		t.Fatal(err)
	}
	var buf [4]byte
	if _, err := io.ReadFull(conn, buf[:]); err != nil || string(buf[:]) != "core" {
		t.Fatalf("reply %q: %v", buf, err)
	}
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}

func TestWindowsMissingPipeCancellation(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer cancel()
	endpoint := fmt.Sprintf(`\\.\pipe\herdr-relay-missing-%d-%d`, os.Getpid(), time.Now().UnixNano())
	conn, err := Dial(ctx, endpoint)
	if conn != nil {
		conn.Close()
		t.Fatal("unexpected pipe")
	}
	// Some Windows versions immediately return file-not-found; all must return
	// within the context deadline rather than waiting indefinitely for Core.
	if err == nil {
		t.Fatal("missing pipe accepted")
	}
}

func TestWindowsPipeReadDeadline(t *testing.T) {
	endpoint := fmt.Sprintf(`\\.\pipe\herdr-relay-deadline-%d-%d`, os.Getpid(), time.Now().UnixNano())
	listener, err := winio.ListenPipe(endpoint, &winio.PipeConfig{})
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	accepted := make(chan net.Conn, 1)
	go func() {
		c, err := listener.Accept()
		if err == nil {
			accepted <- c
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	conn, err := Dial(ctx, endpoint)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	var peer net.Conn
	select {
	case peer = <-accepted:
	case <-ctx.Done():
		t.Fatal(ctx.Err())
	}
	defer peer.Close()
	if err := conn.SetReadDeadline(time.Now().Add(20 * time.Millisecond)); err != nil {
		t.Fatal(err)
	}
	var b [1]byte
	_, err = conn.Read(b[:])
	var timeout net.Error
	if !errors.As(err, &timeout) || !timeout.Timeout() {
		t.Fatalf("expected read timeout, got %v", err)
	}
}
