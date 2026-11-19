package transport

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	relayprotocol "github.com/0cv/herdr-mobile-relay/internal/protocol"
	"os"
	"strings"
	"sync"
)

type SyntheticResolver struct {
	mu                             sync.Mutex
	invite, credential, rendezvous []byte
	relayID                        string
	enrolled                       bool
	used                           map[string]bool
}

func NewSyntheticResolver() *SyntheticResolver {
	r := &SyntheticResolver{invite: make([]byte, 32), credential: make([]byte, 32), rendezvous: make([]byte, 32), used: make(map[string]bool)}
	id := make([]byte, 16)
	for _, b := range [][]byte{r.invite, r.credential, r.rendezvous, id} {
		if _, e := rand.Read(b); e != nil {
			panic(e)
		}
	}
	r.relayID = base64.RawURLEncoding.EncodeToString(id)
	return r
}
func (r *SyntheticResolver) InviteSecret() []byte  { return append([]byte(nil), r.invite...) }
func (r *SyntheticResolver) RelayID() string       { return r.relayID }
func (r *SyntheticResolver) RendezvousKey() []byte { return append([]byte(nil), r.rendezvous...) }
func (r *SyntheticResolver) ResolveE2EESecret(_ context.Context, s E2EEAuthSelector) ([]byte, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if s.Version != 1 {
		return nil, errors.New("wrong version")
	}
	if s.Kind == E2EEAuthInvitation && s.ID == "synthetic-invitation-0001" && !r.enrolled {
		return append([]byte(nil), r.invite...), nil
	}
	if s.Kind == E2EEAuthCredential && s.ID == "synthetic-credential-0001" && r.enrolled {
		return append([]byte(nil), r.credential...), nil
	}
	return nil, errors.New("wrong selector")
}
func (r *SyntheticResolver) CompleteE2EEAuth(_ context.Context, s E2EEAuthSelector, ok bool) (E2EEAuthResult, error) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if !ok || s.Version != 1 {
		return E2EEAuthResult{}, errors.New("not authenticated")
	}
	result := E2EEAuthResult{Identity: AuthenticatedIdentity{DeviceID: "synthetic-device-0001", CredentialID: "synthetic-credential-0001", CredentialVersion: 1, Role: "reader", Locale: "en"}}
	if s.Kind == E2EEAuthInvitation && s.ID == "synthetic-invitation-0001" && !r.enrolled {
		r.enrolled = true
		result.CredentialSecret = append([]byte(nil), r.credential...)
	} else if s.Kind != E2EEAuthCredential || s.ID != "synthetic-credential-0001" || !r.enrolled {
		return E2EEAuthResult{}, errors.New("selector changed")
	}
	return result, nil
}
func (*SyntheticResolver) IsE2EEAuthRejected(error) bool { return true }

type uniqueHello struct {
	FrameConn
	resolver *SyntheticResolver
	first    bool
}

func (c *uniqueHello) ReadFrame(ctx context.Context) ([]byte, error) {
	data, err := c.FrameConn.ReadFrame(ctx)
	if err != nil || !c.first {
		return data, err
	}
	c.first = false
	var h struct {
		Nonce  string `json:"nonce"`
		Public string `json:"public_key"`
	}
	if err = json.Unmarshal(data, &h); err != nil {
		return nil, err
	}
	c.resolver.mu.Lock()
	defer c.resolver.mu.Unlock()
	if c.resolver.used["nonce:"+h.Nonce] || c.resolver.used["pub:"+h.Public] {
		return nil, errors.New("ephemeral reused")
	}
	c.resolver.used["nonce:"+h.Nonce] = true
	c.resolver.used["pub:"+h.Public] = true
	return data, nil
}
func ServeSynthetic(conn FrameConn, r *SyntheticResolver) error {
	session, identity, err := performServerE2EEHandshake(context.Background(), &uniqueHello{conn, r, true}, r)
	if err != nil {
		return err
	}
	if identity.Role != "reader" {
		return errors.New("not reader")
	}
	fmt.Fprintln(os.Stderr, "WSS trace: original Go authenticated fresh client and encrypted finish; reader")
	send := func(value any) error {
		plain, e := json.Marshal(value)
		if e != nil {
			return e
		}
		frame, e := session.seal(plain)
		if e != nil {
			return e
		}
		return conn.WriteFrame(context.Background(), frame)
	}
	if err = send(map[string]any{"type": "push_config", "protocol": 3, "inventory": map[string]any{"state": "ready"}}); err != nil {
		return err
	}
	target := map[string]any{"server_session_id": "synthetic-server", "pane_id": "synthetic-pane", "terminal_id": "synthetic-terminal", "generation": 1, "agent_session_id": "synthetic-agent"}
	row := make(map[string]any)
	for k, v := range target {
		row[k] = v
	}
	row["name"] = "WSS synthetic reader"
	row["status"] = "working"
	if err = send(map[string]any{"type": "agents", "agents": []any{row}}); err != nil {
		return err
	}
	// Invitation connections may close immediately after durable enrollment.
	for {
		frame, e := conn.ReadFrame(context.Background())
		if e != nil {
			return e
		}
		plain, e := session.open(frame)
		if e != nil {
			return e
		}
		var request map[string]any
		if e = json.Unmarshal(plain, &request); e != nil {
			return e
		}
		if _, err := relayprotocol.DecodeMap(request); err != nil {
			return err
		}
		metadata, known := relayprotocol.ClassifyAction("read_pane")
		if !known || metadata.Class != relayprotocol.ActionReadOnly {
			return errors.New("not a known read-only operation")
		}
		if request["server_session_id"] != "synthetic-server" || request["lines"] != float64(80) ||
			request["format"] != "ansi" || request["content_fingerprint"] != "" {
			return errors.New("unexpected snapshot request shape")
		}
		if request["type"] != "read_pane" || request["protocol"] != float64(3) || request["pane_id"] != "synthetic-pane" {
			return errors.New("unexpected request")
		}
		if _, ok := request["request_id"].(string); !ok {
			return errors.New("request id")
		}
		rawTarget, ok := request["target"].(map[string]any)
		if !ok {
			return errors.New("missing target")
		}
		for _, k := range []string{"server_session_id", "pane_id", "terminal_id", "agent_session_id"} {
			if rawTarget[k] != target[k] {
				return errors.New("wrong target")
			}
		}
		if rawTarget["generation"] != float64(1) {
			return errors.New("wrong generation")
		}
		// 300KB UTF-8 spans carrier chunks while staying under the native character cap.
		if err = send(map[string]any{"type": "pane_content", "pane_id": "synthetic-pane", "request_id": request["request_id"],
			"target": target, "format": "text", "content": "synthetic WSS terminal 中文\n" + strings.Repeat("界", 100000), "truncated": false}); err != nil {
			return err
		}
		fmt.Fprintln(os.Stderr, "WSS trace: exact-target read_pane authenticated; multi-chunk UTF-8 snapshot sent")
	}
}
