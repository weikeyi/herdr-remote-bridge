package coordinator

import "context"

type authenticatedDeviceContextKey struct{}

// WithAuthenticatedDevice binds command identity to the authenticated transport,
// never to a device ID supplied in a command payload.
func WithAuthenticatedDevice(ctx context.Context, deviceID string) context.Context {
	return context.WithValue(ctx, authenticatedDeviceContextKey{}, deviceID)
}

func promptLedgerKey(ctx context.Context, paneID, requestID string, generation int64) string {
	deviceID, _ := ctx.Value(authenticatedDeviceContextKey{}).(string)
	return "submit_prompt\x00" + hashPayload(struct {
		DeviceID   string
		PaneID     string
		RequestID  string
		Generation int64
	}{deviceID, paneID, requestID, generation})
}
