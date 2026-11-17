package childenv

import (
	"context"
	"os"
	"os/exec"
	"strings"
)

func WithoutReleaseCredentials(environment []string) []string {
	filtered := make([]string, 0, len(environment))
	for _, variable := range environment {
		key, _, _ := strings.Cut(variable, "=")
		switch strings.ToUpper(key) {
		case "GH_TOKEN", "GITHUB_TOKEN", "HERDR_GITHUB_TOKEN_FILE",
			"HERDR_RELAY_TOKEN", "HERDR_RELAY_TOKEN_FILE",
			"HERDR_GATEWAY_REGISTRATION_TOKEN", "HERDR_GATEWAY_REGISTRATION_TOKEN_FILE",
			"HERDR_BOOTSTRAP_ROLE":
			continue
		}
		filtered = append(filtered, variable)
	}
	return filtered
}

func Command(name string, args ...string) *exec.Cmd {
	command := exec.Command(name, args...)
	command.Env = WithoutReleaseCredentials(os.Environ())
	return command
}

func CommandContext(ctx context.Context, name string, args ...string) *exec.Cmd {
	command := exec.CommandContext(ctx, name, args...)
	command.Env = WithoutReleaseCredentials(os.Environ())
	return command
}
