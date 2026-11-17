package bridgectl

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"net"
	"path/filepath"
	"strings"

	"github.com/Microsoft/go-winio"
	"golang.org/x/sys/windows"
)

func currentSID() (string, error) {
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return "", err
	}
	return user.User.Sid.String(), nil
}
func endpoint(runtimeDir, sid string) (string, error) {
	dir, err := filepath.Abs(runtimeDir)
	if err != nil {
		return "", err
	}
	hash := sha256.Sum256([]byte(sid + "\x00" + strings.ToLower(filepath.Clean(dir))))
	return `\\.\pipe\herdr-bridge-` + hex.EncodeToString(hash[:16]), nil
}
func Listen(runtimeDir string) (net.Listener, error) {
	sid, err := currentSID()
	if err != nil {
		return nil, err
	}
	path, err := endpoint(runtimeDir, sid)
	if err != nil {
		return nil, err
	}
	// Protected DACL grants only the current user's SID access. go-winio also
	// sets FILE_PIPE_REJECT_REMOTE_CLIENTS and first-instance exclusivity.
	return winio.ListenPipe(path, &winio.PipeConfig{SecurityDescriptor: "D:P(A;;GA;;;" + sid + ")", InputBufferSize: MaxRequestBytes, OutputBufferSize: MaxRequestBytes})
}
func dial(ctx context.Context, runtimeDir string) (net.Conn, error) {
	sid, err := currentSID()
	if err != nil {
		return nil, err
	}
	path, err := endpoint(runtimeDir, sid)
	if err != nil {
		return nil, err
	}
	conn, err := winio.DialPipeContext(ctx, path)
	if err != nil {
		return nil, err
	}
	if err = verifyServer(conn, sid); err != nil {
		conn.Close()
		return nil, err
	}
	return conn, nil
}
func verifyServer(conn net.Conn, sid string) error {
	raw, ok := conn.(interface{ Fd() uintptr })
	if !ok {
		return errors.New("cannot verify management pipe owner")
	}
	var pid uint32
	if err := windows.GetNamedPipeServerProcessId(windows.Handle(raw.Fd()), &pid); err != nil {
		return err
	}
	process, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return err
	}
	defer windows.CloseHandle(process)
	var token windows.Token
	if err = windows.OpenProcessToken(process, windows.TOKEN_QUERY, &token); err != nil {
		return err
	}
	defer token.Close()
	user, err := token.GetTokenUser()
	if err != nil {
		return err
	}
	if user.User.Sid.String() != sid {
		return errors.New("management pipe belongs to another user")
	}
	return nil
}
