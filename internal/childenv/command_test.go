package childenv

import (
	"context"
	"os/exec"
	"reflect"
	"strings"
	"testing"
)

func TestWithoutReleaseCredentials(t *testing.T) {
	input := []string{"GH_TOKEN=fixture", "PATH=/bin", "GITHUB_TOKEN=fixture", "HERDR_GITHUB_TOKEN_FILE=/private/fixture", "HTTPS_PROXY=http://proxy.invalid", "CLAUDE_CONFIG_DIR=/agent", "GH_TOKEN=duplicate"}
	want := []string{"PATH=/bin", "HTTPS_PROXY=http://proxy.invalid", "CLAUDE_CONFIG_DIR=/agent"}
	if got := WithoutReleaseCredentials(input); !reflect.DeepEqual(got, want) {
		t.Fatal("unexpected filtered environment")
	}
	if got := WithoutReleaseCredentials(nil); got == nil {
		t.Fatal("empty environment must not inherit ambient credentials")
	}
	if len(input) != 7 || input[0] != "GH_TOKEN=fixture" {
		t.Fatal("input was mutated")
	}
}

func TestCommandsIsolateReleaseCredentials(t *testing.T) {
	t.Setenv("GH_TOKEN", "synthetic")
	t.Setenv("GITHUB_TOKEN", "synthetic")
	t.Setenv("HERDR_GITHUB_TOKEN_FILE", "/private/synthetic")
	t.Setenv("CHILDENV_TEST_VALUE", "preserved")
	for name, create := range map[string]func() *exec.Cmd{
		"command": func() *exec.Cmd {
			return Command("/bin/sh", "-c", `test -z "${GH_TOKEN+x}${GITHUB_TOKEN+x}${HERDR_GITHUB_TOKEN_FILE+x}" && test "$CHILDENV_TEST_VALUE" = preserved`)
		},
		"context": func() *exec.Cmd {
			return CommandContext(context.Background(), "/bin/sh", "-c", `test -z "${GH_TOKEN+x}${GITHUB_TOKEN+x}${HERDR_GITHUB_TOKEN_FILE+x}" && test "$CHILDENV_TEST_VALUE" = preserved`)
		},
	} {
		t.Run(name, func(t *testing.T) {
			if err := create().Run(); err != nil {
				t.Fatalf("child credential boundary failed: %v", err)
			}
		})
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if err := CommandContext(ctx, "/bin/sh", "-c", "exit 0").Run(); err == nil {
		t.Fatal("cancelled child started")
	}
}

func TestRelayAuthorityNeverInheritedCaseInsensitively(t *testing.T) {
	keys := []string{"HERDR_RELAY_TOKEN", "HERDR_RELAY_TOKEN_FILE", "HERDR_GATEWAY_REGISTRATION_TOKEN", "HERDR_GATEWAY_REGISTRATION_TOKEN_FILE", "HERDR_BOOTSTRAP_ROLE", "GH_TOKEN", "GITHUB_TOKEN", "HERDR_GITHUB_TOKEN_FILE"}
	input := []string{"PATH=/bin", "PUBLIC_SETTING=kept"}
	for _, key := range keys {
		t.Setenv(key, "secret-sentinel")
	}
	for _, key := range keys {
		input = append(input, key+"=secret-sentinel", strings.ToLower(key)+"=secret-sentinel", strings.ToLower(key[:1])+key[1:]+"=secret-sentinel")
	}
	if got := WithoutReleaseCredentials(input); !reflect.DeepEqual(got, []string{"PATH=/bin", "PUBLIC_SETTING=kept"}) {
		t.Fatalf("authority leaked: %v", got)
	}
	for _, cmd := range []*exec.Cmd{Command("unused"), CommandContext(context.Background(), "unused")} {
		for _, value := range cmd.Env {
			if strings.Contains(value, "secret-sentinel") {
				t.Fatal("ambient relay authority leaked")
			}
		}
	}
}
