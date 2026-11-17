package securestore

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"unsafe"

	"golang.org/x/sys/windows"
)

var envelope = []byte("HERDR-DPAPI-CURRENTUSER-v1\x00")

func currentSID() (*windows.SID, error) {
	user, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return nil, err
	}
	return user.User.Sid, nil
}
func descriptor(dir bool) (*windows.SECURITY_DESCRIPTOR, error) {
	sid, err := currentSID()
	if err != nil {
		return nil, err
	}
	flags := ""
	if dir {
		flags = "OICI"
	}
	return windows.SecurityDescriptorFromString("O:" + sid.String() + "D:P(A;" + flags + ";FA;;;" + sid.String() + ")")
}

// Open without following reparse points. No delete-sharing keeps the object
// stable while its metadata, ACL and contents are checked.
func openHandle(path string, access uint32, dir bool) (windows.Handle, error) {
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return 0, err
	}
	flags := uint32(windows.FILE_FLAG_OPEN_REPARSE_POINT)
	if dir {
		flags |= windows.FILE_FLAG_BACKUP_SEMANTICS
	}
	h, err := windows.CreateFile(p, access, windows.FILE_SHARE_READ, nil, windows.OPEN_EXISTING, flags, 0)
	if err != nil {
		return 0, err
	}
	var info windows.ByHandleFileInformation
	err = windows.GetFileInformationByHandle(h, &info)
	if err == nil && (info.FileAttributes&windows.FILE_ATTRIBUTE_REPARSE_POINT != 0 || (info.FileAttributes&windows.FILE_ATTRIBUTE_DIRECTORY != 0) != dir || (!dir && info.NumberOfLinks != 1)) {
		err = errors.New("credential path is a reparse point, hard link or wrong object type")
	}
	if err != nil {
		windows.CloseHandle(h)
		return 0, err
	}
	return h, nil
}
func checkACL(h windows.Handle, dir bool) error {
	sd, err := windows.GetSecurityInfo(h, windows.SE_FILE_OBJECT, windows.OWNER_SECURITY_INFORMATION|windows.DACL_SECURITY_INFORMATION)
	if err != nil {
		return err
	}
	owner, _, err := sd.Owner()
	if err != nil {
		return err
	}
	sid, err := currentSID()
	if err != nil {
		return err
	}
	if !windows.EqualSid(owner, sid) {
		return errors.New("credential object is not owned by current user")
	}
	control, _, err := sd.Control()
	if err != nil {
		return err
	}
	if control&windows.SE_DACL_PROTECTED == 0 {
		return errors.New("credential ACL inherits permissions")
	}
	acl, _, err := sd.DACL()
	if err != nil {
		return err
	}
	if acl == nil || acl.AceCount != 1 {
		return errors.New("credential ACL must grant only the current user")
	}
	var ace *windows.ACCESS_ALLOWED_ACE
	if err = windows.GetAce(acl, 0, &ace); err != nil {
		return err
	}
	expectedFlags := byte(0)
	if dir {
		expectedFlags = windows.OBJECT_INHERIT_ACE | windows.CONTAINER_INHERIT_ACE
	}
	if ace.Header.AceType != windows.ACCESS_ALLOWED_ACE_TYPE || ace.Header.AceFlags != expectedFlags || ace.Mask != 0x001f01ff || !windows.EqualSid((*windows.SID)(unsafe.Pointer(&ace.SidStart)), sid) {
		return errors.New("credential ACL is not current-user-only full control")
	}
	return nil
}
func setACL(h windows.Handle, dir bool) error {
	sd, err := descriptor(dir)
	if err != nil {
		return err
	}
	acl, _, err := sd.DACL()
	if err != nil {
		return err
	}
	if err = windows.SetSecurityInfo(h, windows.SE_FILE_OBJECT, windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION, nil, nil, acl, nil); err != nil {
		return err
	}
	return checkACL(h, dir)
}
func EnsureDirectory(dir string) error {
	// Create ancestors normally, then create the secret-bearing final directory
	// with its restrictive descriptor from birth. Existing directories must be
	// owned by this user; their DACL is tightened before any secret is written.
	if err := os.MkdirAll(filepath.Dir(dir), 0700); err != nil {
		return err
	}
	sd, err := descriptor(true)
	if err != nil {
		return err
	}
	p, err := windows.UTF16PtrFromString(dir)
	if err != nil {
		return err
	}
	sa := windows.SecurityAttributes{Length: uint32(unsafe.Sizeof(windows.SecurityAttributes{})), SecurityDescriptor: sd}
	err = windows.CreateDirectory(p, &sa)
	if err != nil && !errors.Is(err, windows.ERROR_ALREADY_EXISTS) {
		return err
	}
	h, err := openHandle(dir, windows.READ_CONTROL|windows.WRITE_DAC, true)
	if err != nil {
		return err
	}
	defer windows.CloseHandle(h)
	actual, err := windows.GetSecurityInfo(h, windows.SE_FILE_OBJECT, windows.OWNER_SECURITY_INFORMATION)
	if err != nil {
		return err
	}
	owner, _, err := actual.Owner()
	if err != nil {
		return err
	}
	sid, err := currentSID()
	if err != nil {
		return err
	}
	if !windows.EqualSid(owner, sid) {
		return errors.New("credential directory belongs to another user")
	}
	return setACL(h, true)
}
func crypt(data []byte, encrypt bool) ([]byte, error) {
	if len(data) == 0 {
		return nil, errors.New("empty DPAPI input")
	}
	in := windows.DataBlob{Size: uint32(len(data)), Data: &data[0]}
	var out windows.DataBlob
	var err error
	if encrypt {
		err = windows.CryptProtectData(&in, nil, nil, 0, nil, windows.CRYPTPROTECT_UI_FORBIDDEN, &out)
	} else {
		err = windows.CryptUnprotectData(&in, nil, nil, 0, nil, windows.CRYPTPROTECT_UI_FORBIDDEN, &out)
	}
	if err != nil {
		return nil, fmt.Errorf("DPAPI current-user protection: %w", err)
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(out.Data)))
	view := unsafe.Slice(out.Data, int(out.Size))
	result := append([]byte(nil), view...)
	clear(view)
	return result, nil
}
func ReadFile(path string) ([]byte, error) {
	h, err := openHandle(path, windows.GENERIC_READ|windows.READ_CONTROL, false)
	if err != nil {
		return nil, err
	}
	f := os.NewFile(uintptr(h), path)
	defer f.Close()
	if err = checkACL(h, false); err != nil {
		return nil, err
	}
	data, err := io.ReadAll(io.LimitReader(f, maxSize+65537))
	if err != nil {
		return nil, err
	}
	if len(data) > maxSize+65536 {
		return nil, errors.New("encrypted credential store exceeds limit")
	}
	if !bytes.HasPrefix(data, envelope) {
		return nil, errors.New("Windows credential store must use DPAPI; plaintext migration is not automatic")
	}
	plain, err := crypt(data[len(envelope):], false)
	if err != nil {
		return nil, err
	}
	if len(plain) == 0 || plain[0] != 1 {
		clear(plain)
		return nil, errors.New("invalid DPAPI credential payload")
	}
	if len(plain) > maxSize+1 {
		clear(plain)
		return nil, errors.New("credential store exceeds limit")
	}
	return plain[1:], nil
}
func WriteFile(path string, data []byte) error { return writeFile(path, data, true) }

