package config

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
)

// WriteFileAtomic publishes a complete file after a successful encode, sync and
// close. The temporary file lives beside the final target and defaults to 0600.
func WriteFileAtomic(path string, write func(io.Writer) error) error {
	if _, err := os.Lstat(path); err == nil {
		// Installations may link the config to a persistent directory.
		target, err := filepath.EvalSymlinks(path)
		if err != nil {
			return fmt.Errorf("resolve config %s: %w", path, err)
		}
		path = target
	} else if !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("inspect config %s: %w", path, err)
	}

	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+"-*")
	if err != nil {
		return fmt.Errorf("create config temporary file for %s: %w", path, err)
	}
	defer os.Remove(tmp.Name())
	defer tmp.Close()

	if err := write(tmp); err != nil {
		return fmt.Errorf("encode config %s: %w", path, err)
	}
	if info, err := os.Stat(path); err == nil {
		if !info.Mode().IsRegular() {
			return fmt.Errorf("config %s is not a regular file", path)
		}
		if err := tmp.Chmod(info.Mode().Perm()); err != nil {
			return fmt.Errorf("set config permissions for %s: %w", path, err)
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("stat config %s: %w", path, err)
	}
	if err := tmp.Sync(); err != nil {
		return fmt.Errorf("sync config %s: %w", path, err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("close config %s: %w", path, err)
	}
	if err := os.Rename(tmp.Name(), path); err != nil {
		return fmt.Errorf("replace config %s: %w", path, err)
	}
	return nil
}
