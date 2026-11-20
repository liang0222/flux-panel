package service

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
)

const trafficShutdownTimeout = 5 * time.Second

type trafficBatch struct {
	id    string
	items []TrafficReportItem
}

// GlobalTrafficManager has one sender. Pending is owned by that sender, while
// new increments can be collected under mu even during a network request.
type GlobalTrafficManager struct {
	mu       sync.Mutex
	traffic  map[string]TrafficReportItem
	pending  trafficBatch
	ctx      context.Context
	cancel   context.CancelFunc
	stopOnce sync.Once
	flush    context.Context
	done     chan struct{}
	interval time.Duration
	lastWarn time.Time
	send     func(context.Context, string, []TrafficReportItem) error
}

var globalManager = sync.OnceValue(func() *GlobalTrafficManager {
	return newTrafficManager(5*time.Second, func(ctx context.Context, id string, items []TrafficReportItem) error {
		_, err := sendBatchTrafficReport(ctx, id, items)
		return err
	})
})

func GetGlobalTrafficManager() *GlobalTrafficManager { return globalManager() }

func newTrafficManager(interval time.Duration, send func(context.Context, string, []TrafficReportItem) error) *GlobalTrafficManager {
	ctx, cancel := context.WithCancel(context.Background())
	m := &GlobalTrafficManager{traffic: make(map[string]TrafficReportItem), ctx: ctx, cancel: cancel,
		done: make(chan struct{}), interval: interval, send: send}
	go m.run()
	return m
}

func (m *GlobalTrafficManager) AddTraffic(name string, up, down int64) {
	if up == 0 && down == 0 {
		return
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	t := m.traffic[name]
	t.N, t.U, t.D = name, t.U+up, t.D+down
	m.traffic[name] = t
}

func (m *GlobalTrafficManager) run() {
	defer close(m.done)
	ticker := time.NewTicker(m.interval)
	defer ticker.Stop()
	for m.ctx.Err() == nil {
		select {
		case <-ticker.C:
			m.report(m.ctx)
		case <-m.ctx.Done():
		}
	}
	// A pending batch may already have been accepted upstream. Retry exactly
	// that batch first, then send at most one snapshot of later increments.
	if m.pending.id != "" && !m.report(m.flush) {
		return
	}
	m.report(m.flush)
}

func (m *GlobalTrafficManager) report(ctx context.Context) bool {
	if ctx.Err() != nil {
		return false
	}
	if m.pending.id == "" {
		m.mu.Lock()
		if len(m.traffic) != 0 {
			id, err := uuid.NewRandom()
			if err != nil {
				m.mu.Unlock()
				fmt.Printf("生成流量批次标识失败: %v\n", err)
				return false
			}
			m.pending = trafficBatch{id: id.String(), items: make([]TrafficReportItem, 0, len(m.traffic))}
			for _, item := range m.traffic {
				m.pending.items = append(m.pending.items, item)
			}
			clear(m.traffic)
		}
		m.mu.Unlock()
	}
	if m.pending.id == "" {
		return true
	}
	if err := m.send(ctx, m.pending.id, m.pending.items); err != nil {
		if ctx.Err() == nil && time.Since(m.lastWarn) >= time.Minute {
			m.lastWarn = time.Now()
			fmt.Printf("全局流量上报失败，保留未确认批次 (%d 个服务): %v\n", len(m.pending.items), err)
		}
		return false
	}
	m.mu.Lock()
	m.pending = trafficBatch{}
	m.mu.Unlock()
	return true
}

// Stop cancels any in-flight periodic request and shares one bounded final
// flush across callers. A caller never starts another sender.
func (m *GlobalTrafficManager) Stop() {
	ctx, cancel := context.WithTimeout(context.Background(), trafficShutdownTimeout)
	defer cancel()
	m.stop(ctx)
}

func (m *GlobalTrafficManager) stop(ctx context.Context) {
	m.stopOnce.Do(func() {
		m.flush = ctx
		m.cancel()
	})
	select {
	case <-m.done:
	case <-ctx.Done():
		fmt.Println("全局流量尾部上报超时")
	}
}

func (m *GlobalTrafficManager) GetServiceTraffic(name string) (upBytes, downBytes int64) {
	m.mu.Lock()
	defer m.mu.Unlock()
	t := m.traffic[name]
	for _, item := range m.pending.items {
		if item.N == name {
			t.U += item.U
			t.D += item.D
			break
		}
	}
	return t.U, t.D
}
