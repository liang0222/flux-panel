package loader

import (
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"os"
	"reflect"
	"strings"

	reg "github.com/go-gost/core/registry"
	"github.com/go-gost/core/service"

	"github.com/go-gost/core/logger"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/config/parsing"
	admission_parser "github.com/go-gost/x/config/parsing/admission"
	auth_parser "github.com/go-gost/x/config/parsing/auth"
	bypass_parser "github.com/go-gost/x/config/parsing/bypass"
	chain_parser "github.com/go-gost/x/config/parsing/chain"
	hop_parser "github.com/go-gost/x/config/parsing/hop"
	hosts_parser "github.com/go-gost/x/config/parsing/hosts"
	ingress_parser "github.com/go-gost/x/config/parsing/ingress"
	limiter_parser "github.com/go-gost/x/config/parsing/limiter"
	logger_parser "github.com/go-gost/x/config/parsing/logger"
	observer_parser "github.com/go-gost/x/config/parsing/observer"
	recorder_parser "github.com/go-gost/x/config/parsing/recorder"
	resolver_parser "github.com/go-gost/x/config/parsing/resolver"
	router_parser "github.com/go-gost/x/config/parsing/router"
	sd_parser "github.com/go-gost/x/config/parsing/sd"
	service_parser "github.com/go-gost/x/config/parsing/service"
	xlogger "github.com/go-gost/x/logger"
	"github.com/go-gost/x/registry"
)

// Apply replaces the runtime configuration. The caller serializes management
// mutations and holds the config snapshot lock for this operation.
// commitExtra may stage and apply ancillary listeners after forwarding validation.
func Apply(old, cfg *config.Config, commitExtra func() error) ([]service.Service, error) {
	if err := validateNames(cfg); err != nil {
		return nil, err
	}
	tlsCfg, err := parsing.BuildDefaultTLSConfig(cfg.TLS)
	if err != nil {
		return nil, err
	}

	logCfg := &config.LogConfig{}
	if cfg.Log != nil {
		*logCfg = *cfg.Log
	}
	// 日志级别默认值：面板托管的节点长期运行，gost 默认的 info 级别会为每条
	// 转发连接打印两行日志（>-> / <-<），单节点可达每分钟数百行，导致 journald
	// 与磁盘持续膨胀。这里在配置文件未显式指定 level 时改为 warn。
	// 需要还原为 gost 原始行为：设置环境变量 GOST_LOG_LEVEL=info，或在
	// gost.json 中写入 "log": {"level": "info"}。
	if strings.TrimSpace(logCfg.Level) == "" {
		if lv := strings.TrimSpace(os.Getenv("GOST_LOG_LEVEL")); lv != "" {
			logCfg.Level = lv
		} else {
			logCfg.Level = "warn"
		}
	}
	nextLog := logger_parser.ParseLogger(&config.LoggerConfig{Log: logCfg})
	// Core stores its logger in an atomic.Value that requires one concrete
	// type. Use a stable local wrapper across reloads and logger options.
	candidateLog := xlogger.WrapDefault(nextLog)
	var components []transaction
	candidateLoggers := stageRegistry(registry.LoggerRegistry(), &components)
	fail := func(cause error, services *serviceTransaction) ([]service.Service, error) {
		if services != nil {
			services.discard()
		}
		for i := len(components) - 1; i >= 0; i-- {
			components[i].discard()
		}
		if services != nil {
			cause = errors.Join(cause, services.restore())
		}
		return nil, cause
	}
	if err := register(cfg, candidateLoggers, candidateLog, &components); err != nil {
		return fail(err, nil)
	}
	services := &serviceTransaction{
		oldConfigs: old.Services, oldServices: registry.ServiceRegistry().GetAll(),
		blocked: make(map[string]bool), next: make(map[string]service.Service),
		log: candidateLog, tls: tlsCfg, getLogger: func(name string) logger.Logger { return candidateLoggers[name] },
		reuse: reflect.DeepEqual(old.Log, cfg.Log) && reflect.DeepEqual(old.Loggers, cfg.Loggers) && reflect.DeepEqual(old.TLS, cfg.TLS),
	}
	if err := services.stage(cfg.Services); err != nil {
		return fail(err, services)
	}
	if commitExtra != nil {
		if err := commitExtra(); err != nil {
			return fail(err, services)
		}
	}
	for _, component := range components {
		component.commit()
	}
	logger.SetDefault(candidateLog)
	parsing.SetDefaultTLSConfig(tlsCfg)
	return services.commit(), nil
}

type transaction struct{ discard, commit func() }

func stageRegistry[T any](r reg.Registry[T], tx *[]transaction) map[string]T {
	values := make(map[string]T)
	*tx = append(*tx, transaction{
		discard: func() { closeValues(values) },
		commit:  func() { closeValues(registry.Replace(r, values)) },
	})
	return values
}

