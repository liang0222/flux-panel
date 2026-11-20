package socket

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/go-gost/x/config"
	"github.com/go-gost/x/internal/util/crypto"
	"github.com/go-gost/x/service"
	"github.com/gorilla/websocket"
	"github.com/shirou/gopsutil/v3/cpu"
	"github.com/shirou/gopsutil/v3/host"
	"github.com/shirou/gopsutil/v3/mem"
	psnet "github.com/shirou/gopsutil/v3/net"
)

// SystemInfo 系统信息结构体
type SystemInfo struct {
	Uptime           uint64  `json:"uptime"`            // 开机时间	（秒）
	BytesReceived    uint64  `json:"bytes_received"`    // 接收字节数
	BytesTransmitted uint64  `json:"bytes_transmitted"` // 发送字节数
	CPUUsage         float64 `json:"cpu_usage"`         // CPU使用率（百分比）
	MemoryUsage      float64 `json:"memory_usage"`      // 内存使用率（百分比）
}

// NetworkStats 网络统计信息
type NetworkStats struct {
	BytesReceived    uint64 `json:"bytes_received"`    // 接收字节数
	BytesTransmitted uint64 `json:"bytes_transmitted"` // 发送字节数
}

// CPUInfo CPU信息
type CPUInfo struct {
	Usage float64 `json:"usage"` // CPU使用率（百分比）
}

// MemoryInfo 内存信息
type MemoryInfo struct {
	Usage float64 `json:"usage"` // 内存使用率（百分比）
}

// CommandMessage 命令消息结构体
type CommandMessage struct {
	Type      string      `json:"type"`
	Data      interface{} `json:"data"`
	RequestId string      `json:"requestId,omitempty"`
}

// CommandResponse 命令响应结构体
type CommandResponse struct {
	Type      string      `json:"type"`
	Success   bool        `json:"success"`
	Message   string      `json:"message"`
	Data      interface{} `json:"data,omitempty"`
	RequestId string      `json:"requestId,omitempty"`
}

// TcpPingRequest TCP ping请求结构体
type TcpPingRequest struct {
	IP        string `json:"ip"`
	Port      int    `json:"port"`
	Count     int    `json:"count"`
	Timeout   int    `json:"timeout"` // 超时时间(毫秒)
	RequestId string `json:"requestId,omitempty"`
}

// TcpPingResponse TCP ping响应结构体
type TcpPingResponse struct {
	IP           string  `json:"ip"`
	Port         int     `json:"port"`
	Success      bool    `json:"success"`
	AverageTime  float64 `json:"averageTime"` // 平均连接时间(ms)
	PacketLoss   float64 `json:"packetLoss"`  // 连接失败率(%)
	ErrorMessage string  `json:"errorMessage,omitempty"`
	RequestId    string  `json:"requestId,omitempty"`
}

type WebSocketReporter struct {
	url            string
	configured     bool
	nodeConfigPath string
	persistPath    string
	reconnectTime  time.Duration
	pingInterval   time.Duration
	ctx            context.Context
	cancel         context.CancelFunc
	start          sync.Once
	aesCrypto      *crypto.AESCrypto
}

type webSocketSession struct {
	ctx       context.Context
	cancel    context.CancelFunc
	conn      *websocket.Conn
	responses chan CommandResponse
}

func NewWebSocketReporter(serverURL, secret string) *WebSocketReporter {
	ctx, cancel := context.WithCancel(context.Background())
	aesCrypto, _ := crypto.NewAESCrypto(secret)
	return &WebSocketReporter{
		url: serverURL, reconnectTime: 5 * time.Second, pingInterval: 2 * time.Second,
		ctx: ctx, cancel: cancel, aesCrypto: aesCrypto,
		nodeConfigPath: "config.json", persistPath: "gost.json",
	}
}

func (w *WebSocketReporter) Start() { w.start.Do(func() { go w.run() }) }

func (w *WebSocketReporter) Stop() { w.cancel() }

