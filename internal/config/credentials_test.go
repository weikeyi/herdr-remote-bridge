package config

import (
	"path/filepath"
	"strings"
	"testing"

	"github.com/0cv/herdr-mobile-relay/internal/securestore"
)

func TestProtectedCredentialFiles(t *testing.T) {
	isolateLoadEnvironment(t)
	t.Setenv("HERDR_GATEWAY_REGISTRATION_TOKEN", "")
	t.Setenv("HERDR_GATEWAY_REGISTRATION_TOKEN_FILE", "")
	t.Setenv("HERDR_RELAY_TOKEN_FILE", "")
	dir := filepath.Join(privateTestDir(t), "private")
	relay := filepath.Join(dir, "relay.key")
	gateway := filepath.Join(dir, "gateway.key")
	token := strings.Repeat("r", 32)
	if err := securestore.WriteFile(relay, []byte(token)); err != nil {
		t.Fatal(err)
	}
	if err := securestore.WriteFile(gateway, []byte("gateway-secret")); err != nil {
		t.Fatal(err)
	}
	t.Setenv("HERDR_RELAY_TOKEN_FILE", relay)
	t.Setenv("HERDR_GATEWAY_REGISTRATION_TOKEN_FILE", gateway)
	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Token != token || cfg.GatewayRegistrationToken != "gateway-secret" {
		t.Fatal("protected credential not loaded")
	}
	t.Setenv("HERDR_RELAY_TOKEN", token)
	if _, err := Load(); err == nil {
		t.Fatal("ambiguous source accepted")
	}
	t.Setenv("HERDR_RELAY_TOKEN", "")
	t.Setenv("HERDR_RELAY_TOKEN_FILE", filepath.Join(dir, "missing"))
	if _, err := Load(); err == nil {
		t.Fatal("missing secret accepted")
	}
}

func TestProtectedCredentialRejectsMalformedAndDoesNotEcho(t *testing.T) {
	for _, content := range []string{"", "secret\nnext", strings.Repeat("x", 4097)} {
		t.Run("invalid", func(t *testing.T) {
			path := filepath.Join(privateTestDir(t), "private", "credential")
			if err := securestore.WriteFile(path, []byte(content)); err != nil {
				t.Fatal(err)
			}
			t.Setenv("TEST_CREDENTIAL", "")
			t.Setenv("TEST_CREDENTIAL_FILE", path)
			if _, err := credentialValue("TEST_CREDENTIAL"); err == nil || strings.Contains(err.Error(), "secret") {
				t.Fatal("malformed credential accepted or leaked")
			}
		})
	}
}
