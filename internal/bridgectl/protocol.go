package bridgectl

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net"
	"time"
)

const MaxRequestBytes = 8192
const MaxResponseBytes = 1 << 20

var ErrUnsupported = errors.New("bridge local management requires Windows")

type Request struct {
	Operation string `json:"operation"`
	Origin    string `json:"origin,omitempty"`
	Name      string `json:"name,omitempty"`
	Role      string `json:"role,omitempty"`
	DeviceID  string `json:"device_id,omitempty"`
}
type Response struct {
	OK    bool   `json:"ok"`
	Error string `json:"error,omitempty"`
	Data  any    `json:"data,omitempty"`
}
type Handler func(Request) Response

func decodeLine(r io.Reader, limit int, value any) error {
	scanner := bufio.NewScanner(io.LimitReader(r, int64(limit+1)))
	scanner.Buffer(make([]byte, 1024), limit+1)
	if !scanner.Scan() {
		return errors.New("missing or oversized management message")
	}
	line := scanner.Bytes()
	if len(line) > limit {
		return errors.New("oversized management message")
	}
	decoder := json.NewDecoder(bytes.NewReader(line))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(value); err != nil {
		return errors.New("invalid management message")
	}
	var extra any
	if decoder.Decode(&extra) != io.EOF {
		return errors.New("trailing management data")
	}
	return nil
}

func ServeConnection(ctx context.Context, conn net.Conn, handler Handler) {
	defer conn.Close()
	stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
	defer stop()
	_ = conn.SetDeadline(time.Now().Add(5 * time.Second))
	var req Request
	response := Response{Error: "invalid management request"}
	if err := decodeLine(conn, MaxRequestBytes, &req); err == nil {
		response = handler(req)
	}
	encoded, err := json.Marshal(response)
	if err != nil || len(encoded) > MaxResponseBytes {
		encoded = []byte(`{"ok":false,"error":"management response too large"}`)
	}
	_, _ = conn.Write(append(encoded, '\n'))
}

func Serve(ctx context.Context, listener net.Listener, handler Handler) {
	stop := context.AfterFunc(ctx, func() { _ = listener.Close() })
	defer stop()
	defer listener.Close()
	slots := make(chan struct{}, 8)
	for {
		conn, err := listener.Accept()
		if err != nil {
			return
		}
		select {
		case slots <- struct{}{}:
			go func() { defer func() { <-slots }(); ServeConnection(ctx, conn, handler) }()
		default:
			_ = conn.Close()
		}
	}
}

func Call(ctx context.Context, runtimeDir string, req Request) (Response, error) {
	var result Response
	data, err := json.Marshal(req)
	if err != nil || len(data) > MaxRequestBytes {
		return result, errors.New("management request too large")
	}
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	conn, err := dial(ctx, runtimeDir)
	if err != nil {
		return result, err
	}
	defer conn.Close()
	stop := context.AfterFunc(ctx, func() { _ = conn.Close() })
	defer stop()
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	if _, err = conn.Write(append(data, '\n')); err != nil {
		return result, err
	}
	err = decodeLine(conn, MaxResponseBytes, &result)
	return result, err
}
