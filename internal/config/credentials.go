package config

import (
	"fmt"
	"os"
	"strings"

	"github.com/0cv/herdr-mobile-relay/internal/securestore"
)

// Secret files are decrypted only inside the relay process. Their paths, not
// their contents, may be supplied by a current-user task launcher.
func credentialValue(name string) (string, error) {
	value, path := os.Getenv(name), os.Getenv(name+"_FILE")
	if path == "" {
		return value, nil
	}
	if value != "" {
		return "", fmt.Errorf("set only %s or %s_FILE, not both", name, name)
	}
	data, err := securestore.ReadFile(path)
	if err != nil {
		return "", fmt.Errorf("cannot read protected %s_FILE", name)
	}
	defer clear(data)
	if len(data) == 0 || len(data) > 4096 || strings.ContainsAny(string(data), "\x00\r\n") {
		return "", fmt.Errorf("invalid protected %s_FILE contents", name)
	}
	return string(data), nil
}