func (w *WebSocketReporter) run() {
	for w.ctx.Err() == nil {
		conn, err := w.connect()
		if err == nil {
			w.handleConnection(conn)
		} else if w.ctx.Err() == nil {
			// A dial error may embed the complete URL, including the node credential.
			fmt.Printf("WebSocket连接失败 (%T)，%v后重试\n", err, w.reconnectTime)
		}
		timer := time.NewTimer(w.reconnectTime)
		select {
		case <-timer.C:
		case <-w.ctx.Done():
			timer.Stop()
			return
		}
	}
}

func (w *WebSocketReporter) connect() (*websocket.Conn, error) {
	endpoint := w.url
	if w.configured {
		u, err := url.Parse(w.url)
		if err != nil {
			return nil, err
		}
		h, t, s := service.ProtocolBlock()
		q := u.Query()
		q.Set("http", strconv.Itoa(h))
		q.Set("tls", strconv.Itoa(t))
		q.Set("socks", strconv.Itoa(s))
		u.RawQuery = q.Encode()
		endpoint = u.String()
	}
	dialer := *websocket.DefaultDialer
	dialer.HandshakeTimeout = 10 * time.Second
	conn, resp, err := dialer.DialContext(w.ctx, endpoint, nil)
	if err != nil && resp != nil {
		resp.Body.Close()
	}
	return conn, err
}

func (w *WebSocketReporter) handleConnection(conn *websocket.Conn) {
	ctx, cancel := context.WithCancel(w.ctx)
	s := &webSocketSession{ctx: ctx, cancel: cancel, conn: conn, responses: make(chan CommandResponse, 16)}
	// Closing the exact session connection also interrupts a blocked read/write.
	stopClose := context.AfterFunc(ctx, func() { conn.Close() })
	defer stopClose()
	readDone := make(chan struct{})
	go func() {
		defer close(readDone)
		defer cancel()
		w.receiveMessages(s)
	}()
	w.writeMessages(s)
	cancel()
	conn.Close()
	// A previous connection must finish its command before a new session starts.
	<-readDone
}

func (w *WebSocketReporter) writeMessages(s *webSocketSession) {
	ticker := time.NewTicker(w.pingInterval)
	defer ticker.Stop()
	for s.ctx.Err() == nil {
		var payload any
		select {
		case <-s.ctx.Done():
			return
		case response := <-s.responses:
			payload = response
		case <-ticker.C:
			payload = w.collectSystemInfo()
		}
		message, err := w.encodeMessage(payload)
		if err != nil {
			fmt.Printf("WebSocket消息编码失败 (%T)\n", err)
			continue
		}
		timeout := 5 * time.Second
		if len(message) > 1024*1024 {
			timeout = 30 * time.Second
		}
		if err := s.conn.SetWriteDeadline(time.Now().Add(timeout)); err != nil {
			return
		}
		if err := s.conn.WriteMessage(websocket.TextMessage, message); err != nil {
			return
		}
	}
}

func (s *webSocketSession) enqueue(response CommandResponse) {
	select {
	case <-s.ctx.Done():
	case s.responses <- response:
	default:
		// Do not silently discard a command response or stall the reader on a slow peer.
		fmt.Println("WebSocket响应队列已满，关闭本次连接")
		s.cancel()
	}
}

// collectSystemInfo 收集系统信息
func (w *WebSocketReporter) collectSystemInfo() SystemInfo {
	networkStats := getNetworkStats()
	cpuInfo := getCPUInfo()
	memoryInfo := getMemoryInfo()

	return SystemInfo{
		Uptime:           getUptime(),
		BytesReceived:    networkStats.BytesReceived,
		BytesTransmitted: networkStats.BytesTransmitted,
		CPUUsage:         cpuInfo.Usage,
		MemoryUsage:      memoryInfo.Usage,
	}
}

func (w *WebSocketReporter) encodeMessage(payload any) ([]byte, error) {
	data, err := json.Marshal(payload)
	if err != nil || w.aesCrypto == nil {
		return data, err
	}
	encrypted, err := w.aesCrypto.Encrypt(data)
	if err != nil {
		fmt.Printf("WebSocket消息加密失败，发送原始数据 (%T)\n", err)
		return data, nil
	}
	return json.Marshal(map[string]any{"encrypted": true, "data": encrypted, "timestamp": time.Now().Unix()})
}

