package main

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"flag"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/0cv/herdr-mobile-relay/internal/securestore"
)

func protectSecret(args []string, input io.Reader) (int, error) {
	flags := flag.NewFlagSet("protect-secret", flag.ContinueOnError)
	output := flags.String("output", "", "new current-user protected credential file")
	generate := flags.Bool("generate-relay-key", false, "generate a random relay key without printing it")
	if err := flags.Parse(args); err != nil {
		return 2, err
	}
	if flags.NArg() != 0 || *output == "" || !filepath.IsAbs(*output) {
		return 2, errors.New("usage: protect-secret --output ABSOLUTE_PATH [--generate-relay-key]")
	}
	if runtime.GOOS != "windows" {
		return 1, errors.New("protect-secret requires Windows CurrentUser DPAPI")
	}
	// Credential rotation is deliberate: this helper never overwrites a live key.
	if _, err := os.Lstat(*output); !errors.Is(err, os.ErrNotExist) {
		return 1, errors.New("credential destination already exists or cannot be inspected")
	}
	secret, err := readProtectedSecretInput(input, *generate)
	if err != nil {
		return 1, err
	}
	defer clear(secret)
	if err = securestore.WriteNewFile(*output, secret); err != nil {
		return 1, errors.New("could not save current-user protected credential")
	}
	return 0, nil
}

func readProtectedSecretInput(input io.Reader, generate bool) ([]byte, error) {
	if generate {
		raw := make([]byte, 24)
		if _, err := rand.Read(raw); err != nil {
			return nil, errors.New("could not generate relay key")
		}
		defer clear(raw)
		return []byte(base64.RawURLEncoding.EncodeToString(raw)), nil
	}
	data, err := io.ReadAll(io.LimitReader(input, 4099))
	if err != nil {
		return nil, errors.New("could not read credential from stdin")
	}
	data = []byte(strings.TrimSuffix(strings.TrimSuffix(string(data), "\n"), "\r"))
	if len(data) == 0 || len(data) > 4096 || strings.ContainsAny(string(data), "\x00\r\n") {
		clear(data)
		return nil, errors.New("credential must be one nonempty line of at most 4096 bytes")
	}
	return data, nil
}
