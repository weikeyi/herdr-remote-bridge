//go:build !windows

package securestore

import (
	"errors"
	"io"
	"os"
	"path/filepath"
)

func EnsureDirectory(dir string) error {
	if err := os.MkdirAll(dir, 0700); err != nil {
		return err
	}
	info, err := os.Lstat(dir)
	if err != nil {
		return err
	}
	if !info.IsDir() || info.Mode()&os.ModeSymlink != 0 {
		return errors.New("credential directory must be a real directory")
	}
	return os.Chmod(dir, 0700)
}
func ReadFile(path string) ([]byte, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return nil, err
	}
	if !info.Mode().IsRegular() {
		return nil, errors.New("credential store must be a regular file")
	}
	if err = os.Chmod(path, 0600); err != nil {
		return nil, err
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	data, err := io.ReadAll(io.LimitReader(f, maxSize+1))
	if len(data) > maxSize {
		return nil, errors.New("credential store exceeds size limit")
	}
	return data, err
}
func WriteFile(path string, data []byte) error { return writeFile(path, data, true) }

// WriteNewFile atomically publishes a new protected file without replacing an
// existing path. Concurrent provisioning can have only one winner.
func WriteNewFile(path string, data []byte) error { return writeFile(path, data, false) }

func writeFile(path string, data []byte, replace bool) error {
	if len(data) > maxSize {
		return errors.New("credential store exceeds size limit")
	}
	dir := filepath.Dir(path)
	if err := EnsureDirectory(dir); err != nil {
		return err
	}
	f, err := os.CreateTemp(dir, ".credentials-*.tmp")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	defer f.Close()
	if err = f.Chmod(0600); err != nil {
		return err
	}
	if _, err = f.Write(data); err != nil {
		return err
	}
	if err = f.Sync(); err != nil {
		return err
	}
	if err = f.Close(); err != nil {
		return err
	}
	if replace {
		if err = os.Rename(f.Name(), path); err != nil {
			return err
		}
	} else {
		// link publishes the already flushed inode and refuses an existing target.
		if err = os.Link(f.Name(), path); err != nil {
			return err
		}
		if err = os.Remove(f.Name()); err != nil {
			return err
		}
	}
	d, err := os.Open(dir)
	if err != nil {
		return err
	}
	defer d.Close()
	return d.Sync()
}