func (w *WebSocketReporter) receiveMessages(s *webSocketSession) {
	for s.ctx.Err() == nil {
		if err := s.conn.SetReadDeadline(time.Now().Add(30 * time.Second)); err != nil {
			return
		}
		typ, message, err := s.conn.ReadMessage()
		if err != nil || s.ctx.Err() != nil {
			return
		}
		w.handleReceivedMessage(s, typ, message)
	}
}

// handleReceivedMessage 处理接收到的消息
func (w *WebSocketReporter) handleReceivedMessage(s *webSocketSession, messageType int, message []byte) {
	switch messageType {
	case websocket.TextMessage:
		// 先检查是否是加密消息
		var encryptedWrapper struct {
			Encrypted bool   `json:"encrypted"`
			Data      string `json:"data"`
			Timestamp int64  `json:"timestamp"`
		}

		// 尝试解析为加密消息格式
		if err := json.Unmarshal(message, &encryptedWrapper); err == nil && encryptedWrapper.Encrypted {
			if w.aesCrypto != nil {
				// 解密数据
				decryptedData, err := w.aesCrypto.Decrypt(encryptedWrapper.Data)
				if err != nil {
					fmt.Printf("❌ 解密失败: %v\n", err)
					s.sendErrorResponse("DecryptError", fmt.Sprintf("解密失败: %v", err))
					return
				}
				message = decryptedData
			} else {
				fmt.Printf("❌ 收到加密消息但没有加密器\n")
				s.sendErrorResponse("NoDecryptor", "没有可用的解密器")
				return
			}
		}
		// 先尝试解析是否是压缩消息
		var compressedMsg struct {
			Type       string          `json:"type"`
			Compressed bool            `json:"compressed"`
			Data       json.RawMessage `json:"data"`
			RequestId  string          `json:"requestId,omitempty"`
		}

		if err := json.Unmarshal(message, &compressedMsg); err == nil && compressedMsg.Compressed {
			// 处理压缩消息
			fmt.Printf("📥 收到压缩消息，正在解压...\n")

			// 解压数据
			gzipReader, err := gzip.NewReader(bytes.NewReader(compressedMsg.Data))
			if err != nil {
				fmt.Printf("❌ 创建解压读取器失败: %v\n", err)
				s.sendErrorResponse("DecompressError", fmt.Sprintf("解压失败: %v", err))
				return
			}
			defer gzipReader.Close()

			var decompressedData bytes.Buffer
			if _, err := decompressedData.ReadFrom(gzipReader); err != nil {
				fmt.Printf("❌ 解压数据失败: %v\n", err)
				s.sendErrorResponse("DecompressError", fmt.Sprintf("解压失败: %v", err))
				return
			}

			// 使用解压后的数据继续处理
			message = decompressedData.Bytes()

			// 构建解压后的命令消息
			var cmdMsg CommandMessage
			cmdMsg.Type = compressedMsg.Type
			cmdMsg.RequestId = compressedMsg.RequestId
			if err := json.Unmarshal(message, &cmdMsg.Data); err != nil {
				s.sendErrorResponse("ParseError", fmt.Sprintf("解析命令失败: %v", err))
				return
			}

			if cmdMsg.Type != "call" {
				s.enqueue(w.routeCommand(s.ctx, cmdMsg))
			}
		} else {
			// 处理普通消息
			var cmdMsg CommandMessage
			if err := json.Unmarshal(message, &cmdMsg); err != nil {
				s.sendErrorResponse("ParseError", fmt.Sprintf("解析命令失败: %v", err))
				return
			}
			if cmdMsg.Type != "call" {
				s.enqueue(w.routeCommand(s.ctx, cmdMsg))
			}
		}

	default:
		fmt.Printf("📨 收到未知类型消息: %d\n", messageType)
	}
}

