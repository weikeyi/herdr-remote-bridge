package main

import (
	"bytes"
	"encoding/base64"
	"strings"
	"testing"
)

func TestProtectedSecretInput(t *testing.T) {
	for _, s := range []string{"", "one\ntwo", "one\x00two", strings.Repeat("a", 4097)} {
		if _, err := readProtectedSecretInput(strings.NewReader(s), false); err == nil {
			t.Fatal("invalid secret accepted")
		}
	}
	got, err := readProtectedSecretInput(strings.NewReader("secret\r\n"), false)
	if err != nil || string(got) != "secret" {
		t.Fatalf("stdin: %v", err)
	}
	a, err := readProtectedSecretInput(nil, true)
	if err != nil {
		t.Fatal(err)
	}
	b, err := readProtectedSecretInput(nil, true)
	if err != nil {
		t.Fatal(err)
	}
	if len(a) != 32 || bytes.Equal(a, b) {
		t.Fatal("key generation length/randomness")
	}
	if _, err := base64.RawURLEncoding.DecodeString(string(a)); err != nil {
		t.Fatal(err)
	}
}
