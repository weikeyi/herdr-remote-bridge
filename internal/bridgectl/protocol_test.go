package bridgectl

import (
	"context"
	"encoding/json"
	"net"
	"strings"
	"testing"
)

func TestManagementDecodeLimitsAndUnknownFields(t *testing.T) {
	for _, input := range []string{"", `{"operation":"status","unexpected":true}` + "\n", `{"operation":` + "\n", `{"operation":"status"} {}` + "\n", strings.Repeat("x", MaxRequestBytes+1) + "\n"} {
		var req Request
		if err := decodeLine(strings.NewReader(input), MaxRequestBytes, &req); err == nil {
			t.Errorf("accepted invalid input (%d bytes)", len(input))
		}
	}
	var req Request
	if err := decodeLine(strings.NewReader("{\"operation\":\"status\"}\n"), MaxRequestBytes, &req); err != nil || req.Operation != "status" {
		t.Fatalf("decode: %v %+v", err, req)
	}
}
func TestManagementConnectionIsSingleRequest(t *testing.T) {
	server, client := net.Pipe()
	defer client.Close()
	done := make(chan struct{})
	go func() {
		defer close(done)
		ServeConnection(context.Background(), server, func(req Request) Response { return Response{OK: req.Operation == "status"} })
	}()
	if _, err := client.Write([]byte("{\"operation\":\"status\"}\n")); err != nil {
		t.Fatal(err)
	}
	var result Response
	if err := json.NewDecoder(client).Decode(&result); err != nil || !result.OK {
		t.Fatalf("response: %+v %v", result, err)
	}
	<-done
}
