package main

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"strings"
	"sync"
)

type logOutput struct {
	mu     sync.Mutex
	writer io.Writer
}

type journalHandlerOperation struct {
	attrs []slog.Attr
	group string
}

type journalHandler struct {
	output     *logOutput
	format     string
	level      slog.Level
	operations []journalHandlerOperation
}

var _ slog.Handler = (*journalHandler)(nil)

func reportError(stderr *os.File, args []string, err error) {
	reportErrorWithJournal(stderr, args, err, stderrIsJournal(stderr))
}

func reportErrorWithJournal(stderr *os.File, args []string, err error, journal bool) {
	if !isServeInvocation(args) {
		fmt.Fprintf(stderr, "herdr-mobile-relay: %v\n", err)
		return
	}

	logger := newRelayLogger(stderr, os.Getenv("HERDR_RELAY_LOG_FORMAT"), slog.LevelError, journal)
	logger.Error("relay failed", "error", err)
}

func isServeInvocation(args []string) bool {
	return len(args) == 0 || args[0] == "serve"
}

func newRelayLogger(output io.Writer, format string, level slog.Level, journal bool) *slog.Logger {
	var handler slog.Handler
	options := &slog.HandlerOptions{Level: level}
	if journal {
		handler = newJournalHandler(output, format, level)
	} else if format == "json" {
		handler = slog.NewJSONHandler(output, options)
	} else {
		handler = slog.NewTextHandler(output, options)
	}
	return slog.New(&privacyHandler{Handler: handler})
}

func newJournalHandler(output io.Writer, format string, level slog.Level) *journalHandler {
	return &journalHandler{
		output: &logOutput{writer: output},
		format: format,
		level:  level,
	}
}

func (h *journalHandler) Enabled(_ context.Context, level slog.Level) bool {
	return level >= h.level
}

func (h *journalHandler) Handle(ctx context.Context, record slog.Record) error {
	if !h.Enabled(ctx, record.Level) {
		return nil
	}

	var output bytes.Buffer
	output.Write(journalPriorityPrefix(record.Level))
	options := &slog.HandlerOptions{Level: h.level}
	var handler slog.Handler
	if h.format == "json" {
		handler = slog.NewJSONHandler(&output, options)
	} else {
		handler = slog.NewTextHandler(&output, options)
	}
	for _, operation := range h.operations {
		if operation.group != "" {
			handler = handler.WithGroup(operation.group)
			continue
		}
		if len(operation.attrs) > 0 {
			handler = handler.WithAttrs(operation.attrs)
		}
	}
	if err := handler.Handle(ctx, record); err != nil {
		return err
	}

	return h.output.write(output.Bytes())
}

func (h *journalHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	if len(attrs) == 0 {
		return h
	}
	copied := make([]slog.Attr, len(attrs))
	copy(copied, attrs)
	return h.withOperation(journalHandlerOperation{attrs: copied})
}

func (h *journalHandler) WithGroup(name string) slog.Handler {
	if name == "" {
		return h
	}
	return h.withOperation(journalHandlerOperation{group: name})
}

func (h *journalHandler) withOperation(operation journalHandlerOperation) *journalHandler {
	operations := make([]journalHandlerOperation, len(h.operations)+1)
	copy(operations, h.operations)
	operations[len(h.operations)] = operation
	return &journalHandler{
		output:     h.output,
		format:     h.format,
		level:      h.level,
		operations: operations,
	}
}

func (o *logOutput) write(record []byte) error {
	o.mu.Lock()
	defer o.mu.Unlock()

	written, err := o.writer.Write(record)
	if err != nil {
		return err
	}
	if written != len(record) {
		return io.ErrShortWrite
	}
	return nil
}

func journalPriorityPrefix(level slog.Level) []byte {
	switch {
	case level < slog.LevelInfo:
		return []byte("<7>")
	case level < slog.LevelWarn:
		return []byte("<6>")
	case level < slog.LevelError:
		return []byte("<4>")
	default:
		return []byte("<3>")
	}
}

// privacyHandler filters runtime diagnostics before any output format or journal
// handler sees them. Error values can echo command payloads returned by core.
type privacyHandler struct{ slog.Handler }

func privateLogAttr(attr slog.Attr) slog.Attr {
	attr.Value = attr.Value.Resolve()
	if attr.Value.Kind() == slog.KindGroup {
		attrs := attr.Value.Group()
		clean := make([]slog.Attr, len(attrs))
		for i, child := range attrs {
			clean[i] = privateLogAttr(child)
		}
		return slog.Attr{Key: attr.Key, Value: slog.GroupValue(clean...)}
	}
	switch strings.ToLower(attr.Key) {
	case "error", "err", "text", "prompt", "content", "output", "stdout", "stderr", "response", "body", "payload", "secret", "token", "key", "keys", "url", "endpoint", "authorization", "message", "reason", "result", "args", "command":
		return slog.String(attr.Key, "[redacted]")
	}
	if attr.Value.Kind() == slog.KindAny {
		return slog.String(attr.Key, "[redacted]")
	}
	return attr
}

func (h *privacyHandler) Handle(ctx context.Context, record slog.Record) error {
	clean := slog.NewRecord(record.Time, record.Level, record.Message, record.PC)
	record.Attrs(func(attr slog.Attr) bool { clean.AddAttrs(privateLogAttr(attr)); return true })
	return h.Handler.Handle(ctx, clean)
}

func (h *privacyHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	clean := make([]slog.Attr, len(attrs))
	for i, attr := range attrs {
		clean[i] = privateLogAttr(attr)
	}
	return &privacyHandler{Handler: h.Handler.WithAttrs(clean)}
}

func (h *privacyHandler) WithGroup(name string) slog.Handler {
	return &privacyHandler{Handler: h.Handler.WithGroup(name)}
}