func closeValues[T any](values map[string]T) {
	for _, v := range values {
		if closer, ok := any(v).(io.Closer); ok {
			closer.Close()
		}
	}
}

func register(cfg *config.Config, loggers map[string]logger.Logger, log logger.Logger, tx *[]transaction) error {
	for _, loggerCfg := range cfg.Loggers {
		loggers[loggerCfg.Name] = logger_parser.ParseLogger(loggerCfg)
	}

	authers := stageRegistry(registry.AutherRegistry(), tx)
	for _, autherCfg := range cfg.Authers {
		authers[autherCfg.Name] = auth_parser.ParseAuther(autherCfg)
	}

	admissions := stageRegistry(registry.AdmissionRegistry(), tx)
	for _, admissionCfg := range cfg.Admissions {
		admissions[admissionCfg.Name] = admission_parser.ParseAdmission(admissionCfg)
	}

	bypasss := stageRegistry(registry.BypassRegistry(), tx)
	for _, bypassCfg := range cfg.Bypasses {
		bypasss[bypassCfg.Name] = bypass_parser.ParseBypass(bypassCfg)
	}

	resolvers := stageRegistry(registry.ResolverRegistry(), tx)
	for _, resolverCfg := range cfg.Resolvers {
		r, err := resolver_parser.ParseResolver(resolverCfg)
		if err != nil {
			return err
		}
		resolvers[resolverCfg.Name] = r
	}

	hostss := stageRegistry(registry.HostsRegistry(), tx)
	for _, hostsCfg := range cfg.Hosts {
		hostss[hostsCfg.Name] = hosts_parser.ParseHostMapper(hostsCfg)
	}

	ingresss := stageRegistry(registry.IngressRegistry(), tx)
	for _, ingressCfg := range cfg.Ingresses {
		ingresss[ingressCfg.Name] = ingress_parser.ParseIngress(ingressCfg)
	}

	routers := stageRegistry(registry.RouterRegistry(), tx)
	for _, routerCfg := range cfg.Routers {
		routers[routerCfg.Name] = router_parser.ParseRouter(routerCfg)
	}

	sDs := stageRegistry(registry.SDRegistry(), tx)
	for _, sdCfg := range cfg.SDs {
		sDs[sdCfg.Name] = sd_parser.ParseSD(sdCfg)
	}

	observers := stageRegistry(registry.ObserverRegistry(), tx)
	for _, observerCfg := range cfg.Observers {
		observers[observerCfg.Name] = observer_parser.ParseObserver(observerCfg)
	}

	recorders := stageRegistry(registry.RecorderRegistry(), tx)
	for _, recorderCfg := range cfg.Recorders {
		recorders[recorderCfg.Name] = recorder_parser.ParseRecorder(recorderCfg)
	}

	trafficLimiters := stageRegistry(registry.TrafficLimiterRegistry(), tx)
	for _, limiterCfg := range cfg.Limiters {
		trafficLimiters[limiterCfg.Name] = limiter_parser.ParseTrafficLimiter(limiterCfg)
	}

	connLimiters := stageRegistry(registry.ConnLimiterRegistry(), tx)
	for _, limiterCfg := range cfg.CLimiters {
		connLimiters[limiterCfg.Name] = limiter_parser.ParseConnLimiter(limiterCfg)
	}

	rateLimiters := stageRegistry(registry.RateLimiterRegistry(), tx)
	for _, limiterCfg := range cfg.RLimiters {
		rateLimiters[limiterCfg.Name] = limiter_parser.ParseRateLimiter(limiterCfg)
	}

	hops := stageRegistry(registry.HopRegistry(), tx)
	for _, hopCfg := range cfg.Hops {
		hop, err := hop_parser.ParseHop(hopCfg, log)
		if err != nil {
			return err
		}
		hops[hopCfg.Name] = hop
	}

	chains := stageRegistry(registry.ChainRegistry(), tx)
	for _, chainCfg := range cfg.Chains {
		c, err := chain_parser.ParseChain(chainCfg, log)
		if err != nil {
			return err
		}
		chains[chainCfg.Name] = c
	}

	return nil
}

type serviceTransaction struct {
	oldConfigs  []*config.ServiceConfig
	oldServices map[string]service.Service
	blocked     map[string]bool
	next        map[string]service.Service
	log         logger.Logger
	tls         *tls.Config
	getLogger   func(string) logger.Logger
	reuse       bool
}

