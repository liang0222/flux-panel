package service

import (
	"context"
	"io"
	"net"
	"testing"
	"time"

	"github.com/go-gost/core/handler"
	md "github.com/go-gost/core/metadata"
	xnet "github.com/go-gost/x/internal/net"
	xlogger "github.com/go-gost/x/logger"
)

// blockingHandler 模拟 relay/forward 的真实行为：建立到“上游”的连接后，
// 调用 net.TransportContext 做双向搬运，并阻塞到 ctx 取消或连接结束。
//
// 这正是生产里删除端口后的场景：listener 已关闭，但这条 goroutine 仍握着
// 两条连接继续转发数据。
type blockingHandler struct {
	upstreamReady chan struct{}
	upstreamPeer  net.Conn
}

func (h *blockingHandler) Init(md.Metadata) error { return nil }

func (h *blockingHandler) Handle(ctx context.Context, conn net.Conn, opts ...handler.HandleOption) error {
	defer conn.Close()

	upstream, peer := net.Pipe()
	h.upstreamPeer = peer
	defer upstream.Close()
	close(h.upstreamReady)

	// 关键：传入 ctx，让 TransportContext 能感知服务关闭。
	xnet.TransportContext(ctx, conn, upstream)
	return nil
}

// TestServeReturnsAfterCloseWithActiveConnection 是本轮修复的核心回归测试。
//
// 修复前：Serve 的 defer 顺序是 wg.Wait() → cancel()，且 Transport 不看 ctx，
// 于是 Close() 之后 Serve 会一直等到连接自然断开（keepalive 默认 10 分钟以上）。
// 修复后：Close() 触发 ctx 取消 → TransportContext 主动断开两端 → wg 归零 →
// Serve 立刻返回。
func TestServeReturnsAfterCloseWithActiveConnection(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}

	h := &blockingHandler{upstreamReady: make(chan struct{})}
	svc := NewService("test-svc", &tcpListener{ln}, h, LoggerOption(xlogger.Nop()))

	serveDone := make(chan error, 1)
	go func() {
		serveDone <- svc.Serve()
	}()

	// 建立一个真实客户端连接，让 handler 进入 TransportContext 阻塞态。
	client, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer client.Close()

	select {
	case <-h.upstreamReady:
	case <-time.After(2 * time.Second):
		t.Fatal("handler 未进入转发状态")
	}

	// 确认此刻连接确实是“活的”：客户端发数据，上游回写，客户端能读到。
	go func() {
		time.Sleep(50 * time.Millisecond)
		h.upstreamPeer.Write([]byte("pong"))
	}()
	if _, err := client.Write([]byte("ping")); err != nil {
		t.Fatalf("写客户端失败: %v", err)
	}
	buf := make([]byte, 4)
	client.SetReadDeadline(time.Now().Add(2 * time.Second))
	if _, err := io.ReadFull(client, buf); err != nil {
		t.Fatalf("转发通路不通: %v", err)
	}
	client.SetReadDeadline(time.Time{})

	// 现在关闭 service —— 等价于面板删除端口。
	start := time.Now()
	if err := svc.Close(); err != nil {
		t.Fatalf("close: %v", err)
	}

	select {
	case err := <-serveDone:
		elapsed := time.Since(start)
		// 旧实现这里会挂到 keepalive 超时；新实现应在毫秒级返回。
		if elapsed > 3*time.Second {
			t.Fatalf("Serve 在 Close 后 %v 才返回，说明旧连接未被及时中断", elapsed)
		}
		if err != nil && err != net.ErrClosed {
			t.Logf("Serve 返回 %v（net.ErrClosed 之外的值，仅记录）", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Serve 在 Close 后 5 秒仍未返回：旧连接仍在转发（回归）")
	}
}

// TestServeClosesClientConnectionAfterClose 验证 Close 之后客户端连接确实断开，
// 而不是仅 Serve 返回、连接继续存在。
func TestServeClosesClientConnectionAfterClose(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}

	h := &blockingHandler{upstreamReady: make(chan struct{})}
	svc := NewService("test-svc-2", &tcpListener{ln}, h, LoggerOption(xlogger.Nop()))

	go svc.Serve()

	client, err := net.Dial("tcp", ln.Addr().String())
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer client.Close()

	select {
	case <-h.upstreamReady:
	case <-time.After(2 * time.Second):
		t.Fatal("handler 未进入转发状态")
	}

	svc.Close()

	// 连接被主动关闭后，读操作应很快返回 EOF / 错误，而不是一直阻塞。
	client.SetReadDeadline(time.Now().Add(3 * time.Second))
	_, err = client.Read(make([]byte, 1))
	if err == nil {
		t.Fatal("Close 之后客户端连接仍可读到数据")
	}
	if ne, ok := err.(net.Error); ok && ne.Timeout() {
		t.Fatal("Close 之后客户端连接既未断开也未报错（读超时）：连接未被主动关闭")
	}
}

// tcpListener 把 net.Listener 适配成 listener.Listener。
type tcpListener struct {
	net.Listener
}

func (l *tcpListener) Init(md.Metadata) error { return nil }
func (l *tcpListener) Accept() (net.Conn, error) {
	return l.Listener.Accept()
}
