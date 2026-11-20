package api

import (
	"net/http"

	"github.com/gin-gonic/gin"
	"github.com/go-gost/core/service"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/config/loader"
	"github.com/go-gost/x/config/parsing/parser"
)

// swagger:parameters reloadConfigRequest
type reloadConfigRequest struct{}

// successful operation.
// swagger:response reloadConfigResponse
type reloadConfigResponse struct {
	Data Response
}

func reloadConfig(ctx *gin.Context, reload func() error) {
	// swagger:route POST /config/reload Reload reloadConfigRequest
	//
	// Hot reload config.
	//
	//     Security:
	//       basicAuth: []
	//
	//     Responses:
	//       200: reloadConfigResponse

	if reload == nil {
		reload = reloadForwardingConfig
	}
	if err := reload(); err != nil {
		writeError(ctx, NewError(http.StatusBadRequest, ErrCodeInvalid, err.Error()))
		return
	}
	ctx.JSON(http.StatusOK, Response{Msg: "OK"})
}

func reloadForwardingConfig() error {
	cfg, err := parser.Parse()
	if err != nil {
		return err
	}
	var started []service.Service
	if err := config.Replace(cfg, func(old *config.Config) error {
		var err error
		started, err = loader.Apply(old, cfg, nil)
		return err
	}); err != nil {
		return err
	}
	for _, svc := range started {
		go svc.Serve()
	}
	return nil
}
