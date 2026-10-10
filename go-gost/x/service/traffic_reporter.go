package service

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/go-gost/core/observer/stats"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/internal/util/crypto"
	"github.com/go-gost/x/registry"
)

// ReportTarget 一个面板端的上报目标。
//
// 多面板场景下，每个面板有独立的上报地址与加密密钥；
// 服务名里带有面板前缀，上报时据此路由到正确的面板，并把前缀剥掉，
// 使面板端拿到的仍然是它自己生成的服务名（forwardId_userId_userTunnelId）。
type ReportTarget struct {
	Prefix          string
	TrafficURL      string
	ConfigURL       string
	AESCrypto       *crypto.AESCrypto
	IsolatedService bool // 该目标下发的服务名是否带前缀
}

var (
	reportTargetsMu sync.RWMutex
	reportTargets   []*ReportTarget

	// 兼容旧调用：单面板时保持原有全局变量语义
	httpReportURL   string
	configReportURL string
	httpAESCrypto   *crypto.AESCrypto
)

// TrafficReportItem 流量报告项（压缩格式）
type TrafficReportItem struct {
	N string `json:"n"` // 服务名（name缩写）
	U int64  `json:"u"` // 上行流量（up缩写）
	D int64  `json:"d"` // 下行流量（down缩写）
}

// SetHTTPReportURL 设置上报地址（单面板，保持向后兼容）
func SetHTTPReportURL(addr string, secret string) {
	target := buildTarget("", addr, secret)
	reportTargetsMu.Lock()
	// 单面板：直接覆盖，避免重复注册
	reportTargets = []*ReportTarget{target}
	httpReportURL = target.TrafficURL
	configReportURL = target.ConfigURL
	httpAESCrypto = target.AESCrypto
	reportTargetsMu.Unlock()

	printCryptoResult(secret)
}

// AddHTTPReportTarget 追加一个上报目标（多面板）
//
// prefix 必须与 WebSocketReporter 使用的前缀一致，
// 否则流量上报会找不到归属面板而被丢弃。
func AddHTTPReportTarget(prefix string, addr string, secret string) {
	target := buildTarget(prefix, addr, secret)

	reportTargetsMu.Lock()
	// 同前缀去重，避免重复添加导致流量重复上报
	for i, t := range reportTargets {
		if t.Prefix == prefix {
			reportTargets[i] = target
			reportTargetsMu.Unlock()
			printCryptoResult(secret)
			return
		}
	}
	reportTargets = append(reportTargets, target)
	reportTargetsMu.Unlock()

	printCryptoResult(secret)
}

func buildTarget(prefix, addr, secret string) *ReportTarget {
	var aes *crypto.AESCrypto
	aes, err := crypto.NewAESCrypto(secret)
	if err != nil {
		fmt.Printf("❌ 创建 HTTP AES 加密器失败(前缀 %s): %v\n", prefix, err)
		aes = nil
	}
	return &ReportTarget{
		Prefix:          prefix,
		TrafficURL:      "http://" + addr + "/flow/upload?secret=" + secret,
		ConfigURL:       "http://" + addr + "/flow/config?secret=" + secret,
		AESCrypto:       aes,
		IsolatedService: prefix != "",
	}
}

func printCryptoResult(secret string) {
	if httpAESCrypto != nil || len(reportTargets) > 0 {
		fmt.Printf("🔐 HTTP AES 加密器创建成功\n")
	}
}

// routeTarget 根据服务名找到归属的上报目标，并返回剥掉前缀后的服务名。
//
// 服务名形如 "p_1_2_3_4_6365_12_5_0"：
//   - 前缀部分用于定位面板
//   - 返回的名字是面板真正认识的名字 "12_5_0"
//
// 找不到归属时返回 ok=false，调用方应丢弃该条上报（宁可不报，
// 也不能把 A 面板的流量错报给 B 面板）。
func routeTarget(serviceName string) (*ReportTarget, string, bool) {
	reportTargetsMu.RLock()
	defer reportTargetsMu.RUnlock()

	if len(reportTargets) == 0 {
		return nil, serviceName, false
	}

	// 单面板且未加前缀：直接命中
	if len(reportTargets) == 1 && !reportTargets[0].IsolatedService {
		return reportTargets[0], serviceName, true
	}

	// 多面板：按最长匹配前缀（避免前缀互为子串时误判）
	var best *ReportTarget
	for _, t := range reportTargets {
		if t.Prefix == "" {
			continue
		}
		if strings.HasPrefix(serviceName, t.Prefix) {
			if best == nil || len(t.Prefix) > len(best.Prefix) {
				best = t
			}
		}
	}
	if best == nil {
		return nil, serviceName, false
	}
	return best, strings.TrimPrefix(serviceName, best.Prefix), true
}

// sendTrafficReport 发送流量报告到对应面板的HTTP接口
func sendTrafficReport(ctx context.Context, reportItems TrafficReportItem) (bool, error) {
	target, plainName, ok := routeTarget(reportItems.N)
	if !ok {
		// 该服务不属于任何已知面板（例如面板已被移除），静默跳过
		return false, fmt.Errorf("服务 %s 未匹配到上报目标", reportItems.N)
	}

	// 关键：上报给面板的名字必须还原（去掉前缀），
	// 否则面板无法解析出 forwardId，流量统计会全部失效。
	payload := TrafficReportItem{N: plainName, U: reportItems.U, D: reportItems.D}

	jsonData, err := json.Marshal(payload)
	if err != nil {
		return false, fmt.Errorf("序列化报告数据失败: %v", err)
	}

	requestBody := encryptPayload(jsonData, target.AESCrypto)

	return postAndCheck(ctx, target.TrafficURL, requestBody, 5*time.Second, "GOST-Traffic-Reporter/1.0")
}