// routeCommand 路由命令到对应的处理函数
func (w *WebSocketReporter) routeCommand(ctx context.Context, cmd CommandMessage) CommandResponse {
	if cmd.Type != "TcpPing" {
		config.RuntimeMu.Lock()
		defer config.RuntimeMu.Unlock()
		if ctx.Err() != nil {
			return CommandResponse{RequestId: cmd.RequestId}
		}
	}
	var err error
	var response CommandResponse

	// 传递 requestId
	response.RequestId = cmd.RequestId

	switch cmd.Type {
	// Service 相关命令
	case "AddService":
		err = w.handleAddService(cmd.Data)
		response.Type = "AddServiceResponse"
	case "UpdateService":
		err = w.handleUpdateService(cmd.Data)
		response.Type = "UpdateServiceResponse"
	case "DeleteService":
		err = w.handleDeleteService(cmd.Data)
		response.Type = "DeleteServiceResponse"
	case "PauseService":
		err = w.handlePauseService(cmd.Data)
		response.Type = "PauseServiceResponse"
	case "ResumeService":
		err = w.handleResumeService(cmd.Data)
		response.Type = "ResumeServiceResponse"

	// Chain 相关命令
	case "AddChains":
		err = w.handleAddChain(cmd.Data)
		response.Type = "AddChainsResponse"
	case "UpdateChains":
		err = w.handleUpdateChain(cmd.Data)
		response.Type = "UpdateChainsResponse"
	case "DeleteChains":
		err = w.handleDeleteChain(cmd.Data)
		response.Type = "DeleteChainsResponse"

	// Limiter 相关命令
	case "AddLimiters":
		err = w.handleAddLimiter(cmd.Data)
		response.Type = "AddLimitersResponse"
	case "UpdateLimiters":
		err = w.handleUpdateLimiter(cmd.Data)
		response.Type = "UpdateLimitersResponse"
	case "DeleteLimiters":
		err = w.handleDeleteLimiter(cmd.Data)
		response.Type = "DeleteLimitersResponse"

	// TCP Ping 诊断命令
	case "TcpPing":
		var tcpPingResult TcpPingResponse
		tcpPingResult, err = w.handleTcpPing(ctx, cmd.Data)
		response.Type = "TcpPingResponse"
		response.Data = tcpPingResult

	// Protocol blocking switches
	case "SetProtocol":
		err = w.handleSetProtocol(cmd.Data)
		response.Type = "SetProtocolResponse"

	default:
		err = fmt.Errorf("未知命令类型: %s", cmd.Type)
		response.Type = "UnknownCommandResponse"
	}

	// Only forwarding mutations write the forwarding file. SetProtocol persists its own file.
	if err == nil && response.Type != "TcpPingResponse" && response.Type != "SetProtocolResponse" {
		if saveErr := saveConfig(w.persistPath); saveErr != nil {
			fmt.Printf("转发配置写入失败: %v\n", saveErr)
			err = fmt.Errorf("运行配置已更新，持久化失败: %w", saveErr)
		}
	}
	response.Success = err == nil
	if err != nil {
		response.Message = err.Error()
	} else {
		response.Message = "OK"
	}
	fmt.Printf("WebSocket命令 result=%t type=%q requestId=%q\n", response.Success, response.Type, logRequestID(cmd.RequestId))
	return response
}

// Service 命令处理函数
func (w *WebSocketReporter) handleAddService(data interface{}) error {
	// 将 interface{} 转换为 JSON 再解析为具体类型
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	// 预处理：将字符串格式的 duration 转换为纳秒数
	processedData, err := w.preprocessDurationFields(jsonData)
	if err != nil {
		return fmt.Errorf("预处理duration字段失败: %v", err)
	}

	var services []config.ServiceConfig
	if err := json.Unmarshal(processedData, &services); err != nil {
		return fmt.Errorf("解析服务配置失败: %v", err)
	}

	req := createServicesRequest{Data: services}
	return createServices(req)
}