// WriteNewFile atomically publishes a new protected file without replacing an
// existing path. Concurrent provisioning can have only one winner.
func WriteNewFile(path string, data []byte) error { return writeFile(path, data, false) }

func writeFile(path string, data []byte, replace bool) error {
	if len(data) > maxSize {
		return errors.New("credential store exceeds limit")
	}
	dir := filepath.Dir(path)
	if err := EnsureDirectory(dir); err != nil {
		return err
	}
	// Keep the directory open without delete sharing for the full transaction.
	dh, err := openHandle(dir, windows.READ_CONTROL, true)
	if err != nil {
		return err
	}
	defer windows.CloseHandle(dh)
	if err = checkACL(dh, true); err != nil {
		return err
	}
	// Authenticate a framing byte as well, so an empty plaintext has a valid
	// nonempty DPAPI input and remains distinct from a malformed envelope.
	framed := append([]byte{1}, data...)
	encrypted, err := crypt(framed, true)
	clear(framed)
	if err != nil {
		return err
	}
	var nonce [16]byte
	if _, err = rand.Read(nonce[:]); err != nil {
		return err
	}
	temp := filepath.Join(dir, ".credentials-"+hex.EncodeToString(nonce[:])+".tmp")
	p, err := windows.UTF16PtrFromString(temp)
	if err != nil {
		return err
	}
	sd, err := descriptor(false)
	if err != nil {
		return err
	}
	sa := windows.SecurityAttributes{Length: uint32(unsafe.Sizeof(windows.SecurityAttributes{})), SecurityDescriptor: sd}
	h, err := windows.CreateFile(p, windows.GENERIC_WRITE|windows.READ_CONTROL, 0, &sa, windows.CREATE_NEW, windows.FILE_ATTRIBUTE_NORMAL, 0)
	if err != nil {
		return err
	}
	f := os.NewFile(uintptr(h), temp)
	defer os.Remove(temp)
	defer f.Close()
	if err = checkACL(h, false); err != nil {
		return err
	}
	if _, err = f.Write(envelope); err != nil {
		return err
	}
	if _, err = f.Write(encrypted); err != nil {
		return err
	}
	if err = f.Sync(); err != nil {
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	target, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return err
	}
	flags := uint32(windows.MOVEFILE_WRITE_THROUGH)
	if replace {
		flags |= windows.MOVEFILE_REPLACE_EXISTING
	}
	return windows.MoveFileEx(p, target, flags)
}
