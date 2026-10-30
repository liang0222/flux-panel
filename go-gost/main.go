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
	// 别名导入：本文件已有全局变量 debug（对应 -D 调试标志），
	// 直接用 debug 作包名会与它冲突。
	runtimedebug "runtime/debug"
	"strconv"
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
	flag.Var(&services, "L", "service list")
	flag.Var(&nodes, "F", "chain node list")
	flag.StringVar(&cfgFile, "C", "", "configuration file")
	flag.BoolVar(&printVersion, "V", false, "print version")
	flag.StringVar(&outputFormat, "O", "", "output format, one of yaml|json format")
	flag.BoolVar(&debug, "D", false, "debug mode")
	flag.BoolVar(&trace, "DD", false, "trace mode")
	flag.StringVar(&apiAddr, "api", "", "api service address")
	flag.StringVar(&metricsAddr, "metrics", "", "metrics service address")
	// 注意：这里只注册 flag，不解析。
	// 原实现在 init() 中直接调用 flag.Parse()，会出现两个问题：
	//   1. init() 早于 testing 包注册 -test.* 标志，go test 时 Parse 会因
	//      "flag provided but not defined: -test.testlogfile" 直接失败，
	//      整个 main 包无法被测试；
	//   2. 在 init() 中提前消费 os.Args，任何以本包为库的调用方都失去
	//      自己解析参数的机会。
	// 因此在 main() 开头显式调用 parseFlags()，命令行行为与原来完全一致。
}

// printVersion 对应 -V 标志
var printVersion bool

// parseFlags 解析命令行参数，并在 -V 时打印版本后退出。
// 从 init() 移到 main() 调用，解析时机仍然早于任何配置读取与业务初始化，
// 因此对外行为（参数语义、版本输出、退出码）与原实现一致。
func parseFlags() {
	flag.Parse()

	if printVersion {
		fmt.Fprintf(os.Stdout, "gost %s (%s %s/%s)\n",
			version, runtime.Version(), runtime.GOOS, runtime.GOARCH)
		os.Exit(0)
	}
}

func main() {
	// 命令行参数解析仍然在任何业务初始化之前完成（原位于 init()，见该处注释）。
	parseFlags()

	// 在加载任何配置、创建任何 goroutine 之前设置 Go runtime 的内存软上限。
	// 位置很关键：越早调用，runtime 越早把 GC 目标与内存归还策略调整到位。
	applyMemoryLimit()

	// 加载配置文件
	config, err := LoadConfig("config.json")
	if err != nil {
		fmt.Printf("❌ 配置加载失败: %v\n", err)
		fmt.Println("请确保当前目录存在 config.json 文件")
		os.Exit(1)
	}

	fmt.Printf("✅ 配置加载成功 - addr: %s\n", config.Addr)

	log := xlogger.NewLogger()
	logger.SetDefault(log)

	wsReporter := socket.StartWebSocketReporterWithConfig(config.Addr, config.Secret, config.Http, config.Tls, config.Socks, "2.0.2")
	defer wsReporter.Stop()
	service.SetHTTPReportURL(config.Addr, config.Secret)

	p := &program{}
	if err := svc.Run(p); err != nil {
		logger.Default().Fatal(err)
	}
}

