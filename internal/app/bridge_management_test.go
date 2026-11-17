package app

import (
	"github.com/0cv/herdr-mobile-relay/internal/bridgectl"
	"github.com/0cv/herdr-mobile-relay/internal/config"
	"github.com/0cv/herdr-mobile-relay/internal/deviceauth"
	"net/url"
	"os"
	"strings"
	"testing"
)

func newBridgeManagementFixture(t *testing.T) *Server {
	store, err := deviceauth.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	return &Server{deviceAuth: store, cfg: &config.Config{Backend: "hc", Token: strings.Repeat("k", 32), ForceRelayTransport: true, GatewayURLs: []string{"wss://bridge.example"}, AllowedOrigins: []string{"https://bridge.example"}}}
}
func TestBridgePairUsesReaderAndSecretFragment(t *testing.T) {
	s := newBridgeManagementFixture(t)
	for _, role := range []string{"", "controller"} {
		response := s.handleBridgeManagement(bridgectl.Request{Operation: "pair", Origin: "https://bridge.example", Role: role})
		if !response.OK {
			t.Fatal(response.Error)
		}
		data := response.Data.(map[string]any)
		want := deviceauth.RoleReader
		if role == "controller" {
			want = deviceauth.RoleController
		}
		if data["role"] != want {
			t.Fatalf("role %v", data["role"])
		}
		link, err := url.Parse(data["url"].(string))
		if err != nil {
			t.Fatal(err)
		}
		params, err := url.ParseQuery(link.Fragment)
		if err != nil {
			t.Fatal(err)
		}
		if link.RawQuery != "" || params.Get("path_policy") != "relay-only" || params.Get("invite") == "" || params.Get("rendezvous") == "" || params.Get("relay_id") == "" {
			t.Fatalf("incomplete link")
		}
		if len(params.Get("setup")) != 43 || strings.Contains(data["url"].(string), s.cfg.Token) {
			t.Fatal("relay key leaked or missing invitation secret")
		}
	}
}
func TestBridgeManagementRejectsUnsafeRequests(t *testing.T) {
	s := newBridgeManagementFixture(t)
	for _, req := range []bridgectl.Request{{Operation: "unknown"}, {Operation: "revoke"}, {Operation: "pair", Origin: "http://bridge.example"}, {Operation: "pair", Origin: "https://elsewhere.example"}, {Operation: "pair", Origin: "https://bridge.example", Role: "admin"}} {
		if result := s.handleBridgeManagement(req); result.OK {
			t.Fatalf("accepted %+v", req)
		}
	}
	s.cfg.ForceRelayTransport = false
	if result := s.handleBridgeManagement(bridgectl.Request{Operation: "pair", Origin: "https://bridge.example"}); result.OK {
		t.Fatal("accepted hybrid pairing")
	}
}

func TestBridgeStatusReportsReadinessAndBuildIdentity(t *testing.T) {
	s := newBridgeManagementFixture(t)
	s.version = "candidate"
	s.revision = "candidate-revision"
	s.ready = true
	result := s.handleBridgeManagement(bridgectl.Request{Operation: "status"})
	if !result.OK {
		t.Fatal(result.Error)
	}
	data := result.Data.(map[string]any)
	if data["ready"] != true || data["version"] != "candidate" || data["revision"] != "candidate-revision" || data["relay_only"] != true || data["process_id"] != os.Getpid() {
		t.Fatal("incomplete status")
	}
}
