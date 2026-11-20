package service

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sync/atomic"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestTrafficStopRetriesExactBatchBeforeNewIncrements(t *testing.T) {
	requests := make(chan trafficBatch, 4)
	var calls, active atomic.Int32
	began := make(chan struct{})
	m := newTrafficManager(10*time.Millisecond, func(ctx context.Context, id string, items []TrafficReportItem) error {
		if active.Add(1) != 1 {
			t.Error("concurrent send")
		}
		defer active.Add(-1)
		requests <- trafficBatch{id: id, items: append([]TrafficReportItem(nil), items...)}
		if calls.Add(1) == 1 {
			close(began)
			<-ctx.Done()
			return ctx.Err()
		}
		return nil
	})
	m.AddTraffic("fixture", 9, 11)
	select {
	case <-began:
	case <-time.After(time.Second):
		t.Fatal("periodic report did not start")
	}
	m.AddTraffic("fixture", 5, 7)
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	m.stop(ctx)
	var attempts []trafficBatch
	for len(requests) > 0 {
		attempts = append(attempts, <-requests)
	}
	if len(attempts) != 3 {
		t.Fatalf("requests: %+v", attempts)
	}
	parsed, err := uuid.Parse(attempts[0].id)
	if err != nil || parsed.Version() != 4 || parsed.Variant() != uuid.RFC4122 || parsed.String() != attempts[0].id {
		t.Fatalf("invalid batch ID: %q", attempts[0].id)
	}
	if !reflect.DeepEqual(attempts[0], attempts[1]) || attempts[2].id == attempts[0].id || !reflect.DeepEqual(attempts[2].items, []TrafficReportItem{{N: "fixture", U: 5, D: 7}}) {
		t.Fatalf("bad retries: %+v", attempts)
	}
	if u, d := m.GetServiceTraffic("fixture"); u != 0 || d != 0 {
		t.Fatalf("acknowledged counters retained: %d %d", u, d)
	}
}

func TestTrafficStopBoundedAndKeepsUnacknowledged(t *testing.T) {
	m := newTrafficManager(time.Hour, func(ctx context.Context, _ string, _ []TrafficReportItem) error { <-ctx.Done(); return ctx.Err() })
	m.AddTraffic("fixture", 3, 4)
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Millisecond)
	defer cancel()
	m.stop(ctx)
	select {
	case <-m.done:
	case <-time.After(time.Second):
		t.Fatal("reporter did not stop")
	}
	if u, d := m.GetServiceTraffic("fixture"); u != 3 || d != 4 {
		t.Fatalf("unacknowledged counters lost: %d %d", u, d)
	}
}

func TestReportAcknowledgementContract(t *testing.T) {
	for _, tc := range []struct {
		status int
		body   string
		ok     bool
	}{{200, " ok\n", true}, {200, "accepted", false}, {http.StatusUnprocessableEntity, "ok", false}} {
		w := httptest.NewRecorder()
		w.WriteHeader(tc.status)
		_, _ = w.WriteString(tc.body)
		resp := w.Result()
		ok, err := checkReportAck(resp)
		resp.Body.Close()
		if ok != tc.ok || (err == nil) != tc.ok || errors.Is(err, context.Canceled) {
			t.Fatalf("%d: got %t %v", tc.status, ok, err)
		}
	}
}
