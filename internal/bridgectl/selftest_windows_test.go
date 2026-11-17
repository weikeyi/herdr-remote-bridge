package bridgectl

import "testing"

func TestWindowsSelfTest(t *testing.T) {
	result, err := SelfTest(t.Context())
	if err != nil || !result.Supported || !result.OK {
		t.Fatalf("result %+v, error %v", result, err)
	}
	for _, name := range []string{"current_user_directory", "dpapi_ciphertext", "dpapi_roundtrip", "overwrite_rejected", "duplicate_pipe_rejected", "current_user_pipe_roundtrip", "temporary_cleanup"} {
		if !result.Checks[name] {
			t.Errorf("missing success for %s", name)
		}
	}
}
