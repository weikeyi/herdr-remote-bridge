//go:build !windows

package bridgectl

import (
	"context"
	"errors"
	"testing"
)

func TestOtherPlatformsFailClosed(t *testing.T) {
	if _, err := Listen(privateTestDir(t)); !errors.Is(err, ErrUnsupported) {
		t.Fatal(err)
	}
	if _, err := Call(context.Background(), privateTestDir(t), Request{Operation: "status"}); !errors.Is(err, ErrUnsupported) {
		t.Fatal(err)
	}
}

func TestSelfTestOtherPlatformsUnsupported(t *testing.T) {
	result, err := SelfTest(t.Context())
	if !errors.Is(err, ErrUnsupported) || result.Supported || result.OK || len(result.Checks) != 0 {
		t.Fatalf("result %+v error %v", result, err)
	}
}
