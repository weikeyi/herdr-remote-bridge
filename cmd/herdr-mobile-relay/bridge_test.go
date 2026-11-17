package main

import "testing"

func TestBridgeCommandRejectsMalformedArguments(t *testing.T) {
	for _, args := range [][]string{nil, {"unknown"}, {"pair"}, {"pair", "--origin", "https://bridge.example", "--role", "admin"}, {"revoke"}, {"status", "unexpected"}, {"devices", "--unexpected"}} {
		code, err := runBridge(args)
		if code != 2 || err == nil {
			t.Fatalf("args %v: code %d error %v", args, code, err)
		}
	}
}
