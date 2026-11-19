package transport

// Test-only adapter around the actual production gateway and framing packages.
// Never imported by the Android app. All material is ephemeral synthetic data.
import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/0cv/herdr-mobile-relay/internal/framing"
	"github.com/0cv/herdr-mobile-relay/internal/gateway"
	"github.com/0cv/herdr-mobile-relay/internal/gatewaywire"
	"github.com/coder/websocket"
)

type fixtureCA struct {
	cert tls.Certificate
	pem  []byte
	pool *x509.CertPool
}

func newFixtureCA() (fixtureCA, error) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return fixtureCA{}, err
	}
	now := time.Now()
	ca := &x509.Certificate{SerialNumber: big.NewInt(now.UnixNano()), Subject: pkix.Name{CommonName: "Synthetic native harness CA"}, NotBefore: now.Add(-time.Minute), NotAfter: now.Add(time.Hour), IsCA: true, BasicConstraintsValid: true, KeyUsage: x509.KeyUsageCertSign | x509.KeyUsageDigitalSignature}
	caDER, err := x509.CreateCertificate(rand.Reader, ca, ca, &key.PublicKey, key)
	if err != nil {
		return fixtureCA{}, err
	}
	leafKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return fixtureCA{}, err
	}
	leaf := &x509.Certificate{SerialNumber: big.NewInt(now.UnixNano() + 1), Subject: pkix.Name{CommonName: "Synthetic loopback only"}, NotBefore: now.Add(-time.Minute), NotAfter: now.Add(time.Hour), KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}, IPAddresses: []net.IP{net.ParseIP("127.0.0.1")}}
	der, err := x509.CreateCertificate(rand.Reader, leaf, ca, &leafKey.PublicKey, key)
	if err != nil {
		return fixtureCA{}, err
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: caDER})
	pool := x509.NewCertPool()
	if !pool.AppendCertsFromPEM(certPEM) {
		return fixtureCA{}, errors.New("test CA")
	}
	return fixtureCA{cert: tls.Certificate{Certificate: [][]byte{der, caDER}, PrivateKey: leafKey}, pem: certPEM, pool: pool}, nil
}
func fixtureServer(handler http.Handler, ca fixtureCA) *httptest.Server {
	server := httptest.NewUnstartedServer(handler)
	server.TLS = &tls.Config{Certificates: []tls.Certificate{ca.cert}, MinVersion: tls.VersionTLS12}
	server.StartTLS()
	return server
}

type fixtureMux struct {
	socket  *websocket.Conn
	writeMu sync.Mutex
}

func (m *fixtureMux) write(ctx context.Context, op byte, id uint32, payload []byte) error {
	m.writeMu.Lock()
	defer m.writeMu.Unlock()
	bounded, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	return m.socket.Write(bounded, websocket.MessageBinary, gatewaywire.EncodeFrame(op, id, payload))
}

type fixtureFrameConn struct {
	mux    *fixtureMux
	id     uint32
	ctx    context.Context
	cancel context.CancelFunc
	input  chan []byte
	recv   *framing.Reassembler
	sendMu sync.Mutex
	once   sync.Once
}

func (c *fixtureFrameConn) ReadFrame(ctx context.Context) ([]byte, error) {
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil, ctx.Err()
		case <-c.ctx.Done():
			return nil, c.ctx.Err()
		case <-ticker.C:
			if c.recv.Expired(time.Now()) {
				c.CloseNow()
				return nil, errors.New("synthetic framing stalled")
			}
		case part := <-c.input:
			logical, err := c.recv.Push(part)
			if err != nil {
				c.CloseNow()
				return nil, err
			}
			if logical != nil {
				return logical, nil
			}
		}
	}
}
func (c *fixtureFrameConn) WriteFrame(ctx context.Context, payload []byte) error {
	c.sendMu.Lock()
	defer c.sendMu.Unlock()
	if len(payload) > framing.MaxLogicalBytes {
		return errors.New("synthetic response too large")
	}
	for _, part := range framing.Chunk(nil, payload, framing.GatewayChunkSize) {
		if err := c.mux.write(ctx, gatewaywire.OpData, c.id, part); err != nil {
			return err
		}
	}
	return nil
}
func (c *fixtureFrameConn) Close(_ CloseStatus, _ string) { c.CloseNow() }
func (c *fixtureFrameConn) CloseNow() {
	c.once.Do(func() {
		c.cancel()
		_ = c.mux.write(context.Background(), gatewaywire.OpClose, c.id, []byte("synthetic_closed"))
	})
}
func (*fixtureFrameConn) Codec() FrameCodec     { return CodecBinary }
func (*fixtureFrameConn) TransportName() string { return "actual-gateway-synthetic-relay" }

