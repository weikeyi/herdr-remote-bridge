package main

import (
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"os"

	"github.com/0cv/herdr-mobile-relay/internal/bridgectl"
	"github.com/0cv/herdr-mobile-relay/internal/config"
)

func runBridge(args []string) (int, error) {
	if len(args) == 0 {
		return 2, errors.New("usage: herdr-mobile-relay bridge pair|devices|revoke|status|self-test")
	}
	req := bridgectl.Request{Operation: args[0]}
	flags := flag.NewFlagSet("bridge "+args[0], flag.ContinueOnError)
	flags.SetOutput(os.Stderr)
	switch req.Operation {
	case "pair":
		flags.StringVar(&req.Origin, "origin", "", "configured HTTPS phone app origin")
		flags.StringVar(&req.Name, "name", "Phone", "device name")
		flags.StringVar(&req.Role, "role", "reader", "reader or explicitly authorized controller")
	case "revoke":
		flags.StringVar(&req.DeviceID, "device-id", "", "device ID from bridge devices")
	case "devices", "status", "self-test":
	default:
		return 2, errors.New("unknown bridge operation")
	}
	if err := flags.Parse(args[1:]); err != nil {
		return 2, err
	}
	if flags.NArg() != 0 {
		return 2, errors.New("unexpected bridge arguments")
	}
	if req.Operation == "self-test" {
		result, err := bridgectl.SelfTest(context.Background())
		if encodeErr := json.NewEncoder(os.Stdout).Encode(result); encodeErr != nil {
			return 1, encodeErr
		}
		return status(err)
	}
	if req.Operation == "pair" && (req.Origin == "" || (req.Role != "reader" && req.Role != "controller")) {
		return 2, errors.New("pair requires --origin HTTPS_ORIGIN and --role reader|controller")
	}
	if req.Operation == "revoke" && req.DeviceID == "" {
		return 2, errors.New("revoke requires --device-id ID")
	}
	cfg, err := config.Load()
	if err != nil {
		return 1, err
	}
	response, err := bridgectl.Call(context.Background(), cfg.RuntimeDir, req)
	if err != nil {
		return 1, fmt.Errorf("cannot reach current user's running bridge: %w", err)
	}
	if !response.OK {
		return 1, errors.New(response.Error)
	}
	// Pair output includes a one-use secret. Write only to the operator's stdout,
	// never the relay logger or a persistent file.
	return status(json.NewEncoder(os.Stdout).Encode(response.Data))
}
