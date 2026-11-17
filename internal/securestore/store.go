// Package securestore persists relay secrets. Windows uses user-bound DPAPI and
// a protected DACL granting access only to the current user. It intentionally
// refuses legacy plaintext on Windows; migration requires explicit reprovisioning.
package securestore

const maxSize = 4 << 20
