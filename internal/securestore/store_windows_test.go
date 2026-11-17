package securestore

import (
	"bytes"
	"os"
	"path/filepath"
	"testing"
	"unsafe"

	"golang.org/x/sys/windows"
)

func TestWindowsCiphertextAndTamper(t *testing.T) {
	path := filepath.Join(privateTestDir(t), "secret")
	plain := []byte("known secret value not for disk")
	if err := WriteFile(path, plain); err != nil {
		t.Fatal(err)
	}
	disk, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.HasPrefix(disk, envelope) || bytes.Contains(disk, plain) {
		t.Fatal("plaintext or missing envelope")
	}
	disk[len(disk)-1] ^= 1
	if err = os.WriteFile(path, disk, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err = ReadFile(path); err == nil {
		t.Fatal("tampered DPAPI accepted")
	}
}
func TestWindowsRefusesPlaintext(t *testing.T) {
	path := filepath.Join(privateTestDir(t), "secret")
	if err := WriteFile(path, []byte("seed")); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(`{"secret":"legacy"}`), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadFile(path); err == nil {
		t.Fatal("plaintext migration allowed")
	}
}
func TestWindowsRefusesBroadACL(t *testing.T) {
	path := filepath.Join(privateTestDir(t), "secret")
	if err := WriteFile(path, []byte("seed")); err != nil {
		t.Fatal(err)
	}
	sd, err := windows.SecurityDescriptorFromString("D:P(A;;FA;;;WD)")
	if err != nil {
		t.Fatal(err)
	}
	acl, _, err := sd.DACL()
	if err != nil {
		t.Fatal(err)
	}
	h, err := openHandle(path, windows.WRITE_DAC, false)
	if err != nil {
		t.Fatal(err)
	}
	err = windows.SetSecurityInfo(h, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, acl, nil)
	windows.CloseHandle(h)
	if err != nil {
		t.Fatal(err)
	}
	if _, err = ReadFile(path); err == nil {
		t.Fatal("Everyone ACL accepted")
	}
}
func TestWindowsRefusesHardlink(t *testing.T) {
	path := filepath.Join(privateTestDir(t), "secret")
	if err := WriteFile(path, []byte("seed")); err != nil {
		t.Fatal(err)
	}
	if err := os.Link(path, path+"-link"); err != nil {
		t.Fatal(err)
	}
	if _, err := ReadFile(path); err == nil {
		t.Fatal("multiply linked credential accepted")
	}
}
func TestWindowsDPAPIRoundTrip(t *testing.T) {
	// Cross-account rejection must additionally run in native Windows CI.
	encrypted, err := crypt([]byte("secret"), true)
	if err != nil {
		t.Fatal(err)
	}
	in := windows.DataBlob{Size: uint32(len(encrypted)), Data: &encrypted[0]}
	var out windows.DataBlob
	if err = windows.CryptUnprotectData(&in, nil, nil, 0, nil, windows.CRYPTPROTECT_UI_FORBIDDEN, &out); err != nil {
		t.Fatal(err)
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(out.Data)))
	if string(unsafe.Slice(out.Data, int(out.Size))) != "secret" {
		t.Fatal("DPAPI roundtrip mismatch")
	}
}