func (w *WebSocketReporter) handleUpdateService(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	// 预处理：将字符串格式的 duration 转换为纳秒数
	processedData, err := w.preprocessDurationFields(jsonData)
	if err != nil {
		return fmt.Errorf("预处理duration字段失败: %v", err)
	}

	var services []config.ServiceConfig
	if err := json.Unmarshal(processedData, &services); err != nil {
		return fmt.Errorf("解析服务配置失败: %v", err)
	}

	req := updateServicesRequest{Data: services}
	return updateServices(req)
}

func (w *WebSocketReporter) handleDeleteService(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	var req deleteServicesRequest
	if err := json.Unmarshal(jsonData, &req); err != nil {
		return fmt.Errorf("解析删除请求失败: %v", err)
	}

	return deleteServices(req)
}

func (w *WebSocketReporter) handlePauseService(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	var req pauseServicesRequest
	if err := json.Unmarshal(jsonData, &req); err != nil {
		return fmt.Errorf("解析暂停请求失败: %v", err)
	}

	return pauseServices(req)
}

func (w *WebSocketReporter) handleResumeService(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	var req resumeServicesRequest
	if err := json.Unmarshal(jsonData, &req); err != nil {
		return fmt.Errorf("解析恢复请求失败: %v", err)
	}

	return resumeServices(req)
}

// Chain 命令处理函数
func (w *WebSocketReporter) handleAddChain(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	var chainConfig config.ChainConfig
	if err := json.Unmarshal(jsonData, &chainConfig); err != nil {
		return fmt.Errorf("解析链配置失败: %v", err)
	}

	req := createChainRequest{Data: chainConfig}
	return createChain(req)
}

func (w *WebSocketReporter) handleUpdateChain(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	// 对于更新操作，Java端发送的格式可能是: {"chain": "name", "data": {...}}
	var updateReq struct {
		Chain string             `json:"chain"`
		Data  config.ChainConfig `json:"data"`
	}

	// 尝试解析为更新请求格式
	if err := json.Unmarshal(jsonData, &updateReq); err != nil {
		// 如果失败，可能是直接的ChainConfig，从name字段获取chain名称
		var chainConfig config.ChainConfig
		if err := json.Unmarshal(jsonData, &chainConfig); err != nil {
			return fmt.Errorf("解析链配置失败: %v", err)
		}
		updateReq.Chain = chainConfig.Name
		updateReq.Data = chainConfig
	}

	req := updateChainRequest{
		Chain: updateReq.Chain,
		Data:  updateReq.Data,
	}
	return updateChain(req)
}

func (w *WebSocketReporter) handleDeleteChain(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	// 删除操作可能是: {"chain": "name"} 或者直接是链名称字符串
	var deleteReq deleteChainRequest

	// 尝试解析为删除请求格式
	if err := json.Unmarshal(jsonData, &deleteReq); err != nil {
		// 如果失败，可能是字符串格式的名称
		var chainName string
		if err := json.Unmarshal(jsonData, &chainName); err != nil {
			return fmt.Errorf("解析链删除请求失败: %v", err)
		}
		deleteReq.Chain = chainName
	}

	return deleteChain(deleteReq)
}

// Limiter 命令处理函数
func (w *WebSocketReporter) handleAddLimiter(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	var limiterConfig config.LimiterConfig
	if err := json.Unmarshal(jsonData, &limiterConfig); err != nil {
		return fmt.Errorf("解析限流器配置失败: %v", err)
	}

	req := createLimiterRequest{Data: limiterConfig}
	return createLimiter(req)
}

func (w *WebSocketReporter) handleUpdateLimiter(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	// 对于更新操作，Java端发送的格式可能是: {"limiter": "name", "data": {...}}
	var updateReq struct {
		Limiter string               `json:"limiter"`
		Data    config.LimiterConfig `json:"data"`
	}

	// 尝试解析为更新请求格式
	if err := json.Unmarshal(jsonData, &updateReq); err != nil {
		// 如果失败，可能是直接的LimiterConfig，从name字段获取limiter名称
		var limiterConfig config.LimiterConfig
		if err := json.Unmarshal(jsonData, &limiterConfig); err != nil {
			return fmt.Errorf("解析限流器配置失败: %v", err)
		}
		updateReq.Limiter = limiterConfig.Name
		updateReq.Data = limiterConfig
	}

	req := updateLimiterRequest{
		Limiter: updateReq.Limiter,
		Data:    updateReq.Data,
	}
	return updateLimiter(req)
}

