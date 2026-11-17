package config

import "strings"

// Match herdr-core v0.1.18 platform/windows.rs and paths.rs. An explicitly
// configured Core socket must be supplied through HC_SOCKET or HC_SOCKET_PATH.
func windowsCoreEndpoint(username, session string) string {
	user := strings.Map(func(r rune) rune {
		if r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' {
			return r
		}
		return -1
	}, username)
	session = strings.TrimSpace(session)
	if session == "" {
		session = "default"
	}
	return `\\.\pipe\herdr-core-` + user + "-" + session + ".sock"
}