// applyMemoryLimit 为 Go runtime 设置堆内存软上限（GOMEMLIMIT 的代码等价形式）。
//
// 背景：Go 默认堆目标由 GOGC=100 控制，即「上次 GC 后存活对象的 2 倍」触发 GC。
// 堆一旦被推到某个峰值，GC 后释放的空间仍留在 runtime 堆中供复用，只在空闲比例
// 很高时才通过 madvise 归还操作系统；在 THP=always 的环境里归还粒度变为 2MiB
// 大页，归还更消极。结果是进程 RSS 长期停在历史峰值附近，即使业务已经空闲。
//
// SetMemoryLimit 设置的是「软上限」：runtime 会把它作为 GC 目标的额外约束，
// 在接近上限时更积极地回收并归还内存。它不会像硬限制那样触发 OOM Kill，
// 因此对业务功能没有行为影响，只影响 GC 的时机与频率。
//
// 取值优先级：
//  1. 环境变量 GOMEMLIMIT —— 与 Go 标准行为保持一致，运维可用它覆盖。
//     支持 "1200MiB" / "1GiB" / "536870912" 这类标准写法，由 runtime 自行解析，
//     因此这里只在 GOMEMLIMIT 未设置时才介入。
//  2. 环境变量 FLUX_AGENT_MEMLIMIT —— 本项目的显式开关。
//  3. 缺省值 1200MiB —— 面向 2GiB 及以上内存节点的一个保守默认：
//     本机实测 flux_agent 在 212 个转发服务下 RSS 约 2.5GiB，其中相当一部分是
//     「历史峰值未归还」而非活跃对象；1200MiB 软上限能让 runtime 在明显超出
//     活跃用量时主动收缩，同时给 212 个服务的真实工作集留足余量。
//
// 设为 0 或 "off" 表示不设置任何上限，完全回到 Go 默认行为（可用于回滚验证）。
func applyMemoryLimit() {
	// 若运维已显式设置标准环境变量 GOMEMLIMIT，则尊重它，本函数不做任何事。
	if os.Getenv("GOMEMLIMIT") != "" {
		return
	}

	raw := os.Getenv("FLUX_AGENT_MEMLIMIT")
	source := "环境变量 FLUX_AGENT_MEMLIMIT"
	if raw == "" {
		raw = "1200MiB"
		source = "内置默认值"
	}
	// 关闭开关：大小写不敏感地接受 off/disable/0，避免误配置落到解析失败分支。
	switch strings.ToLower(strings.TrimSpace(raw)) {
	case "off", "disable", "disabled", "0":
		fmt.Printf("ℹ️  Go 内存软上限已关闭（%s=%s），使用 Go 默认 GC 策略\n", source, raw)
		return
	}

	limit, err := parseMemoryLimit(raw)
	if err != nil {
		// 解析失败不阻断启动：退回到 Go 默认行为，并打印一次可见的告警。
		fmt.Printf("⚠️  FLUX_AGENT_MEMLIMIT=%q 解析失败，使用 Go 默认内存策略: %v\n", raw, err)
		return
	}

	runtimedebug.SetMemoryLimit(limit)
	fmt.Printf("✅ Go 内存软上限已设置: %d MiB（来源: %s，原始值: %s）\n", limit/(1024*1024), source, raw)
}

// parseMemoryLimit 解析形如 "1200MiB"、"1GiB"、"1536MB"、"536870912" 的内存大小。
// 只接受字节数与带单位的写法，单位不区分大小写（KiB/KB/k 视为 1024 或 1000，
// 内存场景统一按 1024 进制处理，避免运维把 MiB 与 MB 混用时产生数量级误解）。
func parseMemoryLimit(s string) (int64, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0, fmt.Errorf("空字符串")
	}

	// 纯数字：按字节解释
	if v, err := strconv.ParseInt(s, 10, 64); err == nil {
		if v <= 0 {
			return 0, fmt.Errorf("必须为正数: %d", v)
		}
		return v, nil
	}

	upper := strings.ToUpper(s)
	units := []struct {
		suffix string
		scale  int64
	}{
		{"KIB", 1024},
		{"MIB", 1024 * 1024},
		{"GIB", 1024 * 1024 * 1024},
		{"KB", 1024},
		{"MB", 1024 * 1024},
		{"GB", 1024 * 1024 * 1024},
		{"K", 1024},
		{"M", 1024 * 1024},
		{"G", 1024 * 1024 * 1024},
		{"B", 1},
	}

	for _, u := range units {
		if strings.HasSuffix(upper, u.suffix) {
			numPart := strings.TrimSpace(strings.TrimSuffix(upper, u.suffix))
			if numPart == "" {
				return 0, fmt.Errorf("缺少数值: %q", s)
			}
			n, err := strconv.ParseFloat(numPart, 64)
			if err != nil {
				return 0, fmt.Errorf("数值无效: %q", numPart)
			}
			if n <= 0 {
				return 0, fmt.Errorf("必须为正数: %q", s)
			}
			return int64(n * float64(u.scale)), nil
		}
	}

	return 0, fmt.Errorf("无法识别的单位: %q", s)
}

// GOOS=linux GOARCH=amd64 go build -ldflags="-s -w" -o gost
// upx --best --lzma gost
