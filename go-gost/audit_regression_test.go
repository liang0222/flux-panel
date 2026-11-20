package main

import (
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/go-gost/core/logger"
	xconfig "github.com/go-gost/x/config"
	xlogger "github.com/go-gost/x/logger"
	"github.com/go-gost/x/registry"
)

func TestResolveConfigPathsAndNodeSecret(t *testing.T) {
	dir := t.TempDir()
	node, forwarding := filepath.Join(dir, "config.json"), filepath.Join(dir, "gost.yaml")
	if err := os.WriteFile(forwarding, []byte("services: []\n"), 0600); err != nil {
		t.Fatal(err)
	}
	for _, input := range []string{dir, forwarding} {
		n, f, p, err := resolveConfigPaths(input, true)
		if err != nil || n != node || f != forwarding || p != forwarding {
			t.Fatalf("paths for %q: %q %q %q %v", input, n, f, p, err)
		}
	}
	for _, test := range []struct {
		secret string
		valid  bool
	}{{"", false}, {"   ", false}, {"fixture secret", true}} {
		if err := os.WriteFile(node, []byte(`{"addr":"127.0.0.1:12345","secret":"`+test.secret+`"}`), 0600); err != nil {
			t.Fatal(err)
		}
		cfg, err := LoadConfig(node)
		if (err == nil) != test.valid || test.valid && cfg.Secret != test.secret {
			t.Fatalf("valid=%v: %v", test.valid, err)
		}
	}
	addr, err := localManagementAddr(":9000", nil)
	if err != nil || addr != "127.0.0.1:9000" {
		t.Fatalf("default management address: %q, %v", addr, err)
	}
}

func TestReloadRestoresForwardingAndComponents(t *testing.T) {
	oldConfig, oldLogger := xconfig.Global(), logger.Default()
	logger.SetDefault(xlogger.WrapDefault(xlogger.NewLogger()))
	name := "audit-local-test"
	other := "audit-failure-test"
	t.Cleanup(func() {
		registry.ServiceRegistry().Unregister(name)
		registry.ServiceRegistry().Unregister(other)
		registry.AutherRegistry().Unregister(name)
		registry.AutherRegistry().Unregister(other)
		xconfig.Set(oldConfig)
		if oldLogger != nil {
			logger.SetDefault(oldLogger)
		}
	})
	port, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := port.Addr().String()
	port.Close()
	makeSrv := func(name, addr, handler string) *xconfig.ServiceConfig {
		return &xconfig.ServiceConfig{Name: name, Addr: addr, Listener: &xconfig.ListenerConfig{Type: "tcp"}, Handler: &xconfig.HandlerConfig{Type: handler}}
	}
	old := &xconfig.Config{Services: []*xconfig.ServiceConfig{makeSrv(name, addr, "auto")}, Authers: []*xconfig.AutherConfig{{Name: name}}}
	p := &program{}
	if err := p.applyConfig(old); err != nil {
		t.Fatal(err)
	}
	// Stage a same-address listener, then fail to construct a second service.
	bad := &xconfig.Config{Services: []*xconfig.ServiceConfig{makeSrv(name, addr, "http"), makeSrv(other, "127.0.0.1:0", "unknown-test-handler")}, Authers: []*xconfig.AutherConfig{{Name: other}}}
	if err := p.applyConfig(bad); err == nil || !strings.Contains(err.Error(), "unknown handler") {
		t.Fatalf("expected failed candidate, got %v", err)
	}
	if got := xconfig.Global().Services; len(got) != 1 || got[0].Handler.Type != "auto" {
		t.Fatalf("failed candidate was published: %v", got)
	}
	if !registry.AutherRegistry().IsRegistered(name) || registry.AutherRegistry().IsRegistered(other) || registry.ServiceRegistry().IsRegistered(other) {
		t.Fatal("candidate registry entries escaped rollback")
	}
	srv := registry.ServiceRegistry().Get(name)
	if srv == nil || srv.Addr().String() != addr {
		t.Fatalf("original listener not restored: %v", srv)
	}
	conn, err := net.DialTimeout("tcp", addr, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	conn.Close()

	// A paused service remains registered with a closed listener. A failed
	// replacement must leave that exact service paused.
	if err := srv.Close(); err != nil {
		t.Fatal(err)
	}
	xconfig.OnUpdate(func(cfg *xconfig.Config) error {
		cfg.Services[0].Metadata = map[string]any{"paused": true}
		return nil
	})
	if err := p.applyConfig(bad); err == nil || !strings.Contains(err.Error(), "unknown handler") {
		t.Fatalf("expected failed candidate after pause, got %v", err)
	}
	if registry.ServiceRegistry().Get(name) != srv || xconfig.Global().Services[0].Metadata["paused"] != true {
		t.Fatal("failed reload replaced a paused service")
	}
	probe, err := net.Listen("tcp", addr)
	if err != nil {
		t.Fatalf("paused port was reopened: %v", err)
	}
	probe.Close()
}

func TestManagementReloadFailurePreservesListener(t *testing.T) {
	oldConfig, oldLogger := xconfig.Global(), logger.Default()
	logger.SetDefault(xlogger.WrapDefault(xlogger.NewLogger()))
	p := &program{}
	t.Cleanup(func() {
		for _, l := range p.management {
			if l.srv != nil {
				l.srv.Close()
			}
		}
		xconfig.Set(oldConfig)
		if oldLogger != nil {
			logger.SetDefault(oldLogger)
		}
	})
	old := &xconfig.Config{Metrics: &xconfig.MetricsConfig{Addr: "127.0.0.1:0"}}
	if err := p.applyConfig(old); err != nil {
		t.Fatal(err)
	}
	addr := p.management[1].srv.Addr().String()
	candidate := &xconfig.Config{Metrics: &xconfig.MetricsConfig{Addr: "127.0.0.1:0", Path: "invalid-path"}}
	if err := p.applyConfig(candidate); err == nil {
		t.Fatal("invalid metrics should fail")
	}
	if p.management[1].srv == nil || xconfig.Global().Metrics.Path != "" {
		t.Fatal("previous metrics config was not retained")
	}
	conn, err := net.DialTimeout("tcp", p.management[1].srv.Addr().String(), time.Second)
	if err != nil {
		t.Fatalf("metrics listener (was %s): %v", addr, err)
	}
	conn.Close()
}
