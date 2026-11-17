package securestore

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

func TestRoundTripAndReplace(t *testing.T) {
	path := filepath.Join(privateTestDir(t), "private", "secret")
	for _, data := range [][]byte{nil, []byte("a secret"), []byte("replacement secret")} {
		if err := WriteFile(path, data); err != nil {
			t.Fatal(err)
		}
		got, err := ReadFile(path)
		if err != nil || !bytes.Equal(got, data) {
			t.Fatalf("read error %v or content mismatch", err)
		}
	}
}
func TestMissingAndOversize(t *testing.T) {
	path := filepath.Join(privateTestDir(t), "missing")
	if _, err := ReadFile(path); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("missing: %v", err)
	}
	if err := WriteFile(path, make([]byte, maxSize+1)); err == nil {
		t.Fatal("oversized value accepted")
	}
}
func TestRefuseDirectoryAsFile(t *testing.T) {
	if _, err := ReadFile(privateTestDir(t)); err == nil {
		t.Fatal("directory accepted")
	}
}

func TestWriteNewFileDoesNotReplace(t *testing.T) {
	path := filepath.Join(privateTestDir(t), "secret")
	if err := WriteNewFile(path, []byte("original")); err != nil {
		t.Fatal(err)
	}
	if err := WriteNewFile(path, []byte("replacement")); !errors.Is(err, os.ErrExist) {
		t.Fatalf("duplicate error = %v", err)
	}
	got, err := ReadFile(path)
	if err != nil || string(got) != "original" {
		t.Fatalf("original changed: %v", err)
	}
}
func TestConcurrentWriteNewFile(t *testing.T) {
	path := filepath.Join(privateTestDir(t), "secret")
	start := make(chan struct{})
	results := make(chan error, 8)
	for i := 0; i < 8; i++ {
		go func() { <-start; results <- WriteNewFile(path, []byte("winner")) }()
	}
	close(start)
	successes := 0
	for i := 0; i < 8; i++ {
		if err := <-results; err == nil {
			successes++
		}
	}
	if successes != 1 {
		t.Fatalf("got %d winners", successes)
	}
	got, err := ReadFile(path)
	if err != nil || string(got) != "winner" {
		t.Fatalf("invalid final value: %v", err)
	}
}
