package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	_ "net/http/pprof"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"sync"

	"github.com/go-gost/core/logger"
	xlogger "github.com/go-gost/x/logger"
	"github.com/go-gost/x/service"
	"github.com/go-gost/x/socket"
	"github.com/judwhite/go-svc"
)

type stringList []string

func (l *stringList) String() string {
	return fmt.Sprintf("%s", *l)
}
func (l *stringList) Set(value string) error {
	*l = append(*l, value)
	return nil
}

var (
	cfgFile      string
	outputFormat string
	services     stringList
	nodes        stringList
	debug        bool
	trace        bool
	apiAddr      string
	metricsAddr  string
)

func init() {
	log.SetFlags(log.LstdFlags | log.Lshortfile | log.Lmicroseconds)

	args := strings.Join(os.Args[1:], "  ")

	if strings.Contains(args, " -- ") {
		var (
			wg  sync.WaitGroup
			ret int
		)

		ctx, cancel := context.WithCancel(context.Background())
		defer cancel()

		for wid, wargs := range strings.Split(" "+args+" ", " -- ") {
			wg.Add(1)
			go func(wid int, wargs string) {
				defer wg.Done()
				defer cancel()
				worker(wid, strings.Split(wargs, "  "), &ctx, &ret)
			}(wid, strings.TrimSpace(wargs))
		}

		wg.Wait()

		os.Exit(ret)
	}
}

func worker(id int, args []string, ctx *context.Context, ret *int) {
	cmd := exec.CommandContext(*ctx, os.Args[0], args...)

	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	cmd.Env = append(os.Environ(), fmt.Sprintf("_GOST_ID=%d", id))

	if err := cmd.Run(); err != nil {
		log.Fatal(err)
	}
	if cmd.ProcessState.Exited() {
		*ret = cmd.ProcessState.ExitCode()
	}
}

func init() {
	var printVersion bool

	flag.Var(&services, "L", "service list")
	flag.Var(&nodes, "F", "chain node list")
	flag.StringVar(&cfgFile, "C", "", "configuration file")
	flag.BoolVar(&printVersion, "V", false, "print version")
	flag.StringVar(&outputFormat, "O", "", "output format, one of yaml|json format")
	flag.BoolVar(&debug, "D", false, "debug mode")
	flag.BoolVar(&trace, "DD", false, "trace mode")
	flag.StringVar(&apiAddr, "api", "", "api service address")
	flag.StringVar(&metricsAddr, "metrics", "", "metrics service address")
	flag.Parse()

	if printVersion {
		fmt.Fprintf(os.Stdout, "gost %s (%s %s/%s)\n",
			version, runtime.Version(), runtime.GOOS, runtime.GOARCH)
		os.Exit(0)
	}
}

func main() {
	// 加载配置文件
	config, err := LoadConfig("config.json")
	if err != nil {
		// 注意：这里必须用 Printf，原先误用 Println 导致占位符不被替换，
		// 错误原因显示成字面量 "%v"，排错时看不到真实信息。
		fmt.Printf("❌ 配置加载失败: %v\n", err)
		fmt.Println("请确保当前目录存在 config.json 文件")
		os.Exit(1)
	}

	if len(config.Panels) == 1 {
		fmt.Printf("✅ 配置加载成功 - 面板: %s\n", config.Panels[0].Addr)
	} else {
		fmt.Printf("✅ 配置加载成功 - 共 %d 个面板\n", len(config.Panels))
		for i, p := range config.Panels {
			fmt.Printf("   [%d] %s\n", i+1, p.Addr)
		}
	}

	log := xlogger.NewLogger()
	logger.SetDefault(log)

	// 一个节点可以同时连接多个面板。
	// 每个面板分配独立的 WebSocket 连接与上报目标，
	// 并通过「隔离前缀」保证服务/链/限速器命名互不冲突。
	var reporters []*socket.WebSocketReporter

	singlePanel := len(config.Panels) == 1

	for i, panel := range config.Panels {
		// 单面板时不加前缀，保持与原有行为完全一致
		// （服务名不变，升级后无需重建任何转发）
		prefix := ""
		if !singlePanel {
			prefix = panel.isolatePrefix()
		}

		fmt.Printf("📡 面板 %d/%d: %s (前缀: %s)\n", i+1, len(config.Panels), panel.Addr, orNone(prefix))

		reporter := socket.StartWebSocketReporterWithPrefix(
			panel.Addr, panel.Secret, config.Http, config.Tls, config.Socks, version, prefix)
		reporters = append(reporters, reporter)

		if singlePanel {
			service.SetHTTPReportURL(panel.Addr, panel.Secret)
		} else {
			service.AddHTTPReportTarget(prefix, panel.Addr, panel.Secret)
		}
	}

	defer func() {
		for _, r := range reporters {
			r.Stop()
		}
	}()

	if len(config.Panels) > 1 {
		fmt.Printf("✅ 已连接 %d 个面板，服务名按面板前缀隔离\n", len(config.Panels))
		fmt.Printf("⚠️  注意：不同面板下发的转发不能占用相同端口，否则先启动的会占住端口\n")
	}

	p := &program{}
	if err := svc.Run(p); err != nil {
		logger.Default().Fatal(err)
	}
}

func orNone(s string) string {
	if s == "" {
		return "(无)"
	}
	return s
}

// GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o gost
// upx --best --lzma gost
