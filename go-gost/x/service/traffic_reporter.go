package service

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
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

func SetHTTPReportURL(addr string, secret string) {
	q := url.Values{"secret": {secret}}.Encode()
	httpReportURL = (&url.URL{Scheme: "http", Host: addr, Path: "/flow/upload", RawQuery: q}).String()
	configReportURL = (&url.URL{Scheme: "http", Host: addr, Path: "/flow/config", RawQuery: q}).String()
	httpAESCrypto, _ = crypto.NewAESCrypto(secret)
}

func reportRequestError(err error) error {
	var urlErr *url.Error
	if errors.As(err, &urlErr) {
		return fmt.Errorf("HTTP transport failed: %w", urlErr.Err)
	}
	return err
}

// sendBatchTrafficReport 批量发送多个服务的流量报告到HTTP接口
func sendBatchTrafficReport(ctx context.Context, batchID string, reportItems []TrafficReportItem) (bool, error) {
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
	req.Header.Set("X-Flow-Batch-Id", batchID)

	client := trafficReportClient

	resp, err := client.Do(req)
	if err != nil {
		return false, reportRequestError(err)
	}
	defer resp.Body.Close()
	return checkReportAck(resp)
}

func checkReportAck(resp *http.Response) (bool, error) {
	if resp.StatusCode != http.StatusOK {
		return false, fmt.Errorf("HTTP acknowledgement status %d", resp.StatusCode)
	}
	const maxAck = 4096
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxAck+1))
	if err != nil {
		return false, fmt.Errorf("read HTTP acknowledgement: %w", err)
	}
	if len(body) > maxAck || strings.TrimSpace(string(body)) != "ok" {
		return false, fmt.Errorf("unexpected HTTP acknowledgement")
	}
	return true, nil
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
		return false, reportRequestError(err)
	}
	defer resp.Body.Close()
	return checkReportAck(resp)
}

// StartConfigReporter keeps at most one configuration report in flight.
func StartConfigReporter(ctx context.Context) {
	if configReportURL == "" {
		return
	}
	ticker := time.NewTicker(10 * time.Minute)
	defer ticker.Stop()
	for ctx.Err() == nil {
		if _, err := sendConfigReport(ctx); err != nil && ctx.Err() == nil {
			fmt.Printf("配置上报失败: %v\n", err)
		}
		select {
		case <-ticker.C:
		case <-ctx.Done():
			return
		}
	}
}

// serviceStatus 接口定义
type serviceStatus interface {
	Status() *Status
}

func getConfigData() ([]byte, error) {
	buf := &bytes.Buffer{}
	err := config.OnUpdate(func(c *config.Config) error {
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
		return c.Write(buf, "json")
	})
	return buf.Bytes(), err
}
