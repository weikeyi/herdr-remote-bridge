package profiles

import "os"

// Persisted launch ownership is unavailable until Windows owner/ACL and file
// identity verification is implemented. Never trust POSIX mode bits on Windows.
func privateOwnershipFile(os.FileInfo) bool { return false }
