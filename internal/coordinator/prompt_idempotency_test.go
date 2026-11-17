package coordinator

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/0cv/herdr-mobile-relay/internal/herdr"
)

func newPromptLedgerFixture(t *testing.T) (*Dispatcher, string) {
	t.Helper()
	dir := t.TempDir()
	record := filepath.Join(dir, "prompts.log")
	client := herdr.NewClient(recordingHerdr(t, dir, record, `{"ok":true}`), filepath.Join(dir, "sock"))
	state := NewState(testLogger())
	state.CommitInventory([]*AgentState{{PaneID: "pane-1", Agent: "claude", Status: "idle"}, {PaneID: "pane-2", Agent: "claude", Status: "idle"}}, state.RevisionCounter())
	d := NewDispatcher(client, state, nil, testLogger())
	t.Cleanup(func() {
		if err := d.Close(context.Background()); err != nil {
			t.Error(err)
		}
		if err := client.Close(); err != nil {
			t.Error(err)
		}
	})
	return d, record
}

func promptMessage(pane, text string) map[string]any {
	return map[string]any{"action": "submit_prompt", "request_id": "same-request", "pane_id": pane, "text": text}
}

func assertPromptCalls(t *testing.T, record string, want int) {
	t.Helper()
	data, err := os.ReadFile(record)
	if err != nil {
		t.Fatal(err)
	}
	if got := strings.Count(string(data), "agent prompt "); got != want {
		t.Fatalf("prompt calls = %d, want %d: %s", got, want, data)
	}
}

func TestPromptLedgerReplayAndPayloadConflict(t *testing.T) {
	d, record := newPromptLedgerFixture(t)
	ctx := WithAuthenticatedDevice(t.Context(), "phone-a")
	first := d.Handle(ctx, promptMessage("pane-1", "hello"))
	replay := d.Handle(ctx, promptMessage("pane-1", "hello"))
	if !first.OK || !replay.OK || !replay.replayed {
		t.Fatalf("results: %+v / %+v", first, replay)
	}
	conflict := d.Handle(ctx, promptMessage("pane-1", "different"))
	if conflict.OK || !strings.EqualFold(conflict.Error, ErrConflict.Error()) {
		t.Fatalf("conflict: %+v", conflict)
	}
	assertPromptCalls(t, record, 1)
}

func TestPromptLedgerScopesAuthenticatedDeviceAndTarget(t *testing.T) {
	d, record := newPromptLedgerFixture(t)
	for _, request := range []struct{ device, pane string }{{"phone-a", "pane-1"}, {"phone-b", "pane-1"}, {"phone-a", "pane-2"}} {
		if result := d.Handle(WithAuthenticatedDevice(t.Context(), request.device), promptMessage(request.pane, "hello")); !result.OK || result.replayed {
			t.Fatalf("result: %+v", result)
		}
	}
	// A payload cannot switch the authenticated identity and evade deduplication.
	message := promptMessage("pane-1", "hello")
	message["device_id"] = "attacker-supplied"
	if result := d.Handle(WithAuthenticatedDevice(t.Context(), "phone-a"), message); !result.OK || !result.replayed {
		t.Fatalf("result: %+v", result)
	}
	assertPromptCalls(t, record, 3)
	ctx := WithAuthenticatedDevice(t.Context(), "phone-a")
	if promptLedgerKey(ctx, "pane-1", "same-request", 1) == promptLedgerKey(ctx, "pane-1", "same-request", 2) {
		t.Fatal("target generations share a ledger key")
	}
}

func TestPromptLedgerConcurrentDuplicates(t *testing.T) {
	d, record := newPromptLedgerFixture(t)
	ctx := WithAuthenticatedDevice(t.Context(), "phone-a")
	var wg sync.WaitGroup
	results := make([]*CommandResult, 8)
	start := make(chan struct{})
	for i := range results {
		wg.Add(1)
		go func() { defer wg.Done(); <-start; results[i] = d.Handle(ctx, promptMessage("pane-1", "hello")) }()
	}
	close(start)
	wg.Wait()
	replayed := 0
	for _, result := range results {
		if !result.OK {
			t.Fatalf("result: %+v", result)
		}
		if result.replayed {
			replayed++
		}
	}
	if replayed != 7 {
		t.Fatalf("replayed = %d, want 7", replayed)
	}
	assertPromptCalls(t, record, 1)
}

func TestPromptLedgerDoesNotResendAfterUnsuccessfulDispatch(t *testing.T) {
	dir := t.TempDir()
	record := filepath.Join(dir, "prompts.log")
	bin := writeScript(t, dir, "herdr", "#!/bin/sh\nprintf '%s\\n' \"$*\" >> \""+record+"\"\nexit 1\n")
	client := herdr.NewClient(bin, filepath.Join(dir, "sock"))
	defer client.Close()
	state := NewState(testLogger())
	state.CommitInventory([]*AgentState{{PaneID: "pane-1", Agent: "claude", Status: "idle"}}, state.RevisionCounter())
	d := NewDispatcher(client, state, nil, testLogger())
	defer d.Close(context.Background())
	ctx := WithAuthenticatedDevice(t.Context(), "phone-a")
	first := d.Handle(ctx, promptMessage("pane-1", "hello"))
	replay := d.Handle(ctx, promptMessage("pane-1", "hello"))
	if first.OK || replay.OK || !replay.replayed || first.Error != replay.Error || first.Phase != replay.Phase {
		t.Fatalf("results: %+v / %+v", first, replay)
	}
	assertPromptCalls(t, record, 1)
}
