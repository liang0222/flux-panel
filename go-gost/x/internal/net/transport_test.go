package net

import (
	"context"
	"errors"
	"io"
	"net"
	"runtime"
	"testing"
	"time"
)

// newPipePair 返回两对互不相干的 net.Pipe 端点，用于模拟
// “客户端连接”和“上游连接”两条独立链路。
func newPipePair(t *testing.T) (client, upstream net.Conn, clientPeer, upstreamPeer net.Conn) {
	t.Helper()
	client, clientPeer = net.Pipe()
	upstream, upstreamPeer = net.Pipe()
	t.Cleanup(func() {
		client.Close()
		clientPeer.Close()
		upstream.Close()
		upstreamPeer.Close()
	})
	return
}

// TestTransportContextCancelledClosesBothEnds 验证 ctx 取消后 TransportContext
// 会主动关闭两端并立即返回——这是「删除端口后旧连接仍在转发」的修复核心。
func TestTransportContextCancelledClosesBothEnds(t *testing.T) {
	client, upstream, _, _ := newPipePair(t)

	ctx, cancel := context.WithCancel(context.Background())

	done := make(chan error, 1)
	go func() {
		done <- TransportContext(ctx, client, upstream)
	}()

	// 没有任何数据流动，两个 copy goroutine 都阻塞在 Read 上。
	time.Sleep(50 * time.Millisecond)
	cancel()

	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("TransportContext 在 ctx 取消后应返回 nil，实际返回 %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("ctx 取消后 TransportContext 未返回：连接未被主动关闭")
	}

	// 两端都应已关闭：向它们写入应立刻报错。
	if _, err := client.Write([]byte("x")); err == nil {
		t.Fatal("ctx 取消后 client 端未关闭")
	}
	if _, err := upstream.Write([]byte("x")); err == nil {
		t.Fatal("ctx 取消后 upstream 端未关闭")
	}
}

// TestTransportContextDoesNotLeakGoroutines 验证反复取消不会累积 goroutine。
// 旧实现用容量为 1 的 errc，ctx 分支提前返回后另一个 goroutine 会永久阻塞在发送上。
func TestTransportContextDoesNotLeakGoroutines(t *testing.T) {
	// 先跑一次让运行时相关 goroutine 稳定下来。
	func() {
		c, u, _, _ := newPipePair(t)
		ctx, cancel := context.WithCancel(context.Background())
		go TransportContext(ctx, c, u)
		cancel()
		time.Sleep(50 * time.Millisecond)
	}()

	runtime.GC()
	before := runtime.NumGoroutine()

	const rounds = 50
	for i := 0; i < rounds; i++ {
		c, u, _, _ := newPipePair(t)
		ctx, cancel := context.WithCancel(context.Background())
		finished := make(chan struct{})
		go func() {
			TransportContext(ctx, c, u)
			close(finished)
		}()
		cancel()
		select {
		case <-finished:
		case <-time.After(2 * time.Second):
			t.Fatalf("第 %d 轮未返回", i)
		}
	}

	// 给已退出的 goroutine 一点调度时间。
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if runtime.NumGoroutine() <= before+5 {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("goroutine 疑似泄漏：之前 %d，之后 %d", before, runtime.NumGoroutine())
}

// TestTransportContextCopiesData 验证正常数据双向搬运不受影响。
func TestTransportContextCopiesData(t *testing.T) {
	client, upstream, clientPeer, upstreamPeer := newPipePair(t)

	done := make(chan error, 1)
	go func() {
		done <- TransportContext(context.Background(), client, upstream)
	}()

	payload := []byte("hello-gost")
	go func() {
		clientPeer.Write(payload)
	}()

	got := make([]byte, len(payload))
	if _, err := io.ReadFull(upstreamPeer, got); err != nil {
		t.Fatalf("读取转发数据失败: %v", err)
	}
	if string(got) != string(payload) {
		t.Fatalf("数据不一致: 期望 %q，实际 %q", payload, got)
	}

	// 关闭一侧，TransportContext 应正常返回（EOF 不视为错误）。
	clientPeer.Close()
	select {
	case err := <-done:
		if err != nil && !errors.Is(err, io.EOF) {
			t.Fatalf("正常关闭应返回 nil，实际 %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("连接关闭后 TransportContext 未返回")
	}
}

// TestTransportContextPropagatesError 验证非 EOF 错误会向上抛出。
//
// 注意：两个 copy goroutine 谁先返回是不确定的（修复前后都是如此），
// 因此这里让两端都返回非 EOF 错误，避免测试本身引入竞态。
// 「EOF 不算错误」由 TestTransportContextCopiesData 覆盖。
func TestTransportContextPropagatesError(t *testing.T) {
	boom := errors.New("boom")
	rw1 := &errReadWriter{readErr: boom}
	rw2 := &errReadWriter{readErr: boom}

	err := TransportContext(context.Background(), rw1, rw2)
	if err == nil {
		t.Fatal("期望返回错误，实际为 nil")
	}
	if !errors.Is(err, boom) {
		t.Fatalf("期望返回 boom，实际 %v", err)
	}
}

// TestTransportWithoutContextStillWorks 确认保留的旧入口行为不变。
func TestTransportWithoutContextStillWorks(t *testing.T) {
	client, upstream, clientPeer, upstreamPeer := newPipePair(t)

	done := make(chan error, 1)
	go func() {
		done <- Transport(client, upstream)
	}()

	go clientPeer.Write([]byte("abc"))
	buf := make([]byte, 3)
	if _, err := io.ReadFull(upstreamPeer, buf); err != nil {
		t.Fatalf("旧入口转发失败: %v", err)
	}
	clientPeer.Close()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("旧入口未返回")
	}
}

type errReadWriter struct {
	readErr  error
	writeErr error
}

func (e *errReadWriter) Read(p []byte) (int, error)  { return 0, e.readErr }
func (e *errReadWriter) Write(p []byte) (int, error) { return 0, e.writeErr }