func (tx *serviceTransaction) stage(configs []*config.ServiceConfig) error {
	keep := make(map[string]bool)
	for _, next := range configs {
		for _, current := range tx.oldConfigs {
			if tx.reuse && current.Name == next.Name && tx.oldServices[current.Name] != nil && sameService(current, next) {
				keep[next.Name] = true
				break
			}
		}
	}
	for _, cfg := range configs {
		if keep[cfg.Name] {
			continue
		}
		// Rebinding an occupied local address requires closing the old listener.
		// Only affected listeners take that path. Restore them if staging fails.
		if cfg.Addr != "" {
			for _, old := range tx.oldConfigs {
				srv := tx.oldServices[old.Name]
				if keep[old.Name] || tx.blocked[old.Name] || old.Addr != cfg.Addr || srv == nil {
					continue
				}
				// A paused service can remain registered with its listener closed.
				// Keep that object untouched so rollback cannot reactivate it.
				if state, ok := srv.(interface{ IsClosed() bool }); ok && state.IsClosed() {
					continue
				}
				tx.blocked[old.Name] = true
				registry.ServiceRegistry().Unregister(old.Name)
			}
		}
		svc, err := service_parser.ParseServiceWithDefaults(cfg, tx.log, tx.tls, tx.getLogger)
		if err != nil {
			return fmt.Errorf("build service %q: %w", cfg.Name, err)
		}
		if svc != nil {
			tx.next[cfg.Name] = svc
		}
	}
	// Mark reused objects without including them in candidate cleanup.
	for name := range keep {
		delete(tx.oldServices, name)
	}
	return nil
}

func sameService(a, b *config.ServiceConfig) bool {
	ac, bc := *a, *b
	ac.Status, bc.Status = nil, nil
	return reflect.DeepEqual(ac, bc)
}

func (tx *serviceTransaction) discard() { closeValues(tx.next) }

func (tx *serviceTransaction) restore() error {
	var result error
	for _, old := range tx.oldConfigs {
		if !tx.blocked[old.Name] {
			continue
		}
		copy := *old
		restored, err := service_parser.ParseService(&copy)
		if err == nil && restored != nil {
			err = registry.ServiceRegistry().Register(old.Name, restored)
			if err == nil {
				go restored.Serve()
			} else {
				restored.Close()
			}
		}
		if err != nil {
			result = errors.Join(result, fmt.Errorf("restore service %q: %w", old.Name, err))
		}
	}
	return result
}

func (tx *serviceTransaction) commit() []service.Service {
	for name := range tx.oldServices {
		if !tx.blocked[name] {
			registry.ServiceRegistry().Unregister(name)
		}
	}
	started := make([]service.Service, 0, len(tx.next))
	for name, svc := range tx.next {
		// The serialized mutation path owns this name after retiring old entries.
		_ = registry.ServiceRegistry().Register(name, svc)
		started = append(started, svc)
	}
	return started
}

func validateNames(cfg *config.Config) error {
	return errors.Join(
		named("logger", cfg.Loggers, func(v *config.LoggerConfig) string { return v.Name }),
		named("auther", cfg.Authers, func(v *config.AutherConfig) string { return v.Name }),
		named("admission", cfg.Admissions, func(v *config.AdmissionConfig) string { return v.Name }),
		named("bypass", cfg.Bypasses, func(v *config.BypassConfig) string { return v.Name }),
		named("resolver", cfg.Resolvers, func(v *config.ResolverConfig) string { return v.Name }),
		named("hosts", cfg.Hosts, func(v *config.HostsConfig) string { return v.Name }),
		named("ingress", cfg.Ingresses, func(v *config.IngressConfig) string { return v.Name }),
		named("router", cfg.Routers, func(v *config.RouterConfig) string { return v.Name }),
		named("sd", cfg.SDs, func(v *config.SDConfig) string { return v.Name }),
		named("observer", cfg.Observers, func(v *config.ObserverConfig) string { return v.Name }),
		named("recorder", cfg.Recorders, func(v *config.RecorderConfig) string { return v.Name }),
		named("limiter", cfg.Limiters, func(v *config.LimiterConfig) string { return v.Name }),
		named("connection limiter", cfg.CLimiters, func(v *config.LimiterConfig) string { return v.Name }),
		named("rate limiter", cfg.RLimiters, func(v *config.LimiterConfig) string { return v.Name }),
		named("hop", cfg.Hops, func(v *config.HopConfig) string { return v.Name }),
		named("chain", cfg.Chains, func(v *config.ChainConfig) string { return v.Name }),
		named("service", cfg.Services, func(v *config.ServiceConfig) string { return v.Name }),
	)
}

func named[T any](kind string, items []*T, nameOf func(*T) string) error {
	seen := make(map[string]bool)
	for _, item := range items {
		if item == nil {
			return fmt.Errorf("nil %s configuration", kind)
		}
		name := nameOf(item)
		if name == "" {
			return fmt.Errorf("empty %s name", kind)
		}
		if seen[name] {
			return fmt.Errorf("duplicate %s %q", kind, name)
		}
		seen[name] = true
	}
	return nil
}