func registerFixtureRelay(ctx context.Context, origin, token string, ca fixtureCA, resolver *SyntheticResolver) (*fixtureMux, error) {
	client := &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{RootCAs: ca.pool, MinVersion: tls.VersionTLS12}}}
	conn, _, err := websocket.Dial(ctx, strings.Replace(origin, "https:", "wss:", 1)+"/relay", &websocket.DialOptions{HTTPClient: client})
	if err != nil {
		return nil, err
	}
	conn.SetReadLimit(gatewaywire.MaxFramePayload + gatewaywire.HeaderSize)
	fail := func(err error) (*fixtureMux, error) { conn.CloseNow(); return nil, err }
	kind, data, err := conn.Read(ctx)
	if err != nil {
		return fail(err)
	}
	var hello gatewaywire.ServerHello
	if kind != websocket.MessageText || json.Unmarshal(data, &hello) != nil || hello.Type != gatewaywire.TypeServerHello || hello.Proto != gatewaywire.Proto {
		return fail(errors.New("synthetic registration hello"))
	}
	nonce, err := base64.RawURLEncoding.DecodeString(hello.Nonce)
	if err != nil || len(nonce) != 32 {
		return fail(errors.New("synthetic registration nonce"))
	}
	request := gatewaywire.RegisterHello{Type: gatewaywire.TypeRegister, Proto: gatewaywire.Proto, RelayID: resolver.RelayID(), RegistrationProof: base64.RawURLEncoding.EncodeToString(gatewaywire.RegistrationProof(token, resolver.RelayID(), nonce))}
	data, err = json.Marshal(request)
	if err != nil {
		return fail(err)
	}
	if err = conn.Write(ctx, websocket.MessageText, data); err != nil {
		return fail(err)
	}
	kind, data, err = conn.Read(ctx)
	if err != nil {
		return fail(err)
	}
	var ready gatewaywire.ReadyMessage
	if kind != websocket.MessageText || json.Unmarshal(data, &ready) != nil || ready.Type != gatewaywire.TypeReady {
		return fail(errors.New("synthetic registration rejected"))
	}
	return &fixtureMux{socket: conn}, nil
}
func serveFixtureMux(ctx context.Context, mux *fixtureMux, resolver *SyntheticResolver) error {
	clients := map[uint32]*fixtureFrameConn{}
	defer func() {
		for _, c := range clients {
			c.cancel()
		}
		mux.socket.CloseNow()
	}()
	for {
		kind, data, err := mux.socket.Read(ctx)
		if err != nil {
			return err
		}
		if kind != websocket.MessageBinary {
			return errors.New("synthetic relay received text")
		}
		op, id, payload, err := gatewaywire.DecodeFrame(data)
		if err != nil {
			return err
		}
		switch op {
		case gatewaywire.OpOpen:
			var open gatewaywire.OpenPayload
			if json.Unmarshal(payload, &open) != nil {
				return errors.New("synthetic open malformed")
			}
			nonce, e1 := base64.RawURLEncoding.DecodeString(open.Nonce)
			proof, e2 := base64.RawURLEncoding.DecodeString(open.Proof)
			if e1 != nil || e2 != nil || !gatewaywire.VerifyConnectProof(resolver.RendezvousKey(), resolver.RelayID(), nonce, proof) {
				_ = mux.write(ctx, gatewaywire.OpClose, id, []byte("unauthorized"))
				continue
			}
			if old := clients[id]; old != nil {
				old.cancel()
			}
			child, cancel := context.WithCancel(ctx)
			c := &fixtureFrameConn{mux: mux, id: id, ctx: child, cancel: cancel, input: make(chan []byte, 32), recv: framing.NewReassembler(framing.GatewayChunkSize)}
			clients[id] = c
			go func() {
				defer c.CloseNow()
				if ServeSynthetic(c, resolver) != nil {
					fmt.Fprintln(os.Stderr, "synthetic application connection ended")
				}
			}()
		case gatewaywire.OpData:
			c := clients[id]
			if c == nil {
				_ = mux.write(ctx, gatewaywire.OpClose, id, []byte("unknown"))
				continue
			}
			select {
			case c.input <- append([]byte(nil), payload...):
			case <-c.ctx.Done():
				delete(clients, id)
			default:
				c.CloseNow()
				delete(clients, id)
			}
		case gatewaywire.OpClose:
			if c := clients[id]; c != nil {
				c.cancel()
				delete(clients, id)
			}
		case gatewaywire.OpPing:
			if err := mux.write(ctx, gatewaywire.OpPong, id, payload); err != nil {
				return err
			}
		case gatewaywire.OpNotice: // Advisory quota notice; no terminal payload.
		default:
			return errors.New("synthetic relay opcode")
		}
	}
}
func syntheticInvitation(origin string, r *SyntheticResolver) string {
	values := url.Values{"setup": {base64.RawURLEncoding.EncodeToString(r.InviteSecret())}, "label": {"Synthetic harness"}, "invite": {"synthetic-invitation-0001"}, "invite_version": {"1"}, "invite_expires": {fmt.Sprint(time.Now().Add(10 * time.Minute).UnixMilli())}, "gateways": {strings.Replace(origin, "https:", "wss:", 1)}, "relay_id": {r.RelayID()}, "rendezvous": {base64.RawURLEncoding.EncodeToString(r.RendezvousKey())}, "path_policy": {"relay-only"}}
	return origin + "/#" + values.Encode()
}
func RunWSS() error {
	dir := os.Getenv("HARNESS_DIR")
	if dir == "" {
		return errors.New("HARNESS_DIR required")
	}
	ca, err := newFixtureCA()
	if err != nil {
		return err
	}
	badCA, err := newFixtureCA()
	if err != nil {
		return err
	}
	caPath := filepath.Join(dir, "test-ca.pem")
	if err = os.WriteFile(caPath, ca.pem, 0600); err != nil {
		return err
	}
	r := NewSyntheticResolver()
	token := make([]byte, 32)
	if _, err = rand.Read(token); err != nil {
		return err
	}
	registration := base64.RawURLEncoding.EncodeToString(token)
	server, err := gateway.New(gateway.Options{RegistrationToken: registration, AllowedRelayIDs: map[string]bool{r.RelayID(): true}, STUNAddr: "", ConnectRatePerMinute: 120})
	if err != nil {
		return err
	}
	defer server.Close()
	good := fixtureServer(server.Handler(), ca)
	defer good.Close()
	bad := fixtureServer(server.Handler(), badCA)
	defer bad.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()
	mux, err := registerFixtureRelay(ctx, good.URL, registration, ca, r)
	if err != nil {
		return err
	}
	configuration := map[string]string{"origin": good.URL, "invitation": syntheticInvitation(good.URL, r), "ca_pem_path": caPath, "bad_origin": bad.URL, "bad_invitation": syntheticInvitation(bad.URL, r), "wrong_host_origin": strings.Replace(good.URL, "127.0.0.1", "localhost", 1)}
	// Private stdout pipe consumed by test JVM. Contains synthetic enrollment only.
	if err = json.NewEncoder(os.Stdout).Encode(configuration); err != nil {
		return err
	}
	return serveFixtureMux(ctx, mux, r)
}
