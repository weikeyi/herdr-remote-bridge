//go:build !windows

package bridgectl

import "context"

func SelfTest(ctx context.Context) (SelfTestResult, error) {
	return SelfTestResult{Checks: map[string]bool{}}, ErrUnsupported
}