func (w *WebSocketReporter) handleDeleteLimiter(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化数据失败: %v", err)
	}

	// 删除操作可能是: {"limiter": "name"} 或者直接是限流器名称字符串
	var deleteReq deleteLimiterRequest

	// 尝试解析为删除请求格式
	if err := json.Unmarshal(jsonData, &deleteReq); err != nil {
		// 如果失败，可能是字符串格式的名称
		var limiterName string
		if err := json.Unmarshal(jsonData, &limiterName); err != nil {
			return fmt.Errorf("解析限流器删除请求失败: %v", err)
		}
		deleteReq.Limiter = limiterName
	}

	return deleteLimiter(deleteReq)
}

// handleSetProtocol 处理设置屏蔽协议的命令
func (w *WebSocketReporter) handleSetProtocol(data interface{}) error {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return fmt.Errorf("序列化协议设置失败: %v", err)
	}

	// 支持 {"http":0/1, "tls":0/1, "socks":0/1}
	var req struct {
		HTTP  *int `json:"http"`
		TLS   *int `json:"tls"`
		SOCKS *int `json:"socks"`
	}
	if err := json.Unmarshal(jsonData, &req); err != nil {
		return fmt.Errorf("解析协议设置失败: %v", err)
	}

	// 读取当前值作为默认
	httpVal, tlsVal, socksVal := 0, 0, 0

	if req.HTTP != nil {
		if *req.HTTP != 0 && *req.HTTP != 1 {
			return fmt.Errorf("http 取值必须为0或1")
		}
		httpVal = *req.HTTP
	}
	if req.TLS != nil {
		if *req.TLS != 0 && *req.TLS != 1 {
			return fmt.Errorf("tls 取值必须为0或1")
		}
		tlsVal = *req.TLS
	}
	if req.SOCKS != nil {
		if *req.SOCKS != 0 && *req.SOCKS != 1 {
			return fmt.Errorf("socks 取值必须为0或1")
		}
		socksVal = *req.SOCKS
	}

	if err := updateLocalConfigJSON(w.nodeConfigPath, httpVal, tlsVal, socksVal); err != nil {
		fmt.Printf("节点配置写入失败: %v\n", err)
		return fmt.Errorf("写入节点配置失败: %w", err)
	}
	service.SetProtocolBlock(httpVal, tlsVal, socksVal)
	return nil
}

func updateLocalConfigJSON(path string, httpVal, tlsVal, socksVal int) error {
	body, err := os.ReadFile(path)
	if err != nil {
		return err
	}
	var cfg map[string]json.RawMessage
	if err := json.Unmarshal(body, &cfg); err != nil {
		return err
	}
	if cfg == nil {
		return fmt.Errorf("节点配置应为 JSON 对象")
	}
	cfg["http"] = json.RawMessage(strconv.Itoa(httpVal))
	cfg["tls"] = json.RawMessage(strconv.Itoa(tlsVal))
	cfg["socks"] = json.RawMessage(strconv.Itoa(socksVal))
	return config.WriteFileAtomic(path, func(w io.Writer) error {
		enc := json.NewEncoder(w)
		enc.SetIndent("", "  ")
		return enc.Encode(cfg)
	})
}

func logRequestID(id string) string {
	if len(id) > 80 {
		return "[omitted]"
	}
	for _, c := range id {
		if !strings.ContainsRune("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_.:", c) {
			return "[omitted]"
		}
	}
	return id
}

func (s *webSocketSession) sendErrorResponse(responseType, message string) {
	s.enqueue(CommandResponse{Type: responseType, Success: false, Message: message})
}

