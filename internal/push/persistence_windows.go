package push

import (
	"github.com/0cv/herdr-mobile-relay/internal/securestore"
	"os"
)

// Protect all push state: queue entries also duplicate subscription auth keys.
// Legacy plaintext Windows state is rejected, never silently migrated.
func readPersisted(path string) ([]byte, error) { return securestore.ReadFile(path) }
func atomicWrite(path string, data []byte, _ os.FileMode) error {
	return securestore.WriteFile(path, data)
}
