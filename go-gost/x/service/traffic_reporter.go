package service

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/go-gost/core/observer/stats"
	"github.com/go-gost/x/config"
	"github.com/go-gost/x/internal/util/crypto"
	"github.com/go-gost/x/registry"
)

var httpReportURL string
var configReportURL string
var httpAESCrypto *crypto.AESCrypto // 新增：HTTP上报加密器

// 复用同一个 http.Client / Transport：
// 原实现每次上报（每 5 秒一次）都新建 &http.Client{}，虽然底层仍复用
// http.DefaultTransport，但会重复分配 Client 结构、且无法自定义连接复用与
// 空闲连接上限。改为包级单例后，连接可稳定复用，减少每 5 秒一次的临时对象。
var (
	trafficReportClient = &http.Client{
		Timeout: 5 * time.Second,
		Transport: &http.Transport{
			Proxy:                 http.ProxyFromEnvironment,
			MaxIdleConns:          16,
			MaxIdleConnsPerHost:   4,
			IdleConnTimeout:       60 * time.Second,
			TLSHandshakeTimeout:   10 * time.Second,
			ExpectContinueTimeout: 1 * time.Second,
		},
	}
	configReportClient = &http.Client{
		Timeout: 10 * time.Second, // 配置上报可以稍长一些
		Transport: &http.Transport{
			Proxy:                 http.ProxyFromEnvironment,
			MaxIdleConns:          8,
			MaxIdleConnsPerHost:   2,
			IdleConnTimeout:       60 * time.Second,
			TLSHandshakeTimeout:   10 * time.Second,
			ExpectContinueTimeout: 1 * time.Second,
		},
	}
)

// TrafficReportItem 流量报告项（压缩格式）
type TrafficReportItem struct {
	N string `json:"n"` // 服务名（name缩写）
	U int64  `json:"u"` // 上行流量（up缩写）
	D int64  `json:"d"` // 下行流量（down缩写）
}

// maskSecretInURL 把 URL 查询串里的 secret 参数值替换为掩码。
// secret 是节点身份凭据，err 里带上完整 URL 会把明文写进 journald 长期留存。
// 保留首尾各 4 位便于比对是哪一份配置；解析失败时返回不含查询串的占位文案。
func maskSecretInURL(raw string) string {
	u, err := url.Parse(raw)
	if err != nil {
		return "(URL 解析失败)"
	}
	q := u.Query()
	if v := q.Get("secret"); v != "" {
		if len(v) <= 8 {
			q.Set("secret", "****")
		} else {
			q.Set("secret", v[:4]+"****"+v[len(v)-4:])
		}
	}
	u.RawQuery = q.Encode()
	return u.String()
}

func SetHTTPReportURL(addr string, secret string) {
	httpReportURL = "http://" + addr + "/flow/upload?secret=" + secret
	configReportURL = "http://" + addr + "/flow/config?secret=" + secret

	// 创建 AES 加密器
	var err error
	httpAESCrypto, err = crypto.NewAESCrypto(secret)
	if err != nil {
		fmt.Printf("❌ 创建 HTTP AES 加密器失败: %v\n", err)
		httpAESCrypto = nil
	} else {
		fmt.Printf("🔐 HTTP AES 加密器创建成功\n")
	}
}

