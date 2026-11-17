package coretransport

import "testing"

func TestLocalPipeValidation(t *testing.T) {
	for _, tt := range []struct {
		endpoint string
		valid    bool
	}{
		{`\\.\pipe\herdr-core-TestUser-default.sock`, true},
		{`\\.\PIPE\custom.sock`, true},
		{`\\remote\pipe\core.sock`, false},
		{`C:\core.sock`, false},
		{`\\.\pipe\`, false},
		{`\\.\pipe\nested\core`, false},
		{"", false},
		{"\\\\.\\pipe\\bad\x00", false},
	} {
		if err := validateLocalPipe(tt.endpoint); (err == nil) != tt.valid {
			t.Errorf("%q: %v", tt.endpoint, err)
		}
	}
}
