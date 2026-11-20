package main

import (
	"context"
	"errors"
	"fmt"
	"github.com/go-gost/core/auth"
	"github.com/go-gost/core/logger"
	"github.com/go-gost/core/service"
	api_service "github.com/go-gost/x/api/service"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/config/loader"
	auth_parser "github.com/go-gost/x/config/parsing/auth"
	"github.com/go-gost/x/config/parsing/parser"
	xmetrics "github.com/go-gost/x/metrics"
	metrics "github.com/go-gost/x/metrics/service"
	"github.com/go-gost/x/registry"
	xservice "github.com/go-gost/x/service"
	"github.com/judwhite/go-svc"
	"net"
	"net/http"
	"os"
	"os/signal"
	"reflect"
	"strings"
	"syscall"
	"time"
)

type program struct {
	management   [3]managementListener
	cancel       context.CancelFunc
	stopReporter func()
	stopping     bool // guarded by config.RuntimeMu
}

type managementListener struct {
	cfg  any
	addr string
	srv  service.Service
}

func (p *program) Init(env svc.Environment) error {
	parser.Init(parser.Args{
		CfgFile:     cfgFile,
		Services:    services,
		Nodes:       nodes,
		Debug:       debug,
		Trace:       trace,
		ApiAddr:     apiAddr,
		MetricsAddr: metricsAddr,
	})

	return nil
}

func (p *program) Start() error {
	cfg, err := parser.Parse()
	if err != nil {
		return err
	}

	if outputFormat != "" {
		if err := cfg.Write(os.Stdout, outputFormat); err != nil {
			return err
		}
		os.Exit(0)
	}

	config.RuntimeMu.Lock()
	err = p.applyConfig(cfg)
	config.RuntimeMu.Unlock()
	if err != nil {
		return err
	}

	ctx, cancel := context.WithCancel(context.Background())
	p.cancel = cancel
	go p.reload(ctx)

	go func() {
		select {
		case <-time.After(10 * time.Second):
			xservice.StartConfigReporter(ctx)
		case <-ctx.Done():
			return
		}
	}()

	return nil
}

func (p *program) applyConfig(cfg *config.Config) error {
	var started []service.Service
	if err := config.Replace(cfg, func(old *config.Config) error {
		var err error
		started, err = loader.Apply(old, cfg, func() error { return p.replaceManagement(cfg) })
		return err
	}); err != nil {
		return err
	}
	for _, srv := range started {
		go srv.Serve()
	}
	return nil
}

func (p *program) replaceManagement(cfg *config.Config) error {
	next := [3]managementListener{}
	if cfg.API != nil {
		next[0] = managementListener{cfg: cfg.API, addr: cfg.API.Addr}
	}
	if cfg.Metrics != nil && cfg.Metrics.Addr != "" {
		next[1] = managementListener{cfg: cfg.Metrics, addr: cfg.Metrics.Addr}
	}
	if cfg.Profiling != nil {
		addr := cfg.Profiling.Addr
		if addr == "" {
			addr = ":6060"
		}
		next[2] = managementListener{cfg: cfg.Profiling, addr: addr}
	}
	var keep, retired [3]bool
	for i := range next {
		keep[i] = reflect.DeepEqual(next[i].cfg, p.management[i].cfg)
		if keep[i] {
			next[i] = p.management[i]
		}
	}
	for i := range next {
		if keep[i] || next[i].cfg == nil {
			continue
		}
		for j := range p.management {
			prev := p.management[j]
			if !keep[j] && !retired[j] && prev.srv != nil && prev.addr == next[i].addr {
				prev.srv.Close()
				retired[j] = true
			}
		}
		srv, err := p.buildManagement(next[i])
		if err != nil {
			// Close every staged listener before rebinding an old address.
			for j := range next {
				if !keep[j] && next[j].srv != nil {
					next[j].srv.Close()
				}
			}
			// Loader restores the old registries after this returns. Named management
			// authenticators resolve dynamically and deny access while unavailable.
			for j := range p.management {
				if !retired[j] {
					continue
				}
				restored, restoreErr := p.buildManagement(p.management[j])
				p.management[j].srv = restored
				if restoreErr != nil {
					err = errors.Join(err, fmt.Errorf("restore %s: %w", managementName(j), restoreErr))
				} else {
					go serveManagement(managementName(j), restored)
				}
			}
			return fmt.Errorf("build %s: %w", managementName(i), err)
		}
		next[i].srv = srv
	}
	for i, prev := range p.management {
		if !keep[i] && !retired[i] && prev.srv != nil {
			prev.srv.Close()
		}
	}
	p.management = next
	xmetrics.Enable(next[1].srv != nil)
	for i, entry := range next {
		if !keep[i] && entry.srv != nil {
			go serveManagement(managementName(i), entry.srv)
		}
	}
	return nil
}

func managementName(i int) string { return [...]string{"@api", "@metrics", "@profiling"}[i] }

func (p *program) buildManagement(entry managementListener) (service.Service, error) {
	switch cfg := entry.cfg.(type) {
	case *config.APIConfig:
		return buildApiService(cfg, p.reloadConfigLocked)
	case *config.MetricsConfig:
		return buildMetricsService(cfg)
	case *config.ProfilingConfig:
		// pprof has no network authentication. Keep the default port and make
		// implicit host bindings loopback-only.
		addr, err := localManagementAddr(entry.addr, nil)
		if err != nil {
			return nil, err
		}
		ln, err := net.Listen("tcp", addr)
		if err != nil {
			return nil, err
		}
		return &profileServer{listener: ln, server: &http.Server{}}, nil
	default:
		return nil, fmt.Errorf("unknown management service")
	}
}

