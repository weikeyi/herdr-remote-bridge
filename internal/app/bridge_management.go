package app

import (
	"context"
	"encoding/base64"
	"errors"
	"io"
	"net/url"
	"os"
	"runtime"
	"strconv"
	"strings"

	"github.com/0cv/herdr-mobile-relay/internal/bridgectl"
	"github.com/0cv/herdr-mobile-relay/internal/deviceauth"
	"github.com/0cv/herdr-mobile-relay/internal/gatewaywire"
	"github.com/0cv/herdr-mobile-relay/internal/setuphelper"
)

func (s *Server) startBridgeManagement(ctx context.Context) (io.Closer, error) {
	if runtime.GOOS != "windows" || s.cfg.Backend != "hc" {
		return nil, nil
	}
	listener, err := bridgectl.Listen(s.cfg.RuntimeDir)
	if err != nil {
		return nil, err
	}
	go bridgectl.Serve(ctx, listener, s.handleBridgeManagement)
	return listener, nil
}

func (s *Server) handleBridgeManagement(req bridgectl.Request) bridgectl.Response {
	fail := func(message string) bridgectl.Response { return bridgectl.Response{Error: message} }
	if s.deviceAuth == nil {
		return fail("device management is unavailable")
	}
	switch req.Operation {
	case "status":
		s.mu.RLock()
		ready := s.ready
		s.mu.RUnlock()
		return bridgectl.Response{OK: true, Data: map[string]any{"ready": ready, "backend": s.cfg.Backend, "relay_only": s.cfg.ForceRelayTransport, "version": s.version, "revision": s.revision, "process_id": os.Getpid()}}
	case "devices":
		return bridgectl.Response{OK: true, Data: map[string]any{"devices": s.deviceAuth.ListCredentials("")}}
	case "pair":
		origin, err := setuphelper.NormalizeOrigin(req.Origin, false)
		if err != nil {
			return fail("pairing requires a valid HTTPS app origin")
		}
		allowed := false
		for _, candidate := range s.cfg.AllowedOrigins {
			if candidate == origin {
				allowed = true
			}
		}
		if !allowed {
			return fail("app origin must be configured in HERDR_ALLOWED_ORIGINS")
		}
		if !s.cfg.ForceRelayTransport || len(s.cfg.GatewayURLs) == 0 || len(s.cfg.Token) != 32 {
			return fail("pairing requires configured relay-only gateway transport")
		}
		for _, gateway := range s.cfg.GatewayURLs {
			u, err := url.Parse(gateway)
			if err != nil || u.Scheme != "wss" || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
				return fail("pairing requires WSS gateway origins")
			}
		}
		role := deviceauth.Role(req.Role)
		if role == "" {
			role = deviceauth.RoleReader
		}
		if role != deviceauth.RoleReader && role != deviceauth.RoleController {
			return fail("role must be reader or controller")
		}
		name := strings.TrimSpace(req.Name)
		if name == "" {
			name = "Phone"
		}
		relayID, err := gatewaywire.DeriveRelayID(s.cfg.Token)
		if err != nil {
			return fail("cannot derive gateway identity")
		}
		rendezvous, err := gatewaywire.DeriveRendezvousKey(s.cfg.Token)
		if err != nil {
			return fail("cannot derive gateway identity")
		}
		invite, err := s.deviceAuth.CreateInvitation(name, role, "en")
		if err != nil {
			return fail("cannot create invitation")
		}
		label, _ := os.Hostname()
		if label == "" {
			label = "This computer"
		}
		params, _ := url.ParseQuery(setuphelper.SetupFragment(invite.Secret, label, ""))
		params.Set("invite", invite.InvitationID)
		params.Set("invite_version", strconv.FormatUint(invite.Version, 10))
		params.Set("invite_expires", strconv.FormatInt(invite.ExpiresAt.UnixMilli(), 10))
		params.Set("gateways", strings.Join(s.cfg.GatewayURLs, ","))
		params.Set("relay_id", relayID)
		params.Set("rendezvous", base64.RawURLEncoding.EncodeToString(rendezvous))
		params.Set("path_policy", "relay-only")
		return bridgectl.Response{OK: true, Data: map[string]any{"url": origin + "/#" + params.Encode(), "role": invite.Role, "expires_at": invite.ExpiresAt}}
	case "revoke":
		if req.DeviceID == "" {
			return fail("device_id is required")
		}
		credentialID, found := deviceCredentialID(s.deviceAuth, req.DeviceID)
		if !found {
			return fail("device was not found")
		}
		credential, err := s.deviceAuth.RevokeCredential(credentialID)
		if errors.Is(err, deviceauth.ErrLastController) {
			return fail("cannot revoke the last controller; pair a replacement controller first")
		}
		if err != nil {
			return fail("cannot revoke device")
		}
		s.forgetPushTest(credential.DeviceID)
		if s.hub != nil {
			s.hub.DisconnectCredential(credential.CredentialID, credential.Version)
		}
		if s.pushM != nil {
			if err = s.pushM.RemoveDevice(credential.DeviceID); err != nil {
				return fail("device revoked, but notification cleanup failed")
			}
		}
		return bridgectl.Response{OK: true, Data: map[string]any{"device": credential}}
	default:
		return fail("unknown management operation")
	}
}
