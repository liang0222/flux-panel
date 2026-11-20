package config

import (
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
)

func TestWriteFileAtomicPreservesOldFileOnFailure(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "gost.json")
	if err := os.WriteFile(path, []byte("old"), 0600); err != nil {
		t.Fatal(err)
	}
	sentinel := errors.New("fixture write failed")
	if err := WriteFileAtomic(path, func(w io.Writer) error { _, _ = io.WriteString(w, "partial"); return sentinel }); !errors.Is(err, sentinel) {
		t.Fatalf("write err: %v", err)
	}
	contents, err := os.ReadFile(path)
	if err != nil || string(contents) != "old" {
		t.Fatalf("after failure: %q, %v", contents, err)
	}
	if err := WriteFileAtomic(path, func(w io.Writer) error { _, err := io.WriteString(w, "new"); return err }); err != nil {
		t.Fatal(err)
	}
	contents, err = os.ReadFile(path)
	entries, readErr := os.ReadDir(dir)
	if err != nil || readErr != nil || string(contents) != "new" || len(entries) != 1 {
		t.Fatalf("after success: %q %v %v %d", contents, err, readErr, len(entries))
	}
}
