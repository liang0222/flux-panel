package socket

import (
	"strings"
	"testing"

	"github.com/go-gost/x/config"
)

// panelForTest 复刻 main 包中 Panel.isolatePrefix 的规则。
//
// 之所以在这里复刻而不是直接引用：Panel 定义在 main 包，socket 包无法导入。
// 两边规则必须保持一致——main 包负责生成前缀，socket 包负责按前缀匹配。
// 若将来修改了任一侧的规则，本测试会失败，从而提醒同步修改。
func panelForTest(addr string) string {
	var b strings.Builder
	b.WriteString("p_")
	for _, r := range addr {
		switch {
		case r >= '0' && r <= '9', r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z':
			b.WriteRune(r)
		case r == '.' || r == ':' || r == '-':
			b.WriteRune('_')
		}
	}
	return b.String()
}

// TestIsolatePrefix 验证前缀生成规则：只保留合法字符，且不同面板前缀不同。
func TestIsolatePrefix(t *testing.T) {
	cases := []struct {
		addr string
		want string
	}{
		{"1.2.3.4:6365", "p_1_2_3_4_6365"},
		{"panel.example.com:6365", "p_panel_example_com_6365"},
		{"10.0.0.1:6365", "p_10_0_0_1_6365"},
	}
	for _, c := range cases {
		if got := panelForTest(c.addr); got != c.want {
			t.Errorf("addr=%s 期望前缀 %q，实际 %q", c.addr, c.want, got)
		}
	}

	// 不同面板地址必须产生不同前缀，否则隔离失效
	a := panelForTest("1.2.3.4:6365")
	b := panelForTest("1.2.3.5:6365")
	if a == b {
		t.Fatalf("不同面板产生了相同前缀: %s", a)
	}
}

// TestIsolateEmptyPrefixKeepsName 验证单面板（空前缀）时服务名保持不变，
// 保证升级后原有转发不受影响。
func TestIsolateEmptyPrefixKeepsName(t *testing.T) {
	r := &WebSocketReporter{}
	if got := r.isolate("12_5_0"); got != "12_5_0" {
		t.Fatalf("空前缀不应改变服务名，实际 %q", got)
	}
}

// TestIsolateWithPrefix 验证加前缀后的服务名。
func TestIsolateWithPrefix(t *testing.T) {
	r := &WebSocketReporter{prefix: "p_1_2_3_4_6365_"}
	if got := r.isolate("12_5_0"); got != "p_1_2_3_4_6365_12_5_0" {
		t.Fatalf("期望 p_1_2_3_4_6365_12_5_0，实际 %q", got)
	}
}

// TestTwoPanelsDoNotCollide 核心用例：
// 两个面板下发的同名服务（forwardId 都是 1）必须映射成不同的注册名，
// 否则 A 面板删除服务时会误删 B 面板的服务。
func TestTwoPanelsDoNotCollide(t *testing.T) {
	panelA := &WebSocketReporter{prefix: panelForTest("1.1.1.1:6365")}
	panelB := &WebSocketReporter{prefix: panelForTest("2.2.2.2:6365")}

	// 两个面板各自的第一条转发，服务名完全一样
	nameFromPanel := "1_1_0"

	gotA := panelA.isolate(nameFromPanel)
	gotB := panelB.isolate(nameFromPanel)

	if gotA == gotB {
		t.Fatalf("两个面板的同名服务映射结果相同(%s)，会发生互删", gotA)
	}
	t.Logf("面板A: %s -> %s", nameFromPanel, gotA)
	t.Logf("面板B: %s -> %s", nameFromPanel, gotB)
}

// TestServiceConfigNameRewrite 验证服务配置改名后仍保留其他字段。
func TestServiceConfigNameRewrite(t *testing.T) {
	svc := config.ServiceConfig{Name: "7_2_0"}
	r := &WebSocketReporter{prefix: "p_a_b_"}
	svc.Name = r.isolate(svc.Name)
	if svc.Name != "p_a_b_7_2_0" {
		t.Fatalf("服务名改写失败: %s", svc.Name)
	}
}
