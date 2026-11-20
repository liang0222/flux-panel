package service

import (
	"net"
	"net/http"
	"sync"

	"github.com/gin-gonic/gin"
	"github.com/go-gost/core/auth"
	"github.com/go-gost/core/service"
	"github.com/go-gost/x/api"
)

type options struct {
	accessLog  bool
	pathPrefix string
	auther     auth.Authenticator
	reload     func() error
}

type Option func(*options)

func PathPrefixOption(pathPrefix string) Option {
	return func(o *options) {
		o.pathPrefix = pathPrefix
	}
}

func AccessLogOption(enable bool) Option {
	return func(o *options) {
		o.accessLog = enable
	}
}

func AutherOption(auther auth.Authenticator) Option {
	return func(o *options) {
		o.auther = auther
	}
}

type server struct {
	s         *http.Server
	ln        net.Listener
	cclose    chan struct{}
	closeOnce sync.Once
}

var ginModeOnce sync.Once

func ReloadOption(reload func() error) Option {
	return func(o *options) { o.reload = reload }
}

func NewService(network, addr string, opts ...Option) (service.Service, error) {
	if network == "" {
		network = "tcp"
	}
	ln, err := net.Listen(network, addr)
	if err != nil {
		return nil, err
	}

	var options options
	for _, opt := range opts {
		opt(&options)
	}

	ginModeOnce.Do(func() { gin.SetMode(gin.ReleaseMode) })

	r := gin.New()
	api.Register(r, &api.Options{
		AccessLog:  options.accessLog,
		PathPrefix: options.pathPrefix,
		Auther:     options.auther,
		Reload:     options.reload,
	})

	return &server{
		s: &http.Server{
			Handler: r,
		},
		ln:     ln,
		cclose: make(chan struct{}),
	}, nil
}

func (s *server) Serve() error {
	return s.s.Serve(s.ln)
}

func (s *server) Addr() net.Addr {
	return s.ln.Addr()
}

func (s *server) Close() error {
	var err error
	s.closeOnce.Do(func() {
		close(s.cclose)
		err = s.s.Close()
		s.ln.Close() // Serve might not have started yet.
	})
	return err
}

func (s *server) IsClosed() bool {
	select {
	case <-s.cclose:
		return true
	default:
		return false
	}
}
