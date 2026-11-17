package bridgectl

import (
	"context"
	"testing"
)

func TestWindowsManagementPipeRoundTrip(t *testing.T) {
	dir := privateTestDir(t)
	listener, err := Listen(dir)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go Serve(ctx, listener, func(req Request) Response { return Response{OK: req.Operation == "status"} })
	response, err := Call(ctx, dir, Request{Operation: "status"})
	if err != nil || !response.OK {
		t.Fatalf("response %+v, %v", response, err)
	}
	if other, err := Listen(dir); err == nil {
		other.Close()
		t.Fatal("second listener accepted")
	}
	conn, err := dial(ctx, dir)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	sid, err := currentSID()
	if err != nil {
		t.Fatal(err)
	}
	if err = verifyServer(conn, sid+"-99999"); err == nil {
		t.Fatal("server with a different expected SID was accepted")
	}
}