// sendConfigReportTo 发送配置报告到指定面板
func sendConfigReportTo(ctx context.Context, target *ReportTarget) (bool, error) {
	if target.ConfigURL == "" {
		return false, fmt.Errorf("配置上报URL未设置")
	}

	configData, err := getConfigData()
	if err != nil {
		return false, fmt.Errorf("获取配置数据失败: %v", err)
	}

	requestBody := encryptPayload(configData, target.AESCrypto)

	return postAndCheck(ctx, target.ConfigURL, requestBody, 10*time.Second, "Config-Reporter/1.0")
}

// encryptPayload 按目标面板的密钥加密（无加密器时返回明文）
func encryptPayload(plain []byte, aes *crypto.AESCrypto) []byte {
	if aes == nil {
		return plain
	}
	encryptedData, err := aes.Encrypt(plain)
	if err != nil {
		fmt.Printf("⚠️ 加密失败，发送原始数据: %v\n", err)
		return plain
	}
	wrapper := map[string]interface{}{
		"encrypted": true,
		"data":      encryptedData,
		"timestamp": time.Now().Unix(),
	}
	body, err := json.Marshal(wrapper)
	if err != nil {
		fmt.Printf("⚠️ 序列化加密消息失败，发送原始数据: %v\n", err)
		return plain
	}
	return body
}

func postAndCheck(ctx context.Context, url string, body []byte, timeout time.Duration, ua string) (bool, error) {
	req, err := http.NewRequestWithContext(ctx, "POST", url, bytes.NewBuffer(body))
	if err != nil {
		return false, fmt.Errorf("创建HTTP请求失败: %v", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", ua)

	client := &http.Client{Timeout: timeout}
	resp, err := client.Do(req)
	if err != nil {
		return false, fmt.Errorf("发送HTTP请求失败: %v", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return false, fmt.Errorf("HTTP响应错误: %d %s", resp.StatusCode, resp.Status)
	}

	var responseBytes bytes.Buffer
	if _, err = responseBytes.ReadFrom(resp.Body); err != nil {
		return false, fmt.Errorf("读取响应内容失败: %v", err)
	}

	if strings.TrimSpace(responseBytes.String()) == "ok" {
		return true, nil
	}
	return false, fmt.Errorf("服务器响应: %s (期望: ok)", strings.TrimSpace(responseBytes.String()))
}

// StartConfigReporter 启动配置定时上报器（每10分钟上报一次）
//
// 多面板时，向每个面板分别上报它自己那部分配置。
func StartConfigReporter(ctx context.Context) {
	reportTargetsMu.RLock()
	targets := make([]*ReportTarget, len(reportTargets))
	copy(targets, reportTargets)
	reportTargetsMu.RUnlock()

	if len(targets) == 0 {
		fmt.Printf("⚠️ 配置上报URL未设置，跳过定时上报\n")
		return
	}

	fmt.Printf("🚀 配置定时上报器已启动，每10分钟上报一次（共 %d 个面板）\n", len(targets))

	reportAll := func() {
		for _, t := range targets {
			t := t
			go func() {
				if _, err := sendConfigReportTo(ctx, t); err != nil {
					fmt.Printf("❌ 配置上报失败(%s): %v\n", t.Prefix, err)
				}
			}()
		}
	}

	// 立即执行一次
	reportAll()

	ticker := time.NewTicker(10 * time.Minute)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			reportAll()
		case <-ctx.Done():
			fmt.Printf("⏹️ 配置定时上报器已停止\n")
			return
		}
	}
}

// serviceStatus 接口定义
type serviceStatus interface {
	Status() *Status
}

// getConfigResponse 配置响应结构
type getConfigResponse struct {
	Config *config.Config `json:"config"`
}

// getConfigData 获取配置数据（避免循环依赖）
func getConfigData() ([]byte, error) {
	config.OnUpdate(func(c *config.Config) error {
		for _, svc := range c.Services {
			if svc == nil {
				continue
			}
			s := registry.ServiceRegistry().Get(svc.Name)
			ss, ok := s.(serviceStatus)
			if ok && ss != nil {
				status := ss.Status()
				svc.Status = &config.ServiceStatus{
					CreateTime: status.CreateTime().Unix(),
					State:      string(status.State()),
				}
				if st := status.Stats(); st != nil {
					svc.Status.Stats = &config.ServiceStats{
						TotalConns:   st.Get(stats.KindTotalConns),
						CurrentConns: st.Get(stats.KindCurrentConns),
						TotalErrs:    st.Get(stats.KindTotalErrs),
						InputBytes:   st.Get(stats.KindInputBytes),
						OutputBytes:  st.Get(stats.KindOutputBytes),
					}
				}
				for _, ev := range status.Events() {
					if !ev.Time.IsZero() {
						svc.Status.Events = append(svc.Status.Events, config.ServiceEvent{
							Time: ev.Time.Unix(),
							Msg:  ev.Message,
						})
					}
				}
			}
		}
		return nil
	})

	var resp getConfigResponse
	resp.Config = config.Global()

	buf := &bytes.Buffer{}
	resp.Config.Write(buf, "json")
	return buf.Bytes(), nil
}