type profileServer struct {
	listener net.Listener
	server   *http.Server
}

func (p *profileServer) Addr() net.Addr { return p.listener.Addr() }
func (p *profileServer) Serve() error   { return p.server.Serve(p.listener) }
func (p *profileServer) Close() error   { p.server.Close(); return p.listener.Close() }

func serveManagement(name string, srv service.Service) {
	defer srv.Close()
	log := logger.Default().WithFields(map[string]any{"kind": "service", "service": name})
	log.Info("listening on ", srv.Addr())
	if err := srv.Serve(); err != nil && !errors.Is(err, http.ErrServerClosed) && !errors.Is(err, net.ErrClosed) {
		log.Error(err)
	}
}

func (p *program) Stop() error {
	if p.cancel != nil {
		p.cancel()
	}
	if p.stopReporter != nil {
		p.stopReporter()
	}
	config.RuntimeMu.Lock()
	p.stopping = true
	forwarding := registry.ServiceRegistry().GetAll()
	management := p.management
	p.management = [3]managementListener{}
	config.RuntimeMu.Unlock()

	// Close and drain forwarding connections before the bounded reporting tail.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	closed := make(chan struct{}, len(forwarding))
	for name, srv := range forwarding {
		go func(name string, srv service.Service) {
			defer func() { closed <- struct{}{} }()
			if err := srv.Close(); err != nil && !errors.Is(err, net.ErrClosed) {
				logger.Default().Warnf("close %s: %v", name, err)
			}
			if drainer, ok := srv.(interface{ Done() <-chan struct{} }); ok {
				select {
				case <-drainer.Done():
				case <-ctx.Done():
				}
			}
		}(name, srv)
	}
	for _, entry := range management {
		if entry.srv != nil {
			entry.srv.Close()
		}
	}
	for range forwarding {
		select {
		case <-closed:
		case <-ctx.Done():
			logger.Default().Warn("forwarding shutdown timed out")
			goto flush
		}
	}
flush:
	xservice.GetGlobalTrafficManager().Stop()
	return nil
}

func (p *program) reload(ctx context.Context) {
	c := make(chan os.Signal, 1)
	signal.Notify(c, syscall.SIGHUP)
	defer signal.Stop(c)

	for {
		select {
		case <-c:
			if err := p.reloadConfig(); err != nil {
				logger.Default().Error(err)
			} else {
				logger.Default().Info("config reloaded")
			}

		case <-ctx.Done():
			return
		}
	}
}

func (p *program) reloadConfig() error {
	config.RuntimeMu.Lock()
	defer config.RuntimeMu.Unlock()
	return p.reloadConfigLocked()
}

func (p *program) reloadConfigLocked() error {
	if p.stopping {
		return fmt.Errorf("service is stopping")
	}
	cfg, err := parser.Parse()
	if err != nil {
		return err
	}
	return p.applyConfig(cfg)
}

// localManagementAddr retains Unix and explicit loopback configurations. An
// unspecified host with no credentials listens on loopback on the same port.
func localManagementAddr(addr string, auther auth.Authenticator) (string, error) {
	if auther != nil || strings.HasPrefix(addr, "unix://") {
		return addr, nil
	}
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return "", err
	}
	if host == "" {
		return net.JoinHostPort("127.0.0.1", port), nil
	}
	if strings.EqualFold(host, "localhost") {
		return addr, nil
	}
	if ip := net.ParseIP(host); ip != nil && ip.IsLoopback() {
		return addr, nil
	}
	return "", fmt.Errorf("management address %q requires authentication or a loopback binding", addr)
}

func managementAuth(inline *config.AuthConfig, name string) (auth.Authenticator, error) {
	if name != "" {
		return registry.StrictAuther(name), nil
	}
	if inline == nil {
		return nil, nil
	}
	if strings.TrimSpace(inline.Username) == "" || strings.TrimSpace(inline.Password) == "" {
		return nil, fmt.Errorf("management authentication requires username and password")
	}
	return auth_parser.ParseAutherFromAuth(inline), nil
}

func buildApiService(cfg *config.APIConfig, reload func() error) (service.Service, error) {
	auther, err := managementAuth(cfg.Auth, cfg.Auther)
	if err != nil {
		return nil, err
	}
	addr, err := localManagementAddr(cfg.Addr, auther)
	if err != nil {
		return nil, err
	}
	network := "tcp"
	if strings.HasPrefix(addr, "unix://") {
		network = "unix"
		addr = strings.TrimPrefix(addr, "unix://")
	}
	return api_service.NewService(
		network, addr,
		api_service.PathPrefixOption(cfg.PathPrefix),
		api_service.AccessLogOption(cfg.AccessLog),
		api_service.AutherOption(auther),
		api_service.ReloadOption(reload),
	)
}

func buildMetricsService(cfg *config.MetricsConfig) (service.Service, error) {
	auther, err := managementAuth(cfg.Auth, cfg.Auther)
	if err != nil {
		return nil, err
	}
	addr, err := localManagementAddr(cfg.Addr, auther)
	if err != nil {
		return nil, err
	}
	network := "tcp"
	if strings.HasPrefix(addr, "unix://") {
		network = "unix"
		addr = strings.TrimPrefix(addr, "unix://")
	}
	return metrics.NewService(
		network, addr,
		metrics.PathOption(cfg.Path),
		metrics.AutherOption(auther),
	)
}
