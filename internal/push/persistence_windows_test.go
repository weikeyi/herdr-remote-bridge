package push

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
)

func TestWindowsPushSecretsEncryptedAtRest(t *testing.T) {
	dir := privateTestDir(t)
	manager, err := NewManager(dir, testLogger())
	if err != nil {
		t.Fatal(err)
	}
	sub := Subscription{Endpoint: "https://fcm.googleapis.com/private-subscription"}
	sub.Keys.Auth = "known-private-subscription-auth"
	sub.Keys.P256dh = "known-public-key"
	if err := manager.Subscribe(sub); err != nil {
		t.Fatal(err)
	}
	// Queue records contain a full subscription, so every persisted push file
	// uses the same encrypted boundary, not only the VAPID private key.
	for _, name := range []string{"vapid_private.pem", "vapid_public.pem", "subscriptions.json", "action_ref.key"} {
		path := filepath.Join(dir, name)
		raw, err := os.ReadFile(path)
		if err != nil {
			t.Fatal(err)
		}
		plain, err := readPersisted(path)
		if err != nil {
			t.Fatal(err)
		}
		if bytes.Equal(raw, plain) || bytes.Contains(raw, []byte(sub.Keys.Auth)) {
			t.Fatalf("plaintext %s", name)
		}
	}
	if _, err := NewManager(dir, testLogger()); err != nil {
		t.Fatal(err)
	}
}
func TestWindowsPushRejectsPlaintextState(t *testing.T) {
	dir := privateTestDir(t)
	if err := os.WriteFile(filepath.Join(dir, "subscriptions.json"), []byte("[]"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := NewManager(dir, testLogger()); err == nil {
		t.Fatal("plaintext subscription state accepted")
	}
}