// getUptime 获取系统开机时间（秒）
func getUptime() uint64 {
	uptime, err := host.Uptime()
	if err != nil {
		return 0
	}
	return uptime
}

// getNetworkStats 获取网络统计信息
func getNetworkStats() NetworkStats {
	var stats NetworkStats

	ioCounters, err := psnet.IOCounters(true)
	if err != nil {
		fmt.Printf("获取网络统计失败: %v\n", err)
		return stats
	}

	// 汇总所有非回环接口的流量
	for _, io := range ioCounters {
		// 跳过回环接口
		if io.Name == "lo" || strings.HasPrefix(io.Name, "lo") {
			continue
		}

		stats.BytesReceived += io.BytesRecv
		stats.BytesTransmitted += io.BytesSent
	}

	return stats
}

// getCPUInfo 获取CPU信息
func getCPUInfo() CPUInfo {
	var cpuInfo CPUInfo

	// 获取CPU使用率
	percentages, err := cpu.Percent(time.Second, false)
	if err == nil && len(percentages) > 0 {
		cpuInfo.Usage = percentages[0]
	}

	return cpuInfo
}

// getMemoryInfo 获取内存信息
func getMemoryInfo() MemoryInfo {
	var memInfo MemoryInfo

	vmStat, err := mem.VirtualMemory()
	if err != nil {
		return memInfo
	}

	memInfo.Usage = vmStat.UsedPercent

	return memInfo
}

// StartWebSocketReporterWithConfig 使用配置字段启动WebSocket报告器
func StartWebSocketReporterWithConfig(addr, secret string, http, tls, socks int, version string, paths ...string) *WebSocketReporter {
	u := url.URL{Scheme: "ws", Host: addr, Path: "/system-info"}
	u.RawQuery = url.Values{"type": {"1"}, "secret": {secret}, "version": {version}}.Encode()
	reporter := NewWebSocketReporter(u.String(), secret)
	reporter.configured = true
	if len(paths) > 0 {
		reporter.nodeConfigPath = paths[0]
	}
	if len(paths) > 1 {
		reporter.persistPath = paths[1]
	}
	service.SetProtocolBlock(http, tls, socks)
	reporter.Start()
	return reporter
}

// handleTcpPing 处理TCP ping诊断命令
func (w *WebSocketReporter) handleTcpPing(ctx context.Context, data interface{}) (TcpPingResponse, error) {
	jsonData, err := json.Marshal(data)
	if err != nil {
		return TcpPingResponse{}, fmt.Errorf("序列化TCP ping数据失败: %v", err)
	}

	var req TcpPingRequest
	if err := json.Unmarshal(jsonData, &req); err != nil {
		return TcpPingResponse{}, fmt.Errorf("解析TCP ping请求失败: %v", err)
	}

	// 验证IP地址格式
	if net.ParseIP(req.IP) == nil && !isValidHostname(req.IP) {
		return TcpPingResponse{
			IP:           req.IP,
			Port:         req.Port,
			Success:      false,
			ErrorMessage: "无效的IP地址或主机名",
			RequestId:    req.RequestId,
		}, nil
	}

	// 验证端口范围
	if req.Port <= 0 || req.Port > 65535 {
		return TcpPingResponse{
			IP:           req.IP,
			Port:         req.Port,
			Success:      false,
			ErrorMessage: "无效的端口号，范围应为1-65535",
			RequestId:    req.RequestId,
		}, nil
	}

	// 设置默认值
	if req.Count <= 0 {
		req.Count = 4
	}
	if req.Timeout <= 0 {
		req.Timeout = 5000 // 默认5秒超时
	}

	// 执行TCP ping操作
	avgTime, packetLoss, err := tcpPingHost(ctx, req.IP, req.Port, req.Count, req.Timeout)

	response := TcpPingResponse{
		IP:        req.IP,
		Port:      req.Port,
		RequestId: req.RequestId,
	}

	if err != nil {
		response.Success = false
		response.ErrorMessage = err.Error()
	} else {
		response.Success = true
		response.AverageTime = avgTime
		response.PacketLoss = packetLoss
	}

	return response, nil
}

