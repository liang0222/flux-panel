package net

import (
	"context"
	"io"

	"github.com/go-gost/core/common/bufpool"
)

const (
	bufferSize = 64 * 1024
)

// Transport 在两个 ReadWriter 之间双向搬运数据，不感知 context。
//
// 保留该函数是为了不破坏既有调用方；新代码应优先使用 TransportContext，
// 否则服务被关闭（service.Close）时已建立的连接无法被及时中断。
func Transport(rw1, rw2 io.ReadWriter) error {
	return TransportContext(context.Background(), rw1, rw2)
}

// TransportContext 与 Transport 行为一致，额外响应 ctx 取消。
//
// 背景：删除端口/隧道时，service.Close() 只会关闭 listener 并取消 service
// 级别的 context，而 io.CopyBuffer 阻塞在 socket 读写系统调用上，光靠
// context 取消无法唤醒它。结果是监听端口已经消失，但删除前建立的连接仍在
// 继续转发真实流量，直到对端主动断开或 TCP keepalive 超时（默认 10 分钟以上）。
//
// 因此在 ctx 被取消时，这里主动关闭两端连接，让两个 copy goroutine 立刻从
// 阻塞的 Read/Write 中返回，从而让 handler 退出、service 的 WaitGroup 归零。
func TransportContext(ctx context.Context, rw1, rw2 io.ReadWriter) error {
	// 缓冲区必须能同时容纳两个 goroutine 的结果：ctx 分支会提前返回，
	// 若容量为 1，另一个 goroutine 会永久阻塞在发送上，造成 goroutine 泄漏。
	errc := make(chan error, 2)

	go func() {
		errc <- CopyBuffer(rw1, rw2, bufferSize)
	}()

	go func() {
		errc <- CopyBuffer(rw2, rw1, bufferSize)
	}()

	if ctx == nil {
		ctx = context.Background()
	}

	select {
	case <-ctx.Done():
		// 主动断开两端，唤醒阻塞中的 copy goroutine。
		// 两个 goroutine 会各自把结果写入容量为 2 的 errc 后退出，不会泄漏。
		closeReadWriter(rw1)
		closeReadWriter(rw2)
		return nil
	case err := <-errc:
		// 保持原有语义：任一方向结束即返回，由调用方的 defer 关闭两端。
		if err != nil && err != io.EOF {
			return err
		}
		return nil
	}
}

// closeReadWriter 关闭实现了 io.Closer 的一端；未实现时静默跳过。
// 重复关闭是安全的：net.Conn 与项目内的包装类型都允许重复 Close。
func closeReadWriter(rw io.ReadWriter) {
	if c, ok := rw.(io.Closer); ok {
		c.Close()
	}
}

func CopyBuffer(dst io.Writer, src io.Reader, bufSize int) error {
	buf := bufpool.Get(bufSize)
	defer bufpool.Put(buf)

	_, err := io.CopyBuffer(dst, src, buf)
	return err
}
