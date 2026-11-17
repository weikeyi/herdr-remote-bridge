package push

import (
	"path/filepath"
	"testing"

	"github.com/0cv/herdr-mobile-relay/internal/securestore"
)

// A fresh child is created with an explicit current-user security descriptor.
// Elevated Windows runners otherwise give t.TempDir an Administrators owner.
// No ownership checks are relaxed and no existing object's owner is changed.
func privateTestDir(t *testing.T) string {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "current-user")
	if err := securestore.EnsureDirectory(dir); err != nil {
		t.Fatal(err)
	}
	return dir
}
