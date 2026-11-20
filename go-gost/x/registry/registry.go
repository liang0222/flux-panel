package registry

import (
	"errors"
	"io"
	"maps"
	"sync"

	"github.com/go-gost/core/admission"
	"github.com/go-gost/core/auth"
	"github.com/go-gost/core/bypass"
	"github.com/go-gost/core/chain"
	"github.com/go-gost/core/hop"
	"github.com/go-gost/core/hosts"
	"github.com/go-gost/core/ingress"
	"github.com/go-gost/core/limiter/conn"
	"github.com/go-gost/core/limiter/rate"
	"github.com/go-gost/core/limiter/traffic"
	"github.com/go-gost/core/logger"
	"github.com/go-gost/core/observer"
	"github.com/go-gost/core/recorder"
	reg "github.com/go-gost/core/registry"
	"github.com/go-gost/core/resolver"
	"github.com/go-gost/core/router"
	"github.com/go-gost/core/sd"
	"github.com/go-gost/core/service"
)

var (
	ErrDup = errors.New("registry: duplicate object")
)

var (
	listenerReg  reg.Registry[NewListener]         = new(listenerRegistry)
	handlerReg   reg.Registry[NewHandler]          = new(handlerRegistry)
	dialerReg    reg.Registry[NewDialer]           = new(dialerRegistry)
	connectorReg reg.Registry[NewConnector]        = new(connectorRegistry)
	serviceReg   reg.Registry[service.Service]     = new(serviceRegistry)
	chainReg     reg.Registry[chain.Chainer]       = new(chainRegistry)
	hopReg       reg.Registry[hop.Hop]             = new(hopRegistry)
	autherReg    reg.Registry[auth.Authenticator]  = new(autherRegistry)
	admissionReg reg.Registry[admission.Admission] = new(admissionRegistry)
	bypassReg    reg.Registry[bypass.Bypass]       = new(bypassRegistry)
	resolverReg  reg.Registry[resolver.Resolver]   = new(resolverRegistry)
	hostsReg     reg.Registry[hosts.HostMapper]    = new(hostsRegistry)
	recorderReg  reg.Registry[recorder.Recorder]   = new(recorderRegistry)

	trafficLimiterReg reg.Registry[traffic.TrafficLimiter] = new(trafficLimiterRegistry)
	connLimiterReg    reg.Registry[conn.ConnLimiter]       = new(connLimiterRegistry)
	rateLimiterReg    reg.Registry[rate.RateLimiter]       = new(rateLimiterRegistry)

	ingressReg  reg.Registry[ingress.Ingress]   = new(ingressRegistry)
	routerReg   reg.Registry[router.Router]     = new(routerRegistry)
	sdReg       reg.Registry[sd.SD]             = new(sdRegistry)
	observerReg reg.Registry[observer.Observer] = new(observerRegistry)

	loggerReg reg.Registry[logger.Logger] = new(loggerRegistry)
)

type registry[T any] struct {
	mu     sync.RWMutex
	values map[string]T
}

// Replace swaps complete registry maps and leaves cleanup of the old values
// to the caller. The new map is copied so the staging owner cannot mutate it.
func Replace[T any](r reg.Registry[T], values map[string]T) map[string]T {
	return r.(interface {
		replace(map[string]T) map[string]T
	}).replace(values)
}

func (r *registry[T]) replace(values map[string]T) map[string]T {
	r.mu.Lock()
	defer r.mu.Unlock()
	old := r.values
	r.values = maps.Clone(values)
	return old
}

func (r *registry[T]) Register(name string, v T) error {
	if name == "" {
		return nil
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, loaded := r.values[name]; loaded {
		return ErrDup
	}
	if r.values == nil {
		r.values = make(map[string]T)
	}
	r.values[name] = v
	return nil
}

func (r *registry[T]) Unregister(name string) {
	r.mu.Lock()
	v, ok := r.values[name]
	delete(r.values, name)
	r.mu.Unlock()
	if ok {
		if closer, ok := any(v).(io.Closer); ok {
			closer.Close()
		}
	}
}

func (r *registry[T]) IsRegistered(name string) bool {
	r.mu.RLock()
	defer r.mu.RUnlock()
	_, ok := r.values[name]
	return ok
}

func (r *registry[T]) Get(name string) T {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.values[name]
}

func (r *registry[T]) GetAll() map[string]T {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return maps.Clone(r.values)
}

func ListenerRegistry() reg.Registry[NewListener] {
	return listenerReg
}

func HandlerRegistry() reg.Registry[NewHandler] {
	return handlerReg
}

func DialerRegistry() reg.Registry[NewDialer] {
	return dialerReg
}

func ConnectorRegistry() reg.Registry[NewConnector] {
	return connectorReg
}

func ServiceRegistry() reg.Registry[service.Service] {
	return serviceReg
}

func ChainRegistry() reg.Registry[chain.Chainer] {
	return chainReg
}

func HopRegistry() reg.Registry[hop.Hop] {
	return hopReg
}

func AutherRegistry() reg.Registry[auth.Authenticator] {
	return autherReg
}

func AdmissionRegistry() reg.Registry[admission.Admission] {
	return admissionReg
}

func BypassRegistry() reg.Registry[bypass.Bypass] {
	return bypassReg
}

func ResolverRegistry() reg.Registry[resolver.Resolver] {
	return resolverReg
}

func HostsRegistry() reg.Registry[hosts.HostMapper] {
	return hostsReg
}

func RecorderRegistry() reg.Registry[recorder.Recorder] {
	return recorderReg
}

func TrafficLimiterRegistry() reg.Registry[traffic.TrafficLimiter] {
	return trafficLimiterReg
}

func ConnLimiterRegistry() reg.Registry[conn.ConnLimiter] {
	return connLimiterReg
}

func RateLimiterRegistry() reg.Registry[rate.RateLimiter] {
	return rateLimiterReg
}

func IngressRegistry() reg.Registry[ingress.Ingress] {
	return ingressReg
}

func RouterRegistry() reg.Registry[router.Router] {
	return routerReg
}

func SDRegistry() reg.Registry[sd.SD] {
	return sdReg
}

func ObserverRegistry() reg.Registry[observer.Observer] {
	return observerReg
}

func LoggerRegistry() reg.Registry[logger.Logger] {
	return loggerReg
}