// tcpPingHost 执行TCP连接测试，返回平均连接时间和失败率
func tcpPingHost(ctx context.Context, ip string, port int, count int, timeoutMs int) (float64, float64, error) {
	var totalTime float64
	var successCount int

	timeout := time.Duration(timeoutMs) * time.Millisecond

	// 使用net.JoinHostPort来正确处理IPv4、IPv6和域名
	// 它会自动为IPv6地址添加方括号
	target := net.JoinHostPort(ip, fmt.Sprintf("%d", port))

	// 如果是域名，先解析一次DNS，避免每次连接都重新解析导致延迟累加
	if net.ParseIP(ip) == nil {
		// 是域名，需要解析

		addrs, err := net.DefaultResolver.LookupHost(ctx, ip)

		if err != nil {
			return 0, 100.0, fmt.Errorf("DNS解析失败: %v", err)
		}
		if len(addrs) == 0 {
			return 0, 100.0, fmt.Errorf("DNS解析未返回任何IP地址")
		}

		// 使用第一个解析到的IP进行测试
		target = net.JoinHostPort(addrs[0], fmt.Sprintf("%d", port))
	}

	for i := 0; i < count; i++ {
		start := time.Now()

		// 创建带超时的TCP连接
		if err := ctx.Err(); err != nil {
			return 0, 100, err
		}
		dialer := net.Dialer{Timeout: timeout}
		conn, err := dialer.DialContext(ctx, "tcp", target)

		elapsed := time.Since(start)

		if err == nil {
			conn.Close()
			totalTime += elapsed.Seconds() * 1000 // 转换为毫秒
			successCount++
		}

		// 如果不是最后一次，等待一下再进行下次测试
		if i < count-1 {
			timer := time.NewTimer(100 * time.Millisecond)
			select {
			case <-timer.C:
			case <-ctx.Done():
				timer.Stop()
				return 0, 100, ctx.Err()
			}
		}
	}

	if successCount == 0 {
		return 0, 100.0, fmt.Errorf("所有TCP连接尝试都失败")
	}

	avgTime := totalTime / float64(successCount)
	packetLoss := float64(count-successCount) / float64(count) * 100

	return avgTime, packetLoss, nil
}

// isValidHostname 验证主机名格式
func isValidHostname(hostname string) bool {
	if len(hostname) == 0 || len(hostname) > 253 {
		return false
	}

	// 简单的主机名验证
	for _, r := range hostname {
		if !((r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') ||
			(r >= '0' && r <= '9') || r == '-' || r == '.') {
			return false
		}
	}

	return true
}

// preprocessDurationFields 预处理 JSON 数据中的 duration 字段
func (w *WebSocketReporter) preprocessDurationFields(jsonData []byte) ([]byte, error) {
	var rawData interface{}
	if err := json.Unmarshal(jsonData, &rawData); err != nil {
		return nil, err
	}

	// 递归处理 duration 字段
	processed := w.processDurationInData(rawData)

	return json.Marshal(processed)
}

// processDurationInData 递归处理数据中的 duration 字段
func (w *WebSocketReporter) processDurationInData(data interface{}) interface{} {
	switch v := data.(type) {
	case []interface{}:
		// 处理数组
		for i, item := range v {
			v[i] = w.processDurationInData(item)
		}
		return v
	case map[string]interface{}:
		// 处理对象
		for key, value := range v {
			if key == "selector" {
				// 处理 selector 对象中的 failTimeout
				if selectorObj, ok := value.(map[string]interface{}); ok {
					if failTimeoutVal, exists := selectorObj["failTimeout"]; exists {
						if failTimeoutStr, ok := failTimeoutVal.(string); ok {
							// 将字符串格式的 duration 转换为纳秒数
							if duration, err := time.ParseDuration(failTimeoutStr); err == nil {
								selectorObj["failTimeout"] = int64(duration)
							}
						}
					}
				}
			}
			v[key] = w.processDurationInData(value)
		}
		return v
	default:
		return v
	}
}