// sendBatchTrafficReport 批量发送多个服务的流量报告到HTTP接口
func sendBatchTrafficReport(ctx context.Context, reportItems []TrafficReportItem) (bool, error) {
	jsonData, err := json.Marshal(reportItems)
	if err != nil {
		return false, fmt.Errorf("序列化报告数据失败: %v", err)
	}

	var requestBody []byte

	// 如果有加密器，则加密数据
	if httpAESCrypto != nil {
		encryptedData, err := httpAESCrypto.Encrypt(jsonData)
		if err != nil {
			fmt.Printf("⚠️ 加密流量报告失败，发送原始数据: %v\n", err)
			requestBody = jsonData
		} else {
			// 创建加密消息包装器
			encryptedMessage := map[string]interface{}{
				"encrypted": true,
				"data":      encryptedData,
				"timestamp": time.Now().Unix(),
			}
			requestBody, err = json.Marshal(encryptedMessage)
			if err != nil {
				fmt.Printf("⚠️ 序列化加密流量报告失败，发送原始数据: %v\n", err)
				requestBody = jsonData
			}
		}
	} else {
		requestBody = jsonData
	}

	req, err := http.NewRequestWithContext(ctx, "POST", httpReportURL, bytes.NewBuffer(requestBody))
	if err != nil {
		return false, fmt.Errorf("创建HTTP请求失败: %v", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "GOST-Traffic-Reporter/1.0")

	client := trafficReportClient

	resp, err := client.Do(req)
	if err != nil {
		// err 会带上完整请求 URL（含 secret），这里替换成脱敏后的 URL，
		// 否则失败日志会把节点凭据明文写进 journald。
		return false, fmt.Errorf("发送HTTP请求失败: %s", strings.Replace(err.Error(), req.URL.String(), maskSecretInURL(req.URL.String()), 1))
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return false, fmt.Errorf("HTTP响应错误: %d %s", resp.StatusCode, resp.Status)
	}

	// 读取响应内容
	var responseBytes bytes.Buffer
	_, err = responseBytes.ReadFrom(resp.Body)
	if err != nil {
		return false, fmt.Errorf("读取响应内容失败: %v", err)
	}

	responseText := strings.TrimSpace(responseBytes.String())

	// 检查响应是否为"ok"
	if responseText == "ok" {
		return true, nil
	} else {
		return false, fmt.Errorf("服务器响应: %s (期望: ok)", responseText)
	}
}


// sendConfigReport 发送配置报告到HTTP接口
func sendConfigReport(ctx context.Context) (bool, error) {
	if configReportURL == "" {
		return false, fmt.Errorf("配置上报URL未设置")
	}

	// 获取配置数据
	configData, err := getConfigData()
	if err != nil {
		return false, fmt.Errorf("获取配置数据失败: %v", err)
	}

	var requestBody []byte

	// 如果有加密器，则加密数据
	if httpAESCrypto != nil {
		encryptedData, err := httpAESCrypto.Encrypt(configData)
		if err != nil {
			fmt.Printf("⚠️ 加密配置报告失败，发送原始数据: %v\n", err)
			requestBody = configData
		} else {
			// 创建加密消息包装器
			encryptedMessage := map[string]interface{}{
				"encrypted": true,
				"data":      encryptedData,
				"timestamp": time.Now().Unix(),
			}
			requestBody, err = json.Marshal(encryptedMessage)
			if err != nil {
				fmt.Printf("⚠️ 序列化加密配置报告失败，发送原始数据: %v\n", err)
				requestBody = configData
			}
		}
	} else {
		requestBody = configData
	}

	req, err := http.NewRequestWithContext(ctx, "POST", configReportURL, bytes.NewBuffer(requestBody))
	if err != nil {
		return false, fmt.Errorf("创建HTTP请求失败: %v", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "Config-Reporter/1.0")

	client := configReportClient

	resp, err := client.Do(req)
	if err != nil {
		// err 会带上完整请求 URL（含 secret），这里替换成脱敏后的 URL，
		// 否则失败日志会把节点凭据明文写进 journald。
		return false, fmt.Errorf("发送HTTP请求失败: %s", strings.Replace(err.Error(), req.URL.String(), maskSecretInURL(req.URL.String()), 1))
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return false, fmt.Errorf("HTTP响应错误: %d %s", resp.StatusCode, resp.Status)
	}

	// 读取响应内容
	var responseBytes bytes.Buffer
	_, err = responseBytes.ReadFrom(resp.Body)
	if err != nil {
		return false, fmt.Errorf("读取响应内容失败: %v", err)
	}

	responseText := strings.TrimSpace(responseBytes.String())

	// 检查响应是否为"ok"
	if responseText == "ok" {
		return true, nil
	} else {
		return false, fmt.Errorf("服务器响应: %s (期望: ok)", responseText)
	}
}

// StartConfigReporter 启动配置定时上报器（每10分钟上报一次）
func StartConfigReporter(ctx context.Context) {
	if configReportURL == "" {
		fmt.Printf("⚠️ 配置上报URL未设置，跳过定时上报\n")
		return
	}

	fmt.Printf("🚀 配置定时上报器已启动，每10分钟上报一次（WebSocket连接稳定后启动）\n")

	// 创建10分钟定时器
	ticker := time.NewTicker(10 * time.Minute)
	defer ticker.Stop()

	// 立即执行一次配置上报
	go func() {
		success, err := sendConfigReport(ctx)
		if err != nil {
			fmt.Printf("❌ 初始配置上报失败: %v\n", err)
		} else if success {
			fmt.Printf("✅ 初始配置上报成功\n")
		}
	}()

	// 定时上报循环
	for {
		select {
		case <-ticker.C:
			go func() {
				success, err := sendConfigReport(ctx)
				if err != nil {
					fmt.Printf("❌ 定时配置上报失败: %v\n", err)
				} else if success {
					fmt.Printf("✅ 定时配置上报成功\n")
				}
			}()

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
