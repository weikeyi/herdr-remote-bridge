// Synthetic vectors only. Never connect to a server or load user credentials.
package main

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/0cv/herdr-mobile-relay/internal/gatewaywire"
	"github.com/0cv/herdr-mobile-relay/internal/panedelta"
	"github.com/0cv/herdr-mobile-relay/internal/protocol"
	"os"
	"path/filepath"
)

func check(err error) {
	if err != nil {
		panic(err)
	}
}
func write(name string, value any) {
	data, err := json.MarshalIndent(value, "", "  ")
	check(err)
	check(os.WriteFile(filepath.Join(os.Getenv("FIXTURE_DIR"), name), append(data, '\n'), 0600))
}
func main() {
	if os.Getenv("BRIDGE_SOURCE") == "" || os.Getenv("FIXTURE_DIR") == "" {
		panic("run via run.sh with BRIDGE_SOURCE")
	}
	check(os.MkdirAll(os.Getenv("FIXTURE_DIR"), 0700))
	// Public, fixed TEST INPUT. Not a deployable relay token or invitation.
	token := "synthetic-native-fixture-key-0001"
	id, err := gatewaywire.DeriveRelayID(token)
	check(err)
	key, err := gatewaywire.DeriveRendezvousKey(token)
	check(err)
	nonce := make([]byte, 32)
	for i := range nonce {
		nonce[i] = byte(i)
	}
	encode := base64.RawURLEncoding.EncodeToString
	proof := gatewaywire.ConnectProof(key, id, nonce)
	write("gateway-auth.json", map[string]any{
		"synthetic_relay_key": token, "rendezvous_key": encode(key),
		"server_hello":   gatewaywire.ServerHello{Type: gatewaywire.TypeServerHello, Proto: gatewaywire.Proto, Nonce: encode(nonce)},
		"client_connect": gatewaywire.ConnectHello{Type: gatewaywire.TypeConnect, Proto: gatewaywire.Proto, RelayID: id, Proof: encode(proof)},
		"server_ready":   gatewaywire.ReadyMessage{Type: gatewaywire.TypeReady, Proto: gatewaywire.Proto},
	})
	target := &protocol.TargetRef{ServerSessionID: "synthetic-server-session", PaneID: "synthetic-pane", TerminalID: "synthetic-terminal", Generation: 1, AgentSessionID: "synthetic-agent-session"}
	var commands []map[string]any
	for _, operation := range []string{"refresh_agents", "read_pane", "watch_pane", "pane_applied", "unwatch_pane"} {
		request := protocol.Inbound{Type: operation, Protocol: protocol.Version}
		if operation != "refresh_agents" {
			request.Target = target
			request.PaneID = target.PaneID
			request.ServerSessionID = target.ServerSessionID
		}
		if operation == "read_pane" {
			request.RequestID = "synthetic-read-001"
		}
		if operation == "read_pane" || operation == "watch_pane" {
			request.Lines = 80
			request.Format = "ansi"
		}
		data, err := json.Marshal(request)
		check(err)
		var raw map[string]any
		check(json.Unmarshal(data, &raw))
		// These fields are map-read by the server, not part of protocol.Inbound.
		if operation == "read_pane" {
			raw["content_fingerprint"] = ""
		}
		if operation == "watch_pane" {
			raw["interval_ms"] = 1000
			raw["content_fingerprint"] = "synthetic-observed-fingerprint"
		}
		if operation == "pane_applied" {
			raw["content_fingerprint"] = "synthetic-observed-fingerprint"
		}
		decoded, err := protocol.DecodeMap(raw)
		check(err)
		metadata, ok := protocol.ClassifyAction(decoded.Type)
		if !ok || metadata.Class != protocol.ActionReadOnly || !protocol.Compatible(decoded) {
			panic("not a read-only compatible action")
		}
		commands = append(commands, raw)
	}
	write("readonly-commands.json", commands)
	old := "first\nsecond\nthird\nold\n"
	next := "first\nsecond\nthird\nnew\n"
	segments := panedelta.Build(old, next)
	applied, ok := panedelta.Apply(old, segments)
	if !ok || applied != next {
		panic("delta roundtrip failed")
	}
	write("pane-delta-algorithm.json", map[string]any{"previous": old, "current": next, "segments": segments})
	// Exact checked-in server contract fixtures; do not invent a session_list API.
	for _, name := range []string{"agents.json", "push_config.json", "agent_update.json"} {
		data, err := os.ReadFile(filepath.Join(os.Getenv("BRIDGE_SOURCE"), "contracts/fixtures/outbound", name))
		check(err)
		var value any
		check(json.Unmarshal(data, &value))
		write(name, value)
	}
	fmt.Println("Generated gateway proof, five validated read-only commands, delta roundtrip, three source contract fixtures")
}
