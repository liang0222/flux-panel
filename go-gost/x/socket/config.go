package socket

import (
	"io"
	"path/filepath"
	"strings"

	"github.com/go-gost/x/config"
)

func saveConfig(path string) error {
	format := strings.ToLower(filepath.Ext(path))
	if format == ".yml" || format == ".yaml" {
		format = "yaml"
	} else {
		format = "json"
	}
	return config.OnUpdate(func(cfg *config.Config) error {
		return config.WriteFileAtomic(path, func(w io.Writer) error { return cfg.Write(w, format) })
	})
}
