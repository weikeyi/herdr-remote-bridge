package transport

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
)

type CloseStatus int
type FrameConn interface {
	ReadFrame(context.Context) ([]byte, error)
	WriteFrame(context.Context, []byte) error
	Close(CloseStatus, string)
	CloseNow()
	Codec() FrameCodec
	TransportName() string
}
type pipe struct {
	reader *bufio.Scanner
	writer io.Writer
}

func (p *pipe) ReadFrame(context.Context) ([]byte, error) {
	if !p.reader.Scan() {
		return nil, io.EOF
	}
	return base64.RawURLEncoding.DecodeString(p.reader.Text())
}
func (p *pipe) WriteFrame(_ context.Context, b []byte) error {
	_, err := fmt.Fprintln(p.writer, base64.RawURLEncoding.EncodeToString(b))
	return err
}
func (*pipe) Close(CloseStatus, string) {}
func (*pipe) CloseNow()                 {}
func (*pipe) Codec() FrameCodec         { return CodecBinary }
func (*pipe) TransportName() string     { return "synthetic-pipe" }

type resolver struct {
	invite, credential []byte
	enrolled           bool
}

func (r *resolver) ResolveE2EESecret(_ context.Context, s E2EEAuthSelector) ([]byte, error) {
	if s.Version != 1 {
		return nil, errors.New("version")
	}
	if s.Kind == E2EEAuthInvitation && s.ID == "synthetic-invite" && !r.enrolled {
		return append([]byte(nil), r.invite...), nil
	}
	if s.Kind == E2EEAuthCredential && s.ID == "synthetic-credential" && r.enrolled {
		return append([]byte(nil), r.credential...), nil
	}
	return nil, errors.New("selector")
}
func (r *resolver) CompleteE2EEAuth(ctx context.Context, s E2EEAuthSelector, ok bool) (E2EEAuthResult, error) {
	if !ok {
		return E2EEAuthResult{}, errors.New("proof")
	}
	if _, err := r.ResolveE2EESecret(ctx, s); err != nil {
		return E2EEAuthResult{}, err
	}
	result := E2EEAuthResult{Identity: AuthenticatedIdentity{DeviceID: "synthetic-device", CredentialID: "synthetic-credential", CredentialVersion: 1, Role: "reader", Locale: "en"}}
	if s.Kind == E2EEAuthInvitation {
		r.enrolled = true
		result.CredentialSecret = append([]byte(nil), r.credential...)
	}
	return result, nil
}
func (*resolver) IsE2EEAuthRejected(error) bool { return true }

// RunPipe uses the original Go handshake, encryption and parser implementations.
// Only the transport, auth authority and application responses are synthetic.
func RunPipe() error {
	r := &resolver{invite: make([]byte, 32), credential: make([]byte, 32)}
	if _, err := rand.Read(r.invite); err != nil {
		return err
	}
	if _, err := rand.Read(r.credential); err != nil {
		return err
	}
	fmt.Fprintln(os.Stdout, base64.RawURLEncoding.EncodeToString(r.invite))
	scan := bufio.NewScanner(os.Stdin)
	scan.Buffer(make([]byte, 4096), 8*1024*1024)
	p := &pipe{reader: scan, writer: os.Stdout}
	for round := 0; round < 2; round++ {
		session, identity, err := performServerE2EEHandshake(context.Background(), p, r)
		if err != nil {
			return fmt.Errorf("round %d handshake: %w", round, err)
		}
		if identity.Role != "reader" {
			return errors.New("wrong role")
		}
		for _, kind := range []string{"refresh_agents", "read_pane"} {
			frame, err := p.ReadFrame(context.Background())
			if err != nil {
				return err
			}
			plaintext, err := session.open(frame)
			if err != nil {
				return err
			}
			var request map[string]any
			if err = json.Unmarshal(plaintext, &request); err != nil {
				return err
			}
			if request["type"] != kind || request["protocol"] != float64(3) {
				return errors.New("unexpected application request")
			}
			response := []byte("{\"type\":\"agents\",\"agents\":[{\"pane_id\":\"synthetic-pane\",\"name\":\"Synthetic test agent\"}]}")
			if kind == "read_pane" {
				if request["pane_id"] != "synthetic-pane" {
					return errors.New("wrong pane")
				}
				response = []byte("{\"type\":\"pane_content\",\"pane_id\":\"synthetic-pane\",\"content\":\"synthetic terminal 中文\\n\",\"truncated\":false}")
			}
			frame, err = session.seal(response)
			if err != nil {
				return err
			}
			if err = p.WriteFrame(context.Background(), frame); err != nil {
				return err
			}
		}
	}
	fmt.Fprintln(os.Stderr, "PASS original Go handshake+finish+encrypted commands across invitation and credential reconnect")
	return nil
}
