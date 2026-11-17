package config

import "testing"

func TestWindowsCoreEndpoint(t *testing.T) {
	for _, tt := range []struct{ user, session, want string }{
		{"TestUser", "default", `\\.\pipe\herdr-core-TestUser-default.sock`},
		{"Test-User 张_1", " work ", `\\.\pipe\herdr-core-TestUser1-work.sock`},
		{"", "  ", `\\.\pipe\herdr-core--default.sock`},
	} {
		if got := windowsCoreEndpoint(tt.user, tt.session); got != tt.want {
			t.Errorf("got %q want %q", got, tt.want)
		}
	}
}

func TestWindowsCoreEndpointOverrides(t *testing.T) {
	t.Setenv("USERNAME", "TestUser")
	t.Setenv("HC_SESSION", "office")
	t.Setenv("HC_SOCKET_PATH", "")
	t.Setenv("HC_SOCKET", "")
	if got := coreSocketPathForOS("windows", "/ignored"); got != `\\.\pipe\herdr-core-TestUser-office.sock` {
		t.Fatal(got)
	}
	t.Setenv("HC_SOCKET", `\\.\pipe\custom`)
	if got := coreSocketPathForOS("windows", "/ignored"); got != `\\.\pipe\custom` {
		t.Fatal(got)
	}
	t.Setenv("HC_SOCKET_PATH", `\\.\pipe\relay-override`)
	if got := coreSocketPathForOS("windows", "/ignored"); got != `\\.\pipe\relay-override` {
		t.Fatal(got)
	}
}
