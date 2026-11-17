package config

import "testing"

func TestBootstrapRoleConfiguration(t *testing.T) {
	for _, role := range []string{"", "reader", "controller", "admin"} {
		t.Run(role, func(t *testing.T) {
			isolateLoadEnvironment(t)
			t.Setenv("HERDR_BOOTSTRAP_ROLE", role)
			cfg, err := Load()
			if role == "admin" {
				if err == nil {
					t.Fatal("invalid role accepted")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			want := role
			if want == "" {
				want = "reader"
			}
			if cfg.BootstrapRole != want {
				t.Fatalf("got %q want %q", cfg.BootstrapRole, want)
			}
		})
	}
}
