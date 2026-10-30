package main

import (
	"testing"
)

// TestParseMemoryLimit 覆盖 parseMemoryLimit 的单位解析与边界行为。
//
// 这个函数是 FLUX_AGENT_MEMLIMIT 的唯一入口，一旦解析出错，要么把上限设成
// 错误的数量级（例如把 MiB 当 GiB），要么让 agent 启动失败。因此对每一类
// 输入都固定下来，避免后续改动悄悄改变语义。
func TestParseMemoryLimit(t *testing.T) {
	tests := []struct {
		name    string
		input   string
		want    int64
		wantErr bool
	}{
		// 带 IEC 单位（1024 进制），这是最常见写法
		{"1200MiB", "1200MiB", 1200 * 1024 * 1024, false},
		{"1GiB", "1GiB", 1024 * 1024 * 1024, false},
		{"512KiB", "512KiB", 512 * 1024, false},

		// 带 SI 写法：本项目统一按 1024 进制解释，避免运维混用时产生数量级误解
		{"1536MB", "1536MB", 1536 * 1024 * 1024, false},
		{"2GB", "2GB", 2 * 1024 * 1024 * 1024, false},
		{"256KB", "256KB", 256 * 1024, false},

		// 单位大小写不敏感
		{"1200mib lowercase", "1200mib", 1200 * 1024 * 1024, false},
		{"1gib lowercase", "1gib", 1024 * 1024 * 1024, false},
		{"800mb mixed", "800Mb", 800 * 1024 * 1024, false},

		// 单字母简写
		{"512m", "512m", 512 * 1024 * 1024, false},
		{"1g", "1g", 1024 * 1024 * 1024, false},
		{"1024k", "1024k", 1024 * 1024, false},

		// 纯字节数
		{"pure bytes", "536870912", 536870912, false},
		{"small bytes", "1024", 1024, false},

		// 带单位 B
		{"bytes with B", "2048B", 2048, false},

		// 小数应被支持（ParseFloat 路径）
		{"1.5GiB", "1.5GiB", int64(1.5 * 1024 * 1024 * 1024), false},

		// 前后空白应被裁剪
		{"whitespace", "  800MiB  ", 800 * 1024 * 1024, false},

		// 非法输入必须报错，而不是静默返回 0
		{"empty", "", 0, true},
		{"bogus unit", "bogus", 0, true},
		{"negative", "-100MiB", 0, true},
		{"zero", "0MiB", 0, true},
		{"zero bytes", "0", 0, true},
		{"no number", "MiB", 0, true},
		{"garbage digits", "abcMiB", 0, true},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := parseMemoryLimit(tc.input)

			if tc.wantErr {
				if err == nil {
					t.Fatalf("parseMemoryLimit(%q) 期望返回错误，实际得到 %d 且 err=nil", tc.input, got)
				}
				return
			}

			if err != nil {
				t.Fatalf("parseMemoryLimit(%q) 意外返回错误: %v", tc.input, err)
			}
			if got != tc.want {
				t.Fatalf("parseMemoryLimit(%q) = %d，期望 %d", tc.input, got, tc.want)
			}
		})
	}
}

// TestParseMemoryLimitUnitOrdering 验证单位匹配顺序：必须优先匹配更长的后缀，
// 否则 "1200MiB" 会被 "B" 抢先匹配，得到错误结果。
func TestParseMemoryLimitUnitOrdering(t *testing.T) {
	// 若单位按从短到长匹配，"1GiB" 会被 "B" 命中而返回 1 字节
	got, err := parseMemoryLimit("1GiB")
	if err != nil {
		t.Fatalf("意外错误: %v", err)
	}
	if got != 1024*1024*1024 {
		t.Fatalf("单位匹配顺序错误：1GiB 被解析为 %d 字节，期望 %d", got, int64(1024*1024*1024))
	}

	// "512MB" 不能被 "B" 抢先匹配
	got, err = parseMemoryLimit("512MB")
	if err != nil {
		t.Fatalf("意外错误: %v", err)
	}
	if got != 512*1024*1024 {
		t.Fatalf("单位匹配顺序错误：512MB 被解析为 %d 字节，期望 %d", got, int64(512*1024*1024))
	}
}
